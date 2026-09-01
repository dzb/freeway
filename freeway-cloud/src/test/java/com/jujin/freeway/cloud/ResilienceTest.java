package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.internal.CircuitBreakerDefault;
import com.jujin.freeway.cloud.internal.RateLimiterDefault;
import com.jujin.freeway.cloud.internal.RetryerDefault;
import com.jujin.freeway.cloud.resilience.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resilience primitives: exponential backoff, sliding-window circuit breaker
 * with half-open probing, token-bucket rate limiter.
 */
class ResilienceTest {

    @Test
    void retryerBacksOffExponentiallyWithCap() {
        RetryerDefault retryer = new RetryerDefault(3, 100, 5000);
        assertBackoffInRange(retryer, 0, 100);
        assertBackoffInRange(retryer, 1, 200);
        assertBackoffInRange(retryer, 2, 400);
        assertTrue(retryer.shouldRetry(0, null));
        assertTrue(retryer.shouldRetry(2, null));
        assertFalse(retryer.shouldRetry(3, null), "maxRetries bounds the attempts");

        RetryerDefault capped = new RetryerDefault(10, 1000, 2500);
        assertBackoffInRange(capped, 4, 2500);
    }

    /** Backoff is jittered so concurrent clients do not retry in lockstep:
     *  it lands in {@code [base/2, base]} and never exceeds the cap. */
    private static void assertBackoffInRange(RetryerDefault retryer, int attempt, long base) {
        for (int i = 0; i < 200; i++) {
            long actual = retryer.backoffMillis(attempt);
            assertTrue(actual >= base / 2 && actual <= base,
                "attempt " + attempt + ": " + actual + " outside [" + (base / 2) + ", " + base + "]");
        }
    }

    @Test
    void retryerJitterSpreadsConcurrentClients() {
        RetryerDefault retryer = new RetryerDefault(3, 100, 5000);
        long distinct = java.util.stream.LongStream.range(0, 200)
            .map(i -> retryer.backoffMillis(1))
            .distinct()
            .count();
        assertTrue(distinct > 1,
            "backoff must vary, or every client retries at the same instant");
    }

    @Test
    void breakerOpensAtThresholdAndHalfOpensAfterWindow() throws Exception {
        CircuitBreakerDefault breaker = new CircuitBreakerDefault(2,
            Duration.ofSeconds(60), Duration.ofMillis(100));

        assertTrue(breaker.allowRequest());
        breaker.onFailure();
        breaker.onFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.allowRequest(), "OPEN rejects requests");

        Thread.sleep(150); // openWindow elapses
        assertTrue(breaker.allowRequest(), "one half-open probe is admitted");
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        assertFalse(breaker.allowRequest(), "only one probe at a time");

        breaker.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.allowRequest());
    }

    @Test
    void halfOpenProbeFailureReopens() throws Exception {
        CircuitBreakerDefault breaker = new CircuitBreakerDefault(1,
            Duration.ofSeconds(60), Duration.ofMillis(50));
        breaker.onFailure(); // threshold 1 → OPEN
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        Thread.sleep(80);
        assertTrue(breaker.allowRequest()); // probe
        breaker.onFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.allowRequest());
    }

    @Test
    void rateLimiterEnforcesTokenBucket() {
        RateLimiterDefault limiter = new RateLimiterDefault(2.0, 2.0); // 2/s, burst 2
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(), "burst exhausted; no refill yet");
    }

    @Test
    void lostProbeTimesOutAndRearmsInsteadOfWedging() throws Exception {
        // A half-open probe that is admitted but never reported (local
        // rejection before the transport call) must time out and re-arm the
        // circuit — it must NOT wedge the breaker in HALF_OPEN forever.
        CircuitBreakerDefault breaker = new CircuitBreakerDefault(1,
            Duration.ofSeconds(60), Duration.ofMillis(50));
        breaker.onFailure(); // OPEN
        Thread.sleep(80);
        assertTrue(breaker.allowRequest()); // probe admitted
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        // No onSuccess/onFailure is ever reported.

        Thread.sleep(80); // probe timeout elapses
        assertTrue(breaker.allowRequest(),
            "a lost probe must time out and admit a fresh probe");
        breaker.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void staleProbeResultAfterRearmIsIgnored() throws Exception {
        CircuitBreakerDefault breaker = new CircuitBreakerDefault(1,
            Duration.ofSeconds(60), Duration.ofMillis(50));
        breaker.onFailure(); // OPEN
        Thread.sleep(80);

        CountDownLatch probeAAdmitted = new CountDownLatch(1);
        CountDownLatch probeBAdmitted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread probeA = Thread.ofPlatform().start(() -> {
            try {
                if (!breaker.allowRequest()) {
                    throw new AssertionError("probe A not admitted");
                }
                probeAAdmitted.countDown();
                if (!probeBAdmitted.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("probe B was not admitted");
                }
                breaker.onFailure(); // stale A failure must be ignored
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        assertTrue(probeAAdmitted.await(2, TimeUnit.SECONDS));
        Thread.sleep(80); // let probe A go lost
        assertTrue(breaker.allowRequest(), "probe B must be admitted after A is lost");
        probeBAdmitted.countDown();
        probeA.join(2000);

        assertNull(failure.get(), "probe A thread must not fail: " + failure.get());
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state(),
            "stale probe A failure must not open the circuit");
        breaker.onSuccess(); // probe B succeeds
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void staleSuccessWhileOpenIsIgnored() throws Exception {
        // A success from a call admitted BEFORE the open must not yank the
        // circuit back to CLOSED, skipping the open window.
        CircuitBreakerDefault breaker = new CircuitBreakerDefault(1,
            Duration.ofSeconds(60), Duration.ofMillis(100));
        assertTrue(breaker.allowRequest()); // call 1 admitted (CLOSED)
        breaker.onFailure();                // ... fails -> OPEN
        breaker.onFailure();                // call 2 fails too (stale? no - OPEN ignores)
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());

        breaker.onSuccess(); // stale success from call 1 returns late
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(),
            "a stale success must not close an OPEN circuit");

        Thread.sleep(120);
        assertTrue(breaker.allowRequest(), "probe must still be admitted");
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
    }

    @Test
    void staleFailureWhileOpenDoesNotExtendWindow() throws Exception {
        CircuitBreakerDefault breaker = new CircuitBreakerDefault(1,
            Duration.ofSeconds(60), Duration.ofMillis(60));
        breaker.onFailure(); // OPEN at t0
        long opened = System.nanoTime();
        Thread.sleep(30);
        breaker.onFailure(); // stale failure while OPEN
        breaker.onFailure();
        Thread.sleep(70);    // > openWindow from t0
        assertTrue(breaker.allowRequest(),
            "stale failures must not re-extend the open window");
    }

    @Test
    void staleClosedEraOutcomesDoNotSettleHalfOpen() throws Exception {
        // Regression: a caller admitted before the circuit opened (no probe
        // epoch) could settle HALF_OPEN by reporting late — a stale failure
        // re-opened the circuit under a live probe, and a stale success closed
        // it while the real probe was still in flight. Pre-open outcomes are
        // now ignored in every non-CLOSED state.
        CircuitBreakerDefault breaker = new CircuitBreakerDefault(1,
            Duration.ofSeconds(60), Duration.ofMillis(50));
        assertTrue(breaker.allowRequest()); // CLOSED-era call
        breaker.onFailure();                // → OPEN
        Thread.sleep(80);
        assertTrue(breaker.allowRequest()); // probe admitted (epoch on this thread)
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread stale = Thread.ofPlatform().start(() -> {
            try {
                breaker.onFailure(); // stale CLOSED-era failure — no probe epoch
                breaker.onSuccess(); // stale CLOSED-era success
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        stale.join(2000);
        assertNull(failure.get(), "stale callback thread must not fail: " + failure.get());
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state(),
            "stale outcomes must not settle a circuit the probe owns");

        breaker.onSuccess(); // the real probe succeeds
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void resetInvalidatesLiveProbes() throws Exception {
        CircuitBreakerDefault breaker = new CircuitBreakerDefault(1,
            Duration.ofSeconds(60), Duration.ofMillis(50));
        breaker.onFailure(); // OPEN
        Thread.sleep(80);
        assertTrue(breaker.allowRequest()); // probe admitted on this thread
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());

        breaker.reset();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        // The probe's late outcome carries a superseded epoch — it must not
        // settle (let alone open) the freshly reset circuit.
        breaker.onFailure();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(),
            "a reset-superseded probe outcome must be ignored");
        assertTrue(breaker.allowRequest());
    }

    @Test
    void constructorValidatesArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> new CircuitBreakerDefault(0, Duration.ofSeconds(60), Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,
            () -> new CircuitBreakerDefault(5, Duration.ZERO, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,
            () -> new RateLimiterDefault(0));
        assertThrows(IllegalArgumentException.class,
            () -> new RateLimiterDefault(Double.NaN));
    }
}
