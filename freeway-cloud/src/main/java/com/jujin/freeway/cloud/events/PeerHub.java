package com.jujin.freeway.cloud.events;

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.ioc.EventBusInbound;
import com.jujin.freeway.http.websocket.WebSocketEndpoint;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The hub: registry of live peer connections + the server-side WS endpoint
 * ({@code /cloud/events}) + the inbound dispatch pipeline.
 *
 * <p>Lifecycle: constructed by {@link CloudEventModule} at bind time,
 * {@link #wire(EventBusInbound, JsonCodec, String, String, List, List, List, String)}
 * runs from a RuntimeHook ordered before {@code freeway.http.server}, so
 * the hub is fully wired before the server can accept a single connection.</p>
 *
 * <p>Connection state is the fact; the registry view (peers map) is its
 * projection — this class only tracks connections and never dials.</p>
 */
public final class PeerHub implements WebSocketEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(PeerHub.class);

    private final Map<String, PeerConnection> peers = new ConcurrentHashMap<>();
    /** Contributed at hook time, read per inbound frame on WS threads —
     *  copy-on-write keeps add-after-wire safe without external locking. */
    private final List<CloudEventInterceptor> interceptors = new CopyOnWriteArrayList<>();
    private volatile EventBusInbound bus;
    private volatile JsonCodec codec;
    private volatile String origin;
    private volatile String serviceId;
    private volatile List<String> subscriptions = List.of();
    private volatile List<String> allowedTypes = List.of();
    private volatile List<String> allowedTopics = List.of();
    private volatile String token = "";
    private volatile boolean wired;

    /**
     * One-shot wiring for the hub — every cross-module input in one place,
     * so the eight values cannot drift apart at a call site.
     *
     * @param bus            the inbound face of the local event bus
     * @param codec          JSON codec for frames and acks
     * @param serviceId      logical service id (envelope source)
     * @param instanceId     this node's mesh identity; blank derives one
     * @param subscriptions  CE type/topic prefixes pulled from the mesh
     * @param allowedTypes   CLASS-channel deserialization allowlist (empty = deny-by-default)
     * @param allowedTopics  TOPIC-channel allowlist (empty = any)
     * @param token          mesh handshake token (blank = peer auth off)
     */
    public record Wiring(
        EventBusInbound bus,
        JsonCodec codec,
        String serviceId,
        String instanceId,
        List<String> subscriptions,
        List<String> allowedTypes,
        List<String> allowedTopics,
        String token
    ) {}

    /** RuntimeHook-time wiring: resolves builtins and config-derived state. */
    public void wire(Wiring w) {
        this.bus = Objects.requireNonNull(w.bus(), "bus");
        this.codec = Objects.requireNonNull(w.codec(), "codec");
        this.serviceId = w.serviceId();
        this.origin = w.instanceId() != null && !w.instanceId().isBlank()
            ? w.instanceId()
            : w.serviceId() + "@" + java.util.UUID.randomUUID();
        this.subscriptions = List.copyOf(w.subscriptions());
        this.allowedTypes = List.copyOf(w.allowedTypes());
        this.allowedTopics = List.copyOf(w.allowedTopics());
        this.token = w.token() == null ? "" : w.token();
        this.wired = true;
        LOG.info("CloudEventBus wired: origin={} subscriptions={} allowedTypes={} allowedTopics={}",
            origin, subscriptions, allowedTypes.size(), allowedTopics.size());
        warnWhenInboundIsUngated();
    }

    /**
     * A node that accepts inbound (it declared subscriptions) is reachable by
     * every peer that can open a socket to it. An empty allowlist or an absent
     * token means "accept anything", which must be visible at startup rather
     * than discovered after an incident.
     */
    private void warnWhenInboundIsUngated() {
        if (subscriptions.isEmpty()) {
            return; // outbound-only: nothing is accepted from peers
        }
        if (allowedTypes.isEmpty()) {
            LOG.warn("CloudEventBus has no CLASS-channel type allowlist — CLASS-channel "
                + "events are dropped (deny-by-default); set {} to accept typed events",
                com.jujin.freeway.cloud.CloudConfigKeys.EVENTS_ALLOWED_TYPES);
        }
        if (allowedTopics.isEmpty()) {
            LOG.warn("CloudEventBus accepts TOPIC-channel payloads on ANY topic from "
                + "connected peers — set {} to restrict inbound topics",
                com.jujin.freeway.cloud.CloudConfigKeys.EVENTS_ALLOWED_TOPICS);
        }
        if (token.isBlank()) {
            LOG.warn("CloudEventBus has no mesh token — any peer that can reach the "
                + "endpoint may connect; set {} to require one",
                com.jujin.freeway.cloud.CloudConfigKeys.EVENTS_TOKEN);
        }
    }

    /** Registers an inbound interceptor (called by the module from contributions). */
    public void addInterceptor(CloudEventInterceptor interceptor) {
        interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
    }

    /**
     * Registers a peer connection. If another connection to the same origin
     * already exists, the mesh keeps exactly one: the connection initiated by
     * the lexicographically smaller origin (both sides apply the same rule,
     * so simultaneous dials converge on one surviving socket).
     */
    public void register(PeerConnection connection) {
        String remote = connection.remoteOrigin();
        while (true) {
            PeerConnection previous = peers.get(remote);
            if (previous == null) {
                if (peers.putIfAbsent(remote, connection) == null) {
                    return;
                }
                continue;
            }
            if (previous == connection) {
                return;
            }
            PeerConnection toClose = duplicateToClose(previous, connection);
            if (toClose == connection) {
                connection.close();
                return;
            }
            if (peers.replace(remote, previous, connection)) {
                previous.close();
                return;
            }
            // Lost a race with another registration; retry against the new head.
        }
    }

    private PeerConnection duplicateToClose(PeerConnection existing, PeerConnection incoming) {
        String local = origin;
        String remote = incoming.remoteOrigin();
        if (local == null || local.equals(remote)) {
            return incoming; // keep the existing connection by default
        }
        boolean localIsSmaller = local.compareTo(remote) < 0;
        if (localIsSmaller) {
            // Keep the connection we initiated (outbound); close the inbound one.
            if (existing.isOutbound()) {
                return incoming;
            }
            if (incoming.isOutbound()) {
                return existing;
            }
        } else {
            // Keep the connection the peer initiated (inbound); close the outbound one.
            if (existing.isOutbound()) {
                return existing;
            }
            if (incoming.isOutbound()) {
                return incoming;
            }
        }
        // Same direction or undecidable: keep the existing connection.
        return incoming;
    }

    /** Removes a peer connection if it is still the registered one (disconnect). */
    public void unregister(PeerConnection connection) {
        peers.remove(connection.remoteOrigin(), connection);
    }

    /** Live peer connections — the sink iterates this for outbound fan-out. */
    public List<PeerConnection> connections() {
        return List.copyOf(peers.values());
    }

    /**
     * True when a connection to {@code remoteOrigin} is still registered.
     * The mesh keeps exactly one connection per origin, so an outbound
     * session that died while a surviving twin is registered (duplicate
     * resolution) must not be re-dialed — the connector consults this
     * instead of remembering which side closed it.
     */
    boolean hasRegistered(String remoteOrigin) {
        return peers.containsKey(remoteOrigin);
    }

    public String origin() {
        return origin;
    }

    public String serviceId() {
        return serviceId;
    }

    /** The mesh token the connector presents on its outbound handshake. */
    public String token() {
        return token;
    }

    public List<String> subscriptions() {
        return subscriptions;
    }

    public boolean wired() {
        return wired;
    }

    // ── WebSocketEndpoint: the server side of the mesh ────────────────────

    @Override
    public WebSocketListener open(WebSocketSession session) {
        if (!wired) {
            LOG.warn("Peer connection arrived before wire() — rejecting");
            try {
                session.close(1013, "not wired");
            } catch (Exception ignored) {
                // session may already be gone; nothing to recover
            }
            return WebSocketListener.NOOP;
        }
        return new ServerSessionHandler(session);
    }

    @Override
    public java.util.Set<String> subprotocols() {
        return java.util.Set.of("freeway.events.v1");
    }

    /**
     * Server session: per-session handshake state machine. The first text
     * must be hello (origin + subscriptions) → ack with own hello; the token
     * check runs only on that path, so CE frames before hello are closed
     * (1002) rather than dispatched — otherwise any client that can open a
     * socket could skip admission and inject events (allowed-topics is
     * accept-any by default). Hello is one-shot per session: a second hello
     * is a protocol error too.
     */
    private final class ServerSessionHandler implements WebSocketListener {
        private final WebSocketSession session;
        private volatile PeerConnection connection;
        /** Set once the hello passed admission; gates every later frame. */
        private volatile boolean handshaken;

        ServerSessionHandler(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void onText(String text) {
            try {
                var frame = com.jujin.freeway.commons.json.JsonUtils.parseObject(text);
                if (frame.containsKey("proto")) {
                    if (handshaken) {
                        // The session already passed admission once; a second
                        // hello would re-negotiate under a new origin.
                        LOG.warn("Duplicate hello from peer — closing");
                        session.close(1002, "duplicate hello");
                    } else {
                        handshake(frame);
                    }
                } else if (frame.containsKey("specversion")) {
                    if (!handshaken) {
                        // Admission gate: receive() dispatches to the local
                        // bus, but the token check lives in the hello path —
                        // a CE frame before hello must never reach it.
                        LOG.warn("CE frame from peer before hello — closing");
                        session.close(1002, "hello expected");
                    } else {
                        receive(CloudEventEnvelope.parse(text));
                    }
                } else {
                    LOG.warn("Unrecognized frame from peer — closing");
                    session.close(1002, "protocol error");
                }
            } catch (Exception e) {
                LOG.error("Frame handling failed", e);
                try {
                    session.close(1011, "frame handling failed");
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }

        private void handshake(com.jujin.freeway.commons.json.JsonObject frame) throws java.io.IOException {
            HelloAdmission admission = validateHello(frame, token);
            if (!admission.accepted()) {
                if (admission.closeCode() == 1008) {
                    LOG.warn("Peer {} failed the mesh token check — closing", admission.origin());
                }
                session.close(admission.closeCode(), admission.reason());
                return;
            }
            String remoteOrigin = admission.origin();
            List<String> remoteSubs = admission.subscriptions();
            connection = new PeerConnection(remoteOrigin, remoteSubs,
                json -> {
                    try {
                        session.sendText(json);
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                },
                false,
                () -> {
                    try {
                        session.close(1000, "duplicate closed");
                    } catch (Exception ignored) {
                        // already closed
                    }
                });
            // Admission passed — from here on frames are CE, and a second
            // hello is rejected by the state machine in onText.
            handshaken = true;
            register(connection);
            if (connection.isClosed()) {
                return; // duplicate resolution closed this inbound connection
            }
            // Ack carries OUR hello: accept + origin + own subscriptions.
            var ack = new java.util.LinkedHashMap<String, Object>();
            ack.put("proto", 1);
            ack.put("accept", true);
            ack.put("origin", origin);
            ack.put("subscribe", subscriptions);
            session.sendText(codec.toJson(ack));
            LOG.info("Peer connected: {} (subscriptions={})", remoteOrigin, remoteSubs);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) throws java.io.IOException {
            if (connection != null) {
                unregister(connection);
                LOG.info("Peer disconnected: {} (code={}, remote={})",
                    connection.remoteOrigin(), code, remote);
            }
        }

        @Override
        public void onError(Throwable error) {
            LOG.debug("Peer session error", error);
        }
    }

    // ── inbound pipeline (shared by server + client legs) ─────────────────

    /** Dispatches one decoded wire frame through interceptors → local bus. */
    public void receive(CloudEventEnvelope.Parsed frame) {
        if (frame.origin().equals(origin)) {
            return; // our own event looped back through the mesh — drop
        }
        for (CloudEventInterceptor interceptor : interceptors) {
            if (!interceptor.onInbound(frame)) {
                return; // dropped by interceptor
            }
        }
        if (frame.channel() == com.jujin.freeway.ioc.EventSink.Channel.CLASS) {
            // CLASS-channel frames deserialize an arbitrary class by name, so
            // the allowlist is deny-by-default: with no allowlist configured,
            // no class is ever resolved. Never fall back to accepting any type.
            if (!allowedTypes.contains(frame.type())) {
                LOG.debug("Type not allowlisted for CLASS-channel delivery — dropped: {}", frame.type());
                return;
            }
            try {
                Class<?> type = Class.forName(frame.type(), false, getClass().getClassLoader());
                Object event = codec.fromJson(frame.dataJson(), type);
                bus.publishInbound(event, frame.id());
            } catch (ClassNotFoundException e) {
                LOG.debug("Event type not on this node's classpath — dropped: {}", frame.type());
            } catch (RuntimeException e) {
                LOG.error("Inbound event dispatch failed for {}", frame.type(), e);
            }
        } else {
            // The TOPIC channel is a gate too, not just the CLASS one: the peer
            // names the topic and supplies the payload.
            if (!allowedTopics.isEmpty() && !matchesPrefix(allowedTopics, frame.type())) {
                LOG.debug("Topic not in allowlist — dropped: {}", frame.type());
                return;
            }
            Object payload = frame.dataJson() == null
                ? null
                : com.jujin.freeway.commons.json.JsonUtils.parse(frame.dataJson());
            bus.publishInbound(frame.type(), payload, frame.id());
        }
    }

    /** hello `subscribe` elements → prefix list (accepts strings or {prefix,group}). */
    static List<String> prefixes(Object raw) {
        List<String> prefixes = new ArrayList<>();
        List<?> items;
        if (raw instanceof List<?> list) {
            items = list;
        } else if (raw instanceof com.jujin.freeway.commons.json.JsonArray arr) {
            items = arr.toList();
        } else {
            items = List.of();
        }
        for (Object item : items) {
            if (item instanceof String s) {
                prefixes.add(s);
            } else if (item instanceof Map<?, ?> m && m.get("prefix") != null) {
                prefixes.add(String.valueOf(m.get("prefix")));
            }
        }
        return prefixes;
    }

    /** True when {@code value} starts with any of {@code prefixes}. */
    private static boolean matchesPrefix(List<String> prefixes, String value) {
        if (value == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Length-independent comparison so the token cannot be probed by timing. */
    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whether a peer's presented token satisfies {@code expected}. An absent
     * expected token disables peer auth entirely (documented default); an
     * absent presented token never satisfies a configured one.
     */
    static boolean acceptsToken(String presented, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return constantTimeEquals(presented == null ? "" : presented, expected);
    }

    /**
     * Outcome of the mesh hello admission check. Accepted carries the parsed
     * remote origin + subscriptions; rejected carries a WS close code + reason
     * (and the origin when it was known, for the log).
     */
    record HelloAdmission(boolean accepted, String origin, List<String> subscriptions,
                          int closeCode, String reason) {
        static HelloAdmission accept(String origin, List<String> subscriptions) {
            return new HelloAdmission(true, origin, subscriptions, 0, null);
        }

        static HelloAdmission reject(String origin, int closeCode, String reason) {
            return new HelloAdmission(false, origin, null, closeCode, reason);
        }
    }

    /**
     * Mesh hello admission: the origin must be present, and the token must
     * match (constant-time) when one is configured. Pure — no session, so the
     * handshake gate is testable in isolation from the running inbound gate
     * ({@link #receive}).
     */
    static HelloAdmission validateHello(com.jujin.freeway.commons.json.JsonObject frame, String expectedToken) {
        String remoteOrigin = Objects.requireNonNullElse(frame.getString("origin"), "");
        if (remoteOrigin.isBlank()) {
            return HelloAdmission.reject(null, 1002, "hello missing origin");
        }
        if (!acceptsToken(frame.getString("token"), expectedToken)) {
            return HelloAdmission.reject(remoteOrigin, 1008, "unauthorized");
        }
        return HelloAdmission.accept(remoteOrigin, prefixes(frame.get("subscribe")));
    }

    /** Codec accessor for the connector (client leg parses acks too). */
    public JsonCodec codec() {
        return codec;
    }
}
