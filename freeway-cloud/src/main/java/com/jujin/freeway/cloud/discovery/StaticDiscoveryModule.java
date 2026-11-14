package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Binds static discovery without a registry process: fixed service-id →
 * instance lists plus the default client-side load balancer.
 *
 * <p>It intentionally binds no {@code ServiceRegistry}. Static membership has
 * no registration or heartbeat; an application that requires those should
 * install {@code LocalDiscoveryModule} (in-process) or {@code FleetModule}
 * (dynamic), not a no-op that would make registration look successful.</p>
 */
public final class StaticDiscoveryModule implements ModuleEx {

    private final StaticServiceDiscovery discovery;

    private StaticDiscoveryModule(StaticServiceDiscovery discovery) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
    }

    /** Builds a static module from the service map; each list may hold a full
     *  replica set (client-side balancing) or one VIP endpoint (external LB). */
    public static StaticDiscoveryModule of(Map<String, List<ServiceInstance>> services) {
        return new StaticDiscoveryModule(StaticServiceDiscovery.of(services));
    }

    /** Single-service convenience; one instance is the VIP form. */
    public static StaticDiscoveryModule of(String serviceId, ServiceInstance... instances) {
        return new StaticDiscoveryModule(StaticServiceDiscovery.of(serviceId, instances));
    }

    @Override
    public void bind(Binder b) {
        b.bind(ServiceDiscovery.class).to(container -> discovery).primary();
        b.bind(LoadBalancer.class).to(LoadBalancerDefault.class).primary();
    }
}
