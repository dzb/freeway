package com.jujin.freeway.cloud.events;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.websocket.WebSocketRoute;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Assembles the CloudEventBus: a WS endpoint at {@code /cloud/events}, the
 * peer connector, and the outbound bridge — wired so that a loaded module
 * turns {@code EventBus.publish} into a cross-node CloudEvents 1.0 broadcast.
 *
 * <p>Requires {@link HttpModule} (the WS endpoint rides the HTTP server).
 * The hook is ordered before {@code freeway.http.server} so the hub is wired
 * before the first connection can arrive.</p>
 *
 * <pre>{@code
 * FreewayApp.run(new String[0],
 *     new AppModule(), new HttpModule(), new CloudEventsModule());
 * }</pre>
 *
 * <p>Config ({@code freeway.cloud.events.*}): {@code enabled} (default
 * false — inert module), {@code peers} (host:port list; optional when a
 * discovery backend feeds {@code setPeers}), {@code subscriptions} (CE type
 * prefixes this node pulls from the mesh; empty = outbound-only),
 * {@code allowed-types} (CLASS-channel deserialization whitelist; empty =
 * allow all), {@code keepalive} (reserved).</p>
 */
public final class CloudEventsModule implements ModuleEx {

    @Override
    public void bind(Binder binder) {
        var hub = new PeerHub();
        var connector = new PeerConnector(hub, List.of(), java.time.Duration.ofSeconds(3));
        var bridge = new CloudEventBridge(hub);

        binder.bind(PeerHub.class).to(hub);

        binder.contribute(WebSocketRoute.class)
            .add("cloud-events", WebSocketRoute.of(
                com.jujin.freeway.cloud.CloudConfigKeys.EVENTS_PATH_DEFAULT, hub));

        binder.contribute(RuntimeHook.class)
            .add("cloud-events", new RuntimeHook() {
                @Override
                public void start(com.jujin.freeway.ioc.Container container) {
                    var symbols = container.get(SymbolSource.class);
                    boolean enabled = Boolean.parseBoolean(
                        symbols.resolve(CloudEventsKeys.ENABLED, "false"));
                    hub.wire(
                        container.get(EventBus.class),
                        container.get(com.jujin.freeway.commons.json.JsonCodec.class),
                        symbols.resolve(com.jujin.freeway.cloud.CloudConfigKeys.REGISTRY_SERVICE_ID, "freeway-app"),
                        symbols.resolve(com.jujin.freeway.cloud.CloudConfigKeys.REGISTRY_SERVICE_INSTANCE_ID, ""),
                        split(symbols.resolve(CloudEventsKeys.SUBSCRIPTIONS, "")),
                        split(symbols.resolve(CloudEventsKeys.ALLOWED_TYPES, "")));

                    // contributions resolved lazily at lookup — safe even when
                    // the contribution view was built at bind time.
                    container.extension(CloudEventInterceptor.class).all()
                        .forEach(hub::addInterceptor);

                    if (!enabled) {
                        org.slf4j.LoggerFactory.getLogger(CloudEventsModule.class)
                            .info("CloudEventBus disabled ({}=false) — inert",
                                CloudEventsKeys.ENABLED);
                        return;
                    }
                    container.get(EventBus.class).setEventBridge(bridge);
                    connector.start(split(symbols.resolve(CloudEventsKeys.PEERS, "")));
                    // keepalive (EVENTS_KEEPALIVE) reserved — WS protocol-level
                    // ping/pong handled by the engine; v1 has no active ping loop
                }
            })
            .before(HttpModule.SERVER_HOOK);
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    /** Literal keys kept in one place — the module resolves them lazily. */
    static final class CloudEventsKeys {
        static final String ENABLED = com.jujin.freeway.cloud.CloudConfigKeys.EVENTS_ENABLED;
        static final String PEERS = com.jujin.freeway.cloud.CloudConfigKeys.EVENTS_PEERS;
        static final String SUBSCRIPTIONS = com.jujin.freeway.cloud.CloudConfigKeys.EVENTS_SUBSCRIPTIONS;
        static final String ALLOWED_TYPES = com.jujin.freeway.cloud.CloudConfigKeys.EVENTS_ALLOWED_TYPES;
        static final String KEEPALIVE = com.jujin.freeway.cloud.CloudConfigKeys.EVENTS_KEEPALIVE;

        private CloudEventsKeys() {}
    }
}
