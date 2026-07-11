package net.minecraft.world.entity.ai;

import net.minecraft.world.entity.ai.goal.Goal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mixin to implement safe, side-effect-free AI caching for goals marked with @CacheableGoal.
 */
@Mixin(targets = "net.minecraft.world.entity.ai.goal.GoalSelector")
public class SelectiveGoalCacheMixin {

    @Unique
    private static final Map<Goal, CacheEntry> CACHE = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    @Unique
    private static final long CACHE_TTL_MS = 50L; // Example TTL for a tick

    @Unique
    private record CacheEntry(boolean result, long timestamp) {}


    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void onCanUse(Goal goal, CallbackInfoReturnable<Boolean> cir) {
        if (goal.getClass().isAnnotationPresent(CacheableGoal.class)) {
            CacheEntry entry = CACHE.get(goal);
            if (entry != null && (System.currentTimeMillis() - entry.timestamp()) < CACHE_TTL_MS) {
                cir.setReturnValue(entry.result());
            }
        }
    }

    @Inject(method = "canUse", at = @At("RETURN"))
    private void onCanUseReturn(Goal goal, CallbackInfoReturnable<Boolean> cir) {
        if (goal.getClass().isAnnotationPresent(CacheableGoal.class)) {
            CACHE.put(goal, new CacheEntry(cir.getReturnValue(), System.currentTimeMillis()));
        }
    }
}
