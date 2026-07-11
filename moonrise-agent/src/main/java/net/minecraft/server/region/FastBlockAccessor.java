package net.minecraft.server.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NMS 레벨 데이터에 직접 접근하여 초고속으로 블록을 조작하는 아키텍처 클래스입니다.
 * 리플렉션을 통해 LevelChunkSection 내부의 PalettedContainer 필드에 직접 접근하며,
 * 특정 블록 좌표에 Block State ID를 안전하게 락을 획득하고 덮어씁니다.
 * 인접 블록에 대한 물리 업데이트(Physics 연산)를 생략하여 성능을 최적화하고,
 * 작업이 끝난 후 외부 경계면에 인접한 블록들에 대해서만 물리 업데이트를 일괄 트리거할 수 있습니다.
 */
public class FastBlockAccessor {
    private static final Logger LOGGER = Logger.getLogger(FastBlockAccessor.class.getName());

    // NMS setBlock flags 조합 (필요시 level.setBlock fallback용)
    // 2 (UPDATE_CLIENTS): 클라이언트에 변경 패킷 전송
    // 16 (UPDATE_KNOWN_SHAPE): 물리 업데이트 방지 및 이웃 블록 알림 방지
    // 1024 (UPDATE_NO_NEIGHBOR_PHYSICS): 이웃 물리 연산 유발 방지
    private static final int NO_PHYSICS_FLAGS = 2 | 16 | 1024;

    // LevelChunkSection reflection fields
    private static Field statesField;
    private static Field nonEmptyBlockCountField;
    private static Field tickingBlockCountField;
    private static Field tickingFluidCountField;

    // PalettedContainer reflection fields and methods
    private static Field containerLockField;
    private static Method containerSetMethod;
    private static Method acquireMethod;
    private static Method releaseMethod;

    static {
        initializeReflection();
    }

    private static void initializeReflection() {
        try {
            // 1. LevelChunkSection states (PalettedContainer) 및 카운터 필드 탐색
            for (Field field : LevelChunkSection.class.getDeclaredFields()) {
                if (PalettedContainer.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    
                    // BlockState를 다루는 컨테이너인지 제네릭 정보로 우선 검증
                    Type genericType = field.getGenericType();
                    if (genericType instanceof ParameterizedType) {
                        ParameterizedType pType = (ParameterizedType) genericType;
                        Type[] actualTypeArguments = pType.getActualTypeArguments();
                        if (actualTypeArguments.length > 0 && actualTypeArguments[0].toString().contains("BlockState")) {
                            statesField = field;
                        }
                    }
                    
                    // Fallback: 필드명 후보군으로 매칭 (Mojang mapping: states, Yarn: blockStateContainer)
                    if (statesField == null) {
                        String name = field.getName();
                        if (name.equals("states") || name.equals("blockStateContainer") || name.equals("c") || name.equals("field_28129")) {
                            statesField = field;
                        }
                    }
                } else if (field.getType() == short.class) {
                    field.setAccessible(true);
                    String name = field.getName();
                    if (name.equals("nonEmptyBlockCount") || name.equals("a") || name.equals("field_28124")) {
                        nonEmptyBlockCountField = field;
                    } else if (name.equals("tickingBlockCount") || name.equals("b") || name.equals("field_28125")) {
                        tickingBlockCountField = field;
                    } else if (name.equals("tickingFluidCount") || name.equals("c") || name.equals("field_28126")) {
                        tickingFluidCountField = field;
                    }
                }
            }

            // statesField 바인딩 실패 시 첫 번째 PalettedContainer 필드로 강제 지정
            if (statesField == null) {
                for (Field field : LevelChunkSection.class.getDeclaredFields()) {
                    if (PalettedContainer.class.isAssignableFrom(field.getType())) {
                        statesField = field;
                        statesField.setAccessible(true);
                        break;
                    }
                }
            }

            // short 카운터 필드 바인딩 보장 (타입 순서대로 매핑)
            if (nonEmptyBlockCountField == null || tickingBlockCountField == null || tickingFluidCountField == null) {
                int shortCount = 0;
                for (Field field : LevelChunkSection.class.getDeclaredFields()) {
                    if (field.getType() == short.class) {
                        field.setAccessible(true);
                        shortCount++;
                        if (shortCount == 1 && nonEmptyBlockCountField == null) {
                            nonEmptyBlockCountField = field;
                        } else if (shortCount == 2 && tickingBlockCountField == null) {
                            tickingBlockCountField = field;
                        } else if (shortCount == 3 && tickingFluidCountField == null) {
                            tickingFluidCountField = field;
                        }
                    }
                }
            }

            // 2. PalettedContainer 내부 락 필드 및 스레드 검출 acquire/release 메서드 탐색
            for (Field field : PalettedContainer.class.getDeclaredFields()) {
                if (Semaphore.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    containerLockField = field;
                    break;
                }
            }

            // 3. PalettedContainer 값 설정 메서드 탐색
            try {
                // Mojang mappings: setAndGetOldValue
                containerSetMethod = PalettedContainer.class.getDeclaredMethod("setAndGetOldValue", int.class, int.class, int.class, Object.class);
            } catch (NoSuchMethodException e) {
                try {
                    // Yarn mappings: set
                    containerSetMethod = PalettedContainer.class.getDeclaredMethod("set", int.class, int.class, int.class, Object.class);
                } catch (NoSuchMethodException ex) {
                    // Fallback: 4개 파라미터를 갖는 적절한 메서드 탐색
                    for (Method m : PalettedContainer.class.getDeclaredMethods()) {
                        if ((m.getName().equals("setAndGetOldValue") || m.getName().equals("set") || m.getName().equals("a"))
                                && m.getParameterCount() == 4 
                                && m.getParameterTypes()[0] == int.class 
                                && m.getParameterTypes()[1] == int.class 
                                && m.getParameterTypes()[2] == int.class) {
                            containerSetMethod = m;
                            break;
                        }
                    }
                }
            }
            if (containerSetMethod != null) {
                containerSetMethod.setAccessible(true);
            }

            // 4. ThreadingDetector / Multi-threading 검증 우회 메서드 탐색
            try {
                acquireMethod = PalettedContainer.class.getDeclaredMethod("acquire");
                acquireMethod.setAccessible(true);
                releaseMethod = PalettedContainer.class.getDeclaredMethod("release");
                releaseMethod.setAccessible(true);
            } catch (NoSuchMethodException e) {
                LOGGER.log(Level.FINE, "PalettedContainer acquire/release methods not found. Normal synchronization will be used.");
            }

            LOGGER.info("[FastBlockAccessor] Successfully initialized reflection for NMS block access.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Critical failure during FastBlockAccessor reflection initialization.", e);
        }
    }

    /**
     * 기존 FastBlockSetter 등과의 호환성을 제공하기 위한 헬퍼 메서드입니다.
     * 물리 연산을 우회하여 블록을 덮어씁니다.
     */
    public static boolean setBlock(ServerLevel level, BlockPos pos, BlockState state) {
        return setBlockDirect(level, pos, state, true);
    }

    /**
     * 대상 좌표에 물리 연산을 완전히 우회하고, 리플렉션을 사용해 LevelChunkSection의
     * PalettedContainer에 직접 BlockState를 안전하게 주입합니다.
     *
     * @param level      NMS 월드 레벨
     * @param pos        대상 좌표 BlockPos
     * @param newState   기록할 BlockState
     * @param sendPacket 클라이언트에 갱신 패킷을 즉시 보낼지 여부
     * @return 성공 시 true, 실패 시 false
     */
    public static boolean setBlockDirect(ServerLevel level, BlockPos pos, BlockState newState, boolean sendPacket) {
        if (level == null || pos == null || newState == null) {
            return false;
        }

        try {
            // 1. 청크 및 섹션 획득
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;
            LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
            if (chunk == null) {
                // 청크가 로드되지 않았다면 NMS 기본 setBlock으로 fallback 처리
                return level.setBlock(pos, newState, NO_PHYSICS_FLAGS);
            }

            int y = pos.getY();
            int sectionIndex;
            try {
                sectionIndex = level.getSectionIndex(y);
            } catch (NoSuchMethodError e) {
                // Fallback: 수동 인덱싱 계산
                sectionIndex = (y - level.getMinY()) >> 4;
            }

            if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
                return false;
            }

            LevelChunkSection section = chunk.getSections()[sectionIndex];
            if (section == null) {
                // 섹션이 없으면 LevelChunk의 getSection(index) 메서드 호출을 통해 자동 생성 유도
                try {
                    Method getSectionMethod = LevelChunk.class.getDeclaredMethod("getSection", int.class);
                    getSectionMethod.setAccessible(true);
                    section = (LevelChunkSection) getSectionMethod.invoke(chunk, sectionIndex);
                } catch (Exception ex) {
                    // 동적 탐색 fallback
                    for (Method m : LevelChunk.class.getDeclaredMethods()) {
                        if (m.getReturnType() == LevelChunkSection.class && m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) {
                            m.setAccessible(true);
                            section = (LevelChunkSection) m.invoke(chunk, sectionIndex);
                            break;
                        }
                    }
                }

                if (section == null) {
                    return false;
                }
            }

            // 2. PalettedContainer 획득
            if (statesField == null) {
                return level.setBlock(pos, newState, NO_PHYSICS_FLAGS);
            }
            @SuppressWarnings("unchecked")
            PalettedContainer<BlockState> container = (PalettedContainer<BlockState>) statesField.get(section);
            if (container == null) {
                return level.setBlock(pos, newState, NO_PHYSICS_FLAGS);
            }

            BlockState oldState = null;
            int localX = pos.getX() & 15;
            int localY = pos.getY() & 15;
            int localZ = pos.getZ() & 15;

            // 단일 synchronized 블록을 통한 다중 스레드 락 확보 (NMS ThreadingDetector 우회용 락 제거로 교착 방지)
            synchronized (container) {
                if (containerSetMethod != null) {
                    oldState = (BlockState) containerSetMethod.invoke(container, localX, localY, localZ, newState);
                }
                if (oldState == null) {
                    oldState = Blocks.AIR.defaultBlockState();
                }

                // 4. LevelChunkSection 내부의 카운터(short) 동기화 (원자성 보장을 위해 락 안으로 이동)
                updateSectionCounters(section, oldState, newState);
            }

            // 5. 클라이언트 갱신 패킷 전송
            if (sendPacket) {
                level.getChunkSource().blockChanged(pos);
            }

            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to set block directly at " + pos + ", falling back to NMS setBlock", e);
            try {
                return level.setBlock(pos, newState, NO_PHYSICS_FLAGS);
            } catch (Exception ex) {
                return false;
            }
        }
    }

    /**
     * LevelChunkSection 내부의 short 카운터 필드들을 동기화하여 청크 연산 무결성을 유지합니다.
     */
    private static void updateSectionCounters(LevelChunkSection section, BlockState oldState, BlockState newState) {
        try {
            // nonEmptyBlockCount (공기가 아닌 블록 수) 업데이트
            boolean oldAir = oldState.isAir();
            boolean newAir = newState.isAir();
            if (oldAir != newAir && nonEmptyBlockCountField != null) {
                short current = nonEmptyBlockCountField.getShort(section);
                short diff = (short) (oldAir ? 1 : -1);
                nonEmptyBlockCountField.setShort(section, (short) (current + diff));
            }

            // tickingBlockCount (랜덤 틱 대상 블록 수) 업데이트
            boolean oldTick = checkIsRandomTicking(oldState);
            boolean newTick = checkIsRandomTicking(newState);
            if (oldTick != newTick && tickingBlockCountField != null) {
                short current = tickingBlockCountField.getShort(section);
                short diff = (short) (oldTick ? -1 : 1);
                tickingBlockCountField.setShort(section, (short) (current + diff));
            }

            // tickingFluidCount (랜덤 틱 대상 유체 수) 업데이트
            boolean oldFluidTick = checkFluidIsRandomTicking(oldState.getFluidState());
            boolean newFluidTick = checkFluidIsRandomTicking(newState.getFluidState());
            if (oldFluidTick != newFluidTick && tickingFluidCountField != null) {
                short current = tickingFluidCountField.getShort(section);
                short diff = (short) (oldFluidTick ? -1 : 1);
                tickingFluidCountField.setShort(section, (short) (current + diff));
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to update LevelChunkSection counters", e);
        }
    }

    /**
     * 지정된 영역(Box)의 모든 블록에 물리 연산 없이 초고속으로 블록을 일괄 기록합니다.
     *
     * @param level    NMS 월드 레벨
     * @param minPos   영역의 최소 좌표 (inclusive)
     * @param maxPos   영역의 최대 좌표 (inclusive)
     * @param newState 기록할 BlockState
     */
    public static void setBlockBoxDirect(ServerLevel level, BlockPos minPos, BlockPos maxPos, BlockState newState) {
        int minX = Math.min(minPos.getX(), maxPos.getX());
        int maxX = Math.max(minPos.getX(), maxPos.getX());
        int minY = Math.min(minPos.getY(), maxPos.getY());
        int maxY = Math.max(minPos.getY(), maxPos.getY());
        int minZ = Math.min(minPos.getZ(), maxPos.getZ());
        int maxZ = Math.max(minPos.getZ(), maxPos.getZ());

        Set<Long> updatedChunks = new HashSet<>();

        // 초고속 기록 (개별 클라이언트 패킷 전송 생략)
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                updatedChunks.add(ChunkPos.asLong(x >> 4, z >> 4));
                for (int y = minY; y <= maxY; y++) {
                    setBlockDirect(level, new BlockPos(x, y, z), newState, false);
                }
            }
        }

        // 업데이트된 청크 단위로 클라이언트 변경 패킷 일괄 통지
        for (long key : updatedChunks) {
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
            if (chunk != null) {
                // 청크 내의 변경 섹션 전체를 클라이언트에 동기화하기 위한 NMS 헬퍼 호출
                // 일반적으로 청크 갱신 패킷은 각 청크 내부의 blockChanged 트리거를 통해 묶음 패킷으로 큐잉됨
                for (int y = minY; y <= maxY; y += 16) {
                    level.getChunkSource().blockChanged(new BlockPos(chunkX << 4, y, chunkZ << 4));
                }
            }
        }
    }

    /**
     * 대규모 블록 조작이 끝난 후, 외부 경계면에 인접한 블록들에 대해서만 물리 업데이트를 일괄적으로 트리거합니다.
     * 영역 내부 블록들의 물리 처리를 생략해 CPU 오버헤드를 극적으로 최소화합니다.
     *
     * @param level  NMS 월드 레벨
     * @param minPos 조작 영역의 최소 좌표 (inclusive)
     * @param maxPos 조작 영역의 최대 좌표 (inclusive)
     */
    public static void triggerBoundaryPhysics(ServerLevel level, BlockPos minPos, BlockPos maxPos) {
        int minX = Math.min(minPos.getX(), maxPos.getX());
        int maxX = Math.max(minPos.getX(), maxPos.getX());
        int minY = Math.min(minPos.getY(), maxPos.getY());
        int maxY = Math.max(minPos.getY(), maxPos.getY());
        int minZ = Math.min(minPos.getZ(), maxPos.getZ());
        int maxZ = Math.max(minPos.getZ(), maxPos.getZ());

        // 6개 면의 바깥쪽 1블록 두께 경계면에 이웃 물리 업데이트를 트리거합니다.
        
        // 1. X축 양쪽 경계면 바깥
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                updateNeighborPhysics(level, new BlockPos(minX - 1, y, z));
                updateNeighborPhysics(level, new BlockPos(maxX + 1, y, z));
            }
        }

        // 2. Y축 양쪽 경계면 바깥
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                updateNeighborPhysics(level, new BlockPos(x, minY - 1, z));
                updateNeighborPhysics(level, new BlockPos(x, maxY + 1, z));
            }
        }

        // 3. Z축 양쪽 경계면 바깥
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                updateNeighborPhysics(level, new BlockPos(x, y, minZ - 1));
                updateNeighborPhysics(level, new BlockPos(x, y, maxZ + 1));
            }
        }
    }

    /**
     * 대상 좌표의 블록에 이웃 물리 업데이트를 안전하게 전송합니다.
     */
    private static void updateNeighborPhysics(ServerLevel level, BlockPos targetPos) {
        if (targetPos.getY() < level.getMinY() || targetPos.getY() >= level.getMaxY()) {
            return;
        }
        BlockState state = level.getBlockState(targetPos);
        if (!state.isAir()) {
            // 이웃 블록들에게 물리 상태 갱신을 즉시 통지
            level.updateNeighborsAt(targetPos, state.getBlock());
        }
    }

    private static boolean checkIsRandomTicking(Object state) {
        if (state instanceof net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase base) {
            return base.isRandomlyTicking();
        }
        return false;
    }

    private static boolean checkFluidIsRandomTicking(Object fluidState) {
        if (fluidState instanceof net.minecraft.world.level.material.FluidState state) {
            return state.isRandomlyTicking();
        }
        return false;
    }
}
