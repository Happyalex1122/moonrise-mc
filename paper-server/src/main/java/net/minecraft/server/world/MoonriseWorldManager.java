package net.minecraft.server.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import net.minecraft.server.storage.LmdbBindings;

public class MoonriseWorldManager {
    private static MoonriseWorldManager instance;
    private LmdbBindings.Env env;
    private int worldsDbi;
    private int linksDbi;
    private boolean initialized = false;

    private MoonriseWorldManager() {
        initDatabase();
    }

    public static MoonriseWorldManager getInstance() {
        if (instance == null) {
            instance = new MoonriseWorldManager();
        }
        return instance;
    }

    private void initDatabase() {
        try {
            // 64MB environment size, maxDbs = 2
            env = new LmdbBindings.Env("worlds.lmdb", 67108864L, 2);
            worldsDbi = env.openDbi("worlds", LmdbBindings.MDB_CREATE);
            linksDbi = env.openDbi("links", LmdbBindings.MDB_CREATE);
            initialized = true;
        } catch (Throwable t) {
            System.err.println("Failed to initialize LMDB for MoonriseWorldManager:");
            t.printStackTrace();
        }

        // Shutdown Hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (env != null) {
                try {
                    env.close();
                    System.out.println("MoonriseWorldManager LMDB Environment closed successfully.");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "MoonriseWorldManager-ShutdownHook"));
    }

    public void loadAllWorlds() {
        if (!initialized) return;
        try (LmdbBindings.Txn txn = env.beginTxn(true)) {
            txn.forEach(worldsDbi, (keyBytes, valBytes) -> {
                String name = new String(keyBytes, StandardCharsets.UTF_8);
                String envStr = new String(valBytes, StandardCharsets.UTF_8);
                World.Environment envVal = World.Environment.valueOf(envStr);
                loadWorldInternal(name, envVal);
            });
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void loadWorldInternal(String name, World.Environment env) {
        Bukkit.getServer().createWorld(new WorldCreator(name).environment(env));
    }

    public void createWorld(String name, World.Environment envVal) {
        if (initialized) {
            try (LmdbBindings.Txn txn = env.beginTxn(false)) {
                byte[] key = name.getBytes(StandardCharsets.UTF_8);
                byte[] val = envVal.name().getBytes(StandardCharsets.UTF_8);
                txn.put(worldsDbi, key, val, 0);
                txn.commit();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        loadWorldInternal(name, envVal);
    }

    public void loadWorld(String name) {
        if (initialized) {
            try (LmdbBindings.Txn txn = env.beginTxn(true)) {
                byte[] key = name.getBytes(StandardCharsets.UTF_8);
                byte[] valBytes = txn.get(worldsDbi, key);
                if (valBytes != null) {
                    String envStr = new String(valBytes, StandardCharsets.UTF_8);
                    World.Environment envVal = World.Environment.valueOf(envStr);
                    loadWorldInternal(name, envVal);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    private byte[] serializeLinkKey(String source, World.Environment env) {
        byte[] srcBytes = source.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + srcBytes.length + 1);
        buf.putShort((short) srcBytes.length);
        buf.put(srcBytes);
        buf.put((byte) env.ordinal());
        return buf.array();
    }

    public void linkWorld(String source, String target, World.Environment envVal) {
        if (initialized) {
            try (LmdbBindings.Txn txn = env.beginTxn(false)) {
                byte[] key = serializeLinkKey(source, envVal);
                byte[] val = target.getBytes(StandardCharsets.UTF_8);
                txn.put(linksDbi, key, val, 0);
                txn.commit();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    public String getLinkedWorld(String source, World.Environment envVal) {
        if (!initialized) return null;
        try (LmdbBindings.Txn txn = env.beginTxn(true)) {
            byte[] key = serializeLinkKey(source, envVal);
            byte[] valBytes = txn.get(linksDbi, key);
            if (valBytes != null) {
                return new String(valBytes, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return null;
    }

    public String getLinkedDimension(String source, World.Environment env) {
        return getLinkedWorld(source, env);
    }
}
