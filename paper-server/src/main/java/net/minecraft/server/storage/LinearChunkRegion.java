package net.minecraft.server.storage;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles cold storage of a 32x32 chunk region using a single Zstd compressed file.
 * Entire region is compressed/decompressed at once.
 */
public class LinearChunkRegion {
    
    private final File file;
    private final Map<Integer, CompoundTag> chunkCache = new HashMap<>();

    public LinearChunkRegion(File directory, int regionX, int regionZ) {
        if (!directory.exists()) {
            directory.mkdirs();
        }
        this.file = new File(directory, "r." + regionX + "." + regionZ + ".linear");
    }

    public synchronized void loadAll() throws IOException {
        chunkCache.clear();
        if (!file.exists()) {
            return;
        }
        
        try (FileInputStream fis = new FileInputStream(file);
             ZstdInputStream zis = new ZstdInputStream(fis);
             DataInputStream dis = new DataInputStream(zis)) {
            
            // Signature check
            long sig = dis.readLong();
            if (sig != 0x4C494E454152L) { // "LINEAR"
                throw new IOException("Invalid linear signature");
            }
            
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                int index = dis.readInt();
                int length = dis.readInt();
                byte[] chunkData = new byte[length];
                dis.readFully(chunkData);
                
                ByteArrayInputStream bais = new ByteArrayInputStream(chunkData);
                DataInputStream chunkDis = new DataInputStream(bais);
                CompoundTag tag = NbtIo.read(chunkDis);
                chunkCache.put(index, tag);
            }
        }
    }

    public synchronized void saveAll() throws IOException {
        if (chunkCache.isEmpty()) {
            if (file.exists()) {
                file.delete();
            }
            return;
        }

        try (FileOutputStream fos = new FileOutputStream(file);
             ZstdOutputStream zos = new ZstdOutputStream(fos);
             DataOutputStream dos = new DataOutputStream(zos)) {
            
            dos.writeLong(0x4C494E454152L); // "LINEAR"
            dos.writeInt(chunkCache.size());
            
            for (Map.Entry<Integer, CompoundTag> entry : chunkCache.entrySet()) {
                dos.writeInt(entry.getKey());
                
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream chunkDos = new DataOutputStream(baos);
                NbtIo.write(entry.getValue(), chunkDos);
                byte[] chunkData = baos.toByteArray();
                
                dos.writeInt(chunkData.length);
                dos.write(chunkData);
            }
        }
    }

    public CompoundTag getChunk(int localX, int localZ) {
        return chunkCache.get(getIndex(localX, localZ));
    }

    public void putChunk(int localX, int localZ, CompoundTag tag) {
        chunkCache.put(getIndex(localX, localZ), tag);
    }
    
    public void deleteChunk(int localX, int localZ) {
        chunkCache.remove(getIndex(localX, localZ));
    }

    public Map<Integer, CompoundTag> getAllChunks() {
        return chunkCache;
    }

    private int getIndex(int localX, int localZ) {
        return (localX & 31) + (localZ & 31) * 32;
    }
}
