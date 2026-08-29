package com.jujin.freeway.cloud.events;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outbound dialer for the event mesh: connects to configured peers
 * ({@code ws://host:port/cloud/events}), performs the hello handshake, and
 * keeps connections alive with exponential-backoff reconnects.
 *
 * <p>Peers resolve from a static list (zero-dependency start) — the discovery
 * backend path is additive and left to the caller to feed via
 * {@link #setPeers(List)} when a registry backend is installed.</p>
 */
public final class PeerConnector implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PeerConnector.class);
    private static final long BACKOFF_BASE_MS = 1_000;
    private static final long BACKOFF_MAX_MS = 30_000;

    private final HttpClient http;
    private final PeerHub hub;
    private final Map<String, AtomicInteger> backoffByPeer = new ConcurrentHashMap<>();
    private final List<URI> staticPeers;
    private final Duration connectTimeout;
    private volatile boolean started;
    private volatile boolean closed;

    /** Peers as host:port strings (design §3, zero-dependency start). */
    public PeerConnector(PeerHub hub, List<String> staticPeers, Duration connectTimeout) {
        this.hub = hub;
        this.staticPeers = staticPeers.stream()
            .map(PeerConnector::toUri)
            .toList();
        this.connectTimeout = connectTimeout;
        this.http = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .build();
    }

    /** Dials every configured peer (virtual threads; failures back off). */
    public void start() {
        start(List.of());
    }

    /** As {@link #start()} with additional dynamically-resolved peers. */
    public synchronized void start(List<String> dynamicPeers) {
        if (started) {
            // late dynamic peers are handled by setPeers — no double dial
            setPeers(dynamicPeers);
            return;
        }
        started = true;
        var all = new java.util.LinkedHashMap<URI, Boolean>();
        for (URI peer : staticPeers) all.put(peer, true);
        for (String p : dynamicPeers) all.put(toUri(p), true);
        for (String k : backoffByPeer.keySet()) all.put(URI.create(k), true);
        for (URI peer : all.keySet()) {
            Thread.ofVirtual().start(() -> dialLoop(peer));
        }
    }

    /** Feeds additional peers (e.g. discovered via an external registry).
     *  Safe before or after {@link #start()}: already-dialed endpoints are
     *  skipped. */
    public void setPeers(List<String> peers) {
        for (URI peer : peers.stream().map(PeerConnector::toUri).toList()) {
            boolean known = staticPeers.stream().anyMatch(p -> sameEndpoint(p, peer))
                || backoffByPeer.keySet().stream()
                    .anyMatch(k -> sameEndpoint(URI.create(k), peer));
            if (!known && started) {
                Thread.ofVirtual().start(() -> dialLoop(peer));
            } else if (!known) {
                // not started yet — start() will pick it up from the map
                backoffByPeer.put(peer.toString(), new AtomicInteger(0));
            }
        }
    }

    private static boolean sameEndpoint(URI a, URI b) {
        return a.getHost().equals(b.getHost()) && a.getPort() == b.getPort();
    }

    /** {@code host:port} → {@code ws://host:port/cloud/events}. */
    static URI toUri(String peer) {
        String host = peer;
        int port = 80;
        int colon = peer.lastIndexOf(':');
        if (colon > 0) {
            host = peer.substring(0, colon);
            port = Integer.parseInt(peer.substring(colon + 1));
        }
        return URI.create("ws://" + host + ":" + port + "/cloud/events");
    }

    /** Connect-retry loop: dials, then parks for backoff on every failure. */
    private void dialLoop(URI peer) {
        AtomicInteger backoff = backoffByPeer.computeIfAbsent(peer.toString(),
            k -> new AtomicInteger(0));
        while (!closed) {
            try {
                connect(peer).get();
                backoff.set(0); // healthy connection — reset on next disconnect
                return; // connection lifecycle now owned by the session listener
            } catch (Exception e) {
                if (closed) {
                    return;
                }
                long sleep = Math.min(
                    BACKOFF_BASE_MS * (1L << Math.min(backoff.get(), 5)),
                    BACKOFF_MAX_MS);
                backoff.incrementAndGet();
                LOG.debug("Peer {} connect failed ({}ms backoff): {}",
                    peer, sleep, String.valueOf(e.getCause() == null ? e : e.getCause()));
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** One dial: build the WebSocket; the listener takes over after onOpen. */
    private java.util.concurrent.CompletableFuture<WebSocket> connect(URI peer) {
        return http.newWebSocketBuilder()
            .subprotocols("freeway.events.v1")
            .connectTimeout(connectTimeout)
            .buildAsync(peer, new ClientSessionHandler(peer));
    }

    @Override
    public void close() {
        closed = true;
    }

    /**
     * Client leg of the mesh: mirrors the server handler — sends hello on
     * open, consumes the ack (registers the connection with the peer's own
     * subscriptions), then feeds CE frames into the hub's inbound pipeline.
     * Any failure schedules a reconnect through the dial loop.
     */
    private final class ClientSessionHandler implements WebSocket.Listener {
        private final URI peer;
        private volatile WebSocket ws;
        private volatile PeerConnection connection;

        ClientSessionHandler(URI peer) {
            this.peer = peer;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            ws = webSocket;
            var hello = new java.util.LinkedHashMap<String, Object>();
            hello.put("proto", 1);
            hello.put("origin", hub.origin());
            hello.put("serviceId", hub.serviceId());
            hello.put("subscribe", hub.subscriptions());
            if (!hub.token().isBlank()) {
                hello.put("token", hub.token());
            }
            webSocket.sendText(hub.codec().toJson(hello), true);
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(
            WebSocket webSocket, CharSequence data, boolean last) {
            String text = data.toString();
            if (!last) {
                webSocket.request(1);
                return null; // v1 frames are single-frame; partial frames ignored
            }
            try {
                var frame = com.jujin.freeway.commons.json.JsonUtils.parseObject(text);
                if (frame.containsKey("proto")) {
                    acceptHandshake(frame);
                } else if (frame.containsKey("specversion")) {
                    hub.receive(CloudEventEnvelope.parse(text));
                }
            } catch (RuntimeException e) {
                LOG.error("Frame handling failed for peer {}", peer, e);
                abort();
                return null;
            }
            webSocket.request(1);
            return null;
        }

        private void acceptHandshake(com.jujin.freeway.commons.json.JsonObject frame) {
            boolean accepted = frame.getBoolean("accept");
            String remoteOrigin = java.util.Objects.requireNonNullElse(
                frame.getString("origin"), "");
            if (!accepted || remoteOrigin.isBlank()) {
                LOG.warn("Peer {} rejected the connection — closing", peer);
                abort();
                return;
            }
            List<String> remoteSubs = PeerHub.prefixes(frame.get("subscribe"));
            // sendText queues the frame; isDone() immediately would be
            // racy. Queued == accepted: the socket serializes frames in
            // order, and a transport failure surfaces via onError/onClose
            // (handleDisconnect → reconnect). False negatives would drop
            // events, so never report failure for a queued send.
            connection = new PeerConnection(remoteOrigin, remoteSubs,
                json -> { ws.sendText(json, true); return true; });
            hub.register(connection);
            LOG.info("Connected to peer: {} (subscriptions={})", remoteOrigin, remoteSubs);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(
            WebSocket webSocket, int statusCode, String reason) {
            handleDisconnect(statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            handleDisconnect(String.valueOf(error));
        }

        private void handleDisconnect(String cause) {
            if (connection != null) {
                hub.unregister(connection.remoteOrigin());
                LOG.info("Peer connection lost: {} ({})", connection.remoteOrigin(), cause);
            } else {
                LOG.debug("Peer {} not established: {}", peer, cause);
            }
            scheduleReconnect();
        }

        private void abort() {
            try {
                if (ws != null) {
                    ws.abort();
                }
            } catch (Exception ignored) {
                // already dead
            }
            scheduleReconnect();
        }

        private void scheduleReconnect() {
            if (!closed) {
                Thread.ofVirtual().start(() -> dialLoop(peer));
            }
        }
    }
}
