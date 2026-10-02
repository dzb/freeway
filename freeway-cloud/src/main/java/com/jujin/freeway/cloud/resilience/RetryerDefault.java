package com.jujin.freeway.cloud.resilience;

import com.jujin.freeway.cloud.CloudModule.ConfigKeys;

/**
 * Exponential-backoff {@link Retryer}: {@code maxRetries} attempts beyond the
 * first. Every wait after the first comes from
 * {@link Backoff#millis(int, long, long)} — {@code baseMillis * 2^attempt} capped
 * at {@code maxMillis} and then jittered — and the first retry
 * ({@code attempt == 0}) waits {@link Backoff#jitter(long)} of
 * {@code baseMillis}, the ceiling its attempt index cannot express.
 *
 * <p>The jitter is not decoration. {@code attempt == 0} is the retry every
 * client of a just-restarted service makes at the same instant, so a
 * deterministic wait there would rebuild the synchronized wave the curve is
 * shared to prevent — at exactly the one attempt where the callers are known to
 * be in lockstep. Note that {@code attempt == 0} is this class's <em>first
 * retry</em>, which is where it differs from the mesh dial leg: that one dials
 * a never-tried peer immediately.
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
        // once backs off before trying again. That first wait is jittered like
        // every later one — it is exactly when a fleet that failed together
        // comes back, so a deterministic base would rebuild the synchronized
        // wave the jittered curve exists to break.
        if (attempt <= 0) {
            return Backoff.jitter(Math.min(baseMillis, maxMillis));
        }
        return Backoff.millis(attempt, baseMillis, maxMillis);
    }
}
