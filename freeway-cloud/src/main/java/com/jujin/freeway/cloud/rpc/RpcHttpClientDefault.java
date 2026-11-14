package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.Propagator;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.internal.CloudHttpClients;
import com.jujin.freeway.cloud.observe.Tracer;
import com.jujin.freeway.cloud.resilience.CircuitBreaker;
import com.jujin.freeway.cloud.resilience.CircuitBreakerDefault;
import com.jujin.freeway.cloud.resilience.RateLimiter;
import com.jujin.freeway.cloud.resilience.RateLimiterDefault;
import com.jujin.freeway.cloud.resilience.Retryer;
import com.jujin.freeway.cloud.resilience.RetryerDefault;
import com.jujin.freeway.commons.metrics.Metrics;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.jujin.freeway.cloud.CloudModule.ConfigKeys;

/**
 * Default {@link RpcHttpClient} — a JDK {@link HttpClient}-backed
 * implementation.
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
 *
 * <p>The framework default for this role: {@link RpcModule} binds it
 * when the module is installed. An extension module replaces it by binding
 * its own {@link RpcHttpClient} implementation with {@code .primary()}.</p>
 */
public final class RpcHttpClientDefault implements RpcHttpClient {

    private static final Logger LOG =
        LoggerFactory.getLogger(RpcHttpClientDefault.class);

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
     *  {@link #orchestrate(String, RpcRequest, boolean, long)}. */
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
    private final java.util.Set<java.util.concurrent.CompletableFuture<RpcResponse>> inFlight =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** How long {@link #close()} lets in-flight calls finish before failing them. */
    private final Duration shutdownGrace;
    private volatile boolean closed;

    /** Library fallbacks (no resilience module installed) — the same values
     *  the config layer defaults to, from one shared source. */
    private static final int DEFAULT_FAILURE_THRESHOLD =
        ConfigKeys.RPC_CB_FAILURE_THRESHOLD_DEFAULT;
    private static final Duration DEFAULT_FAILURE_WINDOW =
        Duration.ofSeconds(ConfigKeys.RPC_CB_FAILURE_WINDOW_DEFAULT);
    private static final Duration DEFAULT_OPEN_WINDOW =
        Duration.ofSeconds(ConfigKeys.RPC_CB_OPEN_WINDOW_DEFAULT);
    /**
     * Optional wiring for {@link RpcHttpClientDefault}. Every field has a
     * production-safe default, so tests and bare setups omit what they do not
     * use. Bundled into one value (mirroring {@code PeerHub.Wiring}) so the
     * optional inputs cannot drift apart at a call site and the client
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
        Duration connectTimeout,
        Duration shutdownGrace
    ) {
        /** Library-fallback timeouts — one value per timeout with the config
         *  layer (RpcModule), sourced from {@link ConfigKeys} and
         *  converted ms→{@link Duration} at this boundary. */
        public static final Duration DEFAULT_REQUEST_TIMEOUT =
            Duration.ofMillis(ConfigKeys.RPC_REQUEST_TIMEOUT_DEFAULT);
        public static final Duration DEFAULT_CONNECT_TIMEOUT =
            Duration.ofMillis(ConfigKeys.RPC_CONNECT_TIMEOUT_DEFAULT);
        /** How long {@link RpcHttpClientDefault#close()} waits for in-flight
         *  calls, from the shared config default. */
        public static final Duration DEFAULT_SHUTDOWN_GRACE =
            ConfigKeys.RPC_SHUTDOWN_GRACE_DEFAULT;

        public Wiring {
            propagators = propagators == null ? List.of() : List.copyOf(propagators);
            requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
            connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
            shutdownGrace = shutdownGrace == null ? DEFAULT_SHUTDOWN_GRACE : shutdownGrace;
        }

        /** All-default wiring: no propagators, built-in resilience, plaintext. */
        public static Wiring defaults() {
            return new Wiring(null, null, null, null, null, null, null, null, null, null);
        }

        public Wiring withTracer(Tracer value) {
            return new Wiring(propagators, retryer, breaker, rateLimiter, transport,
                value, metrics, requestTimeout, connectTimeout,
                shutdownGrace);
        }

        public Wiring withMetrics(Metrics value) {
            return new Wiring(propagators, retryer, breaker, rateLimiter, transport,
                tracer, value, requestTimeout, connectTimeout,
                shutdownGrace);
        }

        public Wiring withPropagators(List<Propagator> value) {
            return new Wiring(value, retryer, breaker, rateLimiter, transport,
                tracer, metrics, requestTimeout, connectTimeout,
                shutdownGrace);
        }

        public Wiring withRetryer(Retryer value) {
            return new Wiring(propagators, value, breaker, rateLimiter, transport,
                tracer, metrics, requestTimeout, connectTimeout,
                shutdownGrace);
        }

        public Wiring withBreaker(CircuitBreaker value) {
            return new Wiring(propagators, retryer, value, rateLimiter, transport,
                tracer, metrics, requestTimeout, connectTimeout,
                shutdownGrace);
        }

        public Wiring withRateLimiter(RateLimiter value) {
            return new Wiring(propagators, retryer, breaker, value, transport,
                tracer, metrics, requestTimeout, connectTimeout,
                shutdownGrace);
        }

        public Wiring withTransport(TransportSecurity value) {
            return new Wiring(propagators, retryer, breaker, rateLimiter, value,
                tracer, metrics, requestTimeout, connectTimeout,
                shutdownGrace);
        }

        public Wiring withRequestTimeout(Duration value) {
            return new Wiring(propagators, retryer, breaker, rateLimiter, transport,
                tracer, metrics, value, connectTimeout,
                shutdownGrace);
        }

        public Wiring withShutdownGrace(Duration value) {
            return new Wiring(propagators, retryer, breaker, rateLimiter, transport,
                tracer, metrics, requestTimeout, connectTimeout,
                value);
        }

        public Wiring withConnectTimeout(Duration value) {
            return new Wiring(propagators, retryer, breaker, rateLimiter, transport,
                tracer, metrics, requestTimeout, value,
                shutdownGrace);
        }
    }

    public RpcHttpClientDefault(ServiceDiscovery discovery, LoadBalancer loadBalancer) {
        this(discovery, loadBalancer, Wiring.defaults());
    }

    public RpcHttpClientDefault(
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
        this.shutdownGrace = wiring.shutdownGrace();
        this.requestTimeout = wiring.requestTimeout();
        this.http = CloudHttpClients
            .builder(wiring.connectTimeout(), this.transport.sslContext())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public RpcResponse call(String serviceId, RpcRequest request) throws RpcException {
        // Registered while it runs, for the same reason as the async path:
        // shutdown must see this call and give it a chance to finish.
        java.util.concurrent.CompletableFuture<RpcResponse> token = enter(serviceId);
        try {
            RpcResponse response = orchestrate(serviceId, request, false, 0L);
            token.complete(response);
            return response;
        } catch (RuntimeException e) {
            token.completeExceptionally(e);
            throw e;
        }
    }

    /**
     * Admits a call: refuses when the client is closing, otherwise registers a
     * completion token so {@link #close()} can see it. Shared by both call
     * paths so "in flight" means the same thing everywhere.
     */
    private java.util.concurrent.CompletableFuture<RpcResponse> enter(String serviceId) {
        requireUsable(serviceId);
        java.util.concurrent.CompletableFuture<RpcResponse> token =
            new java.util.concurrent.CompletableFuture<>();
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("RpcHttpClient is closed");
            }
            inFlight.add(token);
        }
        token.whenComplete((response, error) -> inFlight.remove(token));
        return token;
    }

    @Override
    public java.util.concurrent.CompletableFuture<RpcResponse> callAsync(
        String serviceId, RpcRequest request) {
        return callAsync(serviceId, request, null);
    }

    @Override
    public java.util.concurrent.CompletableFuture<RpcResponse> callAsync(
        String serviceId, RpcRequest request, java.time.Duration deadline) {
        requireUsable(serviceId);
        long deadlineNanos = deadline == null || deadline.isZero() || deadline.isNegative()
            ? 0L
            : deadline.toNanos();
        // Capture the caller's invocation context before handing the work to
        // another thread: ScopedValue/ThreadLocal do not propagate implicitly.
        // The work itself runs on a virtual thread, so blocking HttpClient joins
        // do not pin common-pool platform threads.
        InvocationContext ctx = InvocationContext.current().orElse(null);
        // Registered before the work is submitted: a task dropped by
        // shutdownNow would never complete on its own, so close() must see it
        // to settle it — and must see it to drain it first.
        java.util.concurrent.CompletableFuture<RpcResponse> future = enter(serviceId);
        // Cancellation is a first-class shape: cancelling the returned future
        // aborts the in-flight send (when one exists) and interrupts the
        // worker, so the retry loop/backoff stops instead of running on for a
        // result nobody will read. The underlying transport is best-effort:
        // the JDK client owns the connection, and cancel() asks it to drop it.
        CallState state = new CallState();
        future.whenComplete((response, error) -> {
            if (future.isCancelled()) {
                state.cancel();
            }
        });
        Runnable work = () -> {
            if (state.cancelled()) {
                return;
            }
            state.worker(Thread.currentThread());
            try {
                future.complete(ctx == null
                    ? orchestrate(serviceId, request, true, deadlineNanos, state)
                    : InvocationContext.runWith(ctx,
                        () -> orchestrate(serviceId, request, true, deadlineNanos, state)));
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            } finally {
                state.worker(null);
            }
        };
        try {
            asyncExecutor.execute(work);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // close() won the race after registration — it settles what it
            // already saw; fail here with the type requireUsable uses instead
            // of leaking a raw RejectedExecutionException.
            inFlight.remove(future);
            throw new IllegalStateException("RpcHttpClient is closed", rejected);
        }
        return future;
    }

    /**
     * The per-call cancellation handle: the worker thread and the in-flight
     * send it is waiting on. Cancellation interrupts both, and the retry loop
     * sees an interrupted failure (non-retryable) instead of continuing.
     */
    private static final class CallState {
        private volatile java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> send;
        private volatile Thread worker;
        private volatile boolean cancelled;

        void worker(Thread thread) {
            this.worker = thread;
        }

        void send(java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> future) {
            this.send = future;
            if (cancelled) {
                future.cancel(true);
            }
        }

        void clearSend() {
            this.send = null;
        }

        void cancel() {
            cancelled = true;
            java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> inFlight = send;
            if (inFlight != null) {
                inFlight.cancel(true);
            }
            Thread thread = worker;
            if (thread != null) {
                thread.interrupt();
            }
        }

        boolean cancelled() {
            return cancelled;
        }
    }

    private void requireUsable(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        if (closed) {
            throw new IllegalStateException("RpcHttpClient is closed");
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
    private RpcResponse orchestrate(
        String serviceId, RpcRequest request, boolean async, long deadlineNanos) {
        return orchestrate(serviceId, request, async, deadlineNanos, null);
    }

    private RpcResponse orchestrate(
        String serviceId, RpcRequest request, boolean async, long deadlineNanos,
        CallState state) {
        CircuitBreaker breaker = breakerFor(serviceId);
        RateLimiter rateLimiter = rateLimiterFor(serviceId);
        long callStart = System.nanoTime();
        return policy.run(serviceId, deadlineNanos, breaker, rateLimiter,
            request.idempotent(),
            () -> attempt(serviceId, request, async, deadlineNanos, callStart, state));
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
     * header, a discovery backend bug) is converted to a {@link RpcException}
     * — the caller sees one failure type, and a half-open probe still gets an
     * outcome instead of being lost to an escaping RuntimeException.
     */
    private RpcResponse attempt(String serviceId, RpcRequest request, boolean async,
                                long deadlineNanos, long callStart, CallState state) {
        try {
            List<ServiceInstance> instances = discovery.instances(serviceId);
            ServiceInstance instance = loadBalancer.choose(instances)
                .orElseThrow(() -> RpcException.noInstance(serviceId));
            return doCall(instance, request, async, deadlineNanos, callStart, state);
        } catch (RpcException e) {
            throw e;
        } catch (RuntimeException e) {
            throw RpcException.dispatch(serviceId, e);
        }
    }

    private RpcResponse doCall(ServiceInstance instance, RpcRequest request, boolean async,
                               long deadlineNanos, long callStart, CallState state) {
        // The endpoint URI never ends in '/' (ServiceEndpoint normalizes its base
        // path) and a RpcRequest path always starts with '/', so a plain
        // join cannot produce a double slash.
        String url = instance.endpoint().uri() + request.path();
        Map<String, String> outbound = new HashMap<>(request.headers());
        InvocationContext.current().ifPresent(ctx -> {
            for (Propagator propagator : propagators) {
                propagator.inject(ctx, outbound);
            }
        });
        // The deadline owns this attempt: a fixed request timeout let a send
        // keep running past the caller's bound (orTimeout only ends the future).
        java.time.Duration attemptTimeout = requestTimeout;
        if (deadlineNanos > 0) {
            long remaining = deadlineNanos - (System.nanoTime() - callStart);
            if (remaining <= 0) {
                throw RpcException.timeout(instance.serviceId());
            }
            java.time.Duration remainingTimeout = java.time.Duration.ofNanos(remaining);
            if (remainingTimeout.compareTo(attemptTimeout) < 0) {
                attemptTimeout = remainingTimeout;
            }
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(attemptTimeout);
        outbound.forEach(builder::header);
        builder.method(request.method(),
            request.body() == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body()));
        try {
            HttpResponse<byte[]> response;
            if (async) {
                java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> sendFuture =
                    http.sendAsync(builder.build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (state != null) {
                    state.send(sendFuture);
                }
                try {
                    // get(), not join(): a cancelled call interrupts the worker,
                    // and join() would keep it parked until the peer answers.
                    response = sendFuture.get();
                } catch (java.util.concurrent.CancellationException ce) {
                    throw RpcException.interrupted(instance.serviceId(), ce);
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable c = ee.getCause() == null ? ee : ee.getCause();
                    if (c instanceof HttpTimeoutException ht) throw ht;
                    if (c instanceof IOException io) throw io;
                    if (c instanceof RuntimeException re) throw re;
                    if (c instanceof InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw RpcException.interrupted(instance.serviceId(), ie);
                    }
                    throw new java.util.concurrent.CompletionException(c);
                } catch (InterruptedException ie) {
                    sendFuture.cancel(true);
                    throw ie; // mapped by the outer catch below
                } finally {
                    if (state != null) {
                        state.clearSend();
                    }
                }
            } else {
                response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            }
            if (response.statusCode() >= 500) {
                // Server errors are retryable failures — they must go through
                // retry + circuit-breaker accounting, not return as normal
                // responses (4xx stays a response: the caller owns the body).
                // The retry itself is gated on idempotency by the policy: the
                // peer may have applied the request before answering 5xx.
                throw RpcException.http(instance.serviceId(), response.statusCode());
            }
            return new RpcResponse(response.statusCode(), response.headers().map(), response.body());
        } catch (ConnectException e) {
            // TCP connect refused/reset: the request never left this process.
            throw RpcException.connect(instance.serviceId(), e);
        } catch (HttpConnectTimeoutException e) {
            // Connect-phase timeout (a HttpTimeoutException subtype — must be
            // caught first): nothing was sent, so the outcome is known.
            throw RpcException.connect(instance.serviceId(), e);
        } catch (HttpTimeoutException e) {
            // Response never arrived in time: the peer may have applied it.
            throw RpcException.timeout(instance.serviceId(), e);
        } catch (IOException e) {
            // Mid-flight I/O failure (reset after send, broken pipe): the
            // request may have reached the peer — ambiguous outcome.
            throw RpcException.transport(instance.serviceId(), e);
        } catch (InterruptedException e) {
            // The caller asked to stop: non-retryable, so outside a half-open
            // probe it is neither re-attempted nor fed into the failure window.
            Thread.currentThread().interrupt();
            throw RpcException.interrupted(instance.serviceId(), e);
        }
    }

    /** Releases the underlying JDK {@link HttpClient} (connection pool +
     *  selector thread), plus this client's own executor. Idempotent.
     *
     * <p>Reached through {@link AutoCloseable}, not {@code @PreDestroy}:
     * implementing the JDK interface is what guarantees the container sees it,
     * whereas the annotation is opt-in and easy to forget. {@code @PreDestroy}
     * is the other path, not a replacement — a class carrying both runs both,
     * {@code @PreDestroy} first, with the {@code EventBus} deferred past both.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        // Stop admitting: a call that arrives now is told the client is going
        // away instead of being cut off mid-flight later.
        closed = true;
        // Let what is already in flight finish. Cutting it here is the one
        // shutdown choice that can lose work: the peer may have applied the
        // call while the reply is still on the wire. Bounded by the deployment
        // grace, and free when nothing is in flight.
        waitForInFlight();
        // Settle what the grace did not drain *before* stopping the executor.
        // shutdownNow() interrupts workers, and an interrupted worker would
        // otherwise complete its future with "request interrupted" first,
        // hiding the shutdown cause from the caller. Once these futures are
        // settled, the worker's later completion is a no-op; the interrupt
        // still stops the retry loop, so nothing keeps running behind a
        // returned close().
        for (java.util.concurrent.CompletableFuture<RpcResponse> future : inFlight) {
            future.completeExceptionally(
                new IllegalStateException("RpcHttpClient is closed"));
        }
        inFlight.clear();
        try {
            asyncExecutor.shutdownNow();
        } finally {
            http.close();
        }
    }

    /** Waits for in-flight calls to settle, up to the configured grace. */
    private void waitForInFlight() {
        long graceNanos = shutdownGrace.toNanos();
        if (graceNanos <= 0 || inFlight.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + graceNanos;
        while (!inFlight.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // a cancelled shutdown must not keep waiting
            }
        }
        if (!inFlight.isEmpty()) {
            LOG.warn("RpcHttpClient shutdown grace expired with {} call(s) still in flight"
                + " — failing them", inFlight.size());
        }
    }
}
