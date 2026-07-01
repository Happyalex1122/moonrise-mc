package net.minecraft.server.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class HybridChunkStorage {

    private final File worldDir;
    private final File regionDir;
    private final LMDBChunkRegion hotCache;
    private final boolean lmdbAvailable;
    private final Set<ChunkPos> dirtyChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> loadedRegions = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;
    private final Thread backgroundFlusher;

    public HybridChunkStorage(File worldDir) throws IOException {
        this.worldDir = worldDir;
        this.regionDir = new File(worldDir, "region");
        if (LmdbBindings.isAvailable()) {
            this.hotCache = new LMDBChunkRegion(worldDir);
            this.lmdbAvailable = true;
        } else {
            this.hotCache = null;
            this.lmdbAvailable = false;
        }

        if (this.lmdbAvailable) {
            this.backgroundFlusher = Thread.ofVirtual().name("Hybrid-Chunk-Flusher").start(this::flushLoop);
            // Add shutdown hook to flush on exit
            Runtime.getRuntime().addShutdownHook(new Thread(this::close));
        } else {
            this.backgroundFlusher = null;
        }
    }

    public CompoundTag readChunk(ChunkPos pos) throws IOException {
        if (!this.lmdbAvailable) {
            return readLinearChunk(pos);
        }

        // 1. Fast path: Check LMDB Hot Cache
        CompoundTag chunk = hotCache.readChunk(pos);
        if (chunk != null) {
            return chunk;
        }

        // 2. Slow path: Chunk is not in LMDB. It might be in Linear storage.
        int regionX = pos.x() >> 5;
        int regionZ = pos.z() >> 5;
        long regionKey = ChunkPos.pack(regionX, regionZ);

        // If we already loaded this region from disk but the chunk wasn't there, it doesn't exist
        if (loadedRegions.contains(regionKey)) {
            return null;
        }

        // Load the entire region from Linear to LMDB
        LinearChunkRegion linearRegion = new LinearChunkRegion(regionDir, regionX, regionZ);
        linearRegion.loadAll();
        
        Map<Integer, CompoundTag> allChunks = linearRegion.getAllChunks();
        for (Map.Entry<Integer, CompoundTag> entry : allChunks.entrySet()) {
            int localX = entry.getKey() & 31;
            int localZ = entry.getKey() / 32;
            ChunkPos p = new ChunkPos((regionX << 5) + localX, (regionZ << 5) + localZ);
            hotCache.writeChunk(p, entry.getValue());
        }
        
        loadedRegions.add(regionKey);

        // Try reading again from LMDB
        return hotCache.readChunk(pos);
    }

    public void writeChunk(ChunkPos pos, CompoundTag data) throws IOException {
        if (!this.lmdbAvailable) {
            writeLinearChunk(pos, data);
            return;
        }

        if (data == null) {
            hotCache.deleteChunk(pos);
            // We should also delete it from linear storage during flush, but for simplicity we'll just track dirty
        } else {
            hotCache.writeChunk(pos, data);
        }
        dirtyChunks.add(pos);
    }

    private void flushLoop() {
        while (running) {
            try {
                Thread.sleep(5000); // Flush every 5 seconds
                flushDirtyChunks();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private synchronized void flushDirtyChunks() throws IOException {
        if (!this.lmdbAvailable) {
            return;
        }

        if (dirtyChunks.isEmpty()) {
            return;
        }

        // Copy and clear the dirty set
        List<ChunkPos> toFlush = new ArrayList<>(dirtyChunks);
        dirtyChunks.clear();

        // Group chunks by Region
        Map<Long, List<ChunkPos>> chunksByRegion = new HashMap<>();
        for (ChunkPos pos : toFlush) {
            long regionKey = ChunkPos.pack(pos.x() >> 5, pos.z() >> 5);
            chunksByRegion.computeIfAbsent(regionKey, k -> new ArrayList<>()).add(pos);
        }

        for (Map.Entry<Long, List<ChunkPos>> entry : chunksByRegion.entrySet()) {
            int regionX = ChunkPos.getX(entry.getKey());
            int regionZ = ChunkPos.getZ(entry.getKey());
            
            LinearChunkRegion linearRegion = new LinearChunkRegion(regionDir, regionX, regionZ);
            linearRegion.loadAll(); // Load existing non-dirty chunks

            for (ChunkPos pos : entry.getValue()) {
                CompoundTag tag = hotCache.readChunk(pos);
                if (tag == null) {
                    linearRegion.deleteChunk(pos.x(), pos.z());
                } else {
                    linearRegion.putChunk(pos.x(), pos.z(), tag);
                }
            }

            // Save the compressed region
            linearRegion.saveAll();
        }
    }

    public void close() {
        if (!running) return;
        running = false;
        if (backgroundFlusher != null) {
            backgroundFlusher.interrupt();
            try {
                backgroundFlusher.join(5000);
            } catch (InterruptedException ignored) {}
        }
        
        try {
            flushDirtyChunks();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (hotCache != null) {
            hotCache.close();
        }
    }

    private CompoundTag readLinearChunk(ChunkPos pos) throws IOException {
        int regionX = pos.x() >> 5;
        int regionZ = pos.z() >> 5;

        LinearChunkRegion linearRegion = new LinearChunkRegion(regionDir, regionX, regionZ);
        linearRegion.loadAll();
        return linearRegion.getChunk(pos.x() & 31, pos.z() & 31);
    }

    private void writeLinearChunk(ChunkPos pos, CompoundTag data) throws IOException {
        int regionX = pos.x() >> 5;
        int regionZ = pos.z() >> 5;

        LinearChunkRegion linearRegion = new LinearChunkRegion(regionDir, regionX, regionZ);
        linearRegion.loadAll();

        if (data == null) {
            linearRegion.deleteChunk(pos.x() & 31, pos.z() & 31);
        } else {
            linearRegion.putChunk(pos.x() & 31, pos.z() & 31, data);
        }

        linearRegion.saveAll();
    }
}
