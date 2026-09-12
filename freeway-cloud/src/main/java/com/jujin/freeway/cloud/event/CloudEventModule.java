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
 * Assembles the CloudEventBus: a WS endpoint at {@code /cloud/event}, the
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
 * <p>Config ({@code freeway.cloud.event.*}): {@code enabled} (presence-driven —
 * unset with no peers leaves the mesh unwired, {@code peers} below wire it;
 * {@code false} is a kill switch that suppresses even a configured peer list),
 * {@code peers} (host:port list; optional
 * when a discovery backend feeds {@code setPeers}), {@code subscriptions}
 * (CE type prefixes this node pulls from the mesh; empty = outbound-only),
 * {@code allowed-types} (CLASS-channel deserialization allowlist; empty =
 * deny-by-default — CLASS-channel event are dropped). Installing this module
 * still arms {@code freeway.cloud.event.dedup.*} when configured: dedup is a
 * property of the EventBus, not of the WS mesh (see
 * {@code CloudEventLifecycleHook}).</p>
 */
@Marker(Builtin.class)
public final class CloudEventModule implements ModuleEx {

    @Override
    public void bind(Binder binder) {
        var hub = new PeerHub();
        var sink = new CloudEventSink(hub);

        binder.bind(PeerHub.class).to(container -> hub);

        binder.contribute(WebSocketRoute.class)
            .add("cloud-event", WebSocketRoute.of(
                CloudConfigKeys.EVENT_PATH_DEFAULT, hub));

        binder.contribute(RuntimeHook.class)
            .add(CloudHooks.EVENT, new CloudEventLifecycleHook(hub, sink))
            // After the server: the mesh origin is the node identity, which
            // needs the port this node actually serves on. A peer that dials
            // during the short window before wiring is closed with 1013 and
            // reconnects on its backoff — the mesh treats that as normal.
            .after(CloudHooks.HTTP_SERVER);
    }
}
