package net.minecraft.server.region;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Consumer;

// NMS Imports
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

// Bukkit Imports
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Scheduler for mass block updates with time-slicing to prevent server lag.
 * Bypasses physics via FastBlockAccessor and schedules asynchronous lighting recalculation.
 */
public class FastBlockSetter {
    private static final Logger LOGGER = Logger.getLogger(FastBlockSetter.class.getName());
    
    private static final int DEFAULT_LIMIT_PER_TICK = 5000;
    
    private static final ExecutorService LIGHTING_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "FastBlockSetter-LightingThread");
        thread.setDaemon(true);
        return thread;
    });

    private static final FastBlockSetter INSTANCE = new FastBlockSetter();
    
    private final Queue<BlockTask> taskQueue = new ConcurrentLinkedQueue<>();
    private int limitPerTick = DEFAULT_LIMIT_PER_TICK;
    private BukkitTask bukkitTask = null;

    public static FastBlockSetter getInstance() {
        return INSTANCE;
    }

    private FastBlockSetter() {}

    public static class BlockChange {
        public final BlockPos pos;
        public final BlockState state;

        public BlockChange(BlockPos pos, BlockState state) {
            this.pos = pos;
            this.state = state;
        }
    }

    public static class BlockTask {
        public final UUID taskId;
        public final ServerLevel level;
        public final Queue<BlockChange> changes;
        public final int totalSize;
        public final Runnable onComplete;
        public final Consumer<Exception> onError;
        public final long startTime;
        
        // Track the boundary box of the modified area
        public int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        public int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        public BlockTask(ServerLevel level, List<BlockChange> changesList, Runnable onComplete, Consumer<Exception> onError) {
            this.taskId = UUID.randomUUID();
            this.level = level;
            this.changes = new ConcurrentLinkedQueue<>(changesList);
            this.totalSize = changesList.size();
            this.onComplete = onComplete;
            this.onError = onError;
            this.startTime = System.currentTimeMillis();
            
            // Pre-calculate boundary box
            for (BlockChange change : changesList) {
                int x = change.pos.getX();
                int y = change.pos.getY();
                int z = change.pos.getZ();
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (z < minZ) minZ = z;
                if (z > maxZ) maxZ = z;
            }
        }
    }

    /**
     * Queues a mass block setting task.
     */
    public UUID queueTask(ServerLevel level, List<BlockChange> changes, Runnable onComplete, Consumer<Exception> onError) {
        if (changes == null || changes.isEmpty()) {
            if (onComplete != null) {
                onComplete.run();
            }
            return null;
        }

        BlockTask task = new BlockTask(level, changes, onComplete, onError);
        taskQueue.add(task);
        
        LOGGER.log(Level.INFO, "[FastBlockSetter] Queued task {0} with {1} blocks in world {2}",
            new Object[]{task.taskId, task.totalSize, level.getWorld().getName()});
            
        return task.taskId;
    }

    public void setLimitPerTick(int limit) {
        if (limit > 0) {
            this.limitPerTick = limit;
        }
    }

    public int getLimitPerTick() {
        return limitPerTick;
    }

    /**
     * Process block updates using time-slicing (limit per tick).
     */
    public void tick() {
        if (taskQueue.isEmpty()) {
            return;
        }

        int remainingQuota = limitPerTick;
        long startTimeNs = System.nanoTime();
        int processedThisTick = 0;
        Set<net.minecraft.world.level.ChunkPos> chunksToUpdate = new HashSet<>();

        while (remainingQuota > 0 && !taskQueue.isEmpty()) {
            BlockTask currentTask = taskQueue.peek();
            if (currentTask == null) {
                taskQueue.poll();
                continue;
            }

            int batchSize = Math.min(remainingQuota, currentTask.changes.size());
            if (batchSize <= 0) {
                taskQueue.poll();
                handleTaskCompletion(currentTask, chunksToUpdate);
                continue;
            }

            try {
                for (int i = 0; i < batchSize; i++) {
                    BlockChange change = currentTask.changes.poll();
                    if (change != null) {
                        // Direct block writing, suppressing immediate individual packets for performance
                        if (FastBlockAccessor.setBlockDirect(currentTask.level, change.pos, change.state, false)) {
                            chunksToUpdate.add(new net.minecraft.world.level.ChunkPos(change.pos.getX() >> 4, change.pos.getZ() >> 4));
                            processedThisTick++;
                        }
                    }
                }
                remainingQuota -= batchSize;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Exception occurred during block task " + currentTask.taskId, e);
                if (currentTask.onError != null) {
                    currentTask.onError.accept(e);
                }
                taskQueue.poll();
                continue;
            }

            if (currentTask.changes.isEmpty()) {
                taskQueue.poll();
                handleTaskCompletion(currentTask, chunksToUpdate);
            }
        }

        // Notify client chunk updates collectively for this tick's progress
        for (net.minecraft.world.level.ChunkPos chunkPos : chunksToUpdate) {
            ServerLevel level = null;
            if (!taskQueue.isEmpty()) {
                level = taskQueue.peek().level;
            }
            if (level != null) {
                level.getChunkSource().blockChanged(new BlockPos(chunkPos.x() << 4, level.getMinY(), chunkPos.z() << 4));
            }
        }

        if (processedThisTick > 0 && LOGGER.isLoggable(Level.FINE)) {
            double durationMs = (System.nanoTime() - startTimeNs) / 1_000_000.0;
            LOGGER.log(Level.FINE, "[FastBlockSetter] Processed {0} blocks. Duration: {1} ms. Remaining tasks: {2}",
                new Object[]{processedThisTick, String.format("%.3f", durationMs), taskQueue.size()});
        }
    }

    public synchronized void startRepeatingTask(Plugin plugin) {
        if (bukkitTask != null) {
            bukkitTask.cancel();
        }
        bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        LOGGER.info("[FastBlockSetter] Registered Bukkit repeating task.");
    }

    public synchronized void stopRepeatingTask() {
        if (bukkitTask != null) {
            bukkitTask.cancel();
            bukkitTask = null;
            LOGGER.info("[FastBlockSetter] Stopped Bukkit repeating task.");
        }
    }

    public static void shutdownLightingExecutor() {
        LIGHTING_EXECUTOR.shutdown();
    }

    private void handleTaskCompletion(BlockTask task, Set<net.minecraft.world.level.ChunkPos> chunksToUpdate) {
        long elapsed = System.currentTimeMillis() - task.startTime;
        LOGGER.log(Level.INFO, "[FastBlockSetter] Completed task {0} ({1} blocks) in {2} ms. Triggering boundary physics & async lighting...",
            new Object[]{task.taskId, task.totalSize, elapsed});

        // 1. Trigger physics only at the boundary of the modified area
        try {
            BlockPos minPos = new BlockPos(task.minX, task.minY, task.minZ);
            BlockPos maxPos = new BlockPos(task.maxX, task.maxY, task.maxZ);
            FastBlockAccessor.triggerBoundaryPhysics(task.level, minPos, maxPos);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error triggering boundary physics: " + task.taskId, e);
        }

        // 2. Trigger onComplete callback
        if (task.onComplete != null) {
            try {
                task.onComplete.run();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in onComplete callback: " + task.taskId, e);
            }
        }

        // 3. Trigger async lighting
        triggerAsynchronousLighting(task.level);
    }

    private void triggerAsynchronousLighting(ServerLevel level) {
        LIGHTING_EXECUTOR.submit(() -> {
            if (level.getServer() == null || !level.getServer().isRunning()) return;
            long startNs = System.nanoTime();
            String worldName = level.getWorld().getName();
            try {
                LOGGER.log(Level.INFO, "[FastBlockSetter] Starting async lighting calculation for level: {0}", worldName);

                try {
                    Class<?> starLightClass = Class.forName("ca.spottedleaf.starlight.common.light.StarLightEngine");
                    LOGGER.info("[FastBlockSetter] Starlight API detected.");
                } catch (ClassNotFoundException e) {
                    net.minecraft.server.level.ThreadedLevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
                    if (lightEngine != null) {
                        executeNmsLightEngineUpdate(lightEngine);
                    }
                }

                double durationMs = (System.nanoTime() - startNs) / 1_000_000.0;
                LOGGER.log(Level.INFO, "[FastBlockSetter] Finished async lighting for level: {0} (Took {1} ms)",
                    new Object[]{worldName, String.format("%.3f", durationMs)});
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed async lighting calculation: " + worldName, e);
            }
        });
    }

    private void executeNmsLightEngineUpdate(net.minecraft.server.level.ThreadedLevelLightEngine lightEngine) {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
