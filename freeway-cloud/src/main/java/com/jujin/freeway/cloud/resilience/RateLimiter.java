package com.jujin.freeway.cloud.resilience;

/**
 * Token-bucket rate limiter. {@code tryAcquire()} returns immediately —
 * callers decide how to surface rejection.
 */
public interface RateLimiter {

    /** True when a permit was available and consumed. */
    boolean tryAcquire();

    /** A per-service shard derived from this limiter: same policy, fresh
     *  tokens. The default returns {@code this} (shared); stateful framework
     *  implementations override it so a hot service cannot starve others. */
    default RateLimiter newShard() {
        return this;
    }
}
