package com.jujin.freeway.http.engine.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A DATA frame queued behind a running drain must own its bytes: the caller
 * (the 8KB body loop) reuses its buffer as soon as the write returns, so
 * aliasing corrupts frames that are written later.
 */
class Http2FrameWriterAliasingTest {

    /** Blocks the first write so a second producer enqueues while a drain runs. */
    private static final class BlockingStream extends OutputStream {
        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private boolean first = true;

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            if (first) {
                first = false;
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            sink.write(b, off, len);
        }

        @Override public void write(int b) { sink.write(b); }
        byte[] bytes() { return sink.toByteArray(); }
    }

    @Test
    void aQueuedDataFrameOwnsItsPayloadWhenAnotherThreadDrains() throws Exception {
        BlockingStream out = new BlockingStream();
        Http2FrameWriter writer = new Http2FrameWriter(out);
        byte[] header = {1, 2, 3};
        byte[] payload = {10, 11, 12};

        Thread leader = new Thread(() -> {
            try {
                writer.writeFrame(new byte[]{9});
            } catch (IOException ignored) {
                // the test asserts the collected bytes, not the thread's exit
            }
        });
        leader.start();
        assertTrue(out.entered.await(5, TimeUnit.SECONDS),
            "the leader must be blocked inside the socket write");

        writer.writeDataFrame(header, payload, 0, payload.length); // follower: queued
        Arrays.fill(payload, (byte) 0x7f);                         // caller reuses its buffer

        out.release.countDown();
        leader.join(5_000);

        byte[] written = out.bytes();
        assertEquals(1 + 3 + 3, written.length, Arrays.toString(written));
        assertEquals((byte) 9, written[0]);
        assertEquals((byte) 1, written[1]);
        assertEquals((byte) 10, written[4], "queued payload must be copied, not aliased");
        assertEquals((byte) 11, written[5]);
        assertEquals((byte) 12, written[6]);
    }
}
