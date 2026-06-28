package net.minecraft.server.region;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public class CollisionMitigationManager {

    public static boolean shouldSkipCollision(Object entityObj) {
        if (!AdaptiveTPSManager.isLocalPanicMode()) {
            return false;
        }
        
        if (!(entityObj instanceof Entity)) {
            return false;
        }
        Entity entity = (Entity) entityObj;

        // 1. Boundary Buffer Zone Check
        // If entity is within 3 blocks of a chunk border (x%16 <= 2 or >= 13, z%16 <= 2 or >= 13)
        // Then we force normal collision to prevent phasing/ghost forces at thread boundaries
        int blockX = (int) Math.floor(entity.getX());
        int blockZ = (int) Math.floor(entity.getZ());
        
        int modX = blockX % 16;
        if (modX < 0) modX += 16;
        int modZ = blockZ % 16;
        if (modZ < 0) modZ += 16;
        
        if (modX <= 2 || modX >= 13 || modZ <= 2 || modZ >= 13) {
            return false; // Near boundary, DO NOT skip collision
        }
        
        // Inside safe inner chunk area, we can safely skip collisions
        return true;
    }

    public static void clampVelocity(Object entityObj) {
        if (AdaptiveTPSManager.isLocalPanicMode()) {
            return; // If still in panic mode, no need to clamp yet
        }
        
        if (!(entityObj instanceof Entity)) {
            return;
        }
        Entity entity = (Entity) entityObj;
        
        // To prevent Velocity Explosion when transitioning from Panic to Normal
        try {
            Vec3 vel = entity.getDeltaMovement();
            double vx = vel.x;
            double vy = vel.y;
            double vz = vel.z;
            
            boolean changed = false;
            double MAX_VELOCITY = 1.0;
            
            if (vx > MAX_VELOCITY) { vx = MAX_VELOCITY; changed = true; }
            if (vx < -MAX_VELOCITY) { vx = -MAX_VELOCITY; changed = true; }
            // vy limit slightly higher to allow normal jumps/falling
            if (vy > 2.0) { vy = 2.0; changed = true; } 
            if (vy < -3.0) { vy = -3.0; changed = true; }
            if (vz > MAX_VELOCITY) { vz = MAX_VELOCITY; changed = true; }
            if (vz < -MAX_VELOCITY) { vz = -MAX_VELOCITY; changed = true; }
            
            if (changed) {
                entity.setDeltaMovement(new Vec3(vx, vy, vz));
            }
        } catch (Throwable t) {
            // Ignore mapping or reflection errors
        }
    }
}
