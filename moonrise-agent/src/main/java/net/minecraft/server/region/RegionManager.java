package net.minecraft.server.region;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;

/**
 * 월드의 청크들을 Region 단위로 매핑 및 관리하며,
 * RegionThread 스레드 풀을 활용하여 병렬 리전 틱 작업을 분배 및 조율하는 매니저 클래스입니다.
 */
public class RegionManager {
    private static final RegionManager INSTANCE = new RegionManager();

    // 월드 이름 -> (리전 long 키 -> Region 객체)
    private final ConcurrentMap<String, ConcurrentMap<Long, Region>> worldRegions = new ConcurrentHashMap<>();
    
    // 리전 틱 작업을 분배하기 위한 스레드 풀
    private final ExecutorService regionExecutor;
    private final int threadCount;

    private RegionManager() {
        this.threadCount = Math.max(2, Runtime.getRuntime().availableProcessors());
        System.out.println("[RegionManager] Initializing ForkJoinPool for Work-Stealing with " + threadCount + " workers.");
        
        this.regionExecutor = new ForkJoinPool(threadCount, ForkJoinPool.defaultForkJoinWorkerThreadFactory, (t, e) -> {
            System.err.println("[RegionManager] CRITICAL: Worker thread " + t.getName() + " died with uncaught exception!");
            e.printStackTrace();
        }, true);

        // Prestart all threads to verify creation at startup
        if (this.regionExecutor instanceof ThreadPoolExecutor) {
            ((ThreadPoolExecutor) this.regionExecutor).prestartAllCoreThreads();
        }
    }

    public static RegionManager getInstance() {
        return INSTANCE;
    }

    /**
     * 월드의 특정 청크를 해당하는 Region에 등록합니다.
     */
    public void registerChunk(String worldName, int chunkX, int chunkZ) {
        RegionCoordinate coord = RegionCoordinate.fromChunk(chunkX, chunkZ);
        long key = coord.toLongKey();

        worldRegions.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(key, k -> new Region(worldName, coord))
                    .addChunk(chunkX, chunkZ);
    }

    /**
     * 월드의 특정 청크를 Region에서 등록 해제합니다.
     */
    public void unregisterChunk(String worldName, int chunkX, int chunkZ) {
        ConcurrentMap<Long, Region> regions = worldRegions.get(worldName);
        if (regions == null) return;

        RegionCoordinate coord = RegionCoordinate.fromChunk(chunkX, chunkZ);
        long key = coord.toLongKey();

        regions.compute(key, (k, region) -> {
            if (region == null) return null;
            region.removeChunk(chunkX, chunkZ);
            return region.isEmpty() ? null : region;
        });
    }

    /**
     * 특정 월드의 모든 활성화된 리전 목록을 가져옵니다.
     */
    public Collection<Region> getRegions(String worldName) {
        Map<Long, Region> regions = worldRegions.get(worldName);
        return regions != null ? regions.values() : Collections.emptyList();
    }

    /**
     * 리전들을 스레드 수(threadCount)만큼 스트라이핑(Affinity)하여 병렬 틱을 가동합니다.
     * 제출되는 태스크가 스레드 수로 제한되므로 스케줄링 및 GC 오버헤드가 최소화되며 락 경합이 전혀 발생하지 않습니다.
     */
    public void tickWorldRegions(String worldName, java.util.function.Consumer<Region> regionTicker) {
        if (regionExecutor.isShutdown()) return;
        
        Collection<Region> regions = getRegions(worldName);
        if (regions.isEmpty()) return;

        List<Callable<Void>> tasks = new ArrayList<>();
        for (Region region : regions) {
            tasks.add(() -> {
                regionTicker.accept(region);
                return null;
            });
        }

        try {
            List<Future<Void>> futures = regionExecutor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get(); // 내부 예외 전파 및 대기
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new RuntimeException("Error executing parallel region tick for world " + worldName, e.getCause());
        }
    }

    /**
     * 스레드 풀 자원을 정리합니다.
     */
    public void shutdown() {
        BukkitEventBypass.drainAndCancelPendingTasks();
        regionExecutor.shutdown();
        try {
            if (!regionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                regionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            regionExecutor.shutdownNow();
        }
        worldRegions.clear();
    }

    /**
     * 16x16 청크 리전 영역을 가리키는 내부 클래스입니다.
     */
    public static class Region {
        private final String worldName;
        private final RegionCoordinate coordinate;
        private final Set<Long> registeredChunks = ConcurrentHashMap.newKeySet();
        private final java.util.concurrent.locks.ReentrantLock tickLock = new java.util.concurrent.locks.ReentrantLock();

        public Region(String worldName, RegionCoordinate coordinate) {
            this.worldName = worldName;
            this.coordinate = coordinate;
        }

        public String getWorldName() {
            return worldName;
        }

        public RegionCoordinate getCoordinate() {
            return coordinate;
        }

        public void addChunk(int chunkX, int chunkZ) {
            long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
            registeredChunks.add(key);
        }

        public void removeChunk(int chunkX, int chunkZ) {
            long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
            registeredChunks.remove(key);
        }

        public boolean isEmpty() {
            return registeredChunks.isEmpty();
        }

        public boolean tryLock() {
            return tickLock.tryLock();
        }

        public void unlock() {
            if (tickLock.isHeldByCurrentThread()) {
                tickLock.unlock();
            }
        }
    }
}
