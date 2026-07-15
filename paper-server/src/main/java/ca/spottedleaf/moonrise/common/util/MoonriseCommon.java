package ca.spottedleaf.moonrise.common.util;

import ca.spottedleaf.concurrentutil.executor.thread.BalancedPrioritisedThreadPool;
import ca.spottedleaf.concurrentutil.numa.OSNuma;
import ca.spottedleaf.moonrise.common.PlatformHooks;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class MoonriseCommon {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    public static final long WORKER_QUEUE_HOLD_TIME = (long)(20.0e6); // 20ms
    public static final BalancedPrioritisedThreadPool WORKER_POOL = new BalancedPrioritisedThreadPool(
        WORKER_QUEUE_HOLD_TIME,
        new ThreadFactory() {
            private final AtomicInteger idGenerator = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable run) {
                final Thread thread = new Thread(run, PlatformHooks.get().getBrand() + " Common Worker #" + this.idGenerator.getAndIncrement());

                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(final Thread thread, final Throwable throwable) {
                        LOGGER.error("Uncaught exception in thread " + thread.getName(), throwable);
                    }
                });

                return thread;
            }
        }
    );
    public static final BalancedPrioritisedThreadPool.OrderedStreamGroup CLIENT_GROUP = MoonriseCommon.WORKER_POOL.createOrderedStreamGroup();
    public static final BalancedPrioritisedThreadPool.OrderedStreamGroup SERVER_GROUP = MoonriseCommon.WORKER_POOL.createOrderedStreamGroup();

    public static void adjustWorkerThreads(final int configWorkerThreads, final int configIoThreads) {
        int defaultWorkerThreads = OSNuma.getNativeInstance().getTotalCores();
        // Moonrise start - optimize default thread formulas for containers and low-core setups
        int jvmCores = Runtime.getRuntime().availableProcessors();
        if (jvmCores > defaultWorkerThreads) {
            defaultWorkerThreads = jvmCores;
        }

        if (defaultWorkerThreads <= 2) {
            defaultWorkerThreads = 1;
        } else if (defaultWorkerThreads <= 4) {
            defaultWorkerThreads = Math.max(2, defaultWorkerThreads - 1);
        } else {
            defaultWorkerThreads = Math.min(8, (int) (defaultWorkerThreads * 0.6));
        }
        defaultWorkerThreads = Integer.getInteger(PlatformHooks.get().getBrand() + ".WorkerThreadCount", Integer.valueOf(defaultWorkerThreads));

        int workerThreads = configWorkerThreads;
        if (workerThreads <= 0) {
            workerThreads = defaultWorkerThreads;
        }

        int ioThreads = configIoThreads;
        if (ioThreads <= 0) {
            ioThreads = defaultWorkerThreads >= 3 ? 2 : 1;
        }
        ioThreads = Math.max(1, ioThreads);
        // Moonrise end

        WORKER_POOL.adjustThreadCount(workerThreads);
        IO_POOL.adjustThreadCount(ioThreads);

        LOGGER.info(PlatformHooks.get().getBrand() + " is using " + workerThreads + " worker threads, " + ioThreads + " I/O threads");
    }

    public static final long IO_QUEUE_HOLD_TIME = (long)(25.0e6); // 25ms
    public static final BalancedPrioritisedThreadPool IO_POOL = new BalancedPrioritisedThreadPool(
        IO_QUEUE_HOLD_TIME,
        new ThreadFactory() {
                private final AtomicInteger idGenerator = new AtomicInteger();

                @Override
                public Thread newThread(final Runnable run) {
                    final Thread thread = new Thread(run, PlatformHooks.get().getBrand() + " I/O Worker #" + this.idGenerator.getAndIncrement());

                    thread.setDaemon(true);
                    thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                        @Override
                        public void uncaughtException(final Thread thread, final Throwable throwable) {
                            LOGGER.error("Uncaught exception in thread " + thread.getName(), throwable);
                        }
                    });

                    return thread;
                }
            }
    );
    public static final BalancedPrioritisedThreadPool.OrderedStreamGroup CLIENT_IO_GROUP = IO_POOL.createOrderedStreamGroup();
    public static final BalancedPrioritisedThreadPool.OrderedStreamGroup SERVER_IO_GROUP = IO_POOL.createOrderedStreamGroup();

    public static void haltExecutors() {
        MoonriseCommon.WORKER_POOL.shutdown(false);
        LOGGER.info("Awaiting termination of worker pool for up to 60s...");
        if (!MoonriseCommon.WORKER_POOL.join(TimeUnit.SECONDS.toMillis(60L))) {
            LOGGER.error("Worker pool did not shut down in time!");
            MoonriseCommon.WORKER_POOL.halt(false);
        }

        MoonriseCommon.IO_POOL.shutdown(false);
        LOGGER.info("Awaiting termination of I/O pool for up to 60s...");
        if (!MoonriseCommon.IO_POOL.join(TimeUnit.SECONDS.toMillis(60L))) {
            LOGGER.error("I/O pool did not shut down in time!");
            MoonriseCommon.IO_POOL.halt(false);
        }
    }

    private MoonriseCommon() {}
}
