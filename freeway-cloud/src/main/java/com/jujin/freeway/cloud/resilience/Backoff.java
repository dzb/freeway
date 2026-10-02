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
 * is asking — but the wait itself is not theirs to re-derive, which is why
 * {@link #jitter} samples any ceiling: it is how {@code Retryer}'s first wait
 * (a ceiling of {@code base} that the attempt index cannot express) is jittered
 * exactly like every later one. The configuration is separate too, for the same
 * reason — the right pacing for an outbound call and for a peer dial are
 * different decisions ({@code freeway.cloud.rpc.retry.backoff-*} versus
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
        return jitter(ceilingMillis(attempt, baseMillis, maxMillis));
    }

    /**
     * A jittered sample from {@code ceilingMillis}: half the ceiling is the
     * floor, so spacing still grows while callers that failed together no
     * longer retry together.
     *
     * <p>Exposed for a wait whose ceiling the attempt index cannot express:
     * {@code Retryer} waits {@code base} before its <em>first</em> retry
     * (where the dial loop dials immediately), and that first wait needs the
     * same jitter as every later one — a fleet restarted together comes back
     * at once on exactly that retry.
     */
    public static long jitter(long ceilingMillis) {
        if (ceilingMillis <= 0) {
            return 0;
        }
        long floor = ceilingMillis / 2;
        return floor + ThreadLocalRandom.current().nextLong(ceilingMillis - floor + 1);
    }

    /**
     * The jitter-free exponential value for {@code attempt}: {@code base *
     * 2^attempt} capped at {@code maxMillis}, and 0 for a non-positive attempt
     * or base.
     *
     * <p>For a caller that needs the ceiling itself rather than a sample from
     * it — a bound on total elapsed time, say.
     */
    public static long ceilingMillis(int attempt, long baseMillis, long maxMillis) {
        if (attempt <= 0 || baseMillis <= 0) {
            return 0;
        }
        // Would base * 2^attempt fit in a long? Past that point the answer is the
        // cap by definition, and the shift is never reached — which also rules out
        // the wrap the shift alone would let through: a shift past 63 wraps to a
        // POSITIVE value that sails past the Math.min cap (1000 << 61 is 0),
        // handing the dial loop a zero-millisecond wait and turning the backoff
        // into a busy loop.
        if (attempt >= Long.SIZE - 1 || baseMillis > Long.MAX_VALUE >> attempt) {
            return maxMillis;
        }
        return Math.min(baseMillis << attempt, maxMillis);
    }
}
