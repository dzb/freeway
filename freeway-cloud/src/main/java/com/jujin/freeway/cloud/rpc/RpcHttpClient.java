package com.jujin.freeway.cloud.rpc;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

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
     * ({@code 0} = the client's configured Retryer).
     *
     * <p>An implementation must honor the cap. It must not silently delegate
     * to the unbounded overload: a caller-provided attempt bound is a
     * transport contract, not a hint.</p>
     */
    RpcResponse call(String serviceId, RpcRequest request, int maxAttempts)
            throws RpcException;

    /**
     * Async variant of {@link #call}: the full resilience orchestration
     * (discovery, load balancing, retry, breaker, rate limit) runs as-is —
     * only the final socket wait is lifted off the calling thread. Completes
     * exceptionally with {@link RpcException}; never returns {@code null}.
     *
     * <p>Cancelling the returned future aborts the orchestration: the retry
     * loop (including backoff) is interrupted, the in-flight send is asked to
     * cancel, and the in-flight registration is settled. Cancellation is
     * distinct from a deadline: a deadline fails the call with a timeout,
     * while cancellation means the caller no longer wants the result.</p>
     *
     * <p>The implementation owns this async contract; it may not be left to a
     * generic common-pool bridge because context propagation and cancellation
     * are part of the call semantics.</p>
     */
    CompletableFuture<RpcResponse> callAsync(
        String serviceId, RpcRequest request);

    /**
     * Asynchronous call bounded by an end-to-end deadline across every
     * attempt (not just one socket wait). {@code null} or zero means the
     * client's configured request timeout.
     *
     * <p>An implementation that cannot bound the whole orchestration must
     * fail the call loudly or refuse the declaration at composition time; it
     * must not silently ignore the deadline and fall back to one socket
     * timeout. A caller-provided deadline is a contract.</p>
     */
    CompletableFuture<RpcResponse> callAsync(
        String serviceId, RpcRequest request, Duration deadline);

    /**
     * As {@link #callAsync(String, RpcRequest, Duration)} with a per-call
     * total-attempt cap; {@code 0} means the client default. The attempt cap
     * is a contract, not a hint.
     */
    CompletableFuture<RpcResponse> callAsync(
        String serviceId, RpcRequest request, Duration deadline, int maxAttempts);
}
