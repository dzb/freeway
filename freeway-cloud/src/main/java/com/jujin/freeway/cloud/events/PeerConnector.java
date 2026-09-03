package com.jujin.freeway.cloud.events;

import com.jujin.freeway.cloud.CloudConfigKeys;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /** A peer that accepts the socket but never answers the hello must not
     *  pin a half-open connection forever — abort and let the dial loop
     *  retry with backoff. */
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
    /** Mirrors the server side's inbound message limit
     *  ({@code WebSocket.MAX_MESSAGE_SIZE}): fragment reassembly must not turn
     *  a peer that never sets FIN into unbounded memory. */
    private static final int MAX_INBOUND_MESSAGE = 16 * 1024 * 1024;

    private final HttpClient http;
    private final PeerHub hub;
    private final Map<String, AtomicInteger> backoffByPeer = new ConcurrentHashMap<>();
    private final List<URI> staticPeers;
    /** Open client sessions, so {@link #close()} can abort their sockets. */
    private final java.util.Set<ClientSessionHandler> sessions =
        ConcurrentHashMap.newKeySet();
    /** Dial/retry threads, so {@link #close()} can interrupt parked backoff. */
    private final java.util.Set<Thread> dialers = ConcurrentHashMap.newKeySet();
    /** Arms the per-connection handshake watchdogs. */
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
        Thread.ofVirtual().name("cloud-events-handshake-", 0).factory());
    private final Duration connectTimeout;
    private final String scheme;
    private volatile boolean started;
    private volatile boolean closed;

    /** Peers as host:port strings (design §3, zero-dependency start). */
    public PeerConnector(PeerHub hub, List<String> staticPeers, Duration connectTimeout) {
        this(hub, staticPeers, connectTimeout, "ws");
    }

    /** Creates a connector with an explicit outbound WS scheme ({@code ws} or {@code wss}). */
    public PeerConnector(PeerHub hub, List<String> staticPeers, Duration connectTimeout, String scheme) {
        this.hub = hub;
        this.staticPeers = staticPeers.stream()
            .map(this::toUri)
            .toList();
        this.connectTimeout = connectTimeout;
        this.scheme = scheme == null || scheme.isBlank() ? "ws" : scheme;
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
            spawnDial(peer);
        }
    }

    /** Feeds additional peers (e.g. discovered via an external registry).
     *  Safe before or after {@link #start()}: already-dialed endpoints are
     *  skipped. Synchronized on the same monitor as {@link #start(List)} so
     *  the {@code started} check and the {@code start()} snapshot cannot
     *  interleave — otherwise a peer fed between the two could be dialed by
     *  neither and silently lost. */
    public synchronized void setPeers(List<String> peers) {
        for (URI peer : peers.stream().map(this::toUri).toList()) {
            boolean known = staticPeers.stream().anyMatch(p -> sameEndpoint(p, peer))
                || backoffByPeer.keySet().stream()
                    .anyMatch(k -> sameEndpoint(URI.create(k), peer));
            if (!known && started) {
                spawnDial(peer);
            } else if (!known) {
                // not started yet — start() will pick it up from the map
                backoffByPeer.put(peer.toString(), new AtomicInteger(0));
            }
        }
    }

    private static boolean sameEndpoint(URI a, URI b) {
        return Objects.equals(a.getHost(), b.getHost()) && a.getPort() == b.getPort();
    }

    /**
     * {@code host:port} → {@code scheme://host:port/cloud/events}. IPv6
     * literals are accepted in brackets ({@code [::1]:7001}) or bare
     * ({@code fe80::1} — multiple colons imply no port component); the host
     * is bracketed in the rendered URI per RFC 3986.
     */
    URI toUri(String peer) {
        if (peer == null || peer.isBlank()) {
            throw new IllegalArgumentException("peer must not be blank");
        }
        String host;
        int port = 80;
        if (peer.startsWith("[")) {
            int close = peer.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("unclosed IPv6 literal: " + peer);
            }
            host = peer.substring(1, close);
            if (close + 1 < peer.length()) {
                if (peer.charAt(close + 1) != ':') {
                    throw new IllegalArgumentException("expected :port after ']': " + peer);
                }
                port = parsePort(peer.substring(close + 2));
            }
        } else {
            int colon = peer.indexOf(':');
            if (colon >= 0 && colon == peer.lastIndexOf(':')) {
                host = peer.substring(0, colon);
                port = parsePort(peer.substring(colon + 1));
            } else {
                host = peer; // plain hostname or bare IPv6 literal
            }
        }
        if (host.isBlank()) {
            throw new IllegalArgumentException("peer host must not be blank: " + peer);
        }
        String hostPart = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return URI.create(scheme + "://" + hostPart + ":" + port + CloudConfigKeys.EVENTS_PATH_DEFAULT);
    }

    private static int parsePort(String raw) {
        int port;
        try {
            port = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("peer port is not a number: '" + raw + "'");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("peer port out of range: " + port);
        }
        return port;
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

    /** Starts a dial/retry thread and tracks it for interruptible shutdown. */
    private void spawnDial(URI peer) {
        Thread dialer = Thread.ofVirtual().unstarted(() -> {
            try {
                dialLoop(peer);
            } finally {
                dialers.remove(Thread.currentThread());
            }
        });
        dialers.add(dialer);
        dialer.start();
    }

    /** One dial: build the WebSocket; the listener takes over after onOpen. */
    private java.util.concurrent.CompletableFuture<WebSocket> connect(URI peer) {
        var handler = new ClientSessionHandler(peer);
        sessions.add(handler);
        return http.newWebSocketBuilder()
            .subprotocols("freeway.events.v1")
            .connectTimeout(connectTimeout)
            .buildAsync(peer, handler)
            .whenComplete((ws, err) -> {
                if (err != null) {
                    sessions.remove(handler); // never opened — nothing to abort
                }
            });
    }

    @Override
    public void close() {
        closed = true;
        // Abort live sockets first: abort() → handleDisconnect() → no
        // reconnect, because closed is already set.
        for (ClientSessionHandler session : sessions) {
            session.abort();
        }
        sessions.clear();
        // A dialer parked in Thread.sleep(backoff) must not hold shutdown for
        // up to BACKOFF_MAX_MS.
        for (Thread dialer : dialers) {
            dialer.interrupt();
        }
        dialers.clear();
        watchdog.shutdownNow();
        // Releases the client's selector thread and pooled connections.
        http.close();
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
        private volatile ScheduledFuture<?> handshakeTimer;
        private volatile PeerConnection connection;
        private volatile boolean suppressReconnect;
        /** One dial loop per session, no matter how many failure callbacks
         *  fire for it (JDK WebSocket may deliver both onError and onClose
         *  for one socket; abort() adds its own schedule on top). */
        private final AtomicBoolean reconnectScheduled = new AtomicBoolean();

        ClientSessionHandler(URI peer) {
            this.peer = peer;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            ws = webSocket;
            if (closed) {
                // close() ran while the dial was in flight: the abort loop in
                // close() saw ws == null, so this socket would otherwise go
                // live unowned (and submit watchdog tasks to a shutdown
                // executor). Abort it here instead.
                webSocket.abort();
                return;
            }
            // The socket being open says nothing about the mesh handshake:
            // a peer that never answers the hello is aborted and retried.
            handshakeTimer = watchdog.schedule(() -> {
                if (connection == null && !closed) {
                    LOG.warn("Peer {} never completed the mesh handshake — aborting", peer);
                    abort();
                }
            }, HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
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

        /** Reassembles peer messages that arrive fragmented (see the type). */
        private final TextMessageAssembler inbound = new TextMessageAssembler(MAX_INBOUND_MESSAGE);

        @Override
        public java.util.concurrent.CompletionStage<?> onText(
            WebSocket webSocket, CharSequence data, boolean last) {
            String text;
            try {
                text = inbound.accept(data, last);
            } catch (RuntimeException tooBig) {
                LOG.warn("Inbound message from peer {} rejected — aborting: {}",
                    peer, tooBig.getMessage());
                abort();
                return null;
            }
            if (text == null) {
                webSocket.request(1); // more fragments due
                return null;
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
            // The handshake completed — disarm the watchdog before its race
            // window can abort a healthy connection.
            ScheduledFuture<?> timer = handshakeTimer;
            if (timer != null) {
                timer.cancel(false);
            }
            // sendText queues the frame; isDone() immediately would be
            // racy. Queued == accepted: the socket serializes frames in
            // order, and a transport failure surfaces via onError/onClose
            // (handleDisconnect → reconnect). False negatives would drop
            // events, so the return value never reports a queued send as
            // failed — but the send's completion stage is observed, so a
            // real flush failure reaches handleDisconnect (unregister +
            // reconnect) even when the JDK delivers no listener callback.
            connection = new PeerConnection(remoteOrigin, remoteSubs,
                json -> {
                    WebSocket socket = ws;
                    if (socket == null) {
                        return false;
                    }
                    socket.sendText(json, true).whenComplete((sent, error) -> {
                        if (error != null) {
                            handleDisconnect(String.valueOf(error));
                        }
                    });
                    return true;
                },
                true,
                () -> {
                    suppressReconnect = true;
                    try {
                        if (ws != null) {
                            ws.abort();
                        }
                    } catch (Exception ignored) {
                        // already dead
                    }
                });
            hub.register(connection);
            if (connection.isClosed()) {
                return; // duplicate resolution closed this outbound connection
            }
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
            cancelHandshakeTimer();
            sessions.remove(this);
            if (connection != null) {
                hub.unregister(connection);
                LOG.info("Peer connection lost: {} ({})", connection.remoteOrigin(), cause);
            } else {
                LOG.debug("Peer {} not established: {}", peer, cause);
            }
            if (!suppressReconnect) {
                scheduleReconnect();
            }
        }

        private void abort() {
            cancelHandshakeTimer();
            try {
                if (ws != null) {
                    ws.abort();
                }
            } catch (Exception ignored) {
                // already dead
            }
            if (!suppressReconnect) {
                scheduleReconnect();
            }
        }

        /** Disarms the handshake watchdog: every terminal path (ack, reject,
         *  disconnect) must cancel it, or the timer later aborts a healthy
         *  session and dials an extra connection. */
        private void cancelHandshakeTimer() {
            ScheduledFuture<?> timer = handshakeTimer;
            if (timer != null) {
                timer.cancel(false);
            }
        }

        private void scheduleReconnect() {
            if (!closed && reconnectScheduled.compareAndSet(false, true)) {
                spawnDial(peer);
            }
        }
    }
}
