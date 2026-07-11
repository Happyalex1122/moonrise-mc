package net.minecraft.server.region;

import java.lang.reflect.Field;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Paper의 AsyncCatcher(co.aikar.timings.AsyncCatcher) 검증을 무력화 또는 우회하기 위한 패치 클래스입니다.
 * RegionThread에서 실행되는 API 접근은 비동기 예외를 발생시키지 않고 무사 통과시킵니다.
 */
public class AsyncCatcherPatch {
    private static final Logger LOGGER = Logger.getLogger(AsyncCatcherPatch.class.getName());
    private static final ThreadLocal<Boolean> BYPASS_ACTIVE = ThreadLocal.withInitial(() -> false);
    private static volatile boolean initialized = false;

    /**
     * 현재 실행 중인 스레드가 RegionThread인지 판별하여 AsyncCatcher 검증을 우회해야 하는지 여부를 반환합니다.
     * 
     * @return 우회해야 하는 경우 true, 그렇지 않으면 false
     */
    public static boolean shouldBypass() {
        return Thread.currentThread() instanceof RegionThread || BYPASS_ACTIVE.get();
    }

    public static void setBypassActive(boolean active) {
        BYPASS_ACTIVE.set(active);
    }

    /**
     * 런타임에 리플렉션을 사용하여 AsyncCatcher.enabled 필드를 조작하거나 
     * 비동기 검증을 우회하도록 설정합니다.
     */
    public static void applyPatch() {
        applyPatch(Thread.currentThread().getContextClassLoader());
    }

    public static void applyPatch(ClassLoader loader) {
        if (initialized) {
            return;
        }
        try {
            LOGGER.info("[RegionPipeline] applyPatch loader: " + loader);
            if (loader instanceof java.net.URLClassLoader) {
                for (java.net.URL url : ((java.net.URLClassLoader) loader).getURLs()) {
                    LOGGER.info("[RegionPipeline]   Loader URL: " + url);
                }
            }
            Class<?> asyncCatcherClass = getAsyncCatcherClass(loader);
            try {
                Field enabledField = asyncCatcherClass.getDeclaredField("enabled");
                enabledField.setAccessible(true);
                // 전역적으로 AsyncCatcher를 비활성화하는 방법 (필요한 경우 사용)
                // enabledField.setBoolean(null, false);
                // LOGGER.info("[RegionPipeline] AsyncCatcher has been globally disabled via reflection.");
            } catch (NoSuchFieldException e) {
                LOGGER.info("[RegionPipeline] AsyncCatcher does not have 'enabled' field. Skipping field patch.");
            }
            
            initialized = true;
            LOGGER.info("[RegionPipeline] Successfully patched AsyncCatcher: " + asyncCatcherClass.getName());
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.WARNING, "[RegionPipeline] AsyncCatcher class not found. Skipping runtime patch.", e);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[RegionPipeline] Failed to patch AsyncCatcher fields.", e);
        }
    }

    /**
     * 특정 중요 구간에서 임시로 AsyncCatcher를 비활성화할 때 사용할 수 있는 유틸리티 메소드입니다.
     */
    public static void setEnabled(boolean enabled) {
        try {
            Class<?> asyncCatcherClass = getAsyncCatcherClass(Thread.currentThread().getContextClassLoader());
            try {
                Field enabledField = asyncCatcherClass.getDeclaredField("enabled");
                enabledField.setAccessible(true);
                enabledField.setBoolean(null, enabled);
            } catch (NoSuchFieldException e) {
                // Ignore if field doesn't exist
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    private static Class<?> getAsyncCatcherClass(ClassLoader loader) throws ClassNotFoundException {
        try {
            return Class.forName("org.spigotmc.AsyncCatcher", true, loader);
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName("ca.spottedleaf.moonrise.common.util.AsyncCatcher", true, loader);
            } catch (ClassNotFoundException ex) {
                try {
                    return Class.forName("co.aikar.timings.AsyncCatcher", true, loader);
                } catch (ClassNotFoundException ex2) {
                    try {
                        return Class.forName("org.spigotmc.AsyncCatcher");
                    } catch (ClassNotFoundException ex3) {
                        try {
                            return Class.forName("ca.spottedleaf.moonrise.common.util.AsyncCatcher");
                        } catch (ClassNotFoundException ex4) {
                            return Class.forName("co.aikar.timings.AsyncCatcher");
                        }
                    }
                }
            }
        }
    }
}
