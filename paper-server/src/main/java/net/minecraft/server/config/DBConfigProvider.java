package net.minecraft.server.config;

import java.nio.charset.StandardCharsets;
import net.minecraft.server.storage.LmdbBindings;

public class DBConfigProvider {
    private static LmdbBindings.Env env;
    private static int dbi;
    private static boolean initialized = false;

    static {
        try {
            // 64MB environment size, maxDbs = 2
            env = new LmdbBindings.Env("server_config.lmdb", 67108864L, 2);
            dbi = env.openDbi("config", LmdbBindings.MDB_CREATE);
            initialized = true;
        } catch (Throwable t) {
            System.err.println("Failed to initialize LMDB for DBConfigProvider:");
            t.printStackTrace();
        }

        // Register shutdown hook to close environment
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (env != null) {
                try {
                    env.close();
                    System.out.println("DBConfigProvider LMDB Environment closed successfully.");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "DBConfigProvider-ShutdownHook"));

        try {
            MoonriseConfig.init();
        } catch (Exception e) {
            System.err.println("Failed to initialize MoonriseConfig: " + e.getMessage());
        }
    }

    public static String getProperty(String key, String defaultValue) {
        if (!initialized) {
            return defaultValue;
        }
        try {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            
            // Read-only transaction first
            try (LmdbBindings.Txn txn = env.beginTxn(true)) {
                byte[] valBytes = txn.get(dbi, keyBytes);
                if (valBytes != null) {
                    return new String(valBytes, StandardCharsets.UTF_8);
                }
            }

            // Key not found, insert default value
            String insertValue = defaultValue != null ? defaultValue : "";
            byte[] valBytes = insertValue.getBytes(StandardCharsets.UTF_8);
            try (LmdbBindings.Txn txn = env.beginTxn(false)) {
                // Double check if key was inserted by another thread meanwhile
                byte[] existing = txn.get(dbi, keyBytes);
                if (existing != null) {
                    return new String(existing, StandardCharsets.UTF_8);
                }
                txn.put(dbi, keyBytes, valBytes, 0);
                txn.commit();
            }
            return defaultValue;
        } catch (Throwable t) {
            System.err.println("Failed to read/write property from LMDB: " + key);
            t.printStackTrace();
            return defaultValue;
        }
    }
}
