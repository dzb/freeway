package com.jujin.freeway.cloud.event;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.CloudHooks;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.EventBridgePolicy;
import com.jujin.freeway.ioc.EventSink;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.http.websocket.WebSocketRoute;

/**
 * Assembles the CloudEventBus: a WS endpoint at {@code /cloud/event}, the
 * peer connector, and the outbound sink — wired so that a loaded module
 * turns {@code EventBus.publish} into a cross-node CloudEvents 1.0 broadcast.
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

        // The mesh sink is a sealed contribution, not a runtime install: the
        // sink is fully constructible here (the hub lands unwired and stays
        // inert until the hook wires it), so a loaded module turns
        // EventBus.publish into cross-node broadcast with no bus calls.
        // Contributed through the binding so the hook and the bus share the
        // one instance.
        binder.bind(CloudEventSink.class).to(container -> sink);
        binder.contribute(EventSink.class)
            .add("freeway.cloud.mesh", container -> container.get(CloudEventSink.class));

        // Dedup is a property of the bus, not of the mesh: it also suppresses
        // a single transport's own redeliveries (Kafka hands a record back
        // after a consumer rebalance), so the policy is contributed even when
        // the WS mesh stays unwired. Read at drain, from this module's keys.
        binder.contribute(EventBridgePolicy.class)
            .add("freeway.cloud.dedup", container -> {
                var symbols = container.get(SymbolSource.class);
                boolean enabled = symbols.resolve(CloudEventLifecycleHook.DEDUP_ENABLED);
                int capacity = enabled
                    ? symbols.resolve(CloudEventLifecycleHook.DEDUP_CAPACITY)
                    : 0;
                return new EventBridgePolicy(capacity);
            });

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
