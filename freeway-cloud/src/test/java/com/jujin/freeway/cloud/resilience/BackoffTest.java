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

    @Test
    void attemptZeroMeansDialImmediately() {
        // The dial loop's first attempt must not wait; that is a decision the
        // dial loop owns, and the curve reports 0 so it can express it.
        assertEquals(0, Backoff.ceilingMillis(0, BASE, MAX));
        assertEquals(0, Backoff.millis(0, BASE, MAX));
        assertEquals(0, Backoff.millis(-1, BASE, MAX), "a negative attempt is also immediate");
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
