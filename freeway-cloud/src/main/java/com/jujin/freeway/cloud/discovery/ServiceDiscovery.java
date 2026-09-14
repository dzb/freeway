package com.jujin.freeway.cloud.discovery;

import java.util.List;
import java.util.Optional;

/**
 * Query side of the registry: {@code serviceId} → candidate instances.
 * Instance selection is the {@link LoadBalancer}'s job, not the discovery's.
 */
public interface ServiceDiscovery {

    /** Live and ready instances for the logical service; empty when unknown or all evicted. */
    List<ServiceInstance> instances(String serviceId);

    /** First live/ready instance, if any. */
    default Optional<ServiceInstance> instance(String serviceId) {
        return instances(serviceId).stream().findFirst();
    }
}
