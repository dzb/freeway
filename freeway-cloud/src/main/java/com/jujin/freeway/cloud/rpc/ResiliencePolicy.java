package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.observe.Tracer;
import com.jujin.freeway.cloud.resilience.CircuitBreaker;
import com.jujin.freeway.cloud.resilience.RateLimiter;
import com.jujin.freeway.cloud.resilience.Retryer;
import com.jujin.freeway.cloud.rpc.CloudException;
import com.jujin.freeway.cloud.rpc.CloudResponse;
import com.jujin.freeway.commons.metrics.Metrics;

import java.time.Duration;
import com.jujin.freeway.cloud.rpc.CloudHttpClientDefault;

/**
 * The retry/breaker/limiter/deadline state machine behind
 * {@link CloudHttpClientDefault} — extracted from the client so the loop is
 * unit-testable with fakes: the policy only ever talks to the
 * resilience interfaces plus a {@link TransportAttempt} callback, so the
 * semantics below are pinned without a transport, discovery or real HTTP.
 *
 * <pre>
 * rateLimiter.tryAcquire() → breaker.allowRequest()
 *   → transport attempt (one discovery/choose/send in the callback)
 *   → onSuccess / onFailure (retryable failures only) → retry with backoff
 * </pre>
 *
 * <p>Rate limiting runs <b>before</b> the circuit breaker so a local
 * rejection never consumes a half-open probe (the probe must reach the
 * transport for its outcome to be meaningful). While the circuit is
 * HALF_OPEN, <b>any</b> failure — including local rejections like "no
 * instance" — reports the probe outcome, so the breaker always settles
 * instead of wedging open.
 *
 * <p>Retryable = connect/timeout/5xx; 4xx, dispatch failures and local
 * rejections (no instance, circuit open, rate limited, thread interrupted)
 * fail immediately. Every failure mode — including unmapped local exceptions
 * from discovery or URL building — surfaces as a {@link CloudException}.
 *
 * <p>One span per logical call (retries included); metrics count the same
 * unit. Both are wired only when CloudObserveModule is installed.
 */
final class ResiliencePolicy {

    private final Retryer retryer;
    /** Tracing wiring; null when the observe module is not installed. */
    private final Tracer tracer;
    /** Metrics registry; null when not wired (span/metrics hooks no-op). */
    private final Metrics metrics;

    ResiliencePolicy(Retryer retryer, Tracer tracer, Metrics metrics) {
        this.retryer = retryer;
        this.tracer = tracer;
        this.metrics = metrics;
    }

    /**
     * Runs one logical call through the resilience state machine.
     *
     * @param serviceId     ordering/telemetry domain (span name, failure messages)
     * @param deadlineNanos end-to-end budget for ALL attempts, {@code 0} = unbounded
     * @param breaker       the caller's per-service breaker shard
     * @param rateLimiter   the caller's per-service limiter shard
     * @param attempt       one transport attempt (discovery, choose, send)
     */
    CloudResponse run(
        String serviceId,
        long deadlineNanos,
        CircuitBreaker breaker,
        RateLimiter rateLimiter,
        TransportAttempt attempt
    ) {
        Tracer.Span span = tracer != null ? tracer.start("cloud.rpc." + serviceId) : null;
        long callStart = System.nanoTime();
        long startNanos = metrics != null ? callStart : 0;
        try {
            int attemptNo = 0;
            while (true) {
                if (deadlineExceeded(callStart, deadlineNanos, 0)) {
                    recordFailure(startNanos);
                    throw CloudException.timeout(serviceId);
                }
                // Rate limit first: a local rejection must not consume a half-open
                // probe — only an actual probe outcome may settle the circuit.
                if (!rateLimiter.tryAcquire()) {
                    recordFailure(startNanos);
                    throw CloudException.rateLimited(serviceId);
                }
                if (!breaker.allowRequest()) {
                    recordFailure(startNanos);
                    throw CloudException.circuitOpen(serviceId);
                }
                boolean probe = breaker.state() == CircuitBreaker.State.HALF_OPEN;
                try {
                    CloudResponse response = attempt.attempt();
                    breaker.onSuccess();
                    if (span != null) {
                        span.addTag("http.status", String.valueOf(response.status()));
                    }
                    recordCall(startNanos);
                    return response;
                } catch (CloudException failure) {
                    if (span != null) {
                        span.addError(failure);
                    }
                    // While probing, every failure is the probe outcome — even
                    // local rejections — so the circuit settles instead of
                    // wedging in HALF_OPEN. Otherwise only retryable failures
                    // count (4xx/local rejections are not service failures).
                    if (probe || failure.retryable()) {
                        breaker.onFailure();
                    }
                    if (!failure.retryable() || !retryer.shouldRetry(attemptNo, failure)) {
                        recordFailure(startNanos);
                        throw failure;
                    }
                    long backoff = retryer.backoffMillis(attemptNo);
                    // Stop instead of parking past the caller's deadline: once
                    // orTimeout has fired the result is discarded anyway, and
                    // retrying keeps the connection pool and breaker busy.
                    if (deadlineExceeded(callStart, deadlineNanos, backoff)) {
                        recordFailure(startNanos);
                        throw CloudException.timeout(serviceId);
                    }
                    sleepBackoff(backoff);
                    attemptNo++;
                }
            }
        } finally {
            if (span != null) {
                span.close();
            }
        }
    }

    /** One transport attempt: resolve the instance, send, map transport
     *  failures. Implemented by the client so the loop never touches
     *  discovery or the JDK HttpClient directly. */
    @FunctionalInterface
    interface TransportAttempt {
        CloudResponse attempt() throws CloudException;
    }

    private static boolean deadlineExceeded(long callStart, long deadlineNanos, long pendingMillis) {
        if (deadlineNanos <= 0) {
            return false;
        }
        return System.nanoTime() - callStart + pendingMillis * 1_000_000L >= deadlineNanos;
    }

    private void recordCall(long startNanos) {
        if (metrics == null) {
            return;
        }
        metrics.counter("cloud.rpc.calls").increment();
        metrics.timer("cloud.rpc.duration")
            .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private void recordFailure(long startNanos) {
        if (metrics == null) {
            return;
        }
        metrics.counter("cloud.rpc.failures").increment();
        metrics.timer("cloud.rpc.duration")
            .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private static void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CloudException.interrupted("backoff", e);
        }
    }
}
