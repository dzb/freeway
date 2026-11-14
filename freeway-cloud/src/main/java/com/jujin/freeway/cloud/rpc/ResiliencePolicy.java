package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.observe.Tracer;
import com.jujin.freeway.cloud.resilience.CircuitBreaker;
import com.jujin.freeway.cloud.resilience.RateLimiter;
import com.jujin.freeway.cloud.resilience.Retryer;
import com.jujin.freeway.commons.metrics.Metrics;

import java.time.Duration;
import java.util.Map;

/**
 * The retry/breaker/limiter/deadline state machine behind
 * {@link RpcHttpClientDefault} — extracted from the client so the loop is
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
 * <p>Retryable failures pass two gates before the {@link Retryer} is
 * consulted. The <b>category gate</b> is {@code RpcException.retryable()}:
 * transport failures and 5xx are candidates, 4xx, dispatch failures and
 * local rejections (no instance, circuit open, rate limited, thread
 * interrupted) fail immediately. The <b>idempotency gate</b> protects
 * against duplicate execution: failures whose outcome is unknown (timeout,
 * mid-flight I/O, 5xx) may already have been applied by the peer and are
 * retried only when the operation is idempotent. Connect failures never
 * reached the peer and retry regardless. Every failure mode — including
 * unmapped local exceptions from discovery or URL building — surfaces as a
 * {@link RpcException}.
 *
 * <p>The idempotency gate shapes <b>retries only</b>: an ambiguous failure
 * from a non-idempotent operation still counts against the circuit breaker
 * — it is just as real a service failure.</p>
 *
 * <p>One span per logical call (retries included); metrics count the same
 * unit. Both are wired only when ObserveModule is installed.
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
     * @param idempotent    whether ambiguous outcomes (timeout/mid-flight I/O/5xx)
     *                      may be replayed — see {@link RpcRequest#idempotent()}
     * @param attempt       one transport attempt (discovery, choose, send)
     */
    RpcResponse run(
        String serviceId,
        long deadlineNanos,
        CircuitBreaker breaker,
        RateLimiter rateLimiter,
        boolean idempotent,
        TransportAttempt attempt
    ) {
        Tracer.Span span = tracer != null ? tracer.start("cloud.rpc." + serviceId) : null;
        long callStart = System.nanoTime();
        long startNanos = metrics != null ? callStart : 0;
        try {
            int attemptNo = 0;
            while (true) {
                if (deadlineExceeded(callStart, deadlineNanos, 0)) {
                    recordFailure(serviceId, startNanos);
                    throw RpcException.timeout(serviceId);
                }
                // Rate limit first: a local rejection must not consume a half-open
                // probe — only an actual probe outcome may settle the circuit.
                if (!rateLimiter.tryAcquire()) {
                    recordFailure(serviceId, startNanos);
                    throw RpcException.rateLimited(serviceId);
                }
                if (!breaker.allowRequest()) {
                    recordFailure(serviceId, startNanos);
                    throw RpcException.circuitOpen(serviceId);
                }
                boolean probe = breaker.state() == CircuitBreaker.State.HALF_OPEN;
                try {
                    // Bind the RPC child span for the attempt: propagators read
                    // the current context, and without this the outbound trace
                    // carried the parent span (the child never reached the wire).
                    RpcResponse response = span != null
                        ? com.jujin.freeway.cloud.context.InvocationContext.runWith(
                            span.context(), () -> attempt.attempt())
                        : attempt.attempt();
                    breaker.onSuccess();
                    if (span != null) {
                        span.addTag("http.status", String.valueOf(response.status()));
                    }
                    recordCall(serviceId, startNanos);
                    return response;
                } catch (RpcException failure) {
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
                    // Two gates: the category (retryable class of failure)
                    // and idempotency (ambiguous outcomes may have been
                    // applied by the peer). The Retryer only bounds attempts.
                    boolean mayRetry = failure.retryable()
                        && (idempotent || !failure.outcomeUnknown())
                        && retryer.shouldRetry(attemptNo, failure);
                    if (!mayRetry) {
                        recordFailure(serviceId, startNanos);
                        throw failure;
                    }
                    long backoff = retryer.backoffMillis(attemptNo);
                    // Stop instead of parking past the caller's deadline: once
                    // orTimeout has fired the result is discarded anyway, and
                    // retrying keeps the connection pool and breaker busy.
                    if (deadlineExceeded(callStart, deadlineNanos, backoff)) {
                        recordFailure(serviceId, startNanos);
                        throw RpcException.timeout(serviceId);
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
        RpcResponse attempt() throws RpcException;
    }

    private static boolean deadlineExceeded(long callStart, long deadlineNanos, long pendingMillis) {
        if (deadlineNanos <= 0) {
            return false;
        }
        return System.nanoTime() - callStart + pendingMillis * 1_000_000L >= deadlineNanos;
    }

    private void recordCall(String serviceId, long startNanos) {
        if (metrics == null) {
            return;
        }
        Map<String, String> labels = Map.of("service", serviceId);
        metrics.counter("cloud.rpc.calls", labels).increment();
        metrics.histogram("cloud.rpc.duration", labels)
            .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private void recordFailure(String serviceId, long startNanos) {
        if (metrics == null) {
            return;
        }
        Map<String, String> labels = Map.of("service", serviceId);
        metrics.counter("cloud.rpc.failures", labels).increment();
        metrics.histogram("cloud.rpc.duration", labels)
            .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private static void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw RpcException.interrupted("backoff", e);
        }
    }
}
