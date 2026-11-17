package com.jujin.freeway.cloud.member;

import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.http.HealthResult;

/**
 * Readiness for service membership: reports what {@link MemberState}
 * observed rather than assuming registration worked.
 *
 * <p>The verdict is per-instance state, decided in {@link #check()} rather
 * than at construction: the contributor is built before the lifecycle hook
 * runs, so it cannot know yet whether this boot registers anything. Unhealthy
 * only after {@link MemberState#UNHEALTHY_AFTER} consecutive failed
 * heartbeats, so a single blip does not pull the pod out of rotation. An
 * instance that never registered reports healthy with
 * {@code not registered}, not a vacuous all-clear.</p>
 */
final class MemberReadiness implements CloudHealthContributor {

    private final MemberState state;

    public MemberReadiness(MemberState state) {
        this.state = state;
    }

    @Override
    public String name() {
        return "member";
    }

    @Override
    public HealthResult check() {
        if (state.isDraining()) {
            // Shutdown has begun: answer 503 so a probe-driven load balancer
            // stops routing here while the drain window runs.
            return HealthResult.unhealthy("draining");
        }
        if (!state.isTracking()) {
            return new HealthResult(true, "not registered");
        }
        if (!state.isHealthy()) {
            return HealthResult.unhealthy("heartbeat failed " + state.consecutiveFailures()
                + " times in a row");
        }
        return new HealthResult(true, "registered");
    }
}
