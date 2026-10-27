package com.jujin.freeway.cloud.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * What a peer connection owes the publisher: never wait on it, never grow
 * without bound, and hand the transport a batch rather than a frame at a time.
 *
 * <p>The three facts are one mechanism, so they live together: the queue bounds
 * the publisher's wait, the drain batches what it took, and the drain thread's
 * lifetime is <em>close()'s</em> business — it must end on the interrupt, not on
 * a poll timeout, or every closed connection leaks a waking thread.</p>
 */
class PeerConnectionQueueTest {

    @Test
    void aStalledPeerCannotBlockThePublisher() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.LongAdder dropped = new java.util.concurrent.atomic.LongAdder();
        PeerConnection peer = new PeerConnection("stalled", List.of(""),
            json -> {
                try {
                    release.await();
                    return true;
                } catch (InterruptedException ex) {
                    // A real transport fails the write once the socket is closed:
                    // the interrupted write is a lost frame, not a written one.
                    return false;
                }
            },
            true, () -> { }, dropped::add);

        int accepted = 0;
        long elapsedMillis;
        try {
            long start = System.nanoTime();
            for (int i = 0; i < PeerConnection.MAX_QUEUED_FRAMES + PeerConnection.BATCH_FRAMES + 10;
                    i++) {
                if (peer.send("frame-" + i)) {
                    accepted++;
                }
            }
            elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        } finally {
            peer.close();
            release.countDown();
        }

        // The drain thread does the accounting on its way out; wait for it.
        Thread drain = peer.senderThread();
        if (drain != null) {
            drain.join(5_000);
        }
        assertFalse(drain != null && drain.isAlive(), "the drain thread must have ended");

        // The bound is the queue plus the one batch the drain thread is holding
        // while it blocks on the stalled peer — not the queue alone.
        int ceiling = PeerConnection.MAX_QUEUED_FRAMES + PeerConnection.BATCH_FRAMES;
        assertTrue(accepted >= PeerConnection.MAX_QUEUED_FRAMES && accepted <= ceiling,
            "the backlog is bounded by MAX_QUEUED_FRAMES + one in-flight batch, got " + accepted);
        assertTrue(accepted < PeerConnection.MAX_QUEUED_FRAMES + PeerConnection.BATCH_FRAMES + 10,
            "a full queue must report failure so the caller can drop the connection");
        assertTrue(elapsedMillis < 1_000,
            "send must not wait on the stalled peer; took " + elapsedMillis + " ms");

        // Nothing reached the peer, so every accepted frame must come back as a
        // drop — the batch the transport was holding and whatever was queued.
        assertEquals(accepted, dropped.sum(),
            "every frame the connection accepted but never wrote is accounted once");
    }

    @Test
    void aClosedConnectionRefusesFrames() {
        PeerConnection peer = new PeerConnection("gone", List.of(""), json -> true, true, () -> { });
        peer.close();
        assertTrue(peer.isClosed());
        assertFalse(peer.send("late"), "a closed connection must not accept frames");
    }

    /**
     * {@code close()} must end the drain thread through the interrupt.
     *
     * <p>This pins the <em>observable</em> half of the fix. The lock ordering
     * itself — reading the thread under the monitor that publishes it — is not
     * reachable from a test: it only shows up when {@code close()} lands between
     * the {@code closed} check and the assignment, a window nanoseconds wide
     * that no test can aim at. What this does catch is a {@code close()} that
     * stops interrupting: the drain then parks for a poll timeout, or forever
     * now that the wait is a blocking {@code take()}.</p>
     */
    @Test
    void closeEndsTheDrainThreadInsteadOfWaitingForATimeout() throws Exception {
        PeerConnection peer = new PeerConnection("idle", List.of(""), json -> true, true, () -> { });
        assertTrue(peer.send("one-frame"), "the frame is queued");
        Thread drain = peer.senderThread();
        assertTrue(drain != null, "the sender thread must exist once a frame was accepted");

        peer.close();
        drain.join(100);

        assertFalse(drain.isAlive(),
            "close() must stop the drain thread immediately — a poll timeout would leave it "
                + "parked and waking every 200 ms");
    }

    /**
     * The drain hands the transport a batch, not a frame: while one batch is
     * blocked, everything offered meanwhile is taken in the next pass, so the
     * engine pays one write lock and one flush for the batch. The default
     * {@code sendBatch} loops {@code send}, so a transport that cannot batch
     * (the JDK client leg) still works — this asserts the batching contract,
     * not an optimization detail.
     */
    @Test
    void aBacklogIsHandedToTheTransportAsOneBatch() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch arrived = new CountDownLatch(1);
        List<Integer> batchSizes = new CopyOnWriteArrayList<>();
        List<String> delivered = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        PeerConnection peer = new PeerConnection("batchy", List.of(""),
            new PeerConnection.Sender() {
                @Override
                public boolean send(String json) {
                    return recordBatch(List.of(json));
                }

                @Override
                public boolean sendBatch(List<String> frames) {
                    return recordBatch(frames);
                }

                private boolean recordBatch(List<String> frames) {
                    batchSizes.add(frames.size());
                    delivered.addAll(frames);
                    if (calls.incrementAndGet() == 1) {
                        // Hold the first batch so the rest piles up behind it.
                        arrived.countDown();
                        try {
                            gate.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return true;
                }
            },
            true, () -> { });

        try {
            assertTrue(peer.send("frame-0"), "first frame accepted");
            assertTrue(arrived.await(5, TimeUnit.SECONDS), "the drain took the first batch");
            for (int i = 1; i <= 150; i++) {
                assertTrue(peer.send("frame-" + i), "frame " + i + " must fit the queue");
            }
            gate.countDown();
            assertTrue(await(() -> delivered.size() == 151),
                "every frame must be delivered, got " + delivered.size());
        } finally {
            gate.countDown();
            peer.close();
        }

        assertEquals(151, delivered.size(), "no frame may be dropped");
        assertEquals(151, batchSizes.stream().mapToInt(Integer::intValue).sum(),
            "batch sizes must account for every frame: " + batchSizes);
        assertEquals("frame-150", delivered.get(150),
            "order must hold within a batch and across batches: " + delivered);
        assertTrue(batchSizes.stream().anyMatch(size -> size > 1),
            "a 150-frame backlog must be handed over as a batch, not one frame at a time: "
                + batchSizes);
    }

    /**
     * A batch the transport refuses is the drop the stats exist for: the frames
     * in the refused batch are reported once, the connection closes, and what is
     * still queued at that close is reported too — never both by the same frame.
     */
    @Test
    void aRefusedBatchIsReportedAsDroppedAndClosesTheConnection() throws Exception {
        java.util.concurrent.atomic.LongAdder dropped =
            new java.util.concurrent.atomic.LongAdder();
        CountDownLatch firstArrived = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        PeerConnection peer = new PeerConnection("rejecting", List.of(""),
            new PeerConnection.Sender() {
                @Override
                public boolean send(String json) {
                    return deliver(List.of(json));
                }

                @Override
                public boolean sendBatch(java.util.List<String> frames) {
                    return deliver(frames);
                }

                private boolean deliver(java.util.List<String> frames) {
                    if (calls.incrementAndGet() == 1) {
                        firstArrived.countDown();          // first batch is in flight
                        return false;                      // … and the transport refuses it
                    }
                    return true;
                }
            },
            true, () -> { }, dropped::add);

        for (int i = 0; i < 10; i++) {
            assertTrue(peer.send("frame-" + i), "frame " + i + " must be accepted");
        }
        assertTrue(firstArrived.await(5, TimeUnit.SECONDS), "the drain reached the transport");

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !peer.isClosed()) {
            Thread.sleep(10);
        }
        assertTrue(peer.isClosed(), "a refused batch must close the connection");
        assertEquals(10, dropped.sum(),
            "the refused batch and the frames queued behind it are all accounted: "
                + dropped.sum());
        assertFalse(peer.send("late"), "the closed connection must not take more frames");
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
