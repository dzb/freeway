package com.jujin.freeway.cloud.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;


/**
 * The backoff curve is jittered, and the jitter is what makes it useful.
 *
 * <p>The mesh dial leg used to compute a bare {@code base * 2^n} with no
 * jitter at all, on the one path that can produce the failure jitter exists to
 * prevent: after a rolling restart every node loses its peers in the same
 * instant, and un-jittered backoff has all of them re-dial in lockstep,
 * forever.
 *
 * <p>Distribution tests are normally a smell, so these check the two things
 * that are actually contractual — the range is bounded and deterministic, and
 * repeated calls at the same attempt are <em>not</em> equal. A missing jitter
 * fails the second immediately; an unbounded or inverted one fails the first.
 */
class BackoffTest {

    private static final long BASE = 100;
    private static final long MAX = 5_000;

    @Test
    void jitterStaysWithinHalfTheCurveAndTheCurve() {
        for (int attempt = 1; attempt <= 8; attempt++) {
            long ceiling = Backoff.ceilingMillis(attempt, BASE, MAX);
            for (int i = 0; i < 200; i++) {
                long delay = Backoff.millis(attempt, BASE, MAX);
                assertTrue(delay >= ceiling / 2,
                    "attempt " + attempt + " produced " + delay
                        + ", below the half-ceiling floor of " + ceiling / 2);
                assertTrue(delay <= ceiling,
                    "attempt " + attempt + " produced " + delay
                        + ", above the ceiling " + ceiling);
            }
        }
    }

    @Test
    void repeatedCallsAtTheSameAttemptDiffer() {
        // Without this, every client that failed together retries together.
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(Backoff.millis(4, BASE, MAX));
        }
        assertTrue(seen.size() > 1,
            "200 draws at the same attempt produced a single value — the curve "
                + "is not jittered, so synchronized callers stay synchronized");
    }

    @Test
    void jitteredDelaysDifferWhileCeilingsGrow() {
        assertTrue(Backoff.ceilingMillis(1, BASE, MAX) < Backoff.ceilingMillis(2, BASE, MAX),
            "the ceiling must still double");
        assertTrue(Backoff.ceilingMillis(2, BASE, MAX) < Backoff.ceilingMillis(3, BASE, MAX));
    }

    @Test
    void theCurveIsCappedAndNeverOverflows() {
        assertEquals(MAX, Backoff.ceilingMillis(30, BASE, MAX), "capped well before the cap");
        assertEquals(MAX, Backoff.ceilingMillis(62, BASE, MAX),
            "the shift guard must hold at the overflow boundary");
        assertEquals(MAX, Backoff.ceilingMillis(1_000, BASE, MAX),
            "a large attempt count must not wrap into a negative delay");
        assertTrue(Backoff.millis(1_000, BASE, MAX) > 0,
            "a sampled delay at a saturated attempt must still be positive");
    }

    /**
     * The guard has to be against the base, not against the width of a long.
     *
     * <p>A shift that wraps to a POSITIVE value sails past a sign check and past
     * the cap: {@code 1000 << 61} is {@code 0} exactly, so {@code jitter(0)}
     * returns {@code 0}, and the dial loop's {@code if (sleep > 0)} then skips
     * the wait altogether. The counter only resets on a successful handshake, so
     * this arrives after sustained disconnection — with the factory defaults
     * (base 1000 / max 30000) at roughly 56 saturated intervals, about half an
     * hour — and it arrives for every peer of every node at once, which is the
     * synchronized wave this curve exists to prevent, arriving one exponent
     * later.
     *
     * <p>So the invariant is not "the ceiling is capped" — a wrapped zero is
     * capped — it is that a saturated attempt still produces a positive wait, for
     * any base.
     */
    @Test
    void aSaturatedAttemptStillWaitsForEveryBase() {
        long[] bases = {1, 2, 100, 1_000, 1_024, 65_536, 1_000_000};
        long[] caps = {1, 1_000, 30_000, 300_000};
        for (long base : bases) {
            for (long cap : caps) {
                for (int attempt = 1; attempt <= 200; attempt++) {
                    long ceiling = Backoff.ceilingMillis(attempt, base, cap);
                    assertTrue(ceiling > 0,
                        "base=" + base + " cap=" + cap + " attempt=" + attempt
                            + " wrapped to a zero wait");
                    assertTrue(ceiling <= cap,
                        "base=" + base + " cap=" + cap + " attempt=" + attempt
                            + " reported " + ceiling + ", above the cap");
                }
            }
        }
    }

    /**
     * The measured instance of that wrap, pinned as a fact rather than a range:
     * 1000 is {@code 125 * 2^3}, so {@code 1000 << 61} is a whole number of
     * wraps and lands on zero.
     */
    @Test
    void theWrapThatProducedAZeroWaitIsCovered() {
        assertEquals(MAX, Backoff.ceilingMillis(61, 1_000, MAX),
            "1000 << 61 is 0 modulo 2^64 — the attempt index at which the old "
                + "shift guard let a zero through");
        assertTrue(Backoff.millis(61, 1_000, MAX) > 0,
            "and the sampled wait must still be positive there");
        assertEquals(MAX, Backoff.ceilingMillis(60, 1_000, MAX),
            "the attempt before it was already saturated");
    }

    /**
     * A base that is itself a power of two wraps earlier, and at its own bit
     * width: {@code 1024 << 54} is a whole number of wraps and lands on 0. The
     * attempt below it is where the sign bit first goes, which the old guard
     * caught by accident — this states the boundary the new guard uses.
     */
    @Test
    void aPowerOfTwoBaseWrapsAtItsOwnBitWidth() {
        assertEquals(300_000, Backoff.ceilingMillis(53, 1_024, 300_000),
            "just below the wrap the curve is already capped");
        assertEquals(300_000, Backoff.ceilingMillis(54, 1_024, 300_000),
            "1024 << 54 is 0 modulo 2^64 — the attempt the shift guard let through");
        assertTrue(Backoff.millis(54, 1_024, 300_000) > 0,
            "and the sampled wait must still be positive there");
    }

    @Test
    void attemptZeroMeansDialImmediately() {
        // The dial loop's first attempt must not wait; that is a decision the
        // dial loop owns, and the curve reports 0 so it can express it.
        assertEquals(0, Backoff.ceilingMillis(0, BASE, MAX));
        assertEquals(0, Backoff.millis(0, BASE, MAX));
        assertEquals(0, Backoff.millis(-1, BASE, MAX), "a negative attempt is also immediate");
    }

    /** A non-positive base is "no schedule", the same reading as a non-positive attempt. */
    @Test
    void aNonPositiveBaseIsTreatedAsNoSchedule() {
        assertEquals(0, Backoff.ceilingMillis(3, 0, MAX));
        assertEquals(0, Backoff.millis(3, -1, MAX));
    }

    @Test
    void theRpcRetryerStillWaitsTheBaseOnItsFirstRetry() {
        // The opposite decision from the dial loop, and deliberately not
        // unified: an RPC that has already failed once backs off before trying
        // again, where a never-tried peer does not. The value is jittered all
        // the same — a constant here would be the synchronized wave on the one
        // retry every client of a restarted service makes together.
        RetryerDefault retryer = new RetryerDefault(3, BASE, MAX);
        Set<Long> firstRetries = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            long delay = retryer.backoffMillis(0);
            assertTrue(delay >= BASE / 2 && delay <= BASE,
                "an RPC's first retry waits within [base/2, base], got " + delay);
            firstRetries.add(delay);
        }
        assertTrue(firstRetries.size() > 1,
            "an RPC's first retry is jittered, not a constant: " + firstRetries);
    }

    @Test
    void theRetryerJittersAboveTheBase() {
        RetryerDefault retryer = new RetryerDefault(5, BASE, MAX);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            long delay = retryer.backoffMillis(3);
            assertTrue(delay >= Backoff.ceilingMillis(3, BASE, MAX) / 2);
            assertTrue(delay <= Backoff.ceilingMillis(3, BASE, MAX));
            seen.add(delay);
        }
        assertTrue(seen.size() > 1, "the RPC retryer must jitter too");
    }

    /**
     * The mesh dial leg is covered by {@code MeshDialBackoffJitterTest} in the
     * event package, where {@code PeerConnector} is visible. Testing
     * {@link Backoff} here alone proved nothing about that caller: an earlier
     * version of this class passed while the mesh was still computing its own
     * un-jittered delay, because only the shared curve was under test.
     */
}
