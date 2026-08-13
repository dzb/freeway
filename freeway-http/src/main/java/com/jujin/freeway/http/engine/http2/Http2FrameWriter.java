package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes outbound HTTP/2 frames over one socket. Frames from concurrent
 * streams are queued under a single lock and drained by one leader, so they
 * coalesce into fewer writes and are never interleaved at byte level.
 */
final class Http2FrameWriter {

    private final OutputStream outputStream;
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<OutboundChunk> outbound = new ArrayDeque<>();
    private boolean writing;

    Http2FrameWriter(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    /** The same lock serializes HPACK header encoding and frame enqueueing. */
    void lock() {
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }

    /**
     * Queues one or more whole frame byte-arrays and flushes all pending
     * frames. The first producer to find no active writer drains the queue in
     * a single write+flush; producers that join while a drain is running only
     * append, so concurrent streams share syscalls instead of each flushing
     * its own frame. Frames are drained in FIFO order.
     */
    void writeFrame(byte[]... frames) throws IOException {
        boolean leader;
        lock();
        try {
            for (var frame : frames) {
                if (frame != null && frame.length > 0) {
                    outbound.add(new OutboundChunk(frame, 0, frame.length));
                }
            }
            leader = !writing;
            if (leader) writing = true;
        } finally {
            unlock();
        }
        if (leader) drainOutbound();
    }

    /** Queues a DATA frame without copying the payload: header slice plus the
     *  payload range are appended contiguously under one lock acquisition. */
    void writeDataFrame(byte[] header, byte[] payload, int offset, int length)
            throws IOException {
        boolean leader;
        lock();
        try {
            outbound.add(new OutboundChunk(header, 0, header.length));
            outbound.add(new OutboundChunk(payload, offset, length));
            leader = !writing;
            if (leader) writing = true;
        } finally {
            unlock();
        }
        if (leader) drainOutbound();
    }

    private void drainOutbound() throws IOException {
        try {
            while (true) {
                OutboundChunk next;
                lock();
                try {
                    next = outbound.poll();
                } finally {
                    unlock();
                }
                if (next == null) {
                    lock();
                    try {
                        if (outbound.isEmpty()) {
                            writing = false;
                            outputStream.flush();
                            return;
                        }
                    } finally {
                        unlock();
                    }
                    continue;
                }
                outputStream.write(next.bytes, next.offset, next.length);
            }
        } catch (IOException e) {
            lock();
            try {
                writing = false;
                outbound.clear();
            } finally {
                unlock();
            }
            throw e;
        }
    }

    private record OutboundChunk(byte[] bytes, int offset, int length) {}
}
