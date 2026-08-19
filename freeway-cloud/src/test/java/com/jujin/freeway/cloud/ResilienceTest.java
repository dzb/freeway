package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.internal.CircuitBreakerDefault;
import com.jujin.freeway.cloud.internal.RateLimiterDefault;
import com.jujin.freeway.cloud.internal.RetryerDefault;
import com.jujin.freeway.cloud.resilience.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resilience primitives: exponential backoff, sliding-window circuit breaker
 * with half-open probing, token-bucket rate limiter.
 */
class ResilienceTest {

    @Test
    void retryerBacksOffExponentiallyWithCap() {
        RetryerDefault retryer = new RetryerDefault(3, 100, 5000);
        assertEquals(100, retryer.backoffMillis(0));
        assertEquals(200, retryer.backoffMillis(1));
        assertEquals(400, retryer.backoffMillis(2));
        assertTrue(retryer.shouldRetry(0, null));
        assertTrue(retryer.shouldRetry(2, null));
        assertFalse(retryer.shouldRetry(3, null), "maxRetries bounds the attempts");

        RetryerDefault capped = new RetryerDefault(10, 1000, 2500);
        assertEquals(2500, capped.backoffMillis(4), "backoff is capped at maxMillis");
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
}
