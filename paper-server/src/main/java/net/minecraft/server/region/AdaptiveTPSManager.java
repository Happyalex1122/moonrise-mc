package net.minecraft.server.region;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Collection;

// NMS/Bukkit Imports for priority filtering
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class AdaptiveTPSManager {

    private static long PANIC_THRESHOLD_NANOS;
    private static long RECOVERY_THRESHOLD_NANOS;

    static {
        setPanicTps(Double.parseDouble(System.getProperty("adaptive.panic.tps", "10.0")));
        setRecoveryTps(Double.parseDouble(System.getProperty("adaptive.recovery.tps", "15.0")));
    }

    public static void setPanicTps(double tps) {
        PANIC_THRESHOLD_NANOS = (long) ((1000.0 / tps) * 1_000_000L);
    }

    public static void setRecoveryTps(double tps) {
        RECOVERY_THRESHOLD_NANOS = (long) ((1000.0 / tps) * 1_000_000L);
    }

    private static final AtomicLong lastTickTimeNanos = new AtomicLong(0L);
    private static final AtomicBoolean panicMode = new AtomicBoolean(false);
    private static final AtomicLong currentTickId = new AtomicLong(0L);
    
    // Thread-Local Panic State
    private static final ThreadLocal<Boolean> localPanicMode = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Long> localPanicStartTime = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> localTickStartTime = ThreadLocal.withInitial(() -> 0L);

    // Cache for fast proximity checks without querying the world repeatedly
    private static volatile double[] flattenedPlayerPositions = new double[0];

    private static long panicStartTime = 0L;
    private static long lastPrintTime = 0L;
    private static final long PANIC_COOLDOWN_MS = 5000L; // 패닉 모드 최소 유지 시간 (5초)

    public static void startLocalTick() {
        localTickStartTime.set(System.nanoTime());
    }

    public static void endLocalTick() {
        long start = localTickStartTime.get();
        if (start == 0L) return;
        long duration = System.nanoTime() - start;
        recordLocalTickTime(duration);
    }

    public static void recordLocalTickTime(long timeNanos) {
        long now = System.currentTimeMillis();
        
        if (timeNanos > PANIC_THRESHOLD_NANOS) {
            if (!localPanicMode.get()) {
                localPanicMode.set(true);
                localPanicStartTime.set(now);
                if (now - lastPrintTime > 10000L) {
                    System.out.println("[AdaptiveTPSManager-" + Thread.currentThread().getName() + "] Thread overload detected! (" + (timeNanos / 1_000_000L) + "ms). LOCAL PANIC MODE ENABLED.");
                    lastPrintTime = now;
                }
            } else {
                localPanicStartTime.set(now);
            }
        } else if (timeNanos < RECOVERY_THRESHOLD_NANOS) {
            if (localPanicMode.get()) {
                if (now - localPanicStartTime.get() > PANIC_COOLDOWN_MS) {
                    localPanicMode.set(false);
                    if (now - lastPrintTime > 10000L) {
                        System.out.println("[AdaptiveTPSManager-" + Thread.currentThread().getName() + "] Thread stabilized (" + (timeNanos / 1_000_000L) + "ms). LOCAL PANIC MODE DISABLED.");
                        lastPrintTime = now;
                    }
                }
            }
        }
        
        // Update global fallback for legacy compatibility
        recordTickTime(timeNanos);
    }

    public static boolean isLocalPanicMode() {
        return localPanicMode.get();
    }

    public static boolean isLocalPanicMode(String worldName) {
        // Fallback to thread-local panic mode for now, since per-world TPS configs are not fully exposed yet
        return isLocalPanicMode();
    }

    public static void recordTickTime(long timeNanos) {
        lastTickTimeNanos.set(timeNanos);
        long now = System.currentTimeMillis();
        
        if (timeNanos > PANIC_THRESHOLD_NANOS) {
            if (!panicMode.get()) {
                panicMode.set(true);
                panicStartTime = now;
            } else {
                panicStartTime = now;
            }
        } else if (timeNanos < RECOVERY_THRESHOLD_NANOS) {
            if (panicMode.get()) {
                if (now - panicStartTime > PANIC_COOLDOWN_MS) {
                    panicMode.set(false);
                }
            }
        }
    }

    public static void incrementTick() {
        currentTickId.incrementAndGet();
        
        // Update player positions once per tick if we are in panic mode (to save overhead)
        if (panicMode.get()) {
            try {
                if (Bukkit.getServer() != null) {
                    Collection<? extends Player> players = Bukkit.getOnlinePlayers();
                    double[] newPositions = new double[players.size() * 3];
                    int i = 0;
                    for (Player p : players) {
                        if (i + 2 >= newPositions.length) break;
                        net.minecraft.world.entity.player.Player nmsPlayer = ((org.bukkit.craftbukkit.entity.CraftPlayer) p).getHandle();
                        newPositions[i++] = nmsPlayer.getX();
                        newPositions[i++] = nmsPlayer.getY();
                        newPositions[i++] = nmsPlayer.getZ();
                    }
                    if (i != newPositions.length) {
                        double[] exactPositions = new double[i];
                        System.arraycopy(newPositions, 0, exactPositions, 0, i);
                        flattenedPlayerPositions = exactPositions;
                    } else {
                        flattenedPlayerPositions = newPositions;
                    }
                }
            } catch (Exception e) {
                // Ignore if Bukkit not ready
            }
        }
    }

    public static boolean isPanicMode() {
        return panicMode.get();
    }

    public static long getCurrentTickId() {
        return currentTickId.get();
    }
    
    /**
     * Entity Tick Slicing: Priority filter applies here.
     */
    public static boolean shouldSkipEntityTick(Object entityObj) {
        if (!isPanicMode()) return false;
        
        if (!(entityObj instanceof Entity)) return false;
        Entity entity = (Entity) entityObj;

        // --- Priority 1: High Priority Entity Types ---
        if (entity instanceof Projectile) return false; // Never lag arrows/fireballs
        if (entity instanceof WitherBoss || entity instanceof EnderDragon) return false; // Never lag bosses
        
        // --- Priority 2: Player Proximity Filter (Radius: 8 blocks) ---
        double ex = entity.getX();
        double ey = entity.getY();
        double ez = entity.getZ();
        
        boolean nearPlayer = false;
        double[] positions = flattenedPlayerPositions;
        for (int i = 0; i < positions.length; i += 3) {
            double dx = positions[i] - ex;
            double dy = positions[i+1] - ey;
            double dz = positions[i+2] - ez;
            // 8 blocks squared = 64
            if ((dx*dx + dy*dy + dz*dz) < 64.0) {
                nearPlayer = true;
                break;
            }
        }
        
        if (nearPlayer) {
            return false; // Don't skip ticks for entities right next to a player
        }

        // --- Low Priority: Apply Tick Slicing (skip 2 out of 3 ticks) ---
        int hash = System.identityHashCode(entity);
        long tick = currentTickId.get();
        
        return (hash % 3) != (tick % 3);
    }
}
