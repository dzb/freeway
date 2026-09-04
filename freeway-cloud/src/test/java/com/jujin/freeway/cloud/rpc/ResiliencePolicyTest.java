package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.resilience.CircuitBreaker;
import com.jujin.freeway.cloud.resilience.RateLimiter;
import com.jujin.freeway.cloud.resilience.Retryer;
import com.jujin.freeway.cloud.rpc.CloudException;
import com.jujin.freeway.cloud.rpc.CloudResponse;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the resilience state machine in {@link ResiliencePolicy}:
 * the loop semantics are pinned with fakes for breaker/limiter/retryer and a
 * synthetic transport — no HTTP, no discovery, no real backoff sleeps.
 */
class ResiliencePolicyTest {

    private static ResiliencePolicy policy(Retryer retryer) {
        return new ResiliencePolicy(retryer, null, null);
    }

    private static CloudResponse ok() {
        return new CloudResponse(200, Map.of(), new byte[0]);
    }

    private static CloudException connectFailure() {
        return CloudException.connect("svc", new IOException("down"));
    }

    @Test
    void successReturnsResponseAndCountsBreakerSuccess() {
        FakeBreaker breaker = FakeBreaker.closed();
        ResiliencePolicy policy = policy(Retryer.NO_RETRY);
        AtomicInteger attempts = new AtomicInteger();

        CloudResponse response = policy.run("svc", 0, breaker, FakeLimiter.unlimited(),
            () -> {
                attempts.incrementAndGet();
                return ok();
            });

        assertEquals(200, response.status());
        assertEquals(1, attempts.get());
        assertEquals(1, breaker.successes);
        assertEquals(0, breaker.failures);
    }

    @Test
    void rateLimitRejectionFailsFastBeforeBreakerOrAttempt() {
        FakeBreaker breaker = FakeBreaker.closed();
        ResiliencePolicy policy = policy(Retryer.NO_RETRY);
        AtomicInteger attempts = new AtomicInteger();

        CloudException ex = assertThrows(CloudException.class,
            () -> policy.run("svc", 0, breaker, FakeLimiter.withPermits(0), () -> {
                attempts.incrementAndGet();
                return ok();
            }));

        assertFalse(ex.retryable(), "a local rate-limit rejection is not retryable");
        assertEquals(0, attempts.get(), "the transport must never be reached");
        assertEquals(0, breaker.allowCalls,
            "a local rejection must not consume a half-open probe — rate limit runs first");
    }

    @Test
    void openCircuitFailsFastAfterRateLimitWithoutAttempt() {
        FakeBreaker breaker = FakeBreaker.open();
        FakeLimiter limiter = FakeLimiter.withPermits(1);
        ResiliencePolicy policy = policy(Retryer.NO_RETRY);
        AtomicInteger attempts = new AtomicInteger();

        CloudException ex = assertThrows(CloudException.class,
            () -> policy.run("svc", 0, breaker, limiter, () -> {
                attempts.incrementAndGet();
                return ok();
            }));

        assertFalse(ex.retryable(), "an open circuit is a local rejection");
        assertEquals(0, attempts.get());
        assertEquals(0, limiter.permits(), "the rate limiter ran before the breaker check");
    }

    @Test
    void retryableFailuresRetryWithBackoffThenSucceed() {
        FakeBreaker breaker = FakeBreaker.closed();
        ResiliencePolicy policy = policy(new FakeRetryer(5, 0));
        AtomicInteger attempts = new AtomicInteger();

        CloudResponse response = policy.run("svc", 0, breaker, FakeLimiter.unlimited(),
            () -> {
                if (attempts.incrementAndGet() < 3) {
                    throw connectFailure();
                }
                return ok();
            });

        assertEquals(200, response.status());
        assertEquals(3, attempts.get(), "two retryable failures, then success");
        assertEquals(2, breaker.failures, "each retryable failure counts against the window");
        assertEquals(1, breaker.successes);
    }

    @Test
    void retryBudgetExhaustionPropagatesTheLastFailure() {
        FakeBreaker breaker = FakeBreaker.closed();
        ResiliencePolicy policy = policy(new FakeRetryer(1, 0));
        AtomicInteger attempts = new AtomicInteger();

        CloudException ex = assertThrows(CloudException.class,
            () -> policy.run("svc", 0, breaker, FakeLimiter.unlimited(), () -> {
                attempts.incrementAndGet();
                throw connectFailure();
            }));

        assertTrue(ex.retryable());
        assertEquals(2, attempts.get(), "initial attempt + one retry");
        assertEquals(2, breaker.failures);
    }

    @Test
    void nonRetryableLocalRejectionFailsImmediatelyWithoutBreakerAccounting() {
        FakeBreaker breaker = FakeBreaker.closed();
        ResiliencePolicy policy = policy(new FakeRetryer(5, 0));
        AtomicInteger attempts = new AtomicInteger();

        CloudException ex = assertThrows(CloudException.class,
            () -> policy.run("svc", 0, breaker, FakeLimiter.unlimited(), () -> {
                attempts.incrementAndGet();
                throw CloudException.noInstance("svc");
            }));

        assertFalse(ex.retryable(), "no instance is a configuration error, not retryable");
        assertEquals(1, attempts.get(), "never retried");
        assertEquals(0, breaker.failures,
            "a local rejection outside a probe is not a service failure");
    }

    @Test
    void halfOpenProbeCountsEvenNonRetryableLocalRejections() {
        // While probing, ANY failure is the probe outcome — otherwise a
        // half-open circuit wedges forever on local rejections.
        FakeBreaker breaker = FakeBreaker.halfOpen();
        ResiliencePolicy policy = policy(Retryer.NO_RETRY);

        CloudException ex = assertThrows(CloudException.class,
            () -> policy.run("svc", 0, breaker, FakeLimiter.unlimited(),
                () -> {
                    throw CloudException.noInstance("svc");
                }));

        assertFalse(ex.retryable());
        assertEquals(1, breaker.failures,
            "the probe outcome must settle the breaker even though the rejection is local");
    }

    @Test
    void halfOpenProbeSuccessClosesTheCircuit() {
        FakeBreaker breaker = FakeBreaker.halfOpen();
        ResiliencePolicy policy = policy(Retryer.NO_RETRY);

        CloudResponse response = policy.run("svc", 0, breaker, FakeLimiter.unlimited(),
            () -> ok());

        assertEquals(200, response.status());
        assertEquals(1, breaker.successes, "a successful probe closes the circuit");
        assertEquals(0, breaker.failures);
    }

    @Test
    void deadlineExpiredBeforeFirstAttemptThrowsTimeoutWithoutTouchingAnything() {
        FakeBreaker breaker = FakeBreaker.closed();
        ResiliencePolicy policy = policy(Retryer.NO_RETRY);
        AtomicInteger attempts = new AtomicInteger();

        CloudException ex = assertThrows(CloudException.class,
            () -> policy.run("svc", 1, breaker, FakeLimiter.unlimited(), () -> {
                attempts.incrementAndGet();
                return ok();
            }));

        assertTrue(ex.retryable(), "timeout is a retryable class of failure");
        assertEquals(0, attempts.get());
        assertEquals(0, breaker.allowCalls);
    }

    @Test
    void deadlineDuringBackoffStopsInsteadOfParking() {
        // The backoff would outrun the budget: the loop must throw timeout
        // without sleeping a minute — deterministic, no real sleeps involved.
        FakeBreaker breaker = FakeBreaker.closed();
        ResiliencePolicy policy = policy(new FakeRetryer(10, 60_000));
        AtomicInteger attempts = new AtomicInteger();

        CloudException ex = assertThrows(CloudException.class,
            () -> policy.run("svc", DurationNanos.ms(5), breaker, FakeLimiter.unlimited(),
                () -> {
                    attempts.incrementAndGet();
                    throw connectFailure();
                }));

        assertTrue(ex.retryable());
        assertEquals(1, attempts.get(), "no retry past the deadline");
        assertEquals(1, breaker.failures);
    }

    // ==================== fakes ====================

    private static final class FakeBreaker implements CircuitBreaker {
        final State state;
        final boolean allow;
        int allowCalls;
        int failures;
        int successes;

        FakeBreaker(State state, boolean allow) {
            this.state = state;
            this.allow = allow;
        }

        static FakeBreaker closed() {
            return new FakeBreaker(State.CLOSED, true);
        }

        static FakeBreaker halfOpen() {
            return new FakeBreaker(State.HALF_OPEN, true);
        }

        static FakeBreaker open() {
            return new FakeBreaker(State.OPEN, false);
        }

        @Override
        public State state() {
            return state;
        }

        @Override
        public boolean allowRequest() {
            allowCalls++;
            return allow;
        }

        @Override
        public void onSuccess() {
            successes++;
        }

        @Override
        public void onFailure() {
            failures++;
        }

        @Override
        public void reset() {
        }
    }

    private static final class FakeLimiter implements RateLimiter {
        int permits;

        FakeLimiter(int permits) {
            this.permits = permits;
        }

        static FakeLimiter unlimited() {
            return new FakeLimiter(Integer.MAX_VALUE);
        }

        static FakeLimiter withPermits(int permits) {
            return new FakeLimiter(permits);
        }

        @Override
        public boolean tryAcquire() {
            if (permits > 0) {
                permits--;
                return true;
            }
            return false;
        }

        int permits() {
            return permits;
        }
    }

    private static final class FakeRetryer implements Retryer {
        final int maxRetries;
        final long backoff;

        FakeRetryer(int maxRetries, long backoff) {
            this.maxRetries = maxRetries;
            this.backoff = backoff;
        }

        @Override
        public boolean shouldRetry(int attempt, Throwable failure) {
            return attempt < maxRetries;
        }

        @Override
        public long backoffMillis(int attempt) {
            return backoff;
        }
    }

    /** Small helper so deadline arithmetic in tests reads as milliseconds. */
    private static final class DurationNanos {
        static long ms(long millis) {
            return millis * 1_000_000L;
        }
    }
}
