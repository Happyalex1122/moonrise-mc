package net.minecraft.server.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class LMDBChunkRegion {

    private LmdbBindings.Env env;
    private int dbi;

    public LMDBChunkRegion(File directory) throws IOException {
        if (!directory.exists()) {
            directory.mkdirs();
        }
        // Allocate up to 10GB for chunk memory map
        env = new LmdbBindings.Env(new File(directory, "chunks.lmdb").getAbsolutePath(), 10_737_418240L, 1);
        dbi = env.openDbi("chunks", LmdbBindings.MDB_CREATE);
    }

    public void writeChunk(ChunkPos pos, CompoundTag chunkData) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        NbtIo.write(chunkData, dos);
        byte[] bytes = baos.toByteArray();

        ByteBuffer keyBuf = ByteBuffer.allocate(8);
        keyBuf.putLong(ChunkPos.pack(pos.x(), pos.z()));
        byte[] keyBytes = keyBuf.array();

        try (LmdbBindings.Txn txn = env.beginTxn(false)) {
            txn.put(dbi, keyBytes, bytes, 0);
            txn.commit();
        }
    }

    public CompoundTag readChunk(ChunkPos pos) throws IOException {
        ByteBuffer keyBuf = ByteBuffer.allocate(8);
        keyBuf.putLong(ChunkPos.pack(pos.x(), pos.z()));
        byte[] keyBytes = keyBuf.array();

        byte[] valBytes;
        try (LmdbBindings.Txn txn = env.beginTxn(true)) {
            valBytes = txn.get(dbi, keyBytes);
        }

        if (valBytes == null) {
            return null;
        }

        ByteArrayInputStream bais = new ByteArrayInputStream(valBytes);
        DataInputStream dis = new DataInputStream(bais);
        return NbtIo.read(dis);
    }

    public void deleteChunk(ChunkPos pos) {
        ByteBuffer keyBuf = ByteBuffer.allocate(8);
        keyBuf.putLong(ChunkPos.pack(pos.x(), pos.z()));
        byte[] keyBytes = keyBuf.array();

        try (LmdbBindings.Txn txn = env.beginTxn(false)) {
            txn.delete(dbi, keyBytes);
            txn.commit();
        } catch (Exception e) {
            // Ignore if doesn't exist
        }
    }
    
    public void close() {
        if (env != null) {
            env.close();
        }
    }
}
