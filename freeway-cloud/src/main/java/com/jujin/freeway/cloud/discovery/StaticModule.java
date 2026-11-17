package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.cloud.CloudHookIds;
import com.jujin.freeway.cloud.CloudKeys;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Binds static discovery without a registry process: fixed service-id →
 * instance lists plus the default client-side load balancer.
 *
 * <p>It intentionally binds no {@code ServiceRegistry}. Static membership has
 * no registration or heartbeat; an application that requires those should
 * install {@code MemberModule} with {@code FleetModule} (dynamic), not a
 * no-op that would make registration look successful.</p>
 */
public final class StaticModule implements ModuleEx {

    private final StaticDiscovery discovery;

    /**
     * Config-driven static discovery. Reads
     * {@code freeway.cloud.discovery.static.services} from the container's
     * {@link SymbolSource}; the key must contain at least one
     * {@code serviceId=scheme://host:port[/basePath]} entry.
     *
     * <p>Use {@link #of(Map)} or {@link #of(String, ServiceInstance...)} when
     * the topology is already a typed value in the composition root.</p>
     */
    public StaticModule() {
        this.discovery = null;
    }

    private StaticModule(StaticDiscovery discovery) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
    }

    /** Builds a static module from the service map; each list may hold a full
     *  replica set (client-side balancing) or one VIP endpoint (external LB). */
    public static StaticModule of(Map<String, List<ServiceInstance>> services) {
        return new StaticModule(StaticDiscovery.of(services));
    }

    /** Single-service convenience; one instance is the VIP form. */
    public static StaticModule of(String serviceId, ServiceInstance... instances) {
        return new StaticModule(StaticDiscovery.of(serviceId, instances));
    }

    @Override
    public void bind(Binder b) {
        // The default balancer is a fallback, not a primary: a caller-supplied
        // weighted/canary policy must win without an ambiguity error.
        b.contribute(KnownKeys.class)
            .add(KnownKeys.of(ConfigKeys.class, CloudKeys.PREFIX));
        b.contribute(KnownKeys.class)
            .add(KnownKeys.of(LoadBalancer.ConfigKeys.class, CloudKeys.PREFIX));
        if (discovery == null) {
            b.bind(ServiceDiscovery.class)
                .to(container -> StaticDiscovery.of(container.get(SymbolSource.class)))
                .primary();
            b.contribute(RuntimeHook.class)
                .add(CloudHookIds.STATIC_DISCOVERY, new StaticConfigHook())
                .beforeIfPresent(CloudHookIds.HTTP_SERVER);
        } else {
            b.bind(ServiceDiscovery.class).to(container -> discovery).primary();
        }
        b.bind(LoadBalancer.class)
            .to((Container container) -> new LoadBalancerDefault(
                container.get(SymbolSource.class).resolve(
                    SymbolSpec.of(
                        LoadBalancer.ConfigKeys.LOCAL_ZONE, String.class, ""))))
            .marker(Local.class);
    }

    /** Keys read by config-driven static discovery. */
    public static final class ConfigKeys {
        private ConfigKeys() {}

        /** Comma-separated {@code serviceId=scheme://host:port[/basePath]} entries. */
        public static final String SERVICES = "freeway.cloud.discovery.static.services";
    }

    /** Forces config parsing before the HTTP server starts, so a bad static map
     *  is an app-start failure rather than a first-call failure. */
    private static final class StaticConfigHook implements RuntimeHook {
        @Override
        public void start(Container container) {
            // ServiceDiscovery is an interface binding, so get() may return a
            // scope proxy; invoking a method forces the configured parser to
            // realize and validate the static map.
            container.get(ServiceDiscovery.class).instances("");
        }
    }
}
