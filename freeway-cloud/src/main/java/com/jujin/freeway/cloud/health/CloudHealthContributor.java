package com.jujin.freeway.cloud.health;

/**
 * Extension point: each adapter/dependency contributes its own readiness
 * check; {@code /health/ready} aggregates all contributions.
 */
public interface CloudHealthContributor {

    /** Stable contributor name, e.g. {@code "config-store"}, {@code "s3"}. */
    String name();

    /**
     * True when this contributor's dependency is actually in use. Built-in
     * local contributors return false once an extension adapter replaces
     * their binding with a primary implementation, so {@code /health/ready}
     * never aggregates a stale check against the unused local backend.
     */
    default boolean isActive() {
        return true;
    }

    HealthResult check();
}
