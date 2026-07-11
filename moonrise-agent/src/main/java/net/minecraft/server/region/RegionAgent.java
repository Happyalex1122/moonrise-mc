package net.minecraft.server.region;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

/**
 * Java Agent class to instrument Minecraft server and AsyncCatcher classes at class load time.
 * Injects hooks into net.minecraft.server.MinecraftServer.tickServer() and co.aikar.timings.AsyncCatcher.catchOp().
 */
public class RegionAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[RegionAgent] Premain initialization active.");
        
        // 옵션 C: 공간 해시 그리드 전면 적용 바이트코드 패치 등록
        net.minecraft.server.region.EntityCollisionPatch.applyPatch(inst);

        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer)
                    throws IllegalClassFormatException {
                
                if (className == null) {
                    return null;
                }

                // Inject agent jar URL into the target classloader so it can resolve agent classes at runtime
                if (className.equals("org/spigotmc/AsyncCatcher") ||
                    className.equals("co/aikar/timings/AsyncCatcher") || 
                    className.equals("ca/spottedleaf/moonrise/common/util/AsyncCatcher") || 
                    className.equals("net/minecraft/server/MinecraftServer")) {
                    injectAgentIntoLoader(loader);
                }

                // Transform targets (replacing / with . for javassist)
                if (className.equals("org/spigotmc/AsyncCatcher") ||
                    className.equals("co/aikar/timings/AsyncCatcher") || 
                    className.equals("ca/spottedleaf/moonrise/common/util/AsyncCatcher")) {
                    return instrumentAsyncCatcher(loader, className.replace('/', '.'), classfileBuffer);
                } else if (className.equals("net/minecraft/server/MinecraftServer")) {
                    return instrumentMinecraftServer(loader, classfileBuffer);
                }

                return null;
            }
        });
    }

    private static void injectAgentIntoLoader(ClassLoader loader) {
        if (loader == null) return;
        try {
            java.security.CodeSource codeSource = RegionAgent.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) return;
            java.net.URL location = codeSource.getLocation();
            
            // Try URLClassLoader.addURL first
            try {
                java.lang.reflect.Method method = java.net.URLClassLoader.class.getDeclaredMethod("addURL", java.net.URL.class);
                method.setAccessible(true);
                method.invoke(loader, location);
                System.out.println("[RegionAgent] Injected agent jar via URLClassLoader.addURL to: " + loader);
                return;
            } catch (Throwable ignored) {}

            // Fallback: search for any addURL method in the loader's class hierarchy
            Class<?> clazz = loader.getClass();
            while (clazz != null) {
                try {
                    java.lang.reflect.Method method = clazz.getDeclaredMethod("addURL", java.net.URL.class);
                    method.setAccessible(true);
                    method.invoke(loader, location);
                    System.out.println("[RegionAgent] Injected agent jar via reflection addURL on " + clazz.getName());
                    return;
                } catch (NoSuchMethodException e) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (Throwable t) {
            System.err.println("[RegionAgent] Failed to inject agent JAR into loader: " + t.getMessage());
        }
    }

    private static byte[] instrumentAsyncCatcher(ClassLoader loader, String className, byte[] classfileBuffer) {
        try {
            ClassPool pool = new ClassPool(true);
            ClassLoader agentLoader = RegionAgent.class.getClassLoader();
            if (agentLoader != null) {
                pool.appendClassPath(new javassist.LoaderClassPath(agentLoader));
            } else {
                pool.appendClassPath(new javassist.LoaderClassPath(ClassLoader.getSystemClassLoader()));
            }
            if (loader != null) {
                pool.appendClassPath(new javassist.LoaderClassPath(loader));
            }
            pool.appendClassPath(new javassist.ByteArrayClassPath(className, classfileBuffer));

            CtClass cc = pool.get(className);
            
            boolean patched = false;
            for (CtMethod method : cc.getDeclaredMethods()) {
                if (method.getName().equals("catchOp")) {
                    method.insertBefore("if (net.minecraft.server.region.AsyncCatcherPatch.shouldBypass()) { return; }");
                    System.out.println("[RegionAgent] Successfully patched method " + method.getName() + method.getSignature() + " in " + className);
                    patched = true;
                }
            }

            if (!patched) {
                System.err.println("[RegionAgent] Warning: No catchOp method found to patch in " + className);
            }

            byte[] bytecode = cc.toBytecode();
            cc.detach();
            return bytecode;
        } catch (Throwable t) {
            System.err.println("[RegionAgent] Failed to patch AsyncCatcher (" + className + "): " + t.getMessage());
            t.printStackTrace();
            return null;
        }
    }

    private static byte[] instrumentMinecraftServer(ClassLoader loader, byte[] classfileBuffer) {
        try {
            ClassPool pool = new ClassPool(true);
            ClassLoader agentLoader = RegionAgent.class.getClassLoader();
            if (agentLoader != null) {
                pool.appendClassPath(new javassist.LoaderClassPath(agentLoader));
            } else {
                pool.appendClassPath(new javassist.LoaderClassPath(ClassLoader.getSystemClassLoader()));
            }
            if (loader != null) {
                pool.appendClassPath(new javassist.LoaderClassPath(loader));
            }
            pool.appendClassPath(new javassist.ByteArrayClassPath("net.minecraft.server.MinecraftServer", classfileBuffer));

            CtClass cc = pool.get("net.minecraft.server.MinecraftServer");

            // Locating tickServer(BooleanSupplier) - NMS/Mojang mappings
            CtMethod method = cc.getDeclaredMethod("tickServer", new CtClass[]{pool.get("java.util.function.BooleanSupplier")});

            // Route tickServer control to our custom 3-Phase Tick pipeline
            // $0 -> 'this', $1 -> BooleanSupplier argument
            method.insertBefore("if (net.minecraft.server.region.MinecraftServerTickPatch.handleServerTick($0, $1)) { return; }");

            System.out.println("[RegionAgent] Successfully patched net.minecraft.server.MinecraftServer");
            byte[] bytecode = cc.toBytecode();
            cc.detach();
            return bytecode;
        } catch (Throwable t) {
            System.err.println("[RegionAgent] Failed to patch MinecraftServer: " + t.getMessage());
            t.printStackTrace();
            return null;
        }
    }
}
