package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.Propagator;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.cloud.observe.Tracer;
import com.jujin.freeway.cloud.resilience.CircuitBreaker;
import com.jujin.freeway.cloud.resilience.RateLimiter;
import com.jujin.freeway.cloud.resilience.Retryer;
import com.jujin.freeway.cloud.rpc.CloudException;
import com.jujin.freeway.cloud.rpc.CloudHttpClient;
import com.jujin.freeway.cloud.rpc.CloudRequest;
import com.jujin.freeway.cloud.rpc.CloudResponse;
import com.jujin.freeway.cloud.rpc.TransportSecurity;
import com.jujin.freeway.ioc.annotation.PreDestroy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;

/**
 * JDK {@link HttpClient}-backed {@link CloudHttpClient} with resilience
 * orchestration:
 *
 * <pre>
 * rateLimiter.tryAcquire() → breaker.allowRequest()
 *   → discovery.getInstances → loadBalancer.choose (RE-CHOSEN on every retry)
 *   → context propagation → send (virtual-thread friendly)
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
 * <p>Retryable = connect/timeout/5xx; 4xx and local rejections (no instance,
 * circuit open, rate limited, thread interrupted) fail immediately. Missing
 * resilience bindings degrade to production defaults.
 *
 * <p>Breakers and rate limiters are sharded per {@code serviceId}: one
 * failing service must not poison calls to healthy services. An injected
 * {@link CircuitBreakerDefault}/{@link RateLimiterDefault} acts as the
 * <b>configuration template</b> — every service gets its own instance with
 * the same settings; any other implementation is shared verbatim (caller's
 * choice). The underlying JDK {@link HttpClient} owns a persistent connection
 * pool and selector thread; {@link #close()} releases it exactly once.
 */
public final class CloudHttpClientDefault implements CloudHttpClient, AutoCloseable {

    private final ServiceDiscovery discovery;
    private final LoadBalancer loadBalancer;
    private final Duration requestTimeout;
    private final List<Propagator> propagators;
    private final Retryer retryer;
    private final CircuitBreaker injectedBreaker;
    private final RateLimiter injectedRateLimiter;
    private final TransportSecurity transport;
    /** Tracing/metrics wiring; null when the observe module is not installed. */
    private final Tracer tracer;
    private final Metrics metrics;
    private final HttpClient http;
    /** Per-service shards: one failing service cannot poison the others. An
     *  injected default-implementation breaker/limiter is the configuration
     *  template for each shard; any other implementation (or {@code NOOP})
     *  is shared verbatim — see {@link #newBreaker()}/{@link #newRateLimiter()}. */
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final Duration DEFAULT_FAILURE_WINDOW = Duration.ofSeconds(60);
    private static final Duration DEFAULT_OPEN_WINDOW = Duration.ofSeconds(30);
    private static final int DEFAULT_RATE_PER_SECOND = 100;

    public CloudHttpClientDefault(ServiceDiscovery discovery, LoadBalancer loadBalancer) {
        this(discovery, loadBalancer, List.of(), null, null, null, null, null, null,
            Duration.ofSeconds(10), Duration.ofSeconds(3));
    }

    public CloudHttpClientDefault(ServiceDiscovery discovery, LoadBalancer loadBalancer,
                                  Duration requestTimeout, Duration connectTimeout) {
        this(discovery, loadBalancer, List.of(), null, null, null, null, null, null,
            requestTimeout, connectTimeout);
    }

    public CloudHttpClientDefault(ServiceDiscovery discovery, LoadBalancer loadBalancer,
                                  List<Propagator> propagators,
                                  Retryer retryer, CircuitBreaker breaker, RateLimiter rateLimiter,
                                  TransportSecurity transport,
                                  Tracer tracer, Metrics metrics,
                                  Duration requestTimeout, Duration connectTimeout) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.loadBalancer = Objects.requireNonNull(loadBalancer, "loadBalancer");
        this.propagators = List.copyOf(propagators);
        this.retryer = retryer != null ? retryer : RetryerDefault.withDefaults();
        // Injected breakers/limiters are NOT shared between services: a
        // default-implementation instance is used as the configuration
        // template and every service gets its own shard (see the
        // computeIfAbsent factories in call()). Only non-default custom
        // implementations fall back to sharing — the caller owns their state.
        this.injectedBreaker = breaker;
        this.injectedRateLimiter = rateLimiter;
        this.transport = transport != null ? transport : TransportSecurity.NONE;
        this.tracer = tracer;
        this.metrics = metrics;
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
            .version(HttpClient.Version.HTTP_1_1);
        SSLContext sslContext = this.transport.sslContext();
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.http = builder.build();
    }

    @Override
    public CloudResponse call(String serviceId, CloudRequest request) throws CloudException {
        requireUsable(serviceId);
        return orchestrate(serviceId, request, false, 0L);
    }

    @Override
    public java.util.concurrent.CompletableFuture<CloudResponse> callAsync(
        String serviceId, CloudRequest request) {
        return callAsync(serviceId, request, null);
    }

    @Override
    public java.util.concurrent.CompletableFuture<CloudResponse> callAsync(
        String serviceId, CloudRequest request, java.time.Duration deadline) {
        requireUsable(serviceId);
        long deadlineNanos = deadline == null || deadline.isZero() || deadline.isNegative()
            ? 0L
            : deadline.toNanos();
        // The resilience orchestration (retry loop, backoff parks, breaker
        // accounting) stays on the supply thread — it is CPU-light with short
        // parks. Only the socket wait (doCall) is truly blocking, and JDK
        // HttpClient's sendAsync handles that without pinning a platform thread.
        return java.util.concurrent.CompletableFuture.supplyAsync(
            () -> orchestrate(serviceId, request, true, deadlineNanos));
    }

    private void requireUsable(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        if (closed) {
            throw new IllegalStateException("CloudHttpClient is closed");
        }
    }

    /**
     * One logical call: rate limit → breaker → discovery/choose → send, with
     * retries. Single copy shared by the blocking and async paths so a fix to
     * the retry/breaker accounting cannot land in only one of them.
     *
     * @param async         use {@code sendAsync} for the socket wait
     * @param deadlineNanos end-to-end budget for ALL attempts, {@code 0} = unbounded
     */
    private CloudResponse orchestrate(
        String serviceId, CloudRequest request, boolean async, long deadlineNanos) {
        // One span per logical call (retries included); metrics count the
        // same unit. Both are wired only when CloudObserveModule is installed.
        Tracer.Span span = tracer != null ? tracer.start("cloud.rpc." + serviceId) : null;
        long callStart = System.nanoTime();
        long startNanos = metrics != null ? callStart : 0;
        try {
            CircuitBreaker breaker = breakers.computeIfAbsent(serviceId, k -> newBreaker());
            RateLimiter rateLimiter = rateLimiters.computeIfAbsent(serviceId, k -> newRateLimiter());
            int attempt = 0;
            while (true) {
                if (deadlineExceeded(callStart, deadlineNanos, 0)) {
                    recordFailure(startNanos);
                    throw CloudException.timeout(serviceId);
                }
                // Rate limit first: a local rejection must not consume a half-open
                // probe — only an actual probe outcome may settle the circuit.
                if (!rateLimiter.tryAcquire()) {
                    throw CloudException.rateLimited(serviceId);
                }
                if (!breaker.allowRequest()) {
                    throw CloudException.circuitOpen(serviceId);
                }
                boolean probe = breaker.state() == CircuitBreaker.State.HALF_OPEN;
                try {
                    // Re-choose the instance on every attempt: retries must not
                    // hammer the same dead instance.
                    List<ServiceInstance> instances = discovery.getInstances(serviceId);
                    ServiceInstance instance = loadBalancer.choose(instances)
                        .orElseThrow(() -> CloudException.noInstance(serviceId));
                    CloudResponse response = doCall(instance, request, async);
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
                    if (!failure.retryable() || !retryer.shouldRetry(attempt, failure)) {
                        recordFailure(startNanos);
                        throw failure;
                    }
                    long backoff = retryer.backoffMillis(attempt);
                    // Stop instead of parking past the caller's deadline: once
                    // orTimeout has fired the result is discarded anyway, and
                    // retrying keeps the connection pool and breaker busy.
                    if (deadlineExceeded(callStart, deadlineNanos, backoff)) {
                        recordFailure(startNanos);
                        throw CloudException.timeout(serviceId);
                    }
                    sleepBackoff(backoff);
                    attempt++;
                }
            }
        } finally {
            if (span != null) {
                span.close();
            }
        }
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

    /** Per-service breaker shard. No injection → fresh default; an injected
     *  breaker's {@link CircuitBreaker#newShard()} decides — framework
     *  implementations clone their policy per service, the default (and
     *  externally-managed implementations) share {@code this}. */
    private CircuitBreaker newBreaker() {
        if (injectedBreaker == null) {
            return new CircuitBreakerDefault(DEFAULT_FAILURE_THRESHOLD,
                DEFAULT_FAILURE_WINDOW, DEFAULT_OPEN_WINDOW);
        }
        return injectedBreaker.newShard();
    }

    /** Per-service rate limiter shard — same policy as {@link #newBreaker()}. */
    private RateLimiter newRateLimiter() {
        if (injectedRateLimiter == null) {
            return new RateLimiterDefault(DEFAULT_RATE_PER_SECOND);
        }
        return injectedRateLimiter.newShard();
    }

    private CloudResponse doCall(ServiceInstance instance, CloudRequest request) {
        return doCall(instance, request, false);
    }

    private CloudResponse doCall(ServiceInstance instance, CloudRequest request, boolean async) {
        // Normalize the join so an endpoint with a trailing slash cannot
        // produce a double slash (//) in the request URI.
        String base = instance.endpoint().uri().toString();
        String url = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
            + request.path();
        Map<String, String> outbound = new HashMap<>(request.headers());
        InvocationContext.current().ifPresent(ctx -> {
            for (Propagator propagator : propagators) {
                propagator.inject(ctx, outbound);
            }
        });
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(requestTimeout);
        outbound.forEach(builder::header);
        builder.method(request.method(),
            request.body() == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body()));
        try {
            HttpResponse<byte[]> response;
            if (async) {
                try {
                    response = http.sendAsync(builder.build(),
                        HttpResponse.BodyHandlers.ofByteArray()).join();
                } catch (java.util.concurrent.CompletionException ce) {
                    // Unwrap so the sync catch chain (timeout/connect) applies unchanged.
                    Throwable c = ce.getCause() == null ? ce : ce.getCause();
                    if (c instanceof HttpTimeoutException ht) throw ht;
                    if (c instanceof IOException io) throw io;
                    if (c instanceof RuntimeException re) throw re;
                    throw ce;
                }
            } else {
                response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            }
            if (response.statusCode() >= 500) {
                // Server errors are retryable failures — they must go through
                // retry + circuit-breaker accounting, not return as normal
                // responses (4xx stays a response: the caller owns the body).
                throw CloudException.http(instance.serviceId(), response.statusCode());
            }
            return new CloudResponse(response.statusCode(), response.headers().map(), response.body());
        } catch (HttpTimeoutException e) {
            throw CloudException.timeout(instance.serviceId(), e);
        } catch (IOException e) {
            throw CloudException.connect(instance.serviceId(), e);
        } catch (InterruptedException e) {
            // The caller asked to stop: non-retryable, so outside a half-open
            // probe it is neither re-attempted nor fed into the failure window.
            Thread.currentThread().interrupt();
            throw CloudException.interrupted(instance.serviceId(), e);
        }
    }

    private static void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CloudException.interrupted("backoff", e);
        }
    }

    /** Releases the underlying JDK {@link HttpClient} (connection pool +
     *  selector thread). Idempotent; the container calls this on shutdown
     *  via {@link PreDestroy}. */
    @PreDestroy
    public synchronized void close() {
        if (!closed) {
            closed = true;
            http.close();
        }
    }
}
