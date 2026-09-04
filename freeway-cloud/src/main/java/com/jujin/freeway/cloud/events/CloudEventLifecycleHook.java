package com.jujin.freeway.cloud.events;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.internal.ConfigLists;
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
        CloudConfigKeys.EVENTS_DEDUP_CAPACITY, Integer.class,
        CloudConfigKeys.EVENTS_DEDUP_CAPACITY_DEFAULT, Integer::parseInt);
    private static final SymbolSpec<Boolean> EVENTS_ENABLED = SymbolSpec.of(
        CloudConfigKeys.EVENTS_ENABLED, Boolean.class, false, Boolean::parseBoolean);
    private static final SymbolSpec<Boolean> DEDUP_ENABLED = SymbolSpec.of(
        CloudConfigKeys.EVENTS_DEDUP_ENABLED, Boolean.class, false, Boolean::parseBoolean);

    private static final SymbolSpec<String> SERVICE_INSTANCE_ID = SymbolSpec.of(
        CloudConfigKeys.REGISTRY_SERVICE_INSTANCE_ID, String.class, "", Function.identity());
    private static final SymbolSpec<String> SERVICE_SCHEME = SymbolSpec.of(
        CloudConfigKeys.REGISTRY_SERVICE_SCHEME, String.class, "http", Function.identity());
    private static final SymbolSpec<String> TOKEN = SymbolSpec.of(
        CloudConfigKeys.EVENTS_TOKEN, String.class, "", Function.identity());
    private static final SymbolSpec<List<String>> SUBSCRIPTIONS =
        ConfigLists.spec(CloudConfigKeys.EVENTS_SUBSCRIPTIONS, List.of());
    private static final SymbolSpec<List<String>> ALLOWED_TYPES =
        ConfigLists.spec(CloudConfigKeys.EVENTS_ALLOWED_TYPES, List.of());
    private static final SymbolSpec<List<String>> ALLOWED_TOPICS =
        ConfigLists.spec(CloudConfigKeys.EVENTS_ALLOWED_TOPICS, List.of());
    private static final SymbolSpec<List<String>> PEERS =
        ConfigLists.spec(CloudConfigKeys.EVENTS_PEERS, List.of());
    private static final SymbolSpec<Long> CONNECT_TIMEOUT_MS =
        SymbolSpec.of(CloudConfigKeys.EVENTS_CONNECT_TIMEOUT_MS, Long.class,
            CloudConfigKeys.EVENTS_CONNECT_TIMEOUT_MS_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Long> HANDSHAKE_TIMEOUT_MS =
        SymbolSpec.of(CloudConfigKeys.EVENTS_HANDSHAKE_TIMEOUT_MS, Long.class,
            CloudConfigKeys.EVENTS_HANDSHAKE_TIMEOUT_MS_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Long> BACKOFF_BASE_MS =
        SymbolSpec.of(CloudConfigKeys.EVENTS_BACKOFF_BASE_MS, Long.class,
            CloudConfigKeys.EVENTS_BACKOFF_BASE_MS_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Long> BACKOFF_MAX_MS =
        SymbolSpec.of(CloudConfigKeys.EVENTS_BACKOFF_MAX_MS, Long.class,
            CloudConfigKeys.EVENTS_BACKOFF_MAX_MS_DEFAULT, Long::parseLong);

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
            bus.enableInboundDeduplication(
                symbols.resolve(DEDUP_CAPACITY));
        }

        if (!symbols.resolve(EVENTS_ENABLED)) {
            LOG.info("CloudEventBus disabled ({}=false) — mesh not wired",
                CloudConfigKeys.EVENTS_ENABLED);
            return;
        }

        hub.wire(new PeerHub.Wiring(
            bus,
            container.get(JsonCodec.class),
            // Same fallback chain as HttpServiceDeclaration (registry
            // service-id → freeway.app.name → "freeway-app"): the mesh origin
            // identity must equal the identity registered in discovery, or a
            // node configured only with freeway.app.name would present two
            // names to the rest of the system.
            symbols.resolve(CloudConfigKeys.REGISTRY_SERVICE_ID,
                symbols.resolve("freeway.app.name", "freeway-app")),
            symbols.resolve(SERVICE_INSTANCE_ID),
            symbols.resolve(SUBSCRIPTIONS),
            symbols.resolve(ALLOWED_TYPES),
            symbols.resolve(ALLOWED_TOPICS),
            symbols.resolve(TOKEN)));

        // Contributions are resolved lazily at lookup — safe even when the
        // contribution view was built at bind time.
        container.extension(CloudEventInterceptor.class).all()
            .forEach(hub::addInterceptor);

        // The connector owns an HttpClient; create it only when the mesh is
        // actually enabled so a disabled module stays cheap.
        String registryScheme = symbols.resolve(SERVICE_SCHEME);
        String wsScheme = "https".equalsIgnoreCase(registryScheme) ? "wss" : "ws";
        connector = new PeerConnector(hub, List.of(),
            Duration.ofMillis(symbols.resolve(CONNECT_TIMEOUT_MS)),
            wsScheme,
            Duration.ofMillis(symbols.resolve(HANDSHAKE_TIMEOUT_MS)),
            symbols.resolve(BACKOFF_BASE_MS),
            symbols.resolve(BACKOFF_MAX_MS));
        bus.addEventSink(sink);
        connector.start(symbols.resolve(PEERS));
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
