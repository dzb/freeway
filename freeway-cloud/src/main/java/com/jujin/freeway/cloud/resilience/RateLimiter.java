package com.jujin.freeway.cloud.resilience;

/**
 * Token-bucket rate limiter. {@code tryAcquire()} returns immediately —
 * callers decide how to surface rejection.
 */
public interface RateLimiter {

    /** True when a permit was available and consumed. */
    boolean tryAcquire();
}
