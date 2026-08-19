package com.jujin.freeway.cloud.discovery;

import java.util.List;
import java.util.Optional;

/**
 * Outbound instance selection strategy: picks one candidate from the discovery
 * set before an RPC call. Reads routing inputs (zone/weight/canary) from
 * {@link ServiceInstance#metadata()} — instances provide data, the
 * LoadBalancer provides policy.
 */
@FunctionalInterface
public interface LoadBalancer {

    /** Chooses one instance, or empty when the set is empty. */
    Optional<ServiceInstance> choose(List<ServiceInstance> instances);
}
