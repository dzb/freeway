package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.CloudConfigKeys;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import com.jujin.freeway.cloud.resilience.CircuitBreakerDefault;
import com.jujin.freeway.cloud.resilience.RateLimiterDefault;
import com.jujin.freeway.cloud.resilience.RetryerDefault;

/**
 * JDK {@link HttpClient}-backed {@link CloudHttpClient}.
 *
 * <p>The resilience state machine (rate limit → breaker → discovery/choose →
 * send, retryable classification and half-open probe accounting) lives in
 * {@link ResiliencePolicy}; this class only resolves the per-service shards
 * and supplies one transport attempt. Missing resilience bindings degrade to
 * the production defaults in {@link #newBreaker()} / {@link #newRateLimiter()}.
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
    private final CircuitBreaker injectedBreaker;
    private final RateLimiter injectedRateLimiter;
    private final TransportSecurity transport;
    /** Tracing/metrics wiring; null when the observe module is not installed. */
    private final Tracer tracer;
    private final Metrics metrics;
    /** The retry/breaker/limiter/deadline loop, shared by both paths — see
     *  {@link #orchestrate(String, CloudRequest, boolean, long)}. */
    private final ResiliencePolicy policy;
    private final HttpClient http;
    /** Async calls run on virtual threads so blocking HttpClient joins do not
     *  pin common-pool platform threads. */
    private final ExecutorService asyncExecutor;
    /** Per-service shards: one failing service cannot poison the others. An
     *  injected default-implementation breaker/limiter is the configuration
     *  template for each shard; any other implementation (or {@code NOOP})
     *  is shared verbatim — see {@link #newBreaker()}/{@link #newRateLimiter()}. */
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
    /**
     * Backstop against unbounded growth: breakers/rate limiters are sharded per
     * {@code serviceId}, and that id space comes from the discovery source — its
     * cardinality is not controlled by this node. Under a churning registry the
     * maps would otherwise grow without bound, so once the cap is reached an
     * arbitrary stale shard is evicted before a new one is created.
     */
    private static final int MAX_SHARDED_RESILIENCE = 1 << 10;
    /** Async calls submitted but not yet settled, so close() can fail them
     *  explicitly when the executor drops them (shutdownNow discards queued
     *  tasks — their futures would otherwise never complete). */
    private final java.util.Set<java.util.concurrent.CompletableFuture<CloudResponse>> inFlight =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /** Library fallbacks (no resilience module installed) — the same values
     *  the config layer defaults to, from one shared source. */
    private static final int DEFAULT_FAILURE_THRESHOLD =
        CloudConfigKeys.RPC_CB_FAILURE_THRESHOLD_DEFAULT;
    private static final Duration DEFAULT_FAILURE_WINDOW =
        Duration.ofSeconds(CloudConfigKeys.RPC_CB_FAILURE_WINDOW_DEFAULT);
    private static final Duration DEFAULT_OPEN_WINDOW =
        Duration.ofSeconds(CloudConfigKeys.RPC_CB_OPEN_WINDOW_DEFAULT);
    /**
     * Optional wiring for {@link CloudHttpClientDefault}. Every field has a
     * production-safe default, so tests and bare setups omit what they do not
     * use. Bundled into one value (mirroring {@code PeerHub.Wiring}) so the
     * nine optional inputs cannot drift apart at a call site and the client
     * needs no telescoping constructor overloads. Each field also has a
     * wither ({@code withX}) for stepwise customization from
     * {@link #defaults()}.
     */
    public record Wiring(
        List<Propagator> propagators,
        Retryer retryer,
        CircuitBreaker breaker,
        RateLimiter rateLimiter,
        TransportSecurity transport,
        Tracer tracer,
        Metrics metrics,
        Duration requestTimeout,
        Duration connectTimeout
    ) {
        /** Library-fallback timeouts — one value per timeout with the config
         *  layer (CloudRpcModule), sourced from {@link CloudConfigKeys} and
         *  converted ms→{@link Duration} at this boundary. */
        public static final Duration DEFAULT_REQUEST_TIMEOUT =
            Duration.ofMillis(CloudConfigKeys.RPC_REQUEST_TIMEOUT_DEFAULT);
        public static final Duration DEFAULT_CONNECT_TIMEOUT =
            Duration.ofMillis(CloudConfigKeys.RPC_CONNECT_TIMEOUT_DEFAULT);

        public Wiring {
            propagators = propagators == null ? List.of() : List.copyOf(propagators);
            requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
            connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        }

        /** All-default wiring: no propagators, built-in resilience, plaintext. */
        public static Wiring defaults() {
            return new Wiring(null, null, null, null, null, null, null, null, null);
        }

        public Wiring withTracer(Tracer value) {
            return new Wiring(propagators, retryer, breaker, rateLimiter, transport,
                value, metrics, requestTimeout, connectTimeout);
        }

        public Wiring withMetrics(Metrics value) {
            return new Wiring(propagators, retryer, breaker, rateLimiter, transport,
                tracer, value, requestTimeout, connectTimeout);
        }

        public Wiring withPropagators(List<Propagator> value) {
            return new Wiring(value, retryer, breaker, rateLimiter, transport,
                tracer, metrics, requestTimeout, connectTimeout);
        }

        public Wiring withRetryer(Retryer value) {
            return new Wiring(propagators, value, breaker, rateLimiter, transport,
                tracer, metrics, requestTimeout, connectTimeout);
        }

        public Wiring withBreaker(CircuitBreaker value) {
            return new Wiring(propagators, retryer, value, rateLimiter, transport,
                tracer, metrics, requestTimeout, connectTimeout);
        }

        public Wiring withRateLimiter(RateLimiter value) {
            return new Wiring(propagators, retryer, breaker, value, transport,
                tracer, metrics, requestTimeout, connectTimeout);
        }

        public Wiring withTransport(TransportSecurity value) {
            return new Wiring(propagators, retryer, breaker, rateLimiter, value,
                tracer, metrics, requestTimeout, connectTimeout);
        }

        public Wiring withRequestTimeout(Duration value) {
            return new Wiring(propagators, retryer, breaker, rateLimiter, transport,
                tracer, metrics, value, connectTimeout);
        }

        public Wiring withConnectTimeout(Duration value) {
            return new Wiring(propagators, retryer, breaker, rateLimiter, transport,
                tracer, metrics, requestTimeout, value);
        }
    }

    public CloudHttpClientDefault(ServiceDiscovery discovery, LoadBalancer loadBalancer) {
        this(discovery, loadBalancer, Wiring.defaults());
    }

    public CloudHttpClientDefault(
        ServiceDiscovery discovery, LoadBalancer loadBalancer, Wiring wiring) {
        Objects.requireNonNull(wiring, "wiring");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.loadBalancer = Objects.requireNonNull(loadBalancer, "loadBalancer");
        this.propagators = wiring.propagators();
        // A default-implementation breaker/limiter is used as the per-service
        // shard configuration template; any other implementation (or NOOP) is
        // shared verbatim — see the computeIfAbsent factories in call().
        this.injectedBreaker = wiring.breaker();
        this.injectedRateLimiter = wiring.rateLimiter();
        this.transport = wiring.transport() != null ? wiring.transport() : TransportSecurity.NONE;
        this.tracer = wiring.tracer();
        this.metrics = wiring.metrics();
        // The resolved retryer is never null: an absent one falls back to the
        // built-in default before the policy is built, so a missing resilience
        // module cannot NPE on the first retryable failure.
        Retryer resolvedRetryer = wiring.retryer() != null
            ? wiring.retryer()
            : RetryerDefault.withDefaults();
        this.policy = new ResiliencePolicy(resolvedRetryer, this.tracer, this.metrics);
        this.requestTimeout = wiring.requestTimeout();
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(wiring.connectTimeout())
            .version(HttpClient.Version.HTTP_1_1);
        SSLContext sslContext = this.transport.sslContext();
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.http = builder.build();
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
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
        // Capture the caller's invocation context before handing the work to
        // another thread: ScopedValue/ThreadLocal do not propagate implicitly.
        // The work itself runs on a virtual thread, so blocking HttpClient joins
        // do not pin common-pool platform threads.
        InvocationContext ctx = InvocationContext.current().orElse(null);
        // A task still queued when close() shuts the executor down is dropped
        // and would never complete on its own — so the future is registered
        // under close()'s monitor BEFORE the work is submitted, mirroring
        // CallBus's "no caller waits forever on shutdown".
        java.util.concurrent.CompletableFuture<CloudResponse> future =
            new java.util.concurrent.CompletableFuture<>();
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("CloudHttpClient is closed");
            }
            inFlight.add(future);
        }
        future.whenComplete((response, error) -> inFlight.remove(future));
        Runnable work = () -> {
            try {
                future.complete(ctx == null
                    ? orchestrate(serviceId, request, true, deadlineNanos)
                    : InvocationContext.runWith(ctx,
                        () -> orchestrate(serviceId, request, true, deadlineNanos)));
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        };
        try {
            asyncExecutor.execute(work);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // close() won the race after registration — it settles what it
            // already saw; fail here with the type requireUsable uses instead
            // of leaking a raw RejectedExecutionException.
            inFlight.remove(future);
            throw new IllegalStateException("CloudHttpClient is closed", rejected);
        }
        return future;
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
     * One logical call through the resilience loop: rate limit → breaker →
     * discovery/choose → send, with retries. Single copy shared by the
     * blocking and async paths so a fix to the retry/breaker accounting
     * cannot land in only one of them. The loop itself lives in
     * {@link ResiliencePolicy}; here we only resolve the per-service
     * shards and supply the transport attempt.
     */
    private CloudResponse orchestrate(
        String serviceId, CloudRequest request, boolean async, long deadlineNanos) {
        CircuitBreaker breaker = breakerFor(serviceId);
        RateLimiter rateLimiter = rateLimiterFor(serviceId);
        return policy.run(serviceId, deadlineNanos, breaker, rateLimiter,
            () -> attempt(serviceId, request, async));
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
            return RateLimiter.UNLIMITED;
        }
        return injectedRateLimiter.newShard();
    }

    private CircuitBreaker breakerFor(String serviceId) {
        CircuitBreaker b = breakers.get(serviceId);
        if (b != null) return b;
        if (breakers.size() >= MAX_SHARDED_RESILIENCE) evictOne(breakers);
        return breakers.computeIfAbsent(serviceId, k -> newBreaker());
    }

    private RateLimiter rateLimiterFor(String serviceId) {
        RateLimiter r = rateLimiters.get(serviceId);
        if (r != null) return r;
        if (rateLimiters.size() >= MAX_SHARDED_RESILIENCE) evictOne(rateLimiters);
        return rateLimiters.computeIfAbsent(serviceId, k -> newRateLimiter());
    }

    private static <V> void evictOne(ConcurrentHashMap<String, V> map) {
        var it = map.keySet().iterator();
        if (it.hasNext()) map.remove(it.next());
    }

    /**
     * One transport attempt: resolve the instance, send, map transport
     * failures. The instance is re-chosen on every attempt so retries do not
     * hammer the same dead endpoint. Any unmapped local failure (bad URL or
     * header, a discovery backend bug) is converted to a {@link CloudException}
     * — the caller sees one failure type, and a half-open probe still gets an
     * outcome instead of being lost to an escaping RuntimeException.
     */
    private CloudResponse attempt(String serviceId, CloudRequest request, boolean async) {
        try {
            List<ServiceInstance> instances = discovery.getInstances(serviceId);
            ServiceInstance instance = loadBalancer.choose(instances)
                .orElseThrow(() -> CloudException.noInstance(serviceId));
            return doCall(instance, request, async);
        } catch (CloudException e) {
            throw e;
        } catch (RuntimeException e) {
            throw CloudException.dispatch(serviceId, e);
        }
    }

    private CloudResponse doCall(ServiceInstance instance, CloudRequest request, boolean async) {
        // The endpoint URI never ends in '/' (Endpoint normalizes its base
        // path) and a CloudRequest path always starts with '/', so a plain
        // join cannot produce a double slash.
        String url = instance.endpoint().uri() + request.path();
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

    /** Releases the underlying JDK {@link HttpClient} (connection pool +
     *  selector thread). Idempotent; the container calls this on shutdown
     *  via {@link PreDestroy}. */
    @PreDestroy
    public synchronized void close() {
        if (!closed) {
            closed = true;
            asyncExecutor.shutdownNow();
            // Tasks dropped by shutdownNow never complete on their own —
            // settle them so no caller blocks forever on a dead client.
            for (java.util.concurrent.CompletableFuture<CloudResponse> future : inFlight) {
                future.completeExceptionally(
                    new IllegalStateException("CloudHttpClient is closed"));
            }
            inFlight.clear();
            http.close();
        }
    }
}
