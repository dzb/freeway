package com.jujin.freeway.cloud.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.cloud.resilience.Backoff;

/**
 * The mesh dial leg jitters.
 *
 * <p>Separate from {@code BackoffTest} because {@code PeerConnector} is
 * package-private, and because the point here is not the curve — it is that
 * this particular caller actually reaches it. The two were separate code
 * paths once, and a test on the shared curve alone passed while the mesh was
 * still computing its own un-jittered delay.
 *
 * <p>Why this leg: after a rolling restart every node loses its peers in the
 * same instant, and an un-jittered backoff has all of them re-dial in
 * lockstep, forever. This is the one caller that can produce the
 * synchronized-wave failure jitter exists to prevent.
 */
class MeshDialBackoffJitterTest {

    private static final long BASE = 100;
    private static final long MAX = 5_000;

    private static PeerConnector connector() {
        var wiring = new PeerConnector.Wiring(
            Duration.ofMillis(50), "ws", Duration.ofMillis(50), BASE, MAX, null);
        return new PeerConnector(new PeerHub(), wiring);
    }

    @Test
    void repeatedDialsAtTheSameAttemptGetDifferentDelays() {
        try (PeerConnector connector = connector()) {
            long ceiling = Backoff.ceilingMillis(4, BASE, MAX);
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < 200; i++) {
                long delay = connector.backoffSleepMsForTest(4);
                assertTrue(delay >= ceiling / 2 && delay <= ceiling,
                    "dial backoff " + delay + " outside [" + ceiling / 2 + ", " + ceiling + "]");
                seen.add(delay);
            }
            assertTrue(seen.size() > 1,
                "the mesh dial leg is un-jittered — after a rolling restart every "
                    + "node would re-dial in lockstep");
        }
    }

    @Test
    void aNeverTriedPeerIsDialedImmediately() {
        // The one behavior the mesh needs that the RPC policy does not: no
        // wait before the first attempt. It stays at the call site rather than
        // being unified away into the shared curve.
        try (PeerConnector connector = connector()) {
            assertEquals(0, connector.backoffSleepMsForTest(0));
            assertEquals(0, connector.backoffSleepMsForTest(-1));
        }
    }

    @Test
    void theDelayGrowsAcrossAttempts() {
        try (PeerConnector connector = connector()) {
            long firstCeiling = Backoff.ceilingMillis(2, BASE, MAX);
            long laterCeiling = Backoff.ceilingMillis(5, BASE, MAX);
            assertTrue(laterCeiling > firstCeiling,
                "the ceiling must keep growing as attempts accumulate");

            boolean anyLaterExceedsFirst = false;
            for (int i = 0; i < 200; i++) {
                if (connector.backoffSleepMsForTest(5) > firstCeiling) {
                    anyLaterExceedsFirst = true;
                    break;
                }
            }
            assertTrue(anyLaterExceedsFirst,
                "a later attempt must be able to wait longer than an earlier one");
        }
    }
}
