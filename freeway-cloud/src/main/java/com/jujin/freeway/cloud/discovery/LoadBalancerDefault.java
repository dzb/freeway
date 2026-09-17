package com.jujin.freeway.cloud.discovery;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Weighted round-robin {@link LoadBalancer}: the default strategy. Thread-safe
 * across virtual threads via a monotonically increasing counter.
 *
 * <p>An instance's {@link ServiceInstance#weight()} is its share of the
 * cycle: weight 3 among a total of 4 gets three of every four picks. Weights
 * below 1 are read as 1 (a zero-weight instance is taken out of rotation by
 * deregistration, not by weighting). Anything beyond proportional rotation —
 * zone preference, canary pinning, outlier ejection — is a policy a custom
 * {@link LoadBalancer} binding brings, which is why the interface is a
 * seam.</p>
 */
public final class LoadBalancerDefault implements LoadBalancer {

    private final AtomicLong counter = new AtomicLong();

    @Override
    public Optional<ServiceInstance> choose(List<ServiceInstance> instances) {
        if (instances.isEmpty()) {
            return Optional.empty();
        }
        long totalWeight = 0;
        for (ServiceInstance instance : instances) {
            totalWeight += effectiveWeight(instance);
        }
        long slot = Math.floorMod(counter.getAndIncrement(), totalWeight);
        for (ServiceInstance instance : instances) {
            slot -= effectiveWeight(instance);
            if (slot < 0) {
                return Optional.of(instance);
            }
        }
        throw new IllegalStateException(
            "Weighted slot fell past the end of the instance list — "
                + "the list was mutated while being balanced (weights are "
                + "read per pick; choose() expects a stable snapshot)");
    }

    private static int effectiveWeight(ServiceInstance instance) {
        return Math.max(1, instance.weight());
    }
}
