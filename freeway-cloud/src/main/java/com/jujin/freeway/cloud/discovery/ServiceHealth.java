package com.jujin.freeway.cloud.discovery;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

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
public record ServiceHealth(boolean live, boolean ready, Instant lastSeen) {

    public ServiceHealth {
        if (ready && !live) {
            throw new IllegalArgumentException("a ready service must also be live");
        }
        lastSeen = Objects.requireNonNull(lastSeen, "lastSeen");
    }

    public static ServiceHealth up() {
        return new ServiceHealth(true, true, Instant.now());
    }

    public static ServiceHealth starting() {
        return new ServiceHealth(false, false, Instant.now());
    }

    /** True when no heartbeat arrived within {@code maxAge}. */
    public boolean isStale(Duration maxAge) {
        return lastSeen.plus(maxAge).isBefore(Instant.now());
    }
}
