package com.jujin.freeway.cloud.events;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.CloudHooks;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.http.websocket.WebSocketRoute;

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
 * false — the mesh stays unwired), {@code peers} (host:port list; optional
 * when a discovery backend feeds {@code setPeers}), {@code subscriptions}
 * (CE type prefixes this node pulls from the mesh; empty = outbound-only),
 * {@code allowed-types} (CLASS-channel deserialization allowlist; empty =
 * deny-by-default — CLASS-channel events are dropped). Installing this module
 * still arms {@code freeway.cloud.events.dedup.*} when configured: dedup is a
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
            .add("cloud-events", WebSocketRoute.of(
                CloudConfigKeys.EVENTS_PATH_DEFAULT, hub));

        binder.contribute(RuntimeHook.class)
            .add(CloudHooks.EVENTS, new CloudEventLifecycleHook(hub, sink))
            .before(CloudHooks.HTTP_SERVER);
    }
}
