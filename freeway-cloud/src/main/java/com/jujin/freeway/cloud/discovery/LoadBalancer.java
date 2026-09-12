package com.jujin.freeway.cloud.discovery;

import java.util.List;
import java.util.Optional;

/**
 * Outbound instance selection strategy: picks one candidate from the discovery
 * set before an RPC call. Instances provide data, the LoadBalancer provides
 * policy — and the policy is the substitution point.
 *
 * <p>{@link LoadBalancerDefault} is plain round-robin: it reads no instance
 * attribute at all. Weighted, zone-aware and canary strategies are the
 * application's (or an adapter's) job, bound as the primary implementation:
 * {@code binder.bind(LoadBalancer.class).to(CanaryLb.class).primary()}. Their
 * input vocabulary is the typed accessors on {@link ServiceInstance} —
 * {@code weight()}, {@code zone()}, {@code version()}, {@code isCanary()} —
 * over the {@code metadata} bag the discovery side fills (an adapter mapping a
 * backend's instance attributes, e.g. Nacos weight or a topology zone). Use
 * those accessors rather than reading {@code metadata} keys by hand: they are
 * where the key names and their parsing are defined.
 */
@FunctionalInterface
public interface LoadBalancer {

    /** Chooses one instance, or empty when the set is empty. */
    Optional<ServiceInstance> choose(List<ServiceInstance> instances);
}
