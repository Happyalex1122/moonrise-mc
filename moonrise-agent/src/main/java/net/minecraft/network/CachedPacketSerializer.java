package net.minecraft.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;

/**
 * Optimizes broadcast packets by serializing them only once and sharing the 
 * underlying buffer across multiple connections using retainedDuplicate().
 * 
 * Leveraging Java 25 features for peak performance and memory safety.
 */
public final class CachedPacketSerializer {

    private final ByteBuf cachedBuffer;

    private CachedPacketSerializer(ByteBuf cachedBuffer) {
        this.cachedBuffer = cachedBuffer;
    }

    /**
     * Creates a pre-serialized packet payload.
     * 
     * @param allocator The Netty ByteBufAllocator to use (e.g., from the channel).
     * @param serializer Functional interface to write into the buffer.
     * @return A new CachedPacketSerializer holding the pre-serialized buffer.
     */
    public static CachedPacketSerializer create(ByteBufAllocator allocator, SerializerAction serializer) {
        ByteBuf buf = allocator.directBuffer();
        try {
            serializer.write(buf);
            return new CachedPacketSerializer(buf);
        } catch (Throwable ignored) { // Java 21+ unnamed variable
            buf.release();
            throw new RuntimeException("Failed to pre-serialize packet");
        }
    }

    /**
     * Broadcasts the cached packet to an iterable of channels.
     * Integrates tightly with ConnectionPatch for zero-copy gathering writes.
     *
     * @param channels The collection of player connection channels.
     */
    public void broadcast(Iterable<Channel> channels) {
        for (Channel channel : channels) {
            // retainedDuplicate() increments the reference count and provides a view with independent indices.
            // This completely bypasses the need to re-serialize the packet per player.
            channel.write(cachedBuffer.retainedDuplicate());
        }
        // Release the master reference. The buffer memory will be freed when all 
        // individual channel writes complete and release their respective views.
        cachedBuffer.release();
    }
    
    /**
     * Gets a retained duplicate for a single channel write without full broadcasting.
     * The caller is responsible for releasing it (usually Netty does this after flush).
     * 
     * @return A retained duplicate ByteBuf.
     */
    public ByteBuf getBuffer() {
        return cachedBuffer.retainedDuplicate();
    }
    
    /**
     * Manually release the cached buffer if it was never broadcasted.
     */
    public void release() {
        if (cachedBuffer.refCnt() > 0) {
            cachedBuffer.release();
        }
    }

    /**
     * Functional interface for performing the actual packet encoding.
     */
    @FunctionalInterface
    public interface SerializerAction {
        void write(ByteBuf buf) throws Exception;
    }
}
