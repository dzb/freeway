package com.jujin.freeway.cloud.discovery;

import java.time.Duration;
import java.time.Instant;

/**
 * Probe state of an instance, maintained by the discovery layer / registry —
 * deliberately separate from {@link ServiceInstance} (location + attributes).
 * Load balancing only selects {@code live && ready} instances; instances whose
 * {@code lastSeen} exceeds the staleness threshold are evicted.
 *
 * @param live     process liveness
 * @param ready    readiness (can accept traffic)
 * @param lastSeen last heartbeat / registration timestamp
 */
public record Health(boolean live, boolean ready, Instant lastSeen) {

    public static Health up() {
        return new Health(true, true, Instant.now());
    }

    public static Health starting() {
        return new Health(false, false, Instant.now());
    }

    /** True when no heartbeat arrived within {@code maxAge}. */
    public boolean isStale(Duration maxAge) {
        return lastSeen.plus(maxAge).isBefore(Instant.now());
    }
}
