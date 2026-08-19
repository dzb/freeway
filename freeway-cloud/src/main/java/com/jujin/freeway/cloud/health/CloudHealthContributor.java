package com.jujin.freeway.cloud.health;

/**
 * Extension point: each adapter/dependency contributes its own readiness
 * check; {@code /health/ready} aggregates all contributions.
 */
public interface CloudHealthContributor {

    /** Stable contributor name, e.g. {@code "config-store"}, {@code "s3"}. */
    String name();

    HealthResult check();
}
