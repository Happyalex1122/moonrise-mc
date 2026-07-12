package net.minecraft.server.region;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * 고성능 공간 해시 그리드(Spatial Hash Grid).
 * 엔티티 충돌 연산을 O(N^2)에서 O(N)으로 물리적으로 줄이기 위한 자료구조.
 * 락-프리(Lock-Free) 환경이 보장된 단일 RegionThread 내부에서 사용되므로 비동기화 상태로 작성됨.
 */
public class SpatialHashGrid {
    
    // 그리드 셀 하나의 크기 (일반적인 마인크래프트 엔티티 반경을 고려하여 2.0 블록 크기 채택)
    public static final double CELL_SIZE = 2.0;
    
    // 청크 안의 엔티티 압사/스킵 한계치 (한 셀에 이 이상 있으면 틱 연산 스킵)
    public static final int MAX_ENTITIES_PER_CELL = 24;

    // 셀 좌표를 64비트 정수로 패킹한 값을 키로 사용하고, 엔티티 리스트를 값으로 가짐
    private final Long2ObjectOpenHashMap<List<Entity>> grid = new Long2ObjectOpenHashMap<>(1024);
    
    // 최적화를 위해 이전에 엔티티가 소속되었던 셀의 해시값을 저장
    private final Reference2LongOpenHashMap<Entity> entityCurrentCell;

    public SpatialHashGrid() {
        this.entityCurrentCell = new Reference2LongOpenHashMap<>();
        this.entityCurrentCell.defaultReturnValue(Long.MIN_VALUE);
    }

    /**
     * (X, Z) 좌표를 고유한 Long 64비트 해시값으로 변환
     */
    public static long getHashKey(double x, double z) {
        int cx = (int) Math.floor(x / CELL_SIZE);
        int cz = (int) Math.floor(z / CELL_SIZE);
        return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
    }

    /**
     * 그리드에 엔티티를 추가하거나 갱신함
     */
    public void updateEntity(Entity entity) {
        long newHash = getHashKey(entity.getX(), entity.getZ());
        long oldHash = entityCurrentCell.getLong(entity);
        
        // 이동하지 않아서 셀이 같다면 무시
        if (oldHash != Long.MIN_VALUE && oldHash == newHash) {
            return;
        }

        // 기존 셀에서 제거
        if (oldHash != Long.MIN_VALUE) {
            List<Entity> oldList = grid.get(oldHash);
            if (oldList != null) {
                oldList.remove(entity);
                if (oldList.isEmpty()) {
                    grid.remove(oldHash);
                }
            }
        }

        // 새로운 셀에 추가
        List<Entity> newList = grid.computeIfAbsent(newHash, k -> new ArrayList<>(8));
        newList.add(entity);
        entityCurrentCell.put(entity, newHash);
    }

    /**
     * 그리드에서 엔티티를 완전히 제거함 (사망 시 등)
     */
    public void removeEntity(Entity entity) {
        long oldHash = entityCurrentCell.removeLong(entity);
        if (oldHash != Long.MIN_VALUE) {
            List<Entity> oldList = grid.get(oldHash);
            if (oldList != null) {
                oldList.remove(entity);
                // 메모리 누수 방지: 빈 셀은 제거 (최적화에 따라 놔둘 수도 있음)
                if (oldList.isEmpty()) {
                    grid.remove(oldHash);
                }
            }
        }
    }

    /**
     * 특정 엔티티 주변(같은 셀 및 인접 8개 셀)의 엔티티들을 빠르게 검색하여 반환함.
     * O(N^2) 박스 전체 순회를 막아주는 핵심 메서드.
     */
    public void getNearbyEntities(Entity center, double radius, List<Entity> dest) {
        double minX = center.getX() - radius;
        double maxX = center.getX() + radius;
        double minZ = center.getZ() - radius;
        double maxZ = center.getZ() + radius;

        int minCx = (int) Math.floor(minX / CELL_SIZE);
        int maxCx = (int) Math.floor(maxX / CELL_SIZE);
        int minCz = (int) Math.floor(minZ / CELL_SIZE);
        int maxCz = (int) Math.floor(maxZ / CELL_SIZE);

        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                long hash = ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
                List<Entity> cellEntities = grid.get(hash);
                if (cellEntities != null) {
                    for (Entity e : cellEntities) {
                        if (e != center && e.isAlive()) {
                            dest.add(e);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 특정 엔티티가 속한 셀이 극한으로 과밀집 상태(Entity Cramming)인지 확인
     * 과밀집 상태라면 이 엔티티의 AI 틱이나 물리 틱을 스킵할 수 있음.
     */
    public boolean isOvercrowded(Entity entity) {
        long hash = entityCurrentCell.getLong(entity);
        if (hash == Long.MIN_VALUE) return false;
        
        List<Entity> list = grid.get(hash);
        return list != null && list.size() > MAX_ENTITIES_PER_CELL;
    }
    
    /**
     * 매 틱마다 메모리 최적화를 위해 전체 클리어(필요시)
     */
    public void clear() {
        grid.clear();
        entityCurrentCell.clear();
    }
}
