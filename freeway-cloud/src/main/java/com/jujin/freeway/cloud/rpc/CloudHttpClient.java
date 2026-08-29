package com.jujin.freeway.cloud.rpc;

/**
 * Outbound remote invocation: explicit HTTP calls by logical service name —
 * no method-level RPC in the transport itself; typed remote-call proxies
 * live in {@link RemoteProxyFactory}. The callee is an ordinary Freeway
 * HTTP application (zero cloud code).
 *
 * <p>Call flow: discovery → load-balancer choose → endpoint + path →
 * context header injection → resilience orchestration → JDK
 * {@code HttpClient.send} → {@link CloudResponse}.</p>
 */
public interface CloudHttpClient {

    /** Calls {@code serviceId}'s selected instance at {@code request.path()}. */
    CloudResponse call(String serviceId, CloudRequest request) throws CloudException;

    /**
     * Async variant of {@link #call}: the full resilience orchestration
     * (discovery, load balancing, retry, breaker, rate limit) runs as-is —
     * only the final socket wait is lifted off the calling thread. The
     * default bridges the synchronous form, so existing implementations
     * need no changes; {@link CloudHttpClientDefault} overrides it with a
     * true non-blocking send. Completes exceptionally with
     * {@link CloudException}; never returns {@code null}.
     */
    default java.util.concurrent.CompletableFuture<CloudResponse> callAsync(
        String serviceId, CloudRequest request) {
        return java.util.concurrent.CompletableFuture.supplyAsync(
            () -> call(serviceId, request));
    }
    /** Asynchronous call bounded by an end-to-end deadline across every
     *  attempt (not just one socket wait). Implementations that cannot bound
     *  the whole orchestration fall back to the per-request timeout. */
    default java.util.concurrent.CompletableFuture<CloudResponse> callAsync(
        String serviceId, CloudRequest request, java.time.Duration deadline) {
        return callAsync(serviceId, request);
    }
}
