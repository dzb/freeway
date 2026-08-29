package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.resilience.Retryer;
import java.util.concurrent.ThreadLocalRandom;

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
        long base = baseBackoffMillis(attempt);
        // Jitter on top of the exponential curve: clients that failed at the
        // same instant must not retry at the same instant either, or a
        // recovering service is met by one synchronized wave. Half the base is
        // kept as a floor so the spacing still grows.
        long floor = base / 2;
        return floor + ThreadLocalRandom.current().nextLong(base - floor + 1);
    }

    private long baseBackoffMillis(int attempt) {
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
