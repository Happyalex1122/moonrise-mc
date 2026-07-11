package net.minecraft.network;

/**
 * Throttles packet sending for non-player entities based on their distance to the player.
 * Ensures PvP safety by not throttling players, projectiles, or very close entities.
 * 
 * Optimized for Java 25 multithreaded environment (RegionThread architecture).
 * Uses stateless tick-offsetting to prevent network spikes without requiring concurrent maps.
 */
public final class DistanceBasedPacketThrottler {
    
    // Distance thresholds (squared for performance)
    private static final double NEAR_DIST_SQ = 16.0 * 16.0;   // 16 blocks (No throttling)
    private static final double MID_DIST_SQ = 32.0 * 32.0;    // 32 blocks (1/2 throttling)
    private static final double FAR_DIST_SQ = 64.0 * 64.0;    // 64 blocks (1/4 throttling)
    
    // Private constructor to prevent instantiation
    private DistanceBasedPacketThrottler() {}

    /**
     * Determines whether a packet for a specific entity should be throttled (dropped).
     *
     * @param serverTick The current server tick count.
     * @param entityId The ID of the entity.
     * @param isPlayerOrProjectile True if the entity is a player or projectile (always returns false).
     * @param distanceSq The squared distance between the observing player and the entity.
     * @return true if the packet should be throttled (not sent), false otherwise.
     */
    public static boolean shouldThrottle(int serverTick, int entityId, boolean isPlayerOrProjectile, double distanceSq) {
        // PvP Safe: Never throttle players, projectiles, or entities within close combat range
        if (isPlayerOrProjectile || distanceSq <= NEAR_DIST_SQ) {
            return false;
        }

        // Use entityId as an offset to stagger updates and prevent network spikes
        int staggeredTick = serverTick + entityId;

        if (distanceSq <= MID_DIST_SQ) {
            // Throttle 50% of packets (send every 2nd tick)
            return (staggeredTick & 1) != 0; // Same as % 2
        } else if (distanceSq <= FAR_DIST_SQ) {
            // Throttle 75% of packets (send every 4th tick)
            return (staggeredTick & 3) != 0; // Same as % 4
        } else {
            // Throttle 90% of packets (send every 10th tick) for very far entities
            return (staggeredTick % 10) != 0;
        }
    }
}
