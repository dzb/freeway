package com.jujin.freeway.cloud.resilience;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter — the framework's one such curve.
 *
 * <p>Two callers needed it and each had written its own: the RPC retry policy
 * and the mesh reconnect loop. They had drifted in a way that matters, because
 * the mesh is the path that re-dials an entire fleet after a rolling restart,
 * and its version had no jitter at all. Nodes that failed together therefore
 * retried together, in lockstep — the synchronized-wave failure this curve
 * exists to prevent, on the one caller that can actually produce it.
 *
 * <p>Only the curve is shared; the two callers keep their own decisions about
 * the first attempt. {@code Retryer} waits {@code baseMillis} before retrying
 * at all, while the dial loop dials a fresh peer immediately and only backs
 * off after a failure. That difference is real, so it stays at the call site:
 * {@link #millis} is the curve, and what "attempt 0" means belongs to whoever
 * is asking. The configuration is separate too, for the same reason — the
 * right pacing for an outbound call and for a peer dial are different
 * decisions ({@code freeway.cloud.rpc.retry.backoff-*} versus
 * {@code freeway.cloud.event.backoff-*}).
 *
 * <p>A pure function of its parameters, called directly rather than bound: the
 * alternative was {@link Retryer}, which carries a {@code shouldRetry} policy
 * the dial loop has no use for.
 */
public final class Backoff {

    private Backoff() {
    }

    /**
     * Milliseconds to wait before retry number {@code attempt} (0-based), drawn
     * from the jittered exponential curve.
     *
     * <p>The jitter spans {@code [base/2, base]}: half the exponential value
     * is a floor, so the spacing still grows while the callers that failed
     * together no longer retry together. The range is deterministic in its
     * bounds, which is what makes it testable.
     */
    public static long millis(int attempt, long baseMillis, long maxMillis) {
        long base = ceilingMillis(attempt, baseMillis, maxMillis);
        if (base <= 0) {
            return 0;
        }
        long floor = base / 2;
        return floor + ThreadLocalRandom.current().nextLong(base - floor + 1);
    }

    /**
     * The jitter-free exponential value for {@code attempt}: {@code base *
     * 2^attempt} capped at {@code maxMillis}, and 0 for a non-positive attempt.
     *
     * <p>For a caller that needs the ceiling itself rather than a sample from
     * it — a bound on total elapsed time, say.
     */
    public static long ceilingMillis(int attempt, long baseMillis, long maxMillis) {
        if (attempt <= 0) {
            return 0;
        }
        // Guard the shift itself: past this the multiplication overflows a
        // long, and a negative result would sail past the Math.min cap.
        if (attempt >= 62) {
            return maxMillis;
        }
        long shifted = baseMillis << attempt;
        return shifted < 0 ? maxMillis : Math.min(shifted, maxMillis);
    }
}
