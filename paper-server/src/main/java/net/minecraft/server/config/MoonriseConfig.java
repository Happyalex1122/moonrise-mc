package net.minecraft.server.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

public class MoonriseConfig {

    private static File configFile;
    private static YamlConfiguration config;

    public static double adaptivePanicTps = 10.0;
    public static double adaptiveRecoveryTps = 15.0;
    public static boolean enableLinearFormat = true;
    public static boolean enableAsyncEntityDB = true;
    public static long worldMigrationDelay = 30000L;

    public static void init() {
        configFile = new File("moonrise.yml");
        config = YamlConfiguration.loadConfiguration(configFile);

        loadConfig();
    }

    private static void loadConfig() {
        adaptivePanicTps = config.getDouble("adaptive-tps.panic-threshold", 10.0);
        adaptiveRecoveryTps = config.getDouble("adaptive-tps.recovery-threshold", 15.0);
        enableLinearFormat = config.getBoolean("storage.use-linear-format", true);
        enableAsyncEntityDB = config.getBoolean("storage.use-async-entity-db", true);
        worldMigrationDelay = config.getLong("world-migration.delay", 30000L);

        // Save defaults if the file doesn't exist or is missing keys
        config.set("adaptive-tps.panic-threshold", adaptivePanicTps);
        config.set("adaptive-tps.recovery-threshold", adaptiveRecoveryTps);
        config.set("storage.use-linear-format", enableLinearFormat);
        config.set("storage.use-async-entity-db", enableAsyncEntityDB);
        config.set("world-migration.delay", worldMigrationDelay);

        try {
            config.save(configFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        // Push settings to managers
        net.minecraft.server.region.AdaptiveTPSManager.setPanicTps(adaptivePanicTps);
        net.minecraft.server.region.AdaptiveTPSManager.setRecoveryTps(adaptiveRecoveryTps);
    }
}
