package net.minecraft.server.chunk;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.concurrent.CompletableFuture;

/**
 * Predicts and prefetches chunks asynchronously based on player movement vectors.
 * Uses Paper's async chunk loading API and a Caffeine cache to prevent memory overhead.
 */
public class SafeChunkPrefetcher {
    
    // Caffeine cache using TinyLFU internally. 
    // Constrained to roughly max 10% of the heap to avoid memory pressure.
    private final Cache<Long, CompletableFuture<Chunk>> prefetchCache;
    
    // Estimate each chunk takes ~1MB of memory in loaded state
    private static final int ESTIMATED_CHUNK_SIZE_BYTES = 1024 * 1024;

    public SafeChunkPrefetcher() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        long maxCacheBytes = (long) (maxMemory * 0.10); // 10% heap limit
        long maxChunks = Math.max(100, maxCacheBytes / ESTIMATED_CHUNK_SIZE_BYTES);

        this.prefetchCache = Caffeine.newBuilder()
                .maximumSize(maxChunks)
                .build();
    }

    /**
     * Evaluates player movement and prefetches chunks in the direction of travel.
     * Designed to be called from a PlayerMoveEvent listener.
     */
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Vector from = event.getFrom().toVector();
        Vector to = event.getTo().toVector();
        
        Vector direction = to.clone().subtract(from);
        // Ignore micro-movements
        if (direction.lengthSquared() < 0.01) {
            return;
        }
        
        direction.normalize();

        World world = player.getWorld();
        int currentChunkX = event.getTo().getBlockX() >> 4;
        int currentChunkZ = event.getTo().getBlockZ() >> 4;

        // Determine general movement direction (sign)
        int dirX = (int) Math.signum(direction.getX());
        int dirZ = (int) Math.signum(direction.getZ());
        
        // Predict and prefetch a fan-shape of chunks ahead
        // E.g., if moving +X, we prefetch (X+1, Z), (X+1, Z+1), (X+1, Z-1), etc.
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                // If not moving in a particular axis, we can adjust logic or just use simple offset
                if (dx == 0 && dz == 0) continue;
                
                int targetX = currentChunkX + (dirX * dx);
                int targetZ = currentChunkZ + (dirZ * Math.abs(dz)); // Absolute to spread out, simplified for logic
                
                prefetchChunk(world, targetX, targetZ);
            }
        }
    }

    /**
     * Triggers async loading of the chunk if it isn't already cached/loaded.
     */
    private void prefetchChunk(World world, int x, int z) {
        long chunkKey = getChunkKey(x, z);
        
        // Use Caffeine cache to prevent redundant load requests
        prefetchCache.get(chunkKey, key -> {
            // Paper's async API natively returns CompletableFuture<Chunk>
            return world.getChunkAtAsync(x, z).thenApply(chunk -> {
                // Return loaded chunk to cache
                return chunk;
            });
        });
    }

    /**
     * Cleans up or invalidates cache entries when no longer needed.
     */
    public void invalidate(int x, int z) {
        prefetchCache.invalidate(getChunkKey(x, z));
    }

    /**
     * Creates a unique long key from chunk coordinates.
     */
    private long getChunkKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }
}
