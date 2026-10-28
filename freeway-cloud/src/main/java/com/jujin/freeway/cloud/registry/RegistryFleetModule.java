package com.jujin.freeway.cloud.registry;

import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.cloud.discovery.CachingServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.MissingBindingException;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * {@code freeway.cloud.registry.nodes}; a token shared with the nodes
 * goes in {@code freeway.cloud.registry.token}. It is one of the
 * alternatives for the same seam — the in-process store is the default, and
 * third-party backends (Consul and friends) ship as ext adapters; switching is
 * swapping the module and its keys, never application code.</p>
 */
public final class RegistryFleetModule implements ModuleEx {

    private static final Logger LOG = LoggerFactory.getLogger(RegistryFleetModule.class);

    private static final SymbolSpec<Duration> CACHE_TTL = SymbolSpec.of(
        CloudModule.ConfigKeys.REGISTRY_CACHE_TTL, Duration.class,
        CloudModule.ConfigKeys.REGISTRY_CACHE_TTL_DEFAULT);

    @Override
    public void bind(Binder b) {
        b.bind(RegistryNodes.class).to(RegistryFleetModule::nodes);
        b.bind(RegistryNodeClient.class).to(
            container -> new RegistryNodeClient(container.get(RegistryNodes.class)));
        b.bind(ServiceRegistry.class)
            .to(container -> new RegistryNodeServiceRegistry(container.get(RegistryNodeClient.class)))
            .primary();
        b.bind(ServiceDiscovery.class)
            .to(container -> CachingServiceDiscovery.around(
                new RegistryNodeServiceDiscovery(container.get(RegistryNodeClient.class)),
                cacheTtl(container),
                optional(container, com.jujin.freeway.commons.metrics.Metrics.class)))
            .primary();

        // Declared vocabulary: a key outside this backend's namespace fails the
        // bind instead of sitting outside the unknown-key check. The per-service
        // TTL family is dynamic by design, so it is admitted as a prefix — the
        // same shape boot uses for its per-file log keys.
        b.contribute(KnownKeys.class)
            // The family's static keys live in the cloud table; declaring that
            // table here keeps them in the vocabulary when this module is
            // installed without the bundle. The per-service TTL family is
            // dynamic by design, so it is admitted as a prefix.
            .add(KnownKeys.of(CloudModule.ConfigKeys.class,
                CloudModule.ConfigKeys.PREFIX, CloudModule.ConfigKeys.APP_PREFIX))
            .add(KnownKeys.admit(CloudModule.ConfigKeys.REGISTRY_CACHE_TTL_PREFIX));
    }

    /**
     * Freshness policy: the global {@code cache-ttl} with optional per-service
     * overrides. Resolution is lazy and memoized — a service id is looked up
     * the first time it is discovered, not on every refresh, and an absent
     * override inherits the global value (configuring one is never required).
     */
    private static CachingServiceDiscovery.TtlPolicy cacheTtl(Container container) {
        SymbolSource symbols = container.get(SymbolSource.class);
        Duration fallback = symbols.resolve(CACHE_TTL);
        ConcurrentHashMap<String, Duration> resolved = new ConcurrentHashMap<>();
        return serviceId -> {
            Duration own = resolved.computeIfAbsent(serviceId,
                id -> resolveOverride(symbols, id, fallback));
            return own;
        };
    }

    /** One service's override; a malformed value warns once and inherits the
     *  global default — a typo must not take remote calls down. */
    private static Duration resolveOverride(SymbolSource symbols, String serviceId,
                                           Duration fallback) {
        String key = CloudModule.ConfigKeys.REGISTRY_CACHE_TTL_PREFIX + serviceId;
        try {
            Duration own = symbols.resolve(SymbolSpec.of(key, Duration.class, null));
            return own == null ? fallback : own;
        } catch (RuntimeException invalid) {
            LOG.warn("Ignoring {} — using the global cache TTL: {}",
                key, invalid.getMessage());
            return fallback;
        }
    }

    private static <T> T optional(Container container, Class<T> type) {
        try {
            return container.get(type);
        } catch (MissingBindingException absent) {
            return null;
        }
    }

    private static RegistryNodes nodes(Container container) {
        return RegistryNodes.from(container.get(SymbolSource.class));
    }
}
