package net.minecraft.world.entity.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distance-proportional continuous AI interval scaling.
 * Uses PlayerSpatialIndex for O(1) player distance lookups.
 * Optimized for Java 25.
 */
public class GradualAIScheduler {

    private final PlayerSpatialIndex spatialIndex = new PlayerSpatialIndex();

    /**
     * Calculates the AI tick interval based on distance to the nearest player.
     * Uses a continuous function to scale interval up to 30 ticks.
     */
    public int calculateTickInterval(Entity entity) {
        Level level = entity.level();
        double minDistanceSqr = spatialIndex.getNearestPlayerDistanceSqr(level, entity.getX(), entity.getY(), entity.getZ());
        
        if (minDistanceSqr < 0) {
            return 30; // Max sleep if no players are nearby
        }
        
        double distance = Math.sqrt(minDistanceSqr);
        
        // Continuous function: 1 tick if < 16 blocks, up to 30 ticks if > 128 blocks
        if (distance <= 16.0) {
            return 1;
        }
        
        double scale = (distance - 16.0) / (128.0 - 16.0);
        // Using Math.clamp (Java 21+)
        scale = Math.clamp(scale, 0.0, 1.0);
        
        return 1 + (int)(scale * 29);
    }
    
    public PlayerSpatialIndex getSpatialIndex() {
        return spatialIndex;
    }

    /**
     * Fast O(1) chunk based lookup for players to avoid O(N) entity distance scans.
     */
    public static class PlayerSpatialIndex {
        private final Map<Long, Integer> chunkPlayerCounts = new ConcurrentHashMap<>();

        public void onPlayerMove(Player player, ChunkPos oldPos, ChunkPos newPos) {
            if (oldPos != null) {
                chunkPlayerCounts.computeIfPresent(oldPos.toLong(), (ignored, count) -> count > 1 ? count - 1 : null);
            }
            if (newPos != null) {
                chunkPlayerCounts.merge(newPos.toLong(), 1, Integer::sum);
            }
        }
        
        public double getNearestPlayerDistanceSqr(Level level, double x, double y, double z) {
            int chunkX = (int) Math.floor(x) >> 4;
            int chunkZ = (int) Math.floor(z) >> 4;
            
            double minDistanceSqr = -1;
            int searchRadius = 8; // 128 blocks / 16 blocks per chunk
            
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                    long chunkLong = ChunkPos.asLong(chunkX + dx, chunkZ + dz);
                    if (chunkPlayerCounts.containsKey(chunkLong)) {
                        double cx = (chunkX + dx) * 16 + 8;
                        double cz = (chunkZ + dz) * 16 + 8;
                        
                        double distSqr = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                        
                        if (minDistanceSqr < 0 || distSqr < minDistanceSqr) {
                            minDistanceSqr = distSqr;
                        }
                    }
                }
            }
            
            return minDistanceSqr;
        }
    }
}
