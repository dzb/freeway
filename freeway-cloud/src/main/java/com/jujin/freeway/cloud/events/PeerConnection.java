package com.jujin.freeway.cloud.events;

import java.util.List;
import java.util.Objects;

/**
 * One live peer connection: the remote node's identity, its declared
 * subscription prefixes, and a sender over the underlying WebSocket.
 *
 * <p>Instances are created on handshake completion (hello/ack exchanged) and
 * removed on close. The same shape serves both directions — a connection we
 * dialed and a connection a peer dialed to us are indistinguishable after
 * the handshake (full-duplex mesh).</p>
 */
public final class PeerConnection {

    /** Outbound frame transport; returns false when the send failed. */
    public interface Sender {
        boolean send(String json);
    }

    private final String remoteOrigin;
    private final List<String> remotePrefixes;
    private final Sender sender;

    public PeerConnection(String remoteOrigin, List<String> remotePrefixes, Sender sender) {
        this.remoteOrigin = Objects.requireNonNull(remoteOrigin, "remoteOrigin");
        this.remotePrefixes = List.copyOf(remotePrefixes);
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    public String remoteOrigin() {
        return remoteOrigin;
    }

    public List<String> remotePrefixes() {
        return remotePrefixes;
    }

    /** Sends one wire frame; returns false (and never throws) on failure. */
    public boolean send(String json) {
        try {
            return sender.send(json);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * True when this peer declared interest in the message — prefix match
     * over the hello declaration against ANY of the message's routing keys:
     * the CE {@code type} (event class FQN for the CLASS channel) and the
     * resolved topic ({@code @Topic} value or simple name).
     */
    public boolean matches(String type, String topic) {
        for (String prefix : remotePrefixes) {
            if (prefix.isEmpty()) {
                return true;
            }
            if (type != null && type.startsWith(prefix)) {
                return true;
            }
            if (topic != null && topic.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
