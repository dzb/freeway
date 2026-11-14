package com.jujin.freeway.cloud.discovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed service discovery: a service id maps to a configured instance list.
 *
 * <p>This is the no-registry mode. A list with several instances lets the
 * client-side {@link LoadBalancer} choose per call; a list with one endpoint is
 * the VIP form, where an external load balancer owns instance selection. There
 * is no registration, heartbeat or eviction: static means the deployment owns
 * the membership.</p>
 *
 * <p>Prefer a VIP/DNS name over pod IPs: static lists do not follow
 * rescheduling. The instances still carry their id, endpoint and metadata, so
 * weights and zones work exactly as they do with dynamic discovery.</p>
 */
public final class StaticServiceDiscovery implements ServiceDiscovery {

    private final Map<String, List<ServiceInstance>> services;

    private StaticServiceDiscovery(Map<String, List<ServiceInstance>> services) {
        this.services = services;
    }

    /** Builds a static discovery from a service-id → instance-list map. */
    public static StaticServiceDiscovery of(Map<String, List<ServiceInstance>> services) {
        Objects.requireNonNull(services, "services");
        Map<String, List<ServiceInstance>> copy = new LinkedHashMap<>();
        services.forEach((serviceId, instances) -> {
            Objects.requireNonNull(serviceId, "serviceId");
            Objects.requireNonNull(instances, "instances");
            copy.put(serviceId, List.copyOf(instances));
        });
        return new StaticServiceDiscovery(Map.copyOf(copy));
    }

    /** Single-service convenience; one instance is the VIP form. */
    public static StaticServiceDiscovery of(String serviceId, ServiceInstance... instances) {
        Objects.requireNonNull(serviceId, "serviceId");
        return of(Map.of(serviceId, List.of(instances)));
    }

    @Override
    public List<ServiceInstance> instances(String serviceId) {
        return services.getOrDefault(serviceId, List.of());
    }
}
