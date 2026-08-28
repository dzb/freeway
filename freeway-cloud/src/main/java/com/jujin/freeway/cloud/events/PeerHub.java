package com.jujin.freeway.cloud.events;

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.ioc.EventBus;
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

/**
 * The hub: registry of live peer connections + the server-side WS endpoint
 * ({@code /cloud/events}) + the inbound dispatch pipeline.
 *
 * <p>Lifecycle: constructed by {@link CloudEventsModule} at bind time,
 * {@link #wire(EventBus, JsonCodec, String, List, List, String, boolean)}
 * runs from a RuntimeHook ordered before {@code freeway.http.server}, so
 * the hub is fully wired before the server can accept a single connection.</p>
 *
 * <p>Connection state is the fact; the registry view (peers map) is its
 * projection — this class only tracks connections and never dials.</p>
 */
public final class PeerHub implements WebSocketEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(PeerHub.class);

    private final Map<String, PeerConnection> peers = new ConcurrentHashMap<>();
    private final List<CloudEventInterceptor> interceptors = new ArrayList<>();
    private volatile EventBus bus;
    private volatile JsonCodec codec;
    private volatile String origin;
    private volatile String serviceId;
    private volatile List<String> subscriptions = List.of();
    private volatile List<String> allowedTypes = List.of();
    private volatile boolean wired;

    /** RuntimeHook-time wiring: resolves builtins and config-derived state. */
    public void wire(
        EventBus bus,
        JsonCodec codec,
        String serviceId,
        String instanceId,
        List<String> subscriptions,
        List<String> allowedTypes
    ) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.serviceId = serviceId;
        this.origin = instanceId != null && !instanceId.isBlank()
            ? instanceId
            : serviceId + "@" + java.util.UUID.randomUUID();
        this.subscriptions = List.copyOf(subscriptions);
        this.allowedTypes = List.copyOf(allowedTypes);
        this.wired = true;
        LOG.info("CloudEventBus wired: origin={} subscriptions={} allowedTypes={}",
            origin, subscriptions, allowedTypes.size());
    }

    /** Registers an inbound interceptor (called by the module from contributions). */
    public void addInterceptor(CloudEventInterceptor interceptor) {
        interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
    }

    /** Registers/replaces a peer connection (handshake complete). */
    public void register(PeerConnection connection) {
        PeerConnection previous = peers.put(connection.remoteOrigin(), connection);
        if (previous != null && previous != connection) {
            LOG.debug("Replaced peer connection: {}", connection.remoteOrigin());
        }
    }

    /** Removes a peer connection (disconnect). */
    public void unregister(String remoteOrigin) {
        peers.remove(remoteOrigin);
    }

    /** Live peer connections — the bridge iterates this for outbound fan-out. */
    public List<PeerConnection> connections() {
        return List.copyOf(peers.values());
    }

    public String origin() {
        return origin;
    }

    public String serviceId() {
        return serviceId;
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
     * Server session: first text = hello (origin + subscriptions) → ack with
     * own hello; subsequent text = CE frames → inbound pipeline.
     */
    private final class ServerSessionHandler implements WebSocketListener {
        private final WebSocketSession session;
        private volatile PeerConnection connection;

        ServerSessionHandler(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void onText(String text) {
            try {
                var frame = com.jujin.freeway.commons.json.JsonUtils.parseObject(text);
                if (frame.containsKey("proto")) {
                    handshake(frame);
                } else if (frame.containsKey("specversion")) {
                    receive(CloudEventEnvelope.parse(text));
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
            String remoteOrigin = java.util.Objects.requireNonNullElse(
                frame.getString("origin"), "");
            if (remoteOrigin.isBlank()) {
                session.close(1002, "hello missing origin");
                return;
            }
            List<String> remoteSubs = prefixes(frame.get("subscribe"));
            connection = new PeerConnection(remoteOrigin, remoteSubs,
                json -> {
                    try {
                        session.sendText(json);
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                });
            register(connection);
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
                unregister(connection.remoteOrigin());
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
        if (frame.channel() == com.jujin.freeway.ioc.EventBridge.Channel.CLASS) {
            if (!allowedTypes.isEmpty() && !allowedTypes.contains(frame.type())) {
                LOG.debug("Type not in allowlist — dropped: {}", frame.type());
                return;
            }
            try {
                Class<?> type = Class.forName(frame.type(), false, getClass().getClassLoader());
                Object event = codec.fromJson(frame.dataJson(), type);
                bus.publishInbound(event);
            } catch (ClassNotFoundException e) {
                LOG.debug("Event type not on this node's classpath — dropped: {}", frame.type());
            } catch (RuntimeException e) {
                LOG.error("Inbound event dispatch failed for {}", frame.type(), e);
            }
        } else {
            Object payload = frame.dataJson() == null
                ? null
                : com.jujin.freeway.commons.json.JsonUtils.parse(frame.dataJson());
            bus.publishInbound(frame.type(), payload);
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

    /** Codec accessor for the connector (client leg parses acks too). */
    public JsonCodec codec() {
        return codec;
    }
}
