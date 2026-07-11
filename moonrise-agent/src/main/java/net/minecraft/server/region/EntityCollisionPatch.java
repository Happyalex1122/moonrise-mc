package net.minecraft.server.region;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

public class EntityCollisionPatch implements ClassFileTransformer {

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        
        if (className != null && className.equals("net/minecraft/world/entity/LivingEntity")) {
            System.out.println("[EntityCollisionPatch] Found LivingEntity class. Patching pushEntities for O(N) collision...");
            return patchPushEntities(loader, classfileBuffer);
        }
        
        return classfileBuffer;
    }

    private byte[] patchPushEntities(ClassLoader loader, byte[] originalClass) {
        try {
            ClassPool pool = ClassPool.getDefault();
            pool.insertClassPath(new javassist.LoaderClassPath(loader != null ? loader : ClassLoader.getSystemClassLoader()));
            pool.insertClassPath(new javassist.ByteArrayClassPath("net.minecraft.world.entity.LivingEntity", originalClass));
            
            CtClass cc = pool.get("net.minecraft.world.entity.LivingEntity");
            CtMethod method = cc.getDeclaredMethod("pushEntities");
            
            // Conditionally bypass pushEntities when the local thread is in Panic Mode and not in boundary zone
            method.insertBefore("{ if (net.minecraft.server.region.CollisionMitigationManager.shouldSkipCollision(this)) return; }");
            
            // Add velocity clamping at the end of the method (applies when panic mode is OFF or turning OFF)
            method.insertAfter("{ net.minecraft.server.region.CollisionMitigationManager.clampVelocity(this); }");
            
            System.out.println("[EntityCollisionPatch] Successfully patched LivingEntity.pushEntities with Thread-Local Panic & Clamping!");
            byte[] bytecode = cc.toBytecode();
            cc.detach();
            return bytecode;
        } catch (Exception e) {
            System.err.println("[EntityCollisionPatch] Failed to patch LivingEntity collision: " + e.getMessage());
            e.printStackTrace();
            return originalClass;
        }
    }
    
    public static void applyPatch(java.lang.instrument.Instrumentation inst) {
        inst.addTransformer(new EntityCollisionPatch(), true);
        System.out.println("[EntityCollisionPatch] Transformer registered.");
    }
}
