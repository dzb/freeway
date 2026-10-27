package com.jujin.freeway.cloud.registry;

import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.symbol.SymbolSource;

/**
 * The freeway-native registry backend: binds a fleet of registry nodes as the
 * process's {@code ServiceRegistry}/{@code ServiceDiscovery}. The nodes
 * themselves are freeway applications — {@code HttpModule +
 * CloudDiscoveryModule + RegistryServerModule} (roughly ten lines of assembly;
 * see that module's javadoc) — which makes the backend deployable without any
 * third-party system and keeps client and wire format on the same release
 * train as the server.
 *
 * <p>Writes fan out to every configured node and reads union the answers, so
 * the nodes are independent peers: no replication protocol, and no node is
 * special. One node dying degrades the fleet; the surviving nodes keep serving,
 * and the dead one is repopulated by the next heartbeat of every instance
 * (a renew that answers 404 re-asserts the instance on the spot).</p>
 *
 * <p>Place this module <em>alongside</em> {@code CloudModule} (or at least
 * {@code CloudDiscoveryModule}) and name the fleet with
 * {@code freeway.cloud.registry-node.nodes}; a token shared with the nodes
 * goes in {@code freeway.cloud.registry-node.token}. It is one of the
 * alternatives for the same seam — the in-process store is the default, and
 * third-party backends (Consul and friends) ship as ext adapters; switching is
 * swapping the module and its keys, never application code.</p>
 */
public final class RegistryFleetModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.bind(RegistryNodes.class).to(RegistryFleetModule::nodes);
        b.bind(RegistryNodeClient.class).to(
            container -> new RegistryNodeClient(container.get(RegistryNodes.class)));
        b.bind(ServiceRegistry.class)
            .to(container -> new RegistryNodeServiceRegistry(container.get(RegistryNodeClient.class)))
            .primary();
        b.bind(ServiceDiscovery.class)
            .to(container -> new RegistryNodeServiceDiscovery(container.get(RegistryNodeClient.class)))
            .primary();

        // Declared vocabulary: a key outside this backend's namespace fails the
        // bind instead of sitting outside the unknown-key check.
        b.contribute(KnownKeys.class).add(KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX));
    }

    private static RegistryNodes nodes(Container container) {
        return RegistryNodes.from(container.get(SymbolSource.class));
    }
}
