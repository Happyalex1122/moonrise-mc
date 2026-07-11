package net.minecraft.network;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/**
 * Packet batching interceptor using a pre-allocated ring buffer.
 * Translates multiple write() calls into a single gathering write (writev)
 * by batching them and flushing at fixed intervals or when full.
 * 
 * Optimized for high-throughput Minecraft multithreaded networking.
 */
public class ConnectionPatch extends ChannelOutboundHandlerAdapter {
    private static final int BATCH_SIZE = 128; // Must be a power of 2 for fast masking
    private static final int MASK = BATCH_SIZE - 1;
    
    // Pre-allocated buffers for zero-allocation batching
    private final Object[] messageRing = new Object[BATCH_SIZE];
    private final ChannelPromise[] promiseRing = new ChannelPromise[BATCH_SIZE];
    
    private int head = 0;
    private int tail = 0;

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        int currentTail = tail;
        int nextTail = (currentTail + 1) & MASK;
        
        if (nextTail == head) {
            // Buffer is full, flush immediately to Netty's outbound buffer
            flushBatch(ctx);
            currentTail = tail;
            nextTail = (currentTail + 1) & MASK;
        }
        
        messageRing[currentTail] = msg;
        promiseRing[currentTail] = promise;
        tail = nextTail;
    }
    
    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        flushBatch(ctx);
        ctx.flush(); // Trigger the actual native gathering write (writev) via Netty
    }
    
    private void flushBatch(ChannelHandlerContext ctx) {
        int currentHead = head;
        int currentTail = tail;
        
        if (currentHead == currentTail) {
            return;
        }
        
        // Loop unrolling or standard while loop
        while (currentHead != currentTail) {
            Object msg = messageRing[currentHead];
            ChannelPromise promise = promiseRing[currentHead];
            
            // Null out to avoid memory leaks
            messageRing[currentHead] = null;
            promiseRing[currentHead] = null;
            
            // Write to Netty's ChannelOutboundBuffer (does not trigger syscall yet)
            ctx.write(msg, promise);
            currentHead = (currentHead + 1) & MASK;
        }
        
        head = currentHead;
    }
}
