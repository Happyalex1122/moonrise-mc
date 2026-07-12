package org.moonrise.multiworld;

import org.bukkit.Bukkit;
import org.bukkit.WorldCreator;

import java.io.File;
import java.util.logging.Logger;

public class MoonriseWorldManager {

    public static void autoLoadWorlds() {
        File serverRoot = new File(".");
        File[] files = serverRoot.listFiles();
        Logger logger = Bukkit.getLogger();

        if (files == null) {
            return;
        }

        for (File dir : files) {
            if (dir.isDirectory()) {
                String worldName = dir.getName();

                // Skip default worlds
                if (worldName.equals("world") || worldName.equals("world_nether") || worldName.equals("world_the_end")) {
                    continue;
                }

                File levelDat = new File(dir, "level.dat");
                if (levelDat.exists() && levelDat.isFile()) {
                    if (Bukkit.getWorld(worldName) == null) {
                        logger.info("[Moonrise] Auto-discovering and loading world: " + worldName);
                        try {
                            Bukkit.createWorld(new WorldCreator(worldName));
                        } catch (Exception e) {
                            logger.severe("[Moonrise] Failed to auto-load world " + worldName + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
    }
}
