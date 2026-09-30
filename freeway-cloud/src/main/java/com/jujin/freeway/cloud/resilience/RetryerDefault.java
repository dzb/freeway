package com.jujin.freeway.cloud.resilience;

import com.jujin.freeway.cloud.CloudModule.ConfigKeys;

/**
 * Exponential-backoff {@link Retryer}: {@code maxRetries} attempts beyond the
 * first, {@code baseMillis * 2^attempt} capped at {@code maxMillis}.
 */
public final class RetryerDefault implements Retryer {

    private final int maxRetries;
    private final long baseMillis;
    private final long maxMillis;

    public RetryerDefault(int maxRetries, long baseMillis, long maxMillis) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0: " + maxRetries);
        }
        if (baseMillis <= 0) {
            throw new IllegalArgumentException("baseMillis must be positive: " + baseMillis);
        }
        if (maxMillis < baseMillis) {
            throw new IllegalArgumentException(
                "maxMillis must be >= baseMillis: " + maxMillis + " < " + baseMillis);
        }
        this.maxRetries = maxRetries;
        this.baseMillis = baseMillis;
        this.maxMillis = maxMillis;
    }

    /** The library default retry policy. The values come from the shared
     *  {@code freeway.cloud.rpc.retry.*} defaults ({@link ConfigKeys}),
     *  the same source the config layer falls back to. */
    public static RetryerDefault withDefaults() {
        return new RetryerDefault(
            ConfigKeys.RPC_RETRY_MAX_ATTEMPTS_DEFAULT,
            ConfigKeys.RPC_RETRY_BACKOFF_BASE_DEFAULT,
            ConfigKeys.RPC_RETRY_BACKOFF_MAX_DEFAULT);
    }

    @Override
    public boolean shouldRetry(int attempt, Throwable failure) {
        return attempt >= 0 && attempt < maxRetries;
    }

    @Override
    public long backoffMillis(int attempt) {
        // A retry always waits at least the base: unlike the mesh dial loop,
        // which dials a fresh peer immediately, an RPC that has already failed
        // once backs off before trying again. The jittered curve is shared —
        // see Backoff.
        if (attempt <= 0) {
            return baseMillis;
        }
        return Backoff.millis(attempt, baseMillis, maxMillis);
    }
}
