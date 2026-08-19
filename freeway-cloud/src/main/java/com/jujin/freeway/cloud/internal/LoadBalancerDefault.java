package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.ServiceInstance;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round-robin {@link LoadBalancer}: the default strategy. Thread-safe across
 * virtual threads via a monotonically increasing counter.
 */
public final class LoadBalancerDefault implements LoadBalancer {

    private final AtomicLong counter = new AtomicLong();

    @Override
    public Optional<ServiceInstance> choose(List<ServiceInstance> instances) {
        if (instances.isEmpty()) {
            return Optional.empty();
        }
        long index = counter.getAndIncrement() & Long.MAX_VALUE;
        return Optional.of(instances.get((int) (index % instances.size())));
    }
}
