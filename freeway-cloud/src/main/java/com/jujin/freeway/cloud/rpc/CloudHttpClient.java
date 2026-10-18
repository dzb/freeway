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
 *
 * <p>{@link CloudHttpClientDefault} is the framework default; extension
 * modules replace it by binding their own implementation with
 * {@code .primary()}.</p>
 *
 * <p><b>Replacement must keep the lifecycle.</b> {@link #close()} is part of
 * the role, not of the default: the container closes a managed
 * {@link AutoCloseable} instance, so an implementation bound without
 * {@code close()} would silently lose the in-flight settlement that shutdown
 * depends on (the default waits out {@code rpc.shutdown-grace} before failing
 * unfinished calls).</p>
 */
public interface CloudHttpClient extends AutoCloseable {

    /**
     * Releases the client's resources; idempotent, and a no-op on a client
     * that never left its process. Implementations must settle or fail any
     * call still in flight rather than dropping it.
     */
    @Override
    void close();

    /** Calls {@code serviceId}'s selected instance at {@code request.path()}. */
    CloudResponse call(String serviceId, CloudRequest request) throws CloudException;

    /**
     * Async variant of {@link #call}: the full resilience orchestration
     * (discovery, load balancing, retry, breaker, rate limit) runs as-is —
     * only the final socket wait is lifted off the calling thread. The
     * default bridges the synchronous form, so existing implementations
     * need no changes; {@link CloudHttpClientDefault} overrides it with a
     * virtual-thread based send that keeps the caller's thread free.
     * Completes exceptionally with {@link CloudException}; never returns
     * {@code null}.
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
