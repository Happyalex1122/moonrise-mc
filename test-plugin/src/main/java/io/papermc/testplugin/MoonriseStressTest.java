package io.papermc.testplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

public class MoonriseStressTest {

    public static void runStressTest(Plugin plugin) {
        plugin.getLogger().info("=== MOONRISE STRESS TEST STARTED ===");
        World world = Bukkit.getWorlds().get(0);
        Location loc = world.getSpawnLocation();
        
        // 1. Massive chunk generation (Elytra simulation)
        plugin.getLogger().info("Phase 1: Massive Chunk Loading (Elytra Sim)...");
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                world.getChunkAtAsync(cx + x, cz + z);
            }
        }
        
        // 2. Spawn thousands of entities
        plugin.getLogger().info("Phase 2: Spawning 2000 Entities...");
        for (int i = 0; i < 2000; i++) {
            world.spawnEntity(loc.clone().add(Math.random() * 20 - 10, 5, Math.random() * 20 - 10), EntityType.ZOMBIE);
        }
        
        // 3. Monitor performance
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            double[] tps = Bukkit.getTPS();
            double mspt = Bukkit.getAverageTickTime();
            long freeMem = Runtime.getRuntime().freeMemory() / 1024 / 1024;
            long maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024;
            plugin.getLogger().info(String.format("[STRESS METRICS] TPS: %.2f | MSPT: %.2fms | Memory: %dMB / %dMB", tps[0], mspt, (maxMem - freeMem), maxMem));
        }, 100L, 100L); // every 5 seconds
    }
}
