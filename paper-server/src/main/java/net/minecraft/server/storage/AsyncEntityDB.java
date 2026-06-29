package net.minecraft.server.storage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.Iterator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

public class AsyncEntityDB {
    // RAM Cache configured to hold up to 500,000 entities via LinkedHashMap LRU
    private static final Map<UUID, EntityRecord> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<UUID, EntityRecord>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, EntityRecord> eldest) {
                    return size() > 500_000;
                }
            }
    );
            
    private static final Set<UUID> DIRTY_SET = ConcurrentHashMap.newKeySet();
    private static final Thread WORKER;

    private static LmdbBindings.Env env;
    private static int dbi;
    private static boolean initialized = false;
    private static volatile boolean running = true;

    // WAL components
    private static final Object WAL_LOCK = new Object();
    private static DataOutputStream walStream;

    static {
        // Replay any existing WALs before starting
        replayWAL(new File("entities_wal_flushing.dat"));
        replayWAL(new File("entities_wal.dat"));

        try {
            walStream = new DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream("entities_wal.dat", true)));
        } catch (IOException e) {
            System.err.println("Failed to open WAL stream:");
            e.printStackTrace();
        }

        try {
            // Set Map Size to 4GB, maxDbs = 2
            env = new LmdbBindings.Env("entities.lmdb", 4294967296L, 2);
            dbi = env.openDbi("entities", LmdbBindings.MDB_CREATE);
            initialized = true;
        } catch (Throwable t) {
            System.err.println("Failed to initialize LMDB for AsyncEntityDB:");
            t.printStackTrace();
        }

        // Add Graceful Shutdown Hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            System.out.println("AsyncEntityDB Shutdown Hook: Flushing dirty entities...");
            flush();
            if (env != null) {
                try {
                    env.close();
                    System.out.println("AsyncEntityDB LMDB Environment closed successfully.");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "AsyncEntityDB-ShutdownHook"));

        // Initialize Virtual Thread Worker for Micro-Batching
        WORKER = Thread.ofVirtual().unstarted(() -> {
            if (!initialized) {
                System.err.println("AsyncEntityDB Worker not started due to initialization failure.");
                return;
            }

            while (running) {
                try {
                    Thread.sleep(2000); // 2-second micro-batching interval
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                
                flush();
            }
        });
        WORKER.setName("AsyncEntityDB-Worker");
        WORKER.setDaemon(true);
        WORKER.start();
    }

    private static void replayWAL(File walFile) {
        if (!walFile.exists() || walFile.length() == 0) return;
        System.out.println("Replaying WAL: " + walFile.getName());
        int count = 0;
        try (java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(walFile)))) {
            while (true) {
                try {
                    long most = dis.readLong();
                    long least = dis.readLong();
                    UUID uuid = new UUID(most, least);
                    int chunkX = dis.readInt();
                    int chunkZ = dis.readInt();
                    CompoundTag nbt = NbtIo.read(dis);
                    
                    EntityRecord record = new EntityRecord(chunkX, chunkZ, nbt);
                    CACHE.put(uuid, record);
                    DIRTY_SET.add(uuid);
                    count++;
                } catch (java.io.EOFException e) {
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("Error replaying WAL " + walFile.getName() + ": " + e.getMessage());
        }
        System.out.println("Replayed " + count + " entities from WAL.");
        walFile.delete();
    }

    public static void saveEntity(UUID uuid, int chunkX, int chunkZ, CompoundTag nbt) {
        EntityRecord record = new EntityRecord(chunkX, chunkZ, nbt);
        CACHE.put(uuid, record); // RAM cache updated immediately
        DIRTY_SET.add(uuid);     // Mark as dirty
        
        synchronized (WAL_LOCK) {
            if (walStream != null) {
                try {
                    walStream.writeLong(uuid.getMostSignificantBits());
                    walStream.writeLong(uuid.getLeastSignificantBits());
                    walStream.writeInt(chunkX);
                    walStream.writeInt(chunkZ);
                    NbtIo.write(nbt, walStream);
                    walStream.flush(); // Ensure it reaches OS disk buffer immediately
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void flush() {
        if (!initialized || DIRTY_SET.isEmpty()) return;
        
        File flushingWal = new File("entities_wal_flushing.dat");
        File currentWal = new File("entities_wal.dat");
        
        synchronized (WAL_LOCK) {
            if (walStream != null) {
                try {
                    walStream.close();
                } catch (IOException e) {}
            }
            if (currentWal.exists()) {
                currentWal.renameTo(flushingWal);
            }
            try {
                walStream = new DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(currentWal, true)));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        List<SaveTask> batch = new ArrayList<>();
        Iterator<UUID> it = DIRTY_SET.iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            EntityRecord record = CACHE.get(uuid);
            if (record != null) {
                batch.add(new SaveTask(uuid, record.chunkX(), record.chunkZ(), record.nbt()));
            }
            it.remove(); // Remove from dirty set
        }
        
        if (!batch.isEmpty()) {
            writeBatch(batch);
        }
        
        // LMDB commit successful, we can safely delete the flushing WAL
        if (flushingWal.exists()) {
            flushingWal.delete();
        }
    }

    private static void writeBatch(List<SaveTask> batch) {
        try (LmdbBindings.Txn txn = env.beginTxn(false)) {
            for (SaveTask task : batch) {
                byte[] key = serializeKey(task.uuid());
                byte[] val = serializeValue(task.chunkX(), task.chunkZ(), task.nbt());
                txn.put(dbi, key, val, 0);
            }
            txn.commit();
        } catch (Throwable t) {
            System.err.println("Failed to execute write batch in AsyncEntityDB:");
            t.printStackTrace();
        }
    }

    private static byte[] serializeKey(UUID uuid) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        return buf.array();
    }

    private static byte[] serializeValue(int chunkX, int chunkZ, CompoundTag nbt) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            NbtIo.write(nbt, dos);
        }
        byte[] nbtBytes = baos.toByteArray();
        ByteBuffer buf = ByteBuffer.allocate(8 + nbtBytes.length);
        buf.putInt(chunkX);
        buf.putInt(chunkZ);
        buf.put(nbtBytes);
        return buf.array();
    }

    public record EntityRecord(int chunkX, int chunkZ, CompoundTag nbt) {}

    /**
     * Reads an entity's data. Hits the RAM Cache first, falls back to LMDB.
     */
    public static EntityRecord readEntity(UUID uuid) {
        if (!initialized) return null;
        
        // 1. Check RAM Cache
        EntityRecord cached = CACHE.get(uuid);
        if (cached != null) {
            return cached;
        }

        // 2. Fallback to LMDB
        byte[] keyBytes = serializeKey(uuid);
        try (LmdbBindings.Txn txn = env.beginTxn(true)) {
            byte[] valBytes = txn.get(dbi, keyBytes);
            if (valBytes == null) {
                return null;
            }

            ByteBuffer buf = ByteBuffer.wrap(valBytes);
            int chunkX = buf.getInt();
            int chunkZ = buf.getInt();

            int nbtLen = valBytes.length - 8;
            byte[] nbtBytes = new byte[nbtLen];
            buf.get(nbtBytes);

            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(nbtBytes);
            CompoundTag nbt;
            try (java.io.DataInputStream dis = new java.io.DataInputStream(bais)) {
                nbt = NbtIo.read(dis);
            }

            EntityRecord record = new EntityRecord(chunkX, chunkZ, nbt);
            CACHE.put(uuid, record); // Load into cache
            return record;
        } catch (Throwable t) {
            System.err.println("Failed to read entity " + uuid + ":");
            t.printStackTrace();
            return null;
        }
    }

    private record SaveTask(UUID uuid, int chunkX, int chunkZ, CompoundTag nbt) {}
}
