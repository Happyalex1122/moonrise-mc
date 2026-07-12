package net.minecraft.server.region;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class PredictiveChunkPrefetcher {

    // Simple cache to prevent redundant prefetch queuing
    private static final ConcurrentHashMap<Long, Long> pendingPrefetches = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 5000L;

    public static void onPlayerMove(Player player, Location from, Location to) {
        if (AdaptiveTPSManager.isPanicMode()) {
            return; // Skip prefetching if server is in panic mode
        }

        Vector direction = to.toVector().subtract(from.toVector());
        if (direction.lengthSquared() < 0.01) {
            return; // Player not moving fast enough
        }

        direction.normalize();
        
        List<Long> targets = calculateFanArea(to, direction, 3, 30);
        long now = System.currentTimeMillis();

        for (long chunkKey : targets) {
            Long lastTime = pendingPrefetches.get(chunkKey);
            if (lastTime == null || now - lastTime > COOLDOWN_MS) {
                pendingPrefetches.put(chunkKey, now);
                
                // Dispatch to Virtual Thread Queue
                VirtualThreadIODispatcher.dispatchChunkLoad(() -> {
                    int cx = (int) (chunkKey >> 32);
                    int cz = (int) chunkKey;
                    
                    // PaperLib.getChunkAtAsync(player.getWorld(), cx, cz, true); 
                    // Simulation of chunk load call:
                    player.getWorld().getChunkAtAsync(cx, cz);
                });
            }
        }
    }

    private static List<Long> calculateFanArea(Location loc, Vector dir, int depth, int angleDeg) {
        List<Long> targets = new ArrayList<>();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        double baseAngle = Math.atan2(dir.getZ(), dir.getX());
        double halfAngle = Math.toRadians(angleDeg);

        for (int d = 1; d <= depth; d++) {
            int steps = d * 2 + 1;
            for (int s = 0; s < steps; s++) {
                double angle = baseAngle + halfAngle * (2.0 * s / (steps - 1) - 1.0);
                int tx = cx + (int) Math.round(d * Math.cos(angle));
                int tz = cz + (int) Math.round(d * Math.sin(angle));
                long key = ((long) tx << 32) | (tz & 0xFFFFFFFFL);
                targets.add(key);
            }
        }
        return targets;
    }
}
