package com.jujin.freeway.cloud.registration;

import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.http.HealthResult;

/**
 * Readiness for registration: reports what {@link RegistrationRenewal}
 * observed rather than assuming registration worked.
 *
 * <p>The verdict is per-instance state, decided in {@link #check()} rather
 * than at construction: the contributor is built before the lifecycle hook
 * runs, so it cannot know yet whether this boot registers anything. Unhealthy
 * only after {@link RegistrationRenewal#UNHEALTHY_AFTER} consecutive failed
 * heartbeats, so a single blip does not pull the pod out of rotation. An
 * instance that never registered reports healthy with
 * {@code not registered}, not a vacuous all-clear.</p>
 */
final class RegistrationHealthContributor implements CloudHealthContributor {

    private final RegistrationRenewal renewal;

    public RegistrationHealthContributor(RegistrationRenewal renewal) {
        this.renewal = renewal;
    }

    @Override
    public String name() {
        return "registry";
    }

    @Override
    public HealthResult check() {
        if (renewal.isDraining()) {
            // Shutdown has begun: answer 503 so a probe-driven load balancer
            // stops routing here while the drain window runs.
            return HealthResult.unhealthy("draining");
        }
        if (!renewal.isTracking()) {
            return new HealthResult(true, "not registered");
        }
        if (!renewal.isHealthy()) {
            return HealthResult.unhealthy("heartbeat failed " + renewal.consecutiveFailures()
                + " times in a row");
        }
        return new HealthResult(true, "registered");
    }
}
