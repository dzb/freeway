package com.jujin.freeway.cloud.event;

import com.jujin.freeway.cloud.CloudConfigKeys;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.List;
import java.util.Map;
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
 * ({@code ws://host:port/cloud/event}), performs the hello handshake, and
 * keeps connections alive with exponential-backoff reconnects.
 *
 * <p>Peers resolve from a static list (zero-dependency start) — the discovery
 * backend path is additive and left to the caller to feed via
 * {@link #setPeers(List)} when a registry backend is installed.</p>
 */
final class PeerConnector implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PeerConnector.class);
    /** Default reconnect backoff floor / ceiling (exponential, capped) — one
     *  source with the config layer
     *  ({@link CloudConfigKeys#EVENT_BACKOFF_BASE_MS_DEFAULT} /
     *  {@link CloudConfigKeys#EVENT_BACKOFF_MAX_MS_DEFAULT}), so this library
     *  fallback cannot drift from the lifecycle hook's specs. */
    private static final long BACKOFF_BASE_MS =
        CloudConfigKeys.EVENT_BACKOFF_BASE_MS_DEFAULT;
    /** WebSocket 1001: the peer is going away (RFC 6455 §7.4.1). */
    private static final int GOING_AWAY = 1001;
    /** How long a close frame may take to leave before the socket is aborted. */
    private static final long CLOSE_FRAME_TIMEOUT_MS = 200;
    private static final long BACKOFF_MAX_MS =
        CloudConfigKeys.EVENT_BACKOFF_MAX_MS_DEFAULT;
    /** A peer that accepts the socket but never answers the hello must not
     *  pin a half-open connection forever — abort and let the dial loop
     *  retry with backoff. One source with the config layer
     *  ({@link CloudConfigKeys#EVENT_HANDSHAKE_TIMEOUT_MS_DEFAULT}); the
     *  ms→Duration conversion happens at this boundary. */
    private static final Duration HANDSHAKE_TIMEOUT =
        Duration.ofMillis(CloudConfigKeys.EVENT_HANDSHAKE_TIMEOUT_MS_DEFAULT);
    /** Dial timeout; the config key's own default, so a bare
     *  {@link Wiring#defaults()} behaves exactly like an unconfigured app. */
    private static final Duration CONNECT_TIMEOUT =
        Duration.ofMillis(CloudConfigKeys.EVENT_CONNECT_TIMEOUT_MS_DEFAULT);
    /** Mirrors the server side's inbound message limit
     *  ({@code WebSocket.MAX_MESSAGE_SIZE}): fragment reassembly must not turn
     *  a peer that never sets FIN into unbounded memory. */
    private static final int MAX_INBOUND_MESSAGE = 16 * 1024 * 1024;

    private final HttpClient http;
    private final PeerHub hub;
    private final Map<PeerAddress, AtomicInteger> backoffByPeer = new ConcurrentHashMap<>();
    /** Open client sessions, so {@link #close()} can abort their sockets. */
    private final java.util.Set<ClientSessionHandler> sessions =
        ConcurrentHashMap.newKeySet();
    /** Dial/retry threads, so {@link #close()} can interrupt parked backoff. */
    private final java.util.Set<Thread> dialers = ConcurrentHashMap.newKeySet();
    /** Arms the per-connection handshake watchdogs. */
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
        Thread.ofVirtual().name("cloud-event-handshake-", 0).factory());
    private final Duration connectTimeout;
    private final String scheme;
    /** Handshake watchdog budget; a peer that accepts but never answers the
     *  hello is aborted so the dial loop can retry with backoff. Overridable
     *  via {@code freeway.cloud.event.handshake-timeout-ms}. */
    private final Duration handshakeTimeout;
    private final long backoffBaseMs;
    private final long backoffMaxMs;
    private volatile boolean started;
    private volatile boolean closed;

    /**
     * Peers arrive through {@link #start(List)} or {@link #setPeers(List)} —
     * the configured ones from the lifecycle hook, the discovered ones from an
     * adapter. There is no separate constructor-supplied set: it would be a
     * second way to feed the same thing, and production never used it.
     * Networking values come from the {@code freeway.cloud.event.*} config via
     * {@link com.jujin.freeway.cloud.CloudConfigKeys}.
     */
    public PeerConnector(PeerHub hub, Wiring wiring) {
        this.hub = Objects.requireNonNull(hub, "hub");
        Objects.requireNonNull(wiring, "wiring");
        this.connectTimeout = wiring.connectTimeout();
        this.scheme = wiring.scheme();
        this.handshakeTimeout = wiring.handshakeTimeout();
        this.backoffBaseMs = wiring.backoffBaseMs();
        this.backoffMaxMs = wiring.backoffMaxMs();
        this.http = newClient(wiring.connectTimeout(), wiring.sslContext());
    }

    /**
     * The connector's optional inputs, as one value with named knobs.
     *
     * <p>It replaced a seven-position constructor in which
     * {@code connectTimeout}/{@code handshakeTimeout} (two adjacent
     * {@link Duration}s) and {@code backoffBaseMs}/{@code backoffMaxMs} (two
     * adjacent {@code long}s) were silently interchangeable: swapping either
     * pair compiled and produced a mesh that timed out or backed off on the
     * wrong schedule. Every default here is the value
     * {@code freeway.cloud.event.*} declares, applied in the compact
     * constructor so a {@code null}/non-positive input cannot reach a field.</p>
     */
    public record Wiring(
        Duration connectTimeout,
        String scheme,
        Duration handshakeTimeout,
        long backoffBaseMs,
        long backoffMaxMs,
        javax.net.ssl.SSLContext sslContext
    ) {
        public Wiring {
            connectTimeout = connectTimeout == null ? CONNECT_TIMEOUT : connectTimeout;
            scheme = scheme == null || scheme.isBlank() ? "ws" : scheme;
            handshakeTimeout = handshakeTimeout == null ? HANDSHAKE_TIMEOUT : handshakeTimeout;
            backoffBaseMs = backoffBaseMs <= 0 ? BACKOFF_BASE_MS : backoffBaseMs;
            backoffMaxMs = backoffMaxMs <= 0 ? BACKOFF_MAX_MS : backoffMaxMs;
        }

        /** Production defaults from the config keys, no outbound TLS material. */
        public static Wiring defaults() {
            return new Wiring(CONNECT_TIMEOUT, "ws", HANDSHAKE_TIMEOUT,
                BACKOFF_BASE_MS, BACKOFF_MAX_MS, null);
        }

        public Wiring withConnectTimeout(Duration connectTimeout) {
            return new Wiring(connectTimeout, scheme, handshakeTimeout, backoffBaseMs,
                backoffMaxMs, sslContext);
        }

        /** {@code ws} or {@code wss}; blank falls back to {@code ws}. */
        public Wiring withScheme(String scheme) {
            return new Wiring(connectTimeout, scheme, handshakeTimeout, backoffBaseMs,
                backoffMaxMs, sslContext);
        }

        public Wiring withHandshakeTimeout(Duration handshakeTimeout) {
            return new Wiring(connectTimeout, scheme, handshakeTimeout, backoffBaseMs,
                backoffMaxMs, sslContext);
        }

        public Wiring withBackoff(long backoffBaseMs, long backoffMaxMs) {
            return new Wiring(connectTimeout, scheme, handshakeTimeout, backoffBaseMs,
                backoffMaxMs, sslContext);
        }

        /** Outbound TLS material shared with the RPC client; null = JDK default. */
        public Wiring withSslContext(javax.net.ssl.SSLContext sslContext) {
            return new Wiring(connectTimeout, scheme, handshakeTimeout, backoffBaseMs,
                backoffMaxMs, sslContext);
        }
    }

    /**
     * The dialer's HTTP client. {@code sslContext} is the outbound transport
     * security the application configured ({@link
     * com.jujin.freeway.cloud.rpc.TransportSecurity}) — the mesh is one of the
     * outbound paths that must honour it, so a {@code wss://} dial presents the
     * same client identity and trusts the same authorities as an RPC call.
     * {@code null} means the JDK default (system trust, no client identity).
     */
    static HttpClient newClient(Duration connectTimeout, javax.net.ssl.SSLContext sslContext) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        return builder.build();
    }

    /** Dials the given peers (virtual threads; failures back off). */
    public synchronized void start(List<String> peers) {
        if (started) {
            // late peers are handled by setPeers — no double dial
            setPeers(peers);
            return;
        }
        started = true;
        var all = new java.util.LinkedHashMap<PeerAddress, Boolean>();
        for (String p : peers) all.put(PeerAddress.parse(p), true);
        for (PeerAddress k : backoffByPeer.keySet()) all.put(k, true);
        for (PeerAddress peer : all.keySet()) {
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
        for (PeerAddress peer : peers.stream().map(PeerAddress::parse).toList()) {
            boolean known = backoffByPeer.containsKey(peer);
            if (!known && started) {
                spawnDial(peer);
            } else if (!known) {
                // not started yet — start() will pick it up from the map
                backoffByPeer.put(peer, new AtomicInteger(0));
            }
        }
    }

    /**
     * Connect-retry loop: sleeps per the peer's current backoff, dials once,
     * and hands the session to the listener. The backoff is advanced by the
     * listener when a session ends without a completed handshake (the peer
     * rejected the hello, the watchdog fired) and by this loop on open
     * failures; it is reset only by a successful handshake — an accepted TCP
     * upgrade says nothing about mesh health, and resetting here was what let
     * two token-mismatched peers re-dial each other at full speed forever.
     */
    private void dialLoop(PeerAddress peer) {
        AtomicInteger backoff = backoffByPeer.computeIfAbsent(peer,
            k -> new AtomicInteger(0));
        while (!closed) {
            long sleep = backoffSleepMs(backoff.get());
            if (sleep > 0) {
                LOG.debug("Peer {} re-dial in {}ms (backoff)", peer, sleep);
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                connect(peer, backoff).get();
                return; // connection lifecycle now owned by the session listener
            } catch (Exception e) {
                if (closed) {
                    return;
                }
                backoff.incrementAndGet();
                LOG.debug("Peer {} connect failed: {}",
                    peer, String.valueOf(e.getCause() == null ? e : e.getCause()));
            }
        }
    }

    /** Exponential backoff capped at {@code backoffMaxMs} for the given attempt count. */
    private long backoffSleepMs(int attempts) {
        if (attempts <= 0) {
            return 0;
        }
        return Math.min(backoffBaseMs * (1L << Math.min(attempts, 5)), backoffMaxMs);
    }

    /** Package-visible for the reconnect-pacing test: the failed-attempt
     *  count driving the next dial's backoff. A completed mesh handshake
     *  resets it; a socket open no longer does. */
    int backoffAttempts(PeerAddress peer) {
        AtomicInteger count = backoffByPeer.get(peer);
        return count == null ? 0 : count.get();
    }

    /** Starts a dial/retry thread and tracks it for interruptible shutdown. */
    private void spawnDial(PeerAddress peer) {
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
    private java.util.concurrent.CompletableFuture<WebSocket> connect(PeerAddress peer,
            AtomicInteger backoff) {
        var handler = new ClientSessionHandler(peer, backoff);
        sessions.add(handler);
        return http.newWebSocketBuilder()
            .subprotocols("freeway.event.v1")
            .connectTimeout(connectTimeout)
            .buildAsync(peer.toUri(scheme), handler)
            .whenComplete((ws, err) -> {
                if (err != null) {
                    sessions.remove(handler); // never opened — nothing to abort
                }
            });
    }

    @Override
    public void close() {
        closed = true;
        // Close live sockets first: closeGracefully() → handleDisconnect() →
        // no reconnect, because closed is already set.
        for (ClientSessionHandler session : sessions) {
            session.closeGracefully();
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
     * A lost session is re-dialed unless the hub still serves that origin
     * (duplicate resolution); the plane's failed-send drop re-dials too.
     */
    private final class ClientSessionHandler implements WebSocket.Listener {
        private final PeerAddress peer;
        /** Shared with the dial loop: advanced on a failed attempt, cleared on handshake. */
        private final AtomicInteger backoff;
        private volatile WebSocket ws;
        private volatile ScheduledFuture<?> handshakeTimer;
        private volatile PeerConnection connection;
        /** Set once the peer's ack passed — gates CE frames (client-leg
         *  mirror of the server's hello-first state machine): hub.receive is
         *  only for admitted peers, and hello is one-shot per session. */
        private volatile boolean handshaken;
        /** One dial loop per session, no matter how many failure callbacks
         *  fire for it (JDK WebSocket may deliver both onError and onClose
         *  for one socket; abort() adds its own schedule on top). */
        private final AtomicBoolean reconnectScheduled = new AtomicBoolean();

        ClientSessionHandler(PeerAddress peer, AtomicInteger backoff) {
            this.peer = peer;
            this.backoff = backoff;
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
            }, handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS);
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
                    if (handshaken) {
                        // Mirror the server leg: hello is one-shot per
                        // session — a second one re-negotiates nothing.
                        LOG.warn("Peer {} sent a second hello — aborting", peer);
                        abort();
                        return null;
                    }
                    acceptHandshake(frame);
                } else if (frame.containsKey("specversion")) {
                    if (!handshaken) {
                        // Server-leg mirror: the CE pipeline (hub.receive) is
                        // only for admitted peers. Before the ack the peer
                        // never ran our admission — treat the frame as a
                        // protocol violation, not as an event.
                        LOG.warn("CE frame from peer {} before the handshake "
                            + "completed — aborting", peer);
                        abort();
                        return null;
                    }
                    hub.receive(CloudEventEnvelope.parse(text));
                } else {
                    // Mirror the server leg: an unrecognized frame means the
                    // peer is not speaking the mesh protocol. Ignoring it
                    // would let a malformed session stay alive indefinitely.
                    LOG.warn("Unrecognized frame from peer {} — aborting", peer);
                    abort();
                    return null;
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
            // event, so the return value never reports a queued send as
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
                // Close the transport only. Whether this close must be
                // followed by a re-dial is decided by handleDisconnect from
                // the hub registry (the origin may still be served by the
                // duplicate-resolution twin) — not by who invoked close():
                // the plane's send-failure drop unregisters first and expects
                // the connector to dial again.
                this::abort);
            // Ack accepted — from here on frames are CE, and a second hello
            // is rejected by the state machine in onText. The mesh handshake
            // completing is the health signal the backoff keys on: clear it
            // only here, not at socket open.
            handshaken = true;
            backoff.set(0);
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
                // Opened but never handshaked (rejected hello, watchdog abort,
                // protocol violation before the ack): count it as a failed
                // attempt so the next dial parks — resetting only on socket
                // open was what let a permanently-rejected peer dial at full
                // speed forever.
                backoff.incrementAndGet();
                LOG.debug("Peer {} not established: {}", peer, cause);
            }
            if (closed) {
                return; // connector shutdown — no re-dial may outlive it
            }
            if (connection != null && hub.hasRegistered(connection.remoteOrigin())) {
                // The origin is still served by another registered connection
                // (duplicate resolution kept the twin): nothing to dial.
                LOG.debug("Peer {} still served by another connection — no reconnect", peer);
                return;
            }
            scheduleReconnect();
        }

        /**
         * Sends a WebSocket close frame before the socket goes, so the peer
         * reads a shutdown (1001) instead of a connection reset — the
         * difference between "this node is leaving" and "something broke",
         * which decides whether the peer re-dials or waits. Bounded: a
         * shutdown must not wait on a peer that is already gone, and
         * {@link #abort()} stays the fallback.
         */
        private void closeGracefully() {
            WebSocket socket = ws;
            if (socket != null) {
                try {
                    socket.sendClose(GOING_AWAY, "shutdown")
                        .get(CLOSE_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    // Already gone, or the frame never made it out — abort()
                    // below closes the socket either way.
                }
            }
            abort();
        }

        private void abort() {
            cancelHandshakeTimer();
            try {
                WebSocket socket = ws;
                if (socket != null) {
                    socket.abort();
                }
            } catch (Exception ignored) {
                // already dead
            }
            // Teardown is complete here (not deferred to whatever close
            // callback the JDK delivers): clean up and decide on the re-dial
            // synchronously, so a failed session can never be left neither
            // registered nor scheduled. Later onClose/onError callbacks for
            // the same socket hit the same idempotent path.
            handleDisconnect("aborted");
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
