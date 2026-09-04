package com.jujin.freeway.cloud.resilience;

/**
 * Token-bucket {@link RateLimiter}: refills at {@code permitsPerSecond}, burst
 * capped at {@code maxBurst} (default 1 — strict rate, no bursts).
 */
public final class RateLimiterDefault implements RateLimiter {

    private final double permitsPerSecond;
    private final double maxBurst;

    private double tokens;
    private long lastNanos = System.nanoTime();

    public RateLimiterDefault(double permitsPerSecond) {
        this(permitsPerSecond, 1.0);
    }

    public RateLimiterDefault(double permitsPerSecond, double maxBurst) {
        if (Double.isNaN(permitsPerSecond) || permitsPerSecond <= 0) {
            throw new IllegalArgumentException(
                "permitsPerSecond must be positive: " + permitsPerSecond);
        }
        if (maxBurst < 1.0 || Double.isNaN(maxBurst)) {
            throw new IllegalArgumentException("maxBurst must be >= 1: " + maxBurst);
        }
        this.permitsPerSecond = permitsPerSecond;
        this.maxBurst = maxBurst;
        this.tokens = maxBurst;
    }

    @Override
    public synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        // nanoTime is monotonic per spec, but virtualized clocks have been
        // observed stepping back — a negative elapsed must not deduct tokens
        // and push the next admission into "debt repayment".
        double elapsedSeconds = Math.max(0, now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        tokens = Math.min(maxBurst, tokens + elapsedSeconds * permitsPerSecond);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    @Override
    public RateLimiter newShard() {
        return new RateLimiterDefault(permitsPerSecond, maxBurst);
    }
}
