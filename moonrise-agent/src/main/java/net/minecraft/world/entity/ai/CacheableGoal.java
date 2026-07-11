package net.minecraft.world.entity.ai;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an AI Goal as safe for caching.
 * Applied to TargetGoal or Sensor related goals that are deterministic 
 * and side-effect-free.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CacheableGoal {
}
