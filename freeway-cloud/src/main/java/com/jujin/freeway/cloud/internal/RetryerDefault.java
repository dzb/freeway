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
        this.maxRetries = maxRetries;
        this.baseMillis = baseMillis;
        this.maxMillis = maxMillis;
    }

    public static RetryerDefault withDefaults() {
        return new RetryerDefault(3, 100, 5000);
    }

    @Override
    public boolean shouldRetry(int attempt, Throwable failure) {
        return attempt < maxRetries;
    }

    @Override
    public long backoffMillis(int attempt) {
        long shift = attempt >= 62 ? Long.MAX_VALUE : (baseMillis << attempt);
        return Math.min(shift, maxMillis);
    }
}
