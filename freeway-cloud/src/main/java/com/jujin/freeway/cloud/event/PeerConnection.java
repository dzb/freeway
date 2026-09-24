package com.jujin.freeway.cloud.event;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One live peer connection: the remote node's identity, its declared
 * subscription prefixes, and a sender over the underlying WebSocket.
 *
 * <p>Instances are created on handshake completion (hello/ack exchanged) and
 * removed on close. The same shape serves both directions; the {@code outbound}
 * flag records which side initiated the connection so duplicate simultaneous
 * dials can be resolved deterministically.</p>
 */
public final class PeerConnection {

    /** Outbound frame transport; returns false when the send failed. */
    public interface Sender {
        boolean send(String json);
    }

    private final String remoteOrigin;
    private final List<String> remotePrefixes;
    private final Sender sender;
    private final boolean outbound;
    private final Runnable closer;
    private final AtomicBoolean closed = new AtomicBoolean();

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

    /** Closes the underlying transport exactly once. */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closer.run();
        }
    }

    /** True after {@link #close()} has been called. */
    public boolean isClosed() {
        return closed.get();
    }

    /** Sends one wire frame; returns false (and never throws) on failure. */
    boolean send(String json) {
        try {
            return sender.send(json);
        } catch (RuntimeException e) {
            return false;
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
