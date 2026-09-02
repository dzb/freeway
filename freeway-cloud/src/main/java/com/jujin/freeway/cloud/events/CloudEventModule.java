package com.jujin.freeway.cloud.events;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.CloudHooks;
import com.jujin.freeway.commons.config.ConfigSpec;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.http.websocket.WebSocketRoute;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the CloudEventBus: a WS endpoint at {@code /cloud/events}, the
 * peer connector, and the outbound sink — wired so that a loaded module
 * turns {@code EventBus.publish} into a cross-node CloudEvents 1.0 broadcast.
 *
 * <p>Requires {@link com.jujin.freeway.http.HttpModule} (the WS endpoint rides
 * the HTTP server). The hook is ordered before {@code freeway.http.server} so
 * the hub is wired before the first connection can arrive.</p>
 *
 * <pre>{@code
 * FreewayApp.run(new String[0],
 *     new AppModule(), new HttpModule(), new CloudEventModule());
 * }</pre>
 *
 * <p>Config ({@code freeway.cloud.events.*}): {@code enabled} (default
 * false — inert module), {@code peers} (host:port list; optional when a
 * discovery backend feeds {@code setPeers}), {@code subscriptions} (CE type
 * prefixes this node pulls from the mesh; empty = outbound-only),
 * {@code allowed-types} (CLASS-channel deserialization whitelist; empty =
 * allow all).</p>
 */
@Marker(Builtin.class)
public final class CloudEventModule implements ModuleEx {

    private static final Logger LOG = LoggerFactory.getLogger(CloudEventModule.class);

    private static final ConfigSpec<Integer> DEDUP_CAPACITY = ConfigSpec.of(
        CloudConfigKeys.EVENTS_DEDUP_CAPACITY, Integer.class,
        CloudConfigKeys.EVENTS_DEDUP_CAPACITY_DEFAULT, Integer::parseInt);
    private static final ConfigSpec<Boolean> EVENTS_ENABLED = ConfigSpec.of(
        CloudConfigKeys.EVENTS_ENABLED, Boolean.class, false, Boolean::parseBoolean);
    private static final ConfigSpec<Boolean> DEDUP_ENABLED = ConfigSpec.of(
        CloudConfigKeys.EVENTS_DEDUP_ENABLED, Boolean.class, false, Boolean::parseBoolean);

    private volatile PeerConnector connector;

    @Override
    public void bind(Binder binder) {
        var hub = new PeerHub();
        var sink = new CloudEventSink(hub);

        binder.bind(PeerHub.class).to(hub);

        binder.contribute(WebSocketRoute.class)
            .add("cloud-events", WebSocketRoute.of(
                CloudConfigKeys.EVENTS_PATH_DEFAULT, hub));

        binder.contribute(RuntimeHook.class)
            .add(CloudHooks.EVENTS, new RuntimeHook() {
                @Override
                public void start(Container container) {
                    var symbols = container.get(SymbolSource.class);
                    EventBus bus = container.get(EventBus.class);

                    // Dedup is a property of the bus, not of the mesh: it also
                    // suppresses a single transport's own redeliveries (Kafka
                    // hands a record back after a consumer rebalance), so it is
                    // armed even when the WS mesh is off.
                    if (DEDUP_ENABLED.parse(symbols.resolve(DEDUP_ENABLED.key(), null))) {
                        bus.enableInboundDeduplication(
                            DEDUP_CAPACITY.parse(symbols.resolve(DEDUP_CAPACITY.key(), null)));
                    }

                    if (!EVENTS_ENABLED.parse(symbols.resolve(EVENTS_ENABLED.key(), null))) {
                        LOG.info("CloudEventBus disabled ({}=false) — inert",
                            CloudConfigKeys.EVENTS_ENABLED);
                        return;
                    }

                    hub.wire(new PeerHub.Wiring(
                        bus,
                        container.get(JsonCodec.class),
                        symbols.resolve(CloudConfigKeys.REGISTRY_SERVICE_ID, "freeway-app"),
                        symbols.resolve(CloudConfigKeys.REGISTRY_SERVICE_INSTANCE_ID, ""),
                        split(symbols.resolve(CloudConfigKeys.EVENTS_SUBSCRIPTIONS, "")),
                        split(symbols.resolve(CloudConfigKeys.EVENTS_ALLOWED_TYPES, "")),
                        split(symbols.resolve(CloudConfigKeys.EVENTS_ALLOWED_TOPICS, "")),
                        symbols.resolve(CloudConfigKeys.EVENTS_TOKEN, "")));

                    // Contributions are resolved lazily at lookup — safe even
                    // when the contribution view was built at bind time.
                    container.extension(CloudEventInterceptor.class).all()
                        .forEach(hub::addInterceptor);

                    // The connector owns an HttpClient; create it only when the
                    // mesh is actually enabled so a disabled module stays cheap.
                    String registryScheme = symbols.resolve(
                        CloudConfigKeys.REGISTRY_SERVICE_SCHEME, "http");
                    String wsScheme = "https".equalsIgnoreCase(registryScheme) ? "wss" : "ws";
                    connector = new PeerConnector(hub, List.of(), Duration.ofSeconds(3), wsScheme);
                    bus.addEventSink(sink);
                    connector.start(split(symbols.resolve(CloudConfigKeys.EVENTS_PEERS, "")));
                }

                @Override
                public void stop(Container container) {
                    // Release the channel and the dialer: without this the
                    // sink stays installed on the bus and the connector's
                    // HttpClient and retry threads outlive the app.
                    try {
                        container.get(EventBus.class).removeEventSink(sink);
                    } finally {
                        if (connector != null) {
                            connector.close();
                            connector = null;
                        }
                    }
                }
            })
            .before(CloudHooks.HTTP_SERVER);
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
