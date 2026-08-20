package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.resilience.Retryer;

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

    public static RetryerDefault withDefaults() {
        return new RetryerDefault(3, 100, 5000);
    }

    @Override
    public boolean shouldRetry(int attempt, Throwable failure) {
        return attempt >= 0 && attempt < maxRetries;
    }

    @Override
    public long backoffMillis(int attempt) {
        if (attempt <= 0) {
            return baseMillis;
        }
        if (attempt >= 62) {
            return maxMillis; // shift would overflow — already at the cap
        }
        long shift = baseMillis << attempt;
        return shift < 0 ? maxMillis : Math.min(shift, maxMillis);
    }
}
