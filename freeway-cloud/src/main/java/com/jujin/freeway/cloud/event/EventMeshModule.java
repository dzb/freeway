package com.jujin.freeway.cloud.event;

import com.jujin.freeway.cloud.CloudKeys;
import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.cloud.CloudHookIds;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.cloud.registration.RegistrationModule;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.http.websocket.WebSocketRoute;

/**
 * Assembles the {@link MeshEventBus} — the cloud-native broadcast plane:
 * a WS endpoint at {@code /cloud/event}, the peer connector, and the
 * topic-routed publish/deliver surface of the facade.
 *
 * <p>This plane is entered by name: an application injects
 * {@link MeshEventBus} and calls {@code publish} to cross a process
 * boundary, or contributes a {@link MeshSubscription} to receive
 * across one. The in-process {@code EventBus} is untouched by this module's
 * presence — loading it does not make a local fact travel anywhere.</p>
 *
 * <p>Requires {@link com.jujin.freeway.http.HttpModule} (the WS endpoint rides
 * the HTTP server). The hook runs <em>after</em> {@code freeway.http.server}:
 * the mesh origin is the node identity, which needs the port the node actually
 * serves on. A peer that dials during the short window before wiring is closed
 * with 1013 and reconnects on its backoff, which the mesh treats as normal.</p>
 *
 * <pre>{@code
 * FreewayApp.run(
 *     new AppModule(), new HttpModule(), new EventMeshModule());
 * }</pre>
 *
 * <p>Config ({@code freeway.cloud.event.*}): {@code enabled} (presence-driven —
 * unset with no peers leaves the mesh unwired, {@code peers} below wire it;
 * {@code false} is a kill switch that suppresses even a configured peer list),
 * {@code peers} (host:port list; optional when {@code discovery-feed-ms} makes
 * the module poll the bound {@code ServiceDiscovery} for peers instead),
 * {@code token} (mesh handshake token). Inbound interest
 * is no longer configuration: it is the {@link MeshSubscription}
 * contributions, which simultaneously drive the hello pull-prefixes, the
 * inbound gate and delivery.</p>
 */
@Marker(Builtin.class)
public final class EventMeshModule implements ModuleEx {

    /**
     * The mesh endpoint path. A route path is an identity string, not a
     * configuration key: it lives here with the route that serves it and stays
     * out of {@code ConfigKeys} (which is the key vocabulary only).
     */
    public static final String EVENT_PATH = "/cloud/event";

    @Override
    public void bind(Binder binder) {
        // The mesh origin reuses the registration identity: HttpDeclaration
        // reads app.name / registry.service-* from these two tables, so this
        // module must declare them even when RegistrationModule is absent.
        binder.contribute(KnownKeys.class)
            .add(KnownKeys.of(ConfigKeys.class, CloudKeys.PREFIX,
                CloudKeys.APP_PREFIX))
            .add(KnownKeys.of(CloudModule.ConfigKeys.class, CloudKeys.PREFIX,
                CloudKeys.APP_PREFIX))
            .add(KnownKeys.of(RegistrationModule.ConfigKeys.class,
                CloudKeys.PREFIX, CloudKeys.APP_PREFIX));
        var hub = new PeerHub();

        binder.bind(PeerHub.class).to(container -> hub);

        // The facade is bound, not contributed: it owns the subscription
        // table resolved once from sealed contributions, and both the hook
        // (wiring) and applications (publish/inject) share the one instance.
        binder.bind(MeshEventBus.class).to(container -> new MeshEventBus(
            hub,
            container.get(com.jujin.freeway.commons.json.JsonCodec.class),
            container.extension(MeshSubscription.class).all()));

        binder.contribute(WebSocketRoute.class)
            .add("freeway.cloud.event", WebSocketRoute.of(
                EVENT_PATH, hub));

        binder.contribute(RuntimeHook.class)
            .add(CloudHookIds.EVENT, new MeshEventLifecycleHook(hub))
            // After the server: the mesh origin is the node identity, which
            // needs the port this node actually serves on. Required, not
            // conditional — this plane cannot serve without the HTTP module,
            // so a missing server must fail here naming the absent module
            // rather than later as an unbound JsonCodec. A peer that dials
            // during the short window before wiring is closed with 1013 and
            // reconnects on its backoff — the mesh treats that as normal.
            .after(CloudHookIds.HTTP_SERVER);
    }
    /** Keys read by the event mesh. */
    public static final class ConfigKeys {
        private ConfigKeys() {}
        public static final String EVENT_PEERS = "freeway.cloud.event.peers";
        public static final String EVENT_DISCOVERY_FEED_MS = "freeway.cloud.event.discovery-feed-ms";
        public static final String EVENT_KEEPALIVE_MS = "freeway.cloud.event.keepalive-ms";
        public static final String EVENT_TOKEN = "freeway.cloud.event.token";
        public static final String EVENT_ENABLED = "freeway.cloud.event.enabled";
        public static final String EVENT_CONNECT_TIMEOUT_MS = "freeway.cloud.event.connect-timeout-ms";
        public static final long EVENT_CONNECT_TIMEOUT_MS_DEFAULT = 3000;
        public static final String EVENT_HANDSHAKE_TIMEOUT_MS = "freeway.cloud.event.handshake-timeout-ms";
        public static final long EVENT_HANDSHAKE_TIMEOUT_MS_DEFAULT = 10000;
        public static final String EVENT_BACKOFF_BASE_MS = "freeway.cloud.event.backoff-base-ms";
        public static final long EVENT_BACKOFF_BASE_MS_DEFAULT = 1000;
        public static final String EVENT_BACKOFF_MAX_MS = "freeway.cloud.event.backoff-max-ms";
        public static final long EVENT_BACKOFF_MAX_MS_DEFAULT = 30000;
    }
}
