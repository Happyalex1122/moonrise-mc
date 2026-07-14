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
    public static boolean enableAutoPluginUpdater = false;
    public static String curseforgeApiKey = "";
    public static java.util.Map<String, Integer> spigotMappings = new java.util.HashMap<>();
    public static String language = "ko";

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
        enableAutoPluginUpdater = config.getBoolean("plugins.auto-updater-enabled", false);
        
        String rawKey = config.getString("plugins.curseforge-api-key", "");
        if (rawKey != null && !rawKey.isEmpty()) {
            if (rawKey.startsWith("ENC:")) {
                curseforgeApiKey = decrypt(rawKey);
            } else {
                curseforgeApiKey = rawKey;
                // Auto-encrypt and save back to config
                config.set("plugins.curseforge-api-key", encrypt(rawKey));
            }
        } else {
            curseforgeApiKey = "";
            config.set("plugins.curseforge-api-key", "");
        }

        spigotMappings.clear();
        if (config.isConfigurationSection("plugins.spigot-mappings")) {
            org.bukkit.configuration.ConfigurationSection section = config.getConfigurationSection("plugins.spigot-mappings");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    spigotMappings.put(key, section.getInt(key));
                }
            }
        } else {
            // Default example mapping
            spigotMappings.put("ExampleSpigotPlugin-1.0.jar", 12345);
            config.set("plugins.spigot-mappings.ExampleSpigotPlugin-1.0.jar", 12345);
        }

        language = config.getString("language", "ko");

        // Save defaults if the file doesn't exist or is missing keys
        config.set("adaptive-tps.panic-threshold", adaptivePanicTps);
        config.set("adaptive-tps.recovery-threshold", adaptiveRecoveryTps);
        config.set("storage.use-linear-format", enableLinearFormat);
        config.set("storage.use-async-entity-db", enableAsyncEntityDB);
        config.set("world-migration.delay", worldMigrationDelay);
        config.set("plugins.auto-updater-enabled", enableAutoPluginUpdater);
        // curseforge-api-key is handled above
        config.set("language", language);

        try {
            config.save(configFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        // Push settings to managers
        net.minecraft.server.region.AdaptiveTPSManager.setPanicTps(adaptivePanicTps);
        net.minecraft.server.region.AdaptiveTPSManager.setRecoveryTps(adaptiveRecoveryTps);
    }

    public static void save() {
        if (config == null || configFile == null) return;
        config.set("language", language);
        try {
            config.save(configFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static final byte[] SECRET_KEY = "M00nr1s3_A3S_K3Y".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) return plainText;
        try {
            javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(SECRET_KEY, "AES");
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey);
            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "ENC:" + java.util.Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return plainText;
        }
    }

    private static String decrypt(String encryptedText) {
        if (encryptedText == null || !encryptedText.startsWith("ENC:")) return encryptedText;
        try {
            javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(SECRET_KEY, "AES");
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey);
            byte[] decryptedBytes = cipher.doFinal(java.util.Base64.getDecoder().decode(encryptedText.substring(4)));
            return new String(decryptedBytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return encryptedText;
        }
    }
}
