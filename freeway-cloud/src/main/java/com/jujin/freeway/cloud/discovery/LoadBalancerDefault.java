package com.jujin.freeway.cloud.discovery;

import java.util.ArrayList;
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
 * deregistration, not by weighting).</p>
 *
 * <p><b>Zone preference.</b> When constructed with a local zone and at least
 * one instance declares one, the selection runs weighted round-robin over the
 * local-zone candidates. If none match, the full list is used — a partial
 * topology must not become an outage. With no local zone, or no declared zone
 * metadata, behaviour is the plain weighted round-robin.</p>
 *
 * <p><b>Canary.</b> {@link ServiceInstance#isCanary()} stays a strategy input,
 * not an automatic traffic switch: canary instances participate with their
 * declared weight. Sending a controlled share (or pinning by header/version)
 * is a custom {@link LoadBalancer} binding — the default never routes all
 * traffic to a canary just because one exists.</p>
 */
public final class LoadBalancerDefault implements LoadBalancer {

    /** Empty means "no local zone": pure weighted round-robin. */
    private final String localZone;
    private final AtomicLong counter = new AtomicLong();

    public LoadBalancerDefault() {
        this("");
    }

    /**
     * @param localZone the zone this process runs in; when non-blank and at
     *                  least one instance declares a zone, instances in that
     *                  zone are preferred. If none are local, the full list is
     *                  used — zone preference must not turn a partial topology
     *                  into an outage.
     */
    public LoadBalancerDefault(String localZone) {
        this.localZone = localZone == null ? "" : localZone.strip();
    }

    @Override
    public Optional<ServiceInstance> choose(List<ServiceInstance> instances) {
        if (instances.isEmpty()) {
            return Optional.empty();
        }
        List<ServiceInstance> candidates = preferLocalZone(instances);
        long totalWeight = 0;
        for (ServiceInstance instance : candidates) {
            totalWeight += effectiveWeight(instance);
        }
        long slot = Math.floorMod(counter.getAndIncrement(), totalWeight);
        for (ServiceInstance instance : candidates) {
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

    /**
     * Local-zone candidates, or the original list when this process has no
     * local zone, no instance declares one, or none match. Never returns an
     * empty list for a non-empty input.
     */
    private List<ServiceInstance> preferLocalZone(List<ServiceInstance> instances) {
        if (localZone.isEmpty()) {
            return instances;
        }
        List<ServiceInstance> local = new ArrayList<>();
        boolean anyDeclaredZone = false;
        for (ServiceInstance instance : instances) {
            String zone = instance.zone();
            if (!zone.isBlank()) {
                anyDeclaredZone = true;
            }
            if (localZone.equals(zone)) {
                local.add(instance);
            }
        }
        if (!anyDeclaredZone || local.isEmpty()) {
            return instances;
        }
        return local;
    }

    private static int effectiveWeight(ServiceInstance instance) {
        return Math.max(1, instance.weight());
    }
}
