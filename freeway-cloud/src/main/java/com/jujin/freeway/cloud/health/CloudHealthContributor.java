package com.jujin.freeway.cloud.health;

/**
 * Extension point: each adapter/dependency contributes its own readiness
 * check; {@code /health/ready} aggregates all contributions.
 *
 * <p>Every contribution is consulted — there is no conditional registration and
 * no way for a contributor to step aside. A dependency that is not in use
 * answers with its own state (healthy, or "not configured") instead of removing
 * itself: a check that disappears is a dependency the probe silently stops
 * reporting, which is the failure the registry check exists to prevent.</p>
 */
public interface CloudHealthContributor {

    /** Stable contributor name, e.g. {@code "config-store"}, {@code "s3"}. */
    String name();

    HealthResult check();
}
