package com.jujin.freeway.cloud.rpc;

/**
 * Outbound remote invocation: explicit HTTP calls by logical service name —
 * no method-level RPC in the transport itself; typed remote-call proxies
 * live in {@link RemoteProxyFactory}. The callee is an ordinary Freeway
 * HTTP application (zero cloud code).
 *
 * <p>Call flow: discovery → load-balancer choose → endpoint + path →
 * context header injection → resilience orchestration → JDK
 * {@code HttpClient.send} → {@link RpcResponse}.</p>
 *
 * <p>{@link RpcHttpClientDefault} is the framework default; extension
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
public interface RpcHttpClient extends AutoCloseable {

    /**
     * Releases the client's resources; idempotent, and a no-op on a client
     * that never left its process. Implementations must settle or fail any
     * call still in flight rather than dropping it.
     */
    @Override
    void close();

    /** Calls {@code serviceId}'s selected instance at {@code request.path()}. */
    RpcResponse call(String serviceId, RpcRequest request) throws RpcException;

    /**
     * As {@link #call(String, RpcRequest)} with a per-call total-attempt cap
     * ({@code 0} = the client's configured Retryer). The default ignores the
     * cap so existing implementations keep compiling; the framework default
     * honors it at the resilience loop.
     */
    default RpcResponse call(String serviceId, RpcRequest request, int maxAttempts)
            throws RpcException {
        return call(serviceId, request);
    }

    /**
     * Async variant of {@link #call}: the full resilience orchestration
     * (discovery, load balancing, retry, breaker, rate limit) runs as-is —
     * only the final socket wait is lifted off the calling thread. The
     * default bridges the synchronous form, so existing implementations
     * need no changes; {@link RpcHttpClientDefault} overrides it with a
     * virtual-thread based send that keeps the caller's thread free.
     * Completes exceptionally with {@link RpcException}; never returns
     * {@code null}.
     *
     * <p>Cancelling the returned future should abort the orchestration: the
     * retry loop (including backoff) is interrupted, the in-flight send is
     * asked to cancel, and the in-flight registration is settled.
     * {@link RpcHttpClientDefault} implements that contract; the default
     * implementation below runs a blocking call and can only settle the
     * future, because it has no handle on the running attempt. Transport
     * support is best-effort — the JDK client owns the connection — but the
     * retry loop stops consuming attempts and worker time. Cancellation is
     * distinct from a deadline: a deadline fails the call with a timeout,
     * while cancellation means the caller no longer wants the result.</p>
     */
    default java.util.concurrent.CompletableFuture<RpcResponse> callAsync(
        String serviceId, RpcRequest request) {
        return java.util.concurrent.CompletableFuture.supplyAsync(
            () -> call(serviceId, request));
    }
    /** Asynchronous call bounded by an end-to-end deadline across every
     *  attempt (not just one socket wait). Implementations that cannot bound
     *  the whole orchestration fall back to the per-request timeout. */
    default java.util.concurrent.CompletableFuture<RpcResponse> callAsync(
        String serviceId, RpcRequest request, java.time.Duration deadline) {
        return callAsync(serviceId, request);
    }

    /** As {@link #callAsync(String, RpcRequest, java.time.Duration)} with a
     *  per-call total-attempt cap; {@code 0} means the client default. */
    default java.util.concurrent.CompletableFuture<RpcResponse> callAsync(
        String serviceId, RpcRequest request, java.time.Duration deadline,
        int maxAttempts) {
        return callAsync(serviceId, request, deadline);
    }
}
