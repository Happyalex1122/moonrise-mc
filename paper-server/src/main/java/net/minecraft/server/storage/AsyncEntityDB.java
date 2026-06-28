package net.minecraft.server.storage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

public class AsyncEntityDB {
    private static final String URL = "jdbc:sqlite:entities.db";
    private static final BlockingQueue<SaveTask> QUEUE = new LinkedBlockingQueue<>();
    private static final Thread WORKER;

    static {
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS entities (" +
                         "uuid TEXT PRIMARY KEY, " +
                         "chunkX INT, " +
                         "chunkZ INT, " +
                         "nbt BLOB)");
        } catch (Exception e) {
            e.printStackTrace();
        }

        WORKER = Thread.ofVirtual().unstarted(() -> {
            try (Connection conn = DriverManager.getConnection(URL);
                 PreparedStatement pstmt = conn.prepareStatement(
                         "INSERT OR REPLACE INTO entities (uuid, chunkX, chunkZ, nbt) VALUES (?, ?, ?, ?)")) {
                while (true) {
                    SaveTask task = QUEUE.take();
                    pstmt.setString(1, task.uuid().toString());
                    pstmt.setInt(2, task.chunkX());
                    pstmt.setInt(3, task.chunkZ());
                    
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (DataOutputStream dos = new DataOutputStream(baos)) {
                        NbtIo.write(task.nbt(), dos);
                    }
                    pstmt.setBytes(4, baos.toByteArray());
                    pstmt.executeUpdate();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        WORKER.setName("AsyncEntityDB-Worker");
        WORKER.setDaemon(true);
        WORKER.start();
    }

    public static void saveEntity(UUID uuid, int chunkX, int chunkZ, CompoundTag nbt) {
        QUEUE.offer(new SaveTask(uuid, chunkX, chunkZ, nbt));
    }

    private record SaveTask(UUID uuid, int chunkX, int chunkZ, CompoundTag nbt) {}
}
