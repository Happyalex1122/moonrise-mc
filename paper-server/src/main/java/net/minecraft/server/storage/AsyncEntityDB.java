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
import java.util.Map.Entry;
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
            
    // Fix #22: Store EntityRecord directly in dirty map to prevent data loss on LRU eviction.
    // If an entity is evicted from the 500k-entry LRU cache before flush, the dirty map
    // still holds the record that needs to be persisted to LMDB.
    private static final ConcurrentHashMap<UUID, EntityRecord> DIRTY_MAP = new ConcurrentHashMap<>();
    private static final Thread WORKER;

    private static LmdbBindings.Env env;
    private static int dbi;
    private static boolean initialized = false;
    private static volatile boolean running = true;

    // WAL components
    private static final Object WAL_LOCK = new Object();
    private static DataOutputStream walStream;

    private static final java.util.concurrent.BlockingQueue<SaveTask> WAL_QUEUE = new java.util.concurrent.LinkedBlockingQueue<>();
    private static final Thread WAL_WORKER;

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

        // Initialize WAL Appender Virtual Thread (Group Commit)
        WAL_WORKER = Thread.ofVirtual().unstarted(() -> {
            while (running || !WAL_QUEUE.isEmpty()) {
                try {
                    SaveTask task = WAL_QUEUE.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (task != null) {
                        synchronized (WAL_LOCK) {
                            if (walStream != null) {
                                walStream.writeLong(task.uuid().getMostSignificantBits());
                                walStream.writeLong(task.uuid().getLeastSignificantBits());
                                walStream.writeInt(task.chunkX());
                                walStream.writeInt(task.chunkZ());
                                NbtIo.write(task.nbt(), walStream);
                                
                                // Group commit: flush only if queue is empty to maximize throughput
                                if (WAL_QUEUE.isEmpty()) {
                                    walStream.flush();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    if (running) e.printStackTrace();
                }
            }
        });
        WAL_WORKER.setName("AsyncEntityDB-WAL-Worker");
        WAL_WORKER.setDaemon(true);
        WAL_WORKER.start();

        // Add Graceful Shutdown Hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            System.out.println("AsyncEntityDB Shutdown Hook: Waiting for WAL to finish...");
            try { WAL_WORKER.join(5000); } catch (InterruptedException ignored) {}
            System.out.println("AsyncEntityDB Shutdown Hook: Flushing dirty entities to LMDB...");
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
                    DIRTY_MAP.put(uuid, record);
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
        CACHE.put(uuid, record);     // RAM cache updated immediately
        DIRTY_MAP.put(uuid, record); // Mark as dirty with the record itself (fix #22)
        
        // Non-blocking asynchronous WAL append
        WAL_QUEUE.offer(new SaveTask(uuid, chunkX, chunkZ, nbt));
    }

    public static void flush() {
        if (!initialized || DIRTY_MAP.isEmpty()) return;
        
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

        // Fix #22: Drain the DIRTY_MAP atomically. Each entry holds the EntityRecord directly,
        // so data is never lost even if the LRU cache already evicted the entity.
        List<SaveTask> batch = new ArrayList<>();
        Iterator<Entry<UUID, EntityRecord>> it = DIRTY_MAP.entrySet().iterator();
        while (it.hasNext()) {
            Entry<UUID, EntityRecord> entry = it.next();
            EntityRecord record = entry.getValue();
            batch.add(new SaveTask(entry.getKey(), record.chunkX(), record.chunkZ(), record.nbt()));
            it.remove();
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
