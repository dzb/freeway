package com.jujin.freeway.cloud.event;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.rpc.TransportSecurity;

import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.internal.HttpServiceDeclaration;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CloudEventBus lifecycle hook: arms bus-level inbound deduplication, wires
 * the hub, installs the outbound sink and starts the peer connector. Runs
 * before the HTTP server so the hub is wired before the first connection can
 * arrive; stop removes the sink and releases the connector's threads.
 *
 * <p>Dedup is a property of the bus, not of the mesh: it also suppresses a
 * single transport's own redeliveries (Kafka hands a record back after a
 * consumer rebalance), so it is armed even when the WS mesh is disabled.
 */
final class CloudEventLifecycleHook implements RuntimeHook {

    private static final Logger LOG = LoggerFactory.getLogger(CloudEventLifecycleHook.class);

    private static final SymbolSpec<Integer> DEDUP_CAPACITY = SymbolSpec.of(
        CloudConfigKeys.EVENT_DEDUP_CAPACITY, Integer.class,
        CloudConfigKeys.EVENT_DEDUP_CAPACITY_DEFAULT, Integer::parseInt);
    /** The explicit form of the master switch, kept raw so "unset" (blank)
     *  is distinguishable from an explicit {@code false} — the presence rule
     *  applies only to the unset case. */
    private static final SymbolSpec<String> EVENT_ENABLED_EXPLICIT = SymbolSpec.of(
        CloudConfigKeys.EVENT_ENABLED, String.class, "", Function.identity());
    private static final SymbolSpec<Boolean> DEDUP_ENABLED = SymbolSpec.of(
        CloudConfigKeys.EVENT_DEDUP_ENABLED, Boolean.class, false);

    private static final SymbolSpec<String> TOKEN = SymbolSpec.of(
        CloudConfigKeys.EVENT_TOKEN, String.class, "", Function.identity());
    private static final SymbolSpec<List<String>> SUBSCRIPTIONS =
        SymbolSpec.list(CloudConfigKeys.EVENT_SUBSCRIPTIONS, List.of());
    private static final SymbolSpec<List<String>> ALLOWED_TYPES =
        SymbolSpec.list(CloudConfigKeys.EVENT_ALLOWED_TYPES, List.of());
    private static final SymbolSpec<List<String>> ALLOWED_TOPICS =
        SymbolSpec.list(CloudConfigKeys.EVENT_ALLOWED_TOPICS, List.of());
    private static final SymbolSpec<List<String>> PEERS =
        SymbolSpec.list(CloudConfigKeys.EVENT_PEERS, List.of());
    private static final SymbolSpec<Long> CONNECT_TIMEOUT_MS =
        SymbolSpec.of(CloudConfigKeys.EVENT_CONNECT_TIMEOUT_MS, Long.class,
            CloudConfigKeys.EVENT_CONNECT_TIMEOUT_MS_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Long> HANDSHAKE_TIMEOUT_MS =
        SymbolSpec.of(CloudConfigKeys.EVENT_HANDSHAKE_TIMEOUT_MS, Long.class,
            CloudConfigKeys.EVENT_HANDSHAKE_TIMEOUT_MS_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Long> BACKOFF_BASE_MS =
        SymbolSpec.of(CloudConfigKeys.EVENT_BACKOFF_BASE_MS, Long.class,
            CloudConfigKeys.EVENT_BACKOFF_BASE_MS_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Long> BACKOFF_MAX_MS =
        SymbolSpec.of(CloudConfigKeys.EVENT_BACKOFF_MAX_MS, Long.class,
            CloudConfigKeys.EVENT_BACKOFF_MAX_MS_DEFAULT, Long::parseLong);

    private final PeerHub hub;
    private final CloudEventSink sink;
    private volatile PeerConnector connector;

    CloudEventLifecycleHook(PeerHub hub, CloudEventSink sink) {
        this.hub = hub;
        this.sink = sink;
    }

    @Override
    public void start(Container container) {
        var symbols = container.get(SymbolSource.class);
        EventBus bus = container.get(EventBus.class);

        if (symbols.resolve(DEDUP_ENABLED)) {
            bus.inboundDeduplication(
                symbols.resolve(DEDUP_CAPACITY));
        }

        List<String> peers = symbols.resolve(PEERS);
        if (!meshOn(symbols.resolve(EVENT_ENABLED_EXPLICIT), peers)) {
            LOG.info("CloudEventBus not wired — dial peers with {}=<host:port,...> "
                    + "or set {}=true (discovery-fed mesh)",
                CloudConfigKeys.EVENT_PEERS, CloudConfigKeys.EVENT_ENABLED);
            return;
        }

        // The mesh presents the identity the registry registers — literally
        // the same resolution, not a second derivation of it. This hook runs
        // after the HTTP server (see CloudEventModule), so host:port are the
        // ones this node actually serves on.
        ServiceInstance self = HttpServiceDeclaration.of(container);
        if (self == null) {
            throw new IllegalStateException(
                "CloudEventBus needs the HTTP module: its mesh endpoint lives on "
                    + "the HTTP server, so the node's identity cannot be derived");
        }
        hub.wire(new PeerHub.Wiring(
            bus,
            container.get(JsonCodec.class),
            self.serviceId(),
            self.instanceId(),
            symbols.resolve(SUBSCRIPTIONS),
            symbols.resolve(ALLOWED_TYPES),
            symbols.resolve(ALLOWED_TOPICS),
            symbols.resolve(TOKEN)));

        // Contributions are resolved lazily at lookup — safe even when the
        // contribution view was built at bind time.
        container.extension(CloudEventInterceptor.class).all()
            .forEach(hub::addInterceptor);

        // The connector owns an HttpClient; create it only when the mesh is
        // actually enabled so a disabled module stays cheap. The dial scheme
        // is read off the identity resolved above — the same `auto` derivation
        // the registry stores — so a node cannot register https:// and dial
        // ws:// (or the reverse).
        String wsScheme = switch (self.endpoint().scheme()) {
            case "https" -> "wss";
            case "http" -> "ws";
            default -> throw new IllegalStateException(
                "Unsupported registered scheme: " + self.endpoint().scheme());
        };
        warnIfTokenOverCleartext(wsScheme, hub.token());
        // Outbound TLS material is shared with the RPC client: the mesh is an
        // outbound path too, and a wss:// dial must present the same identity.
        // Resolved optionally — the event module installs without the RPC one.
        TransportSecurity security = optional(container, TransportSecurity.class);
        connector = new PeerConnector(hub,
            Duration.ofMillis(symbols.resolve(CONNECT_TIMEOUT_MS)),
            wsScheme,
            Duration.ofMillis(symbols.resolve(HANDSHAKE_TIMEOUT_MS)),
            symbols.resolve(BACKOFF_BASE_MS),
            symbols.resolve(BACKOFF_MAX_MS),
            security == null ? null : security.sslContext());
        bus.addEventSink(sink);
        connector.start(peers);
    }

    /**
     * Presence-driven activation via {@link SymbolSpec}: an explicit
     * {@code event.enabled} wins — {@code true} turns the mesh on and
     * {@code false} is the kill switch (suppressing even a configured peer
     * list). Unset falls to the presence rule: configured peers imply a
     * mesh. Nothing set leaves the module inert — installing
     * CloudEventModule alone is never a side effect.
     */
    /** A collaborator the mesh can work without (the RPC module not installed). */
    private static <T> T optional(Container container, Class<T> type) {
        try {
            return container.get(type);
        } catch (com.jujin.freeway.ioc.MissingBindingException e) {
            return null;
        }
    }

    /**
     * A mesh token authenticates peers, so it must be encrypted in transit to
     * mean anything. Dialing {@code ws://} with one configured is worth a loud
     * startup warning — not a refusal: terminating TLS in a sidecar (a service
     * mesh) is a normal deployment, and the token is then protected by the
     * mesh's own mTLS even though this process speaks cleartext.
     */
    static boolean tokenOverCleartext(String scheme, String token) {
        return token != null && !token.isBlank() && "ws".equalsIgnoreCase(scheme);
    }

    private void warnIfTokenOverCleartext(String scheme, String token) {
        if (tokenOverCleartext(scheme, token)) {
            LOG.warn("Event mesh token configured over cleartext {}:// — the token is only"
                + " protected inside a service mesh or a trusted network; terminate TLS"
                + " (freeway.cloud.registry.service-scheme=https for wss://) or remove the token",
                scheme);
        }
    }

    private static boolean meshOn(String enabledRaw, List<String> peers) {
        return SymbolSpec.activated(CloudConfigKeys.EVENT_ENABLED, enabledRaw, !peers.isEmpty());
    }

    @Override
    public void stop(Container container) {
        // Release the channel and the dialer: without this the sink stays
        // installed on the bus and the connector's HttpClient and retry
        // threads outlive the app.
        try {
            container.get(EventBus.class).removeEventSink(sink);
        } finally {
            if (connector != null) {
                connector.close();
                connector = null;
            }
        }
    }

}
