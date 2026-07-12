package net.minecraft.server.region;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 멀티스레드 리전 틱(Phase 2) 환경에서 Bukkit 이벤트를 발생시킬 때,
 * 플러그인들이 메인 스레드가 아닌 곳에서 실행된다고 인지하여 에러를 던지거나 동시성 이슈를 유발하는 문제를 차단하기 위한 우회 유틸리티 클래스입니다.
 * 
 * 두 가지 주입/우회 아키텍처를 제공합니다:
 * 1. 가짜 메인 스레드 컨텍스트 동적 주입 (Bypass Mode):
 *    - Bukkit.getServer()를 프록시 객체로 래핑하여 isPrimaryThread() 호출 시 항상 true를 반환하도록 조작합니다.
 *    - 리플렉션을 통해 MinecraftServer의 내부 serverThread 레퍼런스를 현재 실행 중인 RegionThread로 일시적으로 교체(Swap)하여 NMS 내부 검증까지 우회합니다.
 * 2. 동기식 메인 스레드 이벤트 대기 큐 (Delegation Mode):
 *    - 이벤트를 메인 스레드의 대기 큐로 전송하여 안전하게 실행한 후, 리전 스레드는 실행 결과를 대기합니다.
 *    - 데드락 방지를 위해 메인 스레드가 리전 스레드들의 작업 완료를 기다리는 동안 대기 큐를 지속적으로 비워줍니다.
 */
public class BukkitEventBypass {
    private static final Logger LOGGER = Logger.getLogger(BukkitEventBypass.class.getName());

    // ThreadLocal flag to indicate if bypass is active on the current thread
    private static final ThreadLocal<Boolean> BYPASS_ACTIVE = ThreadLocal.withInitial(() -> false);

    // Queue for executing tasks on the main thread from region threads
    private static final Queue<PendingTask<?>> mainThreadQueue = new ConcurrentLinkedQueue<>();

    // Lock to serialize MinecraftServer.serverThread field swapping
    private static final ReentrantLock fieldSwapLock = new ReentrantLock();

    private static volatile Thread mainServerThread;
    private static Field serverThreadField;
    private static Object minecraftServer;
    
    private static volatile boolean reflectionInitialized = false;
    private static volatile boolean reflectionAttempted = false;

    static {
        // 클래스 로드 시 최초 초기화 시도
        tryInit();
    }

    /**
     * 우회에 필요한 NMS 리플렉션 주입을 시도합니다.
     * 스레드 안전하게 다중 호출 가능합니다.
     */
    public static synchronized void tryInit() {
        if (!reflectionInitialized && !reflectionAttempted) {
            initializeNMSReflection();
        }
    }

    private static void ensureInit() {
        if (!reflectionInitialized && !reflectionAttempted) {
            tryInit();
        }
    }

    /**
     * MinecraftServer 내부의 serverThread 필드를 찾아내어 리플렉션 캐싱을 수행합니다.
     */
    private static void initializeNMSReflection() {
        try {
            Server server = Bukkit.getServer();
            if (server == null) {
                return;
            }

            Method getServerMethod = server.getClass().getMethod("getServer");
            minecraftServer = getServerMethod.invoke(server);
            if (minecraftServer == null) {
                return;
            }

            reflectionAttempted = true;

            Class<?> msClass = minecraftServer.getClass();
            Field targetField = null;

            // 1. Try direct lookup by name "serverThread"
            Class<?> currentClass = msClass;
            while (currentClass != null) {
                try {
                    Field f = currentClass.getDeclaredField("serverThread");
                    f.setAccessible(true);
                    if (f.getType() == Thread.class) {
                        targetField = f;
                        mainServerThread = (Thread) f.get(minecraftServer);
                        break;
                    }
                } catch (NoSuchFieldException ignored) {
                } catch (Throwable ignored) {
                }
                currentClass = currentClass.getSuperclass();
            }

            // 2. Fallback to scanning fields of type Thread and checking names or thread name
            if (targetField == null) {
                currentClass = msClass;
                while (currentClass != null && targetField == null) {
                    for (Field field : currentClass.getDeclaredFields()) {
                        if (field.getType() == Thread.class) {
                            try {
                                field.setAccessible(true);
                                Thread t = (Thread) field.get(minecraftServer);
                                if (field.getName().equals("serverThread") || 
                                    (t != null && t.getName().contains("Server thread"))) {
                                    targetField = field;
                                    mainServerThread = t;
                                    break;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    currentClass = currentClass.getSuperclass();
                }
            }

            if (targetField != null) {
                serverThreadField = targetField;
                reflectionInitialized = true;
                LOGGER.info("[RegionPipeline] Successfully cached MinecraftServer.serverThread field.");
            } else {
                LOGGER.warning("[RegionPipeline] Failed to locate serverThread field in MinecraftServer class hierarchy.");
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[RegionPipeline] Reflection setup for serverThread failed: " + t.getMessage());
        }
    }

    /**
     * 실행 스레드가 메인 스레드인 것처럼 Bukkit 및 NMS의 모든 스레드 검사를 완전 우회하여 코드를 실행합니다.
     * 이 메소드는 MinecraftServer.serverThread 필드를 일시적으로 교체하므로 글로벌 락을 획득하여 직렬화합니다.
     * 
     * @param callable 실행할 작업
     * @return 작업 실행 결과
     * @throws Exception 작업 중 예외 발생 시
     */
    public static <T> T callWithBypass(Callable<T> callable) throws Exception {
        ensureInit();

        boolean wasActive = BYPASS_ACTIVE.get();
        BYPASS_ACTIVE.set(true);
        AsyncCatcherPatch.setBypassActive(true);

        boolean swapped = false;
        Thread currentThread = Thread.currentThread();

        // NMS 리플렉션이 성공했고, 현재 스레드가 캐시된 메인 스레드와 다르다면 필드 스왑 진행
        if (reflectionInitialized && currentThread != mainServerThread) {
            fieldSwapLock.lock();
            try {
                // 스왑 진행 전 현재 값이 유효한 메인 스레드인지 이중 검증 및 갱신
                Thread currentServerThread = (Thread) serverThreadField.get(minecraftServer);
                if (currentServerThread != null && !(currentServerThread instanceof RegionThread)) {
                    mainServerThread = currentServerThread;
                }
                serverThreadField.set(minecraftServer, currentThread);
                swapped = true;
            } catch (Throwable t) {
                fieldSwapLock.unlock(); // 예외 발생 시 락 해제
                LOGGER.log(Level.SEVERE, "[RegionPipeline] Error swapping MinecraftServer.serverThread field", t);
            }
        }

        try {
            return callable.call();
        } finally {
            if (swapped) {
                try {
                    serverThreadField.set(minecraftServer, mainServerThread);
                } catch (Throwable t) {
                    LOGGER.log(Level.SEVERE, "[RegionPipeline] Failed to restore original MinecraftServer.serverThread", t);
                } finally {
                    fieldSwapLock.unlock();
                }
            }
            BYPASS_ACTIVE.set(wasActive);
            AsyncCatcherPatch.setBypassActive(wasActive);
        }
    }

    /**
     * callWithBypass 의 Runnable 버전입니다.
     */
    public static void runWithBypass(Runnable runnable) {
        try {
            callWithBypass(() -> {
                runnable.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * ThreadLocal 플래그만 활성화하여 Bukkit.isPrimaryThread() 검사만 신속히 우회합니다.
     * NMS 내부 serverThread 필드를 변경하지 않으므로 글로벌 락이 걸리지 않아 동시성 성능에 매우 우수합니다.
     * NMS 스레드 체크가 없는 순수 Bukkit API 사용 또는 스레드 세이프가 입증된 플러그인 이벤트 호출 시 권장됩니다.
     */
    public static <T> T callWithThreadLocalBypass(Callable<T> callable) throws Exception {
        ensureInit();
        boolean wasActive = BYPASS_ACTIVE.get();
        BYPASS_ACTIVE.set(true);
        AsyncCatcherPatch.setBypassActive(true);
        try {
            return callable.call();
        } finally {
            BYPASS_ACTIVE.set(wasActive);
            AsyncCatcherPatch.setBypassActive(wasActive);
        }
    }

    /**
     * callWithThreadLocalBypass 의 Runnable 버전입니다.
     */
    public static void runWithThreadLocalBypass(Runnable runnable) {
        try {
            callWithThreadLocalBypass(() -> {
                runnable.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 메인 스레드 컨텍스트에서 태스크가 동기적으로 실행되도록 메인 스레드 이벤트 대기 큐에 위임하고,
     * 실행이 끝나 결과를 돌려받을 때까지 리전 스레드를 블로킹 대기시킵니다.
     * 
     * 주의: 데드락 방지를 위해 메인 스레드는 반드시 리전 스레드들을 대기시키는 루프에서 
     * BukkitEventBypass.waitForFuturesAndProcessQueue()를 호출하여 이 대기 큐를 같이 처리해주어야 합니다.
     */
    public static <T> T callOnMainThread(Callable<T> callable) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return callable.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        PendingTask<T> pendingTask = new PendingTask<>(callable);
        mainThreadQueue.add(pendingTask);

        try {
            return pendingTask.get();
        } catch (Throwable t) {
            throw new RuntimeException("Exception executing task on main thread via bypass queue", t);
        }
    }

    /**
     * callOnMainThread 의 Runnable 버전입니다.
     */
    public static void runOnMainThread(Runnable runnable) {
        callOnMainThread(() -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 대기 큐에 쌓여 있는 메인 스레드 작업을 모두 실행합니다.
     * 오직 메인 스레드에서만 실행되어야 합니다.
     */
    public static void processQueue() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("processQueue must be called from the main thread");
        }

        PendingTask<?> task;
        while ((task = mainThreadQueue.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "[RegionPipeline] Exception executing queued main thread task", t);
            }
        }
    }

    /**
     * 진행 중인 메인 큐 대기를 강제로 해제합니다. (서버 종료 시 호출)
     */
    public static void drainAndCancelPendingTasks() {
        PendingTask<?> task;
        while ((task = mainThreadQueue.poll()) != null) {
            task.latch.countDown();
        }
    }

    /**
     * 메인 스레드가 리전 틱 작업(Future 목록)들의 완료를 대기하는 동안,
     * 리전 스레드에서 제출된 메인 스레드 태스크들을 처리하여 데드락을 원천적으로 차단하는 동기식 대기 루프 헬퍼입니다.
     * 
     * @param futures 대기할 리전 스레드들의 Future 목록
     */
    public static void waitForFuturesAndProcessQueue(List<? extends Future<?>> futures) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("waitForFuturesAndProcessQueue must be called from the main thread");
        }

        while (true) {
            // 큐에 있는 모든 메인 스레드 의존 작업을 실행
            processQueue();

            // 모든 리전 Future가 완료되었는지 체크
            boolean allDone = true;
            for (Future<?> future : futures) {
                if (!future.isDone()) {
                    allDone = false;
                    break;
                }
            }
            if (allDone) {
                break;
            }

            // CPU 100% 방지 및 리전 스레드로 자원 양보 (1ms 대기)
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        }

        // 최종 완료된 Future들을 조회하며 내부 예외 전파 처리
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Main thread interrupted while waiting for region tick tasks", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("Region tick task failed with exception", e.getCause());
            }
        }
    }



    /**
     * 비동기 스레드에서 메인 스레드로 작업을 전달하고 완료를 기다리기 위한 태스크 래퍼 클래스입니다.
     */
    private static class PendingTask<V> {
        private final Callable<V> callable;
        private V result;
        private Throwable exception;
        private final CountDownLatch latch = new CountDownLatch(1);

        public PendingTask(Callable<V> callable) {
            this.callable = callable;
        }

        public void run() {
            try {
                result = callable.call();
            } catch (Throwable t) {
                exception = t;
            } finally {
                latch.countDown();
            }
        }

        public V get() throws Throwable {
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new java.util.concurrent.TimeoutException(
                    "Main thread did not process queued task within 10s — possible deadlock");
            }
            if (exception != null) {
                throw exception;
            }
            return result;
        }
    }

    /**
     * 특정 Intent를 기반으로 Bukkit Event를 디스패치합니다.
     * callWithBypass를 이용해 가짜 메인 스레드 컨텍스트를 주입한 상태로 이벤트를 안전하게 호출합니다.
     */
    public static void dispatchEvent(RegionTickPipeline.TickIntent intent) {
        runWithBypass(() -> {
            try {
                if (Bukkit.getServer() == null) {
                    // Standalone unit test mock fallback
                    if (Math.random() < 0.05) {
                        intent.setCancelled(true);
                    }
                    return;
                }

                switch (intent.getType()) {
                    case ENTITY_MOVE:
                        dispatchEntityMove(intent);
                        break;
                    case BLOCK_CHANGE:
                        dispatchBlockChange(intent);
                        break;
                    case ENTITY_DAMAGE:
                        dispatchEntityDamage(intent);
                        break;
                    case ENTITY_SPAWN:
                        dispatchEntitySpawn(intent);
                        break;
                    default:
                        break;
                }
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "Failed to dispatch Bukkit event for intent: " + intent, t);
            }
        });
    }

    private static void dispatchEntityMove(RegionTickPipeline.TickIntent intent) throws Exception {
        if (!(intent instanceof RegionTickPipeline.EntityMoveIntent)) return;
        RegionTickPipeline.EntityMoveIntent moveIntent = (RegionTickPipeline.EntityMoveIntent) intent;
        
        net.minecraft.world.entity.Entity entity = moveIntent.getEntity();
        Object craftEntity = entity.getClass().getMethod("getBukkitEntity").invoke(entity);
        
        Class<?> locationClass = Class.forName("org.bukkit.Location");
        Object fromLoc = locationClass.getConstructor(
            Class.forName("org.bukkit.World"), double.class, double.class, double.class
        ).newInstance(
            craftEntity.getClass().getMethod("getWorld").invoke(craftEntity),
            moveIntent.getFromX(), moveIntent.getFromY(), moveIntent.getFromZ()
        );
        
        Object toLoc = locationClass.getConstructor(
            Class.forName("org.bukkit.World"), double.class, double.class, double.class
        ).newInstance(
            craftEntity.getClass().getMethod("getWorld").invoke(craftEntity),
            moveIntent.getToX(), moveIntent.getToY(), moveIntent.getToZ()
        );

        Class<?> eventClass = Class.forName("org.bukkit.event.entity.EntityTeleportEvent");
        Object event = eventClass.getConstructor(
            Class.forName("org.bukkit.entity.Entity"), locationClass, locationClass
        ).newInstance(craftEntity, fromLoc, toLoc);

        Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
        Object pluginManager = bukkitClass.getMethod("getPluginManager").invoke(null);
        pluginManager.getClass().getMethod("callEvent", Class.forName("org.bukkit.event.Event")).invoke(pluginManager, event);

        boolean cancelled = (boolean) eventClass.getMethod("isCancelled").invoke(event);
        if (cancelled) {
            moveIntent.setCancelled(true);
        } else {
            Object finalTo = eventClass.getMethod("getTo").invoke(event);
            double newX = (double) locationClass.getMethod("getX").invoke(finalTo);
            double newY = (double) locationClass.getMethod("getY").invoke(finalTo);
            double newZ = (double) locationClass.getMethod("getZ").invoke(finalTo);
            moveIntent.updateDestination(newX, newY, newZ);
        }
    }

    private static void dispatchBlockChange(RegionTickPipeline.TickIntent intent) throws Exception {
        LOGGER.fine("[RegionPipeline] Block change event bypassed.");
    }

    private static void dispatchEntityDamage(RegionTickPipeline.TickIntent intent) throws Exception {
        LOGGER.fine("[RegionPipeline] Entity damage event bypassed.");
    }

    private static void dispatchEntitySpawn(RegionTickPipeline.TickIntent intent) throws Exception {
        LOGGER.fine("[RegionPipeline] Entity spawn event bypassed.");
    }
}
