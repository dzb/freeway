package com.jujin.freeway.cloud.event;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.CloudHooks;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.http.websocket.WebSocketRoute;

/**
 * Assembles the {@link CloudEventBus} — the cloud-native broadcast plane:
 * a WS endpoint at {@code /cloud/event}, the peer connector, and the
 * topic-routed publish/deliver surface of the facade.
 *
 * <p>This plane is entered by name: an application injects
 * {@link CloudEventBus} and calls {@code publish} to cross a process
 * boundary, or contributes a {@link CloudEventSubscription} to receive
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
 * FreewayApp.run(new String[0],
 *     new AppModule(), new HttpModule(), new CloudEventModule());
 * }</pre>
 *
 * <p>Config ({@code freeway.cloud.event.*}): {@code enabled} (presence-driven —
 * unset with no peers leaves the mesh unwired, {@code peers} below wire it;
 * {@code false} is a kill switch that suppresses even a configured peer list),
 * {@code peers} (host:port list; optional when a discovery backend feeds
 * {@code setPeers}), {@code token} (mesh handshake token). Inbound interest
 * is no longer configuration: it is the {@link CloudEventSubscription}
 * contributions, which simultaneously drive the hello pull-prefixes, the
 * inbound gate and delivery.</p>
 */
@Marker(Builtin.class)
public final class CloudEventModule implements ModuleEx {

    @Override
    public void bind(Binder binder) {
        var hub = new PeerHub();

        binder.bind(PeerHub.class).to(container -> hub);

        // The facade is bound, not contributed: it owns the subscription
        // table resolved once from sealed contributions, and both the hook
        // (wiring) and applications (publish/inject) share the one instance.
        binder.bind(CloudEventBus.class).to(container -> new CloudEventBus(
            hub,
            container.get(com.jujin.freeway.commons.json.JsonCodec.class),
            container.extension(CloudEventSubscription.class).all()));

        binder.contribute(WebSocketRoute.class)
            .add("freeway.cloud.event", WebSocketRoute.of(
                CloudConfigKeys.EVENT_PATH_DEFAULT, hub));

        binder.contribute(RuntimeHook.class)
            .add(CloudHooks.EVENT, new CloudEventLifecycleHook(hub))
            // After the server: the mesh origin is the node identity, which
            // needs the port this node actually serves on. A peer that dials
            // during the short window before wiring is closed with 1013 and
            // reconnects on its backoff — the mesh treats that as normal.
            .after(CloudHooks.HTTP_SERVER);
    }
}
