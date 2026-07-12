package net.minecraft.server.region;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class VirtualThreadIODispatcher {

    private static final ExecutorService CHUNK_LOAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final ExecutorService PACKET_DECODE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final ExecutorService REDSTONE_EXECUTOR = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            CHUNK_LOAD_EXECUTOR.shutdown();
            PACKET_DECODE_EXECUTOR.shutdown();
            REDSTONE_EXECUTOR.shutdown();
        }));
    }

    public static <T> CompletableFuture<T> dispatchChunkLoad(Supplier<T> task) {
        if (AdaptiveTPSManager.isPanicMode()) {
            // Drop low priority loads or handle load shedding if needed
        }
        return CompletableFuture.supplyAsync(task, CHUNK_LOAD_EXECUTOR);
    }

    public static void dispatchChunkLoad(Runnable task) {
        CHUNK_LOAD_EXECUTOR.submit(() -> {
            long start = System.nanoTime();
            try {
                task.run();
            } finally {
                long duration = System.nanoTime() - start;
                AdaptiveTPSManager.recordTickTime(duration); // Wire up
            }
        });
    }

    public static void dispatchPacketDecode(Runnable task) {
        PACKET_DECODE_EXECUTOR.submit(() -> {
            long start = System.nanoTime();
            try {
                task.run();
            } finally {
                long duration = System.nanoTime() - start;
                AdaptiveTPSManager.recordTickTime(duration); // Wire up
            }
        });
    }

    public static CompletableFuture<Void> dispatchRedstoneUpdate(Runnable task) {
        return CompletableFuture.runAsync(task, REDSTONE_EXECUTOR);
    }
}
