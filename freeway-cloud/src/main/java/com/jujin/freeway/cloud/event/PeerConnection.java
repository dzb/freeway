package com.jujin.freeway.cloud.event;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * unregisters and counts the drop exactly as before, without waiting.</p>
 */
public final class PeerConnection {

    /** Outbound frame transport; returns false when the send failed. */
    public interface Sender {
        boolean send(String json);
    }

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
    private final BlockingQueue<String> pending = new ArrayBlockingQueue<>(MAX_QUEUED_FRAMES);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread senderThread;

    public PeerConnection(String remoteOrigin, List<String> remotePrefixes, Sender sender,
                          boolean outbound, Runnable closer) {
        this.remoteOrigin = Objects.requireNonNull(remoteOrigin, "remoteOrigin");
        this.remotePrefixes = List.copyOf(remotePrefixes);
        this.sender = Objects.requireNonNull(sender, "sender");
        this.outbound = outbound;
        this.closer = Objects.requireNonNull(closer, "closer");
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
            Thread worker = senderThread;
            if (worker != null && worker != Thread.currentThread()) {
                // Ends an idle poll; a write blocked on a stalled peer ends
                // when the socket is closed just below.
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
     * Hands one wire frame to the connection's sender thread. Returns false
     * when the connection is closed or the queue is full — never waits on the
     * peer, so the publisher's thread cannot be held by a stalled reader.
     */
    boolean send(String json) {
        if (closed.get()) {
            return false;
        }
        startSenderThread();
        if (!pending.offer(json)) {
            return false;
        }
        // close() may have won between the check above and the offer: the drain
        // thread exits on `closed` and this frame is never sent, so report the
        // failure the caller's drop accounting expects.
        return !closed.get();
    }

    private void startSenderThread() {
        if (senderThread != null) {
            return;
        }
        synchronized (this) {
            if (senderThread == null && !closed.get()) {
                senderThread = Thread.ofVirtual()
                    .name("cloud-mesh-send-" + remoteOrigin)
                    .start(this::drain);
            }
        }
    }

    /** Drains the queue on this connection's own thread; a failed write closes it. */
    private void drain() {
        while (!closed.get()) {
            String json;
            try {
                json = pending.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if (json == null) {
                continue;
            }
            boolean sent;
            try {
                sent = sender.send(json);
            } catch (RuntimeException ex) {
                sent = false;
            }
            if (!sent) {
                // Close: the server's read loop (inbound) or the connector's
                // (outbound) unregisters the connection from the hub.
                close();
                return;
            }
        }
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
