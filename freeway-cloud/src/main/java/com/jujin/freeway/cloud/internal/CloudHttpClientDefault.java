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

import javax.net.ssl.SSLContext;

/**
 * JDK {@link HttpClient}-backed {@link CloudHttpClient} with resilience
 * orchestration:
 *
 * <pre>
 * breaker.allowRequest() → rateLimiter.tryAcquire()
 *   → discovery.getInstances → loadBalancer.choose (RE-CHOSEN on every retry)
 *   → context propagation → send (virtual-thread friendly)
 *   → onSuccess / onFailure (retryable failures only) → retry with backoff
 * </pre>
 *
 * Retryable = connect/timeout/5xx; 4xx and local rejections (no instance,
 * circuit open, rate limited) fail immediately. Missing resilience bindings
 * degrade to production defaults.
 */
public final class CloudHttpClientDefault implements CloudHttpClient {

    private final ServiceDiscovery discovery;
    private final LoadBalancer loadBalancer;
    private final Duration requestTimeout;
    private final List<Propagator> propagators;
    private final Retryer retryer;
    private final CircuitBreaker breaker;
    private final RateLimiter rateLimiter;
    private final TransportSecurity transport;
    private final HttpClient http;

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
        this.breaker = breaker != null ? breaker : new CircuitBreakerDefault(5,
            Duration.ofSeconds(60), Duration.ofSeconds(30));
        this.rateLimiter = rateLimiter != null ? rateLimiter : new RateLimiterDefault(100);
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
        int attempt = 0;
        while (true) {
            if (!breaker.allowRequest()) {
                throw CloudException.circuitOpen(serviceId);
            }
            if (!rateLimiter.tryAcquire()) {
                throw CloudException.rateLimited(serviceId);
            }
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
                if (failure.retryable()) {
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
        String url = instance.endpoint().uri().toString() + request.path();
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
            throw CloudException.timeout(instance.serviceId());
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
}
