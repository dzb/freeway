package com.jujin.freeway.cloud.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * The publisher must never wait on a peer. A WebSocket write blocks once the
 * peer stops reading, so the connection buffers frames and hands them to its
 * own sender thread; a full queue means "drop the connection", not "block the
 * thread that published".
 */
class PeerConnectionQueueTest {

    @Test
    void aStalledPeerCannotBlockThePublisher() {
        CountDownLatch release = new CountDownLatch(1);
        PeerConnection peer = new PeerConnection("stalled", List.of(""),
            json -> {
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return true;
            },
            true, () -> { });

        int accepted = 0;
        long elapsedMillis;
        try {
            long start = System.nanoTime();
            for (int i = 0; i < PeerConnection.MAX_QUEUED_FRAMES + 10; i++) {
                if (peer.send("frame-" + i)) {
                    accepted++;
                }
            }
            elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        } finally {
            peer.close();
            release.countDown();
        }

        assertTrue(accepted >= PeerConnection.MAX_QUEUED_FRAMES
                && accepted <= PeerConnection.MAX_QUEUED_FRAMES + 1,
            "the queue is bounded by MAX_QUEUED_FRAMES (+1 in the drain thread), got " + accepted);
        assertTrue(accepted < PeerConnection.MAX_QUEUED_FRAMES + 10,
            "a full queue must report failure so the caller can drop the connection");
        assertTrue(elapsedMillis < 1_000,
            "send must not wait on the stalled peer; took " + elapsedMillis + " ms");
    }

    @Test
    void aClosedConnectionRefusesFrames() {
        PeerConnection peer = new PeerConnection("gone", List.of(""), json -> true, true, () -> { });
        peer.close();
        assertTrue(peer.isClosed());
        assertFalse(peer.send("late"), "a closed connection must not accept frames");
    }
}
