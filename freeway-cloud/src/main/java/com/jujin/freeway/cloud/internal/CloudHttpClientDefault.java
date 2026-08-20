package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.Propagator;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
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
 * circuit open, rate limited) fail immediately. Missing resilience bindings
 * degrade to production defaults.
 *
 * <p>Breakers and rate limiters are sharded per {@code serviceId}: one
 * failing service must not poison calls to healthy services. The underlying
 * JDK {@link HttpClient} owns a persistent connection pool and selector
 * thread; {@link #close()} releases it exactly once.
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
    private final HttpClient http;
    /** Per-service shards; null injected values mean each service gets its
     *  own fresh default instance, so one failing service cannot poison the
     *  others. An explicitly injected breaker/limiter is shared (caller's
     *  choice). */
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final Duration DEFAULT_FAILURE_WINDOW = Duration.ofSeconds(60);
    private static final Duration DEFAULT_OPEN_WINDOW = Duration.ofSeconds(30);
    private static final int DEFAULT_RATE_PER_SECOND = 100;

    public CloudHttpClientDefault(ServiceDiscovery discovery, LoadBalancer loadBalancer) {
        this(discovery, loadBalancer, List.of(), null, null, null, null,
            Duration.ofSeconds(10), Duration.ofSeconds(3));
    }

    public CloudHttpClientDefault(ServiceDiscovery discovery, LoadBalancer loadBalancer,
                                  Duration requestTimeout, Duration connectTimeout) {
        this(discovery, loadBalancer, List.of(), null, null, null, null, requestTimeout, connectTimeout);
    }

    public CloudHttpClientDefault(ServiceDiscovery discovery, LoadBalancer loadBalancer,
                                  List<Propagator> propagators,
                                  Retryer retryer, CircuitBreaker breaker, RateLimiter rateLimiter,
                                  TransportSecurity transport,
                                  Duration requestTimeout, Duration connectTimeout) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.loadBalancer = Objects.requireNonNull(loadBalancer, "loadBalancer");
        this.propagators = List.copyOf(propagators);
        this.retryer = retryer != null ? retryer : RetryerDefault.withDefaults();
        // Explicitly injected breakers/limiters act as the shared default for
        // every service; when nothing was injected, each service gets its own
        // fresh default instance (see the computeIfAbsent factories below).
        this.injectedBreaker = breaker;
        this.injectedRateLimiter = rateLimiter;
        this.transport = transport != null ? transport : TransportSecurity.NONE;
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
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        if (closed) {
            throw new IllegalStateException("CloudHttpClient is closed");
        }
        CircuitBreaker breaker = breakers.computeIfAbsent(serviceId, k ->
            injectedBreaker != null ? injectedBreaker
                : new CircuitBreakerDefault(DEFAULT_FAILURE_THRESHOLD,
                    DEFAULT_FAILURE_WINDOW, DEFAULT_OPEN_WINDOW));
        RateLimiter rateLimiter = rateLimiters.computeIfAbsent(serviceId, k ->
            injectedRateLimiter != null ? injectedRateLimiter
                : new RateLimiterDefault(DEFAULT_RATE_PER_SECOND));
        int attempt = 0;
        while (true) {
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
                CloudResponse response = doCall(instance, request);
                breaker.onSuccess();
                return response;
            } catch (CloudException failure) {
                // While probing, every failure is the probe outcome — even
                // local rejections — so the circuit settles instead of
                // wedging in HALF_OPEN. Otherwise only retryable failures
                // count (4xx/local rejections are not service failures).
                if (probe || failure.retryable()) {
                    breaker.onFailure();
                }
                if (!failure.retryable() || !retryer.shouldRetry(attempt, failure)) {
                    throw failure;
                }
                sleepBackoff(retryer.backoffMillis(attempt));
                attempt++;
            }
        }
    }

    private CloudResponse doCall(ServiceInstance instance, CloudRequest request) {
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
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
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
            Thread.currentThread().interrupt();
            throw CloudException.connect(instance.serviceId(), e);
        }
    }

    private static void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CloudException.timeout("backoff interrupted");
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
