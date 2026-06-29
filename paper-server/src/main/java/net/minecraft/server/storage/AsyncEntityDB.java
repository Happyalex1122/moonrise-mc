package net.minecraft.server.storage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

public class AsyncEntityDB {
    private static final BlockingQueue<SaveTask> QUEUE = new LinkedBlockingQueue<>();
    private static final Thread WORKER;

    private static LmdbBindings.Env env;
    private static int dbi;
    private static boolean initialized = false;

    static {
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
            System.out.println("AsyncEntityDB Shutdown Hook: Flushing queue...");
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

        // Initialize Virtual Thread Worker
        WORKER = Thread.ofVirtual().unstarted(() -> {
            if (!initialized) {
                System.err.println("AsyncEntityDB Worker not started due to initialization failure.");
                return;
            }

            List<SaveTask> batch = new ArrayList<>();
            while (true) {
                try {
                    SaveTask firstTask = QUEUE.take(); // Blocks until work is available
                    batch.add(firstTask);

                    // Drain up to 499 more tasks currently in queue (batch limit 500)
                    QUEUE.drainTo(batch, 499);

                    writeBatch(batch);
                    batch.clear();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    System.err.println("Error in AsyncEntityDB worker loop:");
                    t.printStackTrace();
                    batch.clear();
                }
            }
        });
        WORKER.setName("AsyncEntityDB-Worker");
        WORKER.setDaemon(true);
        WORKER.start();
    }

    public static void saveEntity(UUID uuid, int chunkX, int chunkZ, CompoundTag nbt) {
        QUEUE.offer(new SaveTask(uuid, chunkX, chunkZ, nbt));
    }

    public static void flush() {
        if (!initialized) return;
        List<SaveTask> remaining = new ArrayList<>();
        QUEUE.drainTo(remaining);
        if (!remaining.isEmpty()) {
            writeBatch(remaining);
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

    // Optional Entity Record for retrieval
    public record EntityRecord(int chunkX, int chunkZ, CompoundTag nbt) {}

    /**
     * Reads an entity's data from LMDB. This demonstrates concurrent lock-free reads.
     */
    public static EntityRecord readEntity(UUID uuid) {
        if (!initialized) return null;
        byte[] keyBytes = serializeKey(uuid);
        try (LmdbBindings.Txn txn = env.beginTxn(true)) {
            byte[] valBytes = txn.get(dbi, keyBytes);
            if (valBytes == null) {
                return null;
            }

            // Deserialize composite value payload
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

            return new EntityRecord(chunkX, chunkZ, nbt);
        } catch (Throwable t) {
            System.err.println("Failed to read entity " + uuid + ":");
            t.printStackTrace();
            return null;
        }
    }

    private record SaveTask(UUID uuid, int chunkX, int chunkZ, CompoundTag nbt) {}
}
