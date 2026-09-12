package com.jujin.freeway.cloud.internal;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Heartbeat state the registry hook publishes and the readiness contributor
 * reads: "this instance is registered, and the registry still knows it".
 *
 * <p>Readiness must not claim more than the framework can verify. A local
 * instance is only useful while it is actually listed, so the hook counts a
 * heartbeat that failed — a renew that threw, or a presence check that came
 * back empty — and readiness reports unhealthy after
 * {@value #UNHEALTHY_AFTER} consecutive failures (three default intervals, so a
 * single blip does not pull a pod out of rotation). Any successful heartbeat
 * resets the count.</p>
 *
 * <p>An instance that never registered (no HTTP module, no service declaration)
 * is not tracked at all: there is nothing to guard, and the contributor says so
 * instead of reporting a vacuous "healthy".</p>
 */
public final class RegistryRenewal {

    /** Consecutive failed heartbeats before readiness reports unhealthy. */
    public static final int UNHEALTHY_AFTER = 3;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile boolean tracking;

    /** Called once this boot has registered at least one instance. */
    void track() {
        tracking = true;
    }

    /** A heartbeat that renewed and verified every registered instance. */
    void renewed() {
        consecutiveFailures.set(0);
    }

    /** A heartbeat where at least one instance failed to renew or verify. */
    void failed() {
        consecutiveFailures.incrementAndGet();
    }

    /** True when this boot registered instances, i.e. readiness has something to say. */
    public boolean isTracking() {
        return tracking;
    }

    /** Failures observed back to back; resets on the first healthy heartbeat. */
    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    /** True when the registration can still be relied on (or was never needed). */
    public boolean isHealthy() {
        return consecutiveFailures.get() < UNHEALTHY_AFTER;
    }
}
