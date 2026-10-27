package com.jujin.freeway.cloud.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One live peer connection: the remote node's identity, its declared
 * subscription prefixes, and a sender over the underlying WebSocket.
 *
 * <p>Instances are created on handshake completion (hello/ack exchanged) and
 * removed on close. The same shape serves both directions; the {@code outbound}
 * flag records which side initiated the connection so duplicate simultaneous
 * dials can be resolved deterministically.</p>
 *
 * <p><b>Sends are queued, not done on the publisher's thread.</b> A WebSocket
 * write blocks once the peer stops reading (the socket buffer fills and there
 * is no write timeout), so writing inline would let one stalled peer hang the
 * thread that published — an HTTP worker or a {@code Defer} commit thread.
 * Each connection therefore owns a bounded queue drained by its own virtual
 * thread; a full queue, or a failed write noticed by the drain thread, closes
 * the connection — the at-most-once plane's existing "drop and detach" answer.
 * Queue-full is reported to the caller as a failed send, so the publisher
 * unregisters and counts the drop exactly as before, without waiting. The
 * drain takes a whole batch per pass ({@link Sender#sendBatch}), so the
 * transport pays one flush per batch rather than one per frame. Every frame
 * this connection accepts is accounted exactly once: written, or reported to
 * {@code onFramesDropped} — by the drain when the transport refuses a batch, at
 * close for whatever was still queued.</p>
 */
public final class PeerConnection {

    private static final Logger LOG = LoggerFactory.getLogger(PeerConnection.class);

    /** Outbound frame transport; returns false when the send failed. */
    public interface Sender {
        boolean send(String json);

        /**
         * One batch, in order. The default loops {@link #send}; a transport
         * that can flush once per batch (the framework engine's
         * {@code sendTextBatch}) overrides it and pays one syscall instead of
         * one per frame.
         */
        default boolean sendBatch(List<String> frames) {
            for (String frame : frames) {
                if (!send(frame)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Frames handed to the transport in one batch. Batching is where the per-frame
     * syscall goes away (the engine writes a batch under one lock and flushes
     * once); the cap bounds how long one batch can keep the loop from noticing
     * {@code close()}.
     */
    static final int BATCH_FRAMES = 64;

    /**
     * Frames buffered for a peer that is slow to read. Beyond this the
     * connection is treated as failed rather than allowed to grow without
     * bound: the plane is at-most-once, so dropping the connection is its
     * documented failure mode.
     */
    static final int MAX_QUEUED_FRAMES = 256;

    private final String remoteOrigin;
    private final List<String> remotePrefixes;
    private final Sender sender;
    private final boolean outbound;
    private final Runnable closer;
    /**
     * Where this connection reports frames it accepted but never wrote — a batch
     * the transport refused, or whatever was still queued when the connection
     * closed. The counter lives in the broadcast plane; a connection that never
     * got a sink (tests, a mesh not yet wired) reports nowhere, which is a lost
     * count, never a wrong one.
     */
    private final LongConsumer onFramesDropped;
    private final BlockingQueue<String> pending = new ArrayBlockingQueue<>(MAX_QUEUED_FRAMES);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread senderThread;

    /** Callers without mesh metrics — tests and standalone use. */
    public PeerConnection(String remoteOrigin, List<String> remotePrefixes, Sender sender,
                          boolean outbound, Runnable closer) {
        this(remoteOrigin, remotePrefixes, sender, outbound, closer, frames -> { });
    }

    public PeerConnection(String remoteOrigin, List<String> remotePrefixes, Sender sender,
                          boolean outbound, Runnable closer, LongConsumer onFramesDropped) {
        this.remoteOrigin = Objects.requireNonNull(remoteOrigin, "remoteOrigin");
        this.remotePrefixes = List.copyOf(remotePrefixes);
        this.sender = Objects.requireNonNull(sender, "sender");
        this.outbound = outbound;
        this.closer = Objects.requireNonNull(closer, "closer");
        this.onFramesDropped = Objects.requireNonNull(onFramesDropped, "onFramesDropped");
    }

    public String remoteOrigin() {
        return remoteOrigin;
    }

    /** True when this side initiated the connection; false for an inbound peer connection. */
    public boolean isOutbound() {
        return outbound;
    }

    /** Stops the sender thread and closes the underlying transport, exactly once. */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Thread worker;
            // Read under the same monitor that publishes it. A read outside the
            // lock could see null and leave the drain thread parked in take()
            // forever, which is why this used to poll on a 200 ms timeout.
            synchronized (this) {
                worker = senderThread;
            }
            if (worker != null && worker != Thread.currentThread()) {
                // Ends the blocking take(); a write blocked on a stalled peer
                // ends when the socket is closed just below.
                worker.interrupt();
            }
            closer.run();
        }
    }

    /** True after {@link #close()} has been called. */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Hands one wire frame to the connection's sender thread. Returns false when
     * the frame was not taken — the connection is closed or the queue is full —
     * and never waits on the peer, so the publisher's thread cannot be held by a
     * stalled reader.
     *
     * <p>Responsibility for a frame changes hands at {@code true}: from there the
     * connection owns it, and if it is never written (a refused batch, a close)
     * that is counted once, by the drain thread. A false return means the
     * publisher still owns the frame and counts the loss itself.</p>
     */
    boolean send(String json) {
        if (closed.get()) {
            return false;
        }
        // The drain thread is what makes an accepted frame accountable — it is
        // the one that reports what never got written. No thread, no acceptance.
        if (!startSenderThread()) {
            return false;
        }
        return pending.offer(json);
    }

    /** Starts the drain thread unless one exists; false when the connection
     *  closed first, so the frame must not be accepted unowned. */
    private boolean startSenderThread() {
        if (senderThread != null) {
            return true;
        }
        synchronized (this) {
            if (senderThread != null) {
                return true;
            }
            if (closed.get()) {
                return false;
            }
            senderThread = Thread.ofVirtual()
                .name("cloud-mesh-send-" + remoteOrigin)
                .start(this::drain);
            return true;
        }
    }

    /** Drains the queue on this connection's own thread; a failed write closes it.
     *  Whatever is still queued when the thread ends is reported as dropped — after
     *  it, nothing will ever write again, and while it runs no one else takes from
     *  the queue, so the count is exact. */
    private void drain() {
        try {
            while (!closed.get()) {
                List<String> batch = new ArrayList<>(BATCH_FRAMES);
                try {
                    // take() first: draining an empty queue would spin, and the
                    // interrupt in close() is what ends this wait.
                    batch.add(pending.take());
                    pending.drainTo(batch, BATCH_FRAMES - 1);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                boolean sent;
                try {
                    sent = sender.sendBatch(batch);
                } catch (RuntimeException ex) {
                    sent = false;
                }
                if (!sent) {
                    onFramesDropped.accept(batch.size());
                    LOG.warn("Send to peer {} failed for {} queued frame(s) — dropping the connection",
                        remoteOrigin, batch.size());
                    // Close: the server's read loop (inbound) or the connector's
                    // (outbound) unregisters the connection from the hub.
                    close();
                    return;
                }
            }
        } finally {
            // close() itself is called from here on the failure path; the CAS in
            // close() makes that re-entry a no-op, so once is still once.
            reportStranded();
        }
    }

    /** Reports what this connection accepted but never wrote: whatever the queue
     *  still holds when the drain thread ends. */
    private void reportStranded() {
        List<String> stranded = new ArrayList<>(pending.size());
        pending.drainTo(stranded);
        if (!stranded.isEmpty()) {
            onFramesDropped.accept(stranded.size());
            LOG.debug("Peer {} dropped with {} queued frame(s) unwritten",
                remoteOrigin, stranded.size());
        }
    }

    /** Package-visible for the shutdown test: what close() has to stop. */
    Thread senderThread() {
        return senderThread;
    }

    /**
     * True when this peer declared interest in the topic — prefix match over
     * the hello declaration (an empty prefix pulls everything). The mesh's
     * only routing key is the topic string; class names never reach here.
     */
    boolean matchesTopic(String topic) {
        if (topic == null) {
            return false;
        }
        for (String prefix : remotePrefixes) {
            if (prefix.isEmpty() || topic.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
