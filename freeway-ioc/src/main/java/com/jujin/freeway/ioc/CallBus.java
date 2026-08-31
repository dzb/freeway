package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.ioc.internal.CallAdviceChain;
import com.jujin.freeway.ioc.internal.CallProxyFactory;
import com.jujin.freeway.ioc.internal.CallStats;
import com.jujin.freeway.ioc.internal.CallTargetRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Topic-addressable request-reply calls over string topics — the asking
 * counterpart of {@link EventBus}'s broadcast. A provider registers an
 * object whose public methods become call targets
 * ({@code topicMapping + "." + methodName}); a consumer either invokes
 * {@link #call} directly or injects a typed interface proxy from
 * {@link #consumer}. Both sides agree on the method shape; neither needs a
 * compile-time dependency on the other's module.
 *
 * <p><b>Arguments are positional</b> ({@code List<Object>}), not name-keyed:
 * it keeps the wire contract independent of compiler {@code -parameters}
 * flags. Method overloads are rejected at registration time — one method
 * name per topic, so reordering parameters is the only silent hazard and is
 * covered by contract tests.</p>
 *
 * <p><b>Error semantics differ from broadcast:</b> {@link EventBus} isolates
 * throwing subscribers (best-effort delivery); a failing handler here must
 * reach the caller. The stage carries the original throwable — advices see
 * business exceptions too. {@code join()} and {@code get()} then apply the
 * standard {@link CompletableFuture} wrapping: RuntimeExceptions as-is,
 * checked ones inside {@link CompletionException}/{@code ExecutionException}.
 * A call with no handler fails with
 * {@link DeadCallException}; consumer proxies fall back to interface default
 * methods in that case (graceful degradation without a provider).</p>
 *
 * <p><b>Transactions:</b> calls dispatch inline, inside whatever context
 * the caller is in — within a DB transaction a call behaves exactly like
 * a local method call: it sees the mid-transaction world and its reply is
 * consumed now. Side effects that must wait for commit are facts, not
 * calls — publish them on the EventBus, whose Defer buffering drains
 * post-commit and discards on rollback. Truly deferred calls remain
 * expressible explicitly: {@code Defer.defer(() -> bus.call(...))}.</p>
 *
 * <p><b>Providers are slots, not lists:</b> registering a target replaces
 * any previous handler for the same method topic (hot swap, O(1) dispatch,
 * no ordering ambiguity). One provider per mapping is the intended shape.</p>
 *
 * <p><b>Topic grammar separates the two namespaces:</b> broadcast topics
 * (see {@link EventBus#publish}) are past-tense facts ({@code user.created});
 * call topics are imperative {@code mapping.methodName} pairs
 * ({@code user.getUser}) — a shape enforced simply by how providers
 * register. Keep the grammars apart and the two worlds cannot tangle.</p>
 *
 * <p>Calls are strictly local: payloads carry an in-JVM reply future and are
 * never bridged to external MQ. For remote invocation see freeway-cloud.</p>
 */
public final class CallBus implements AutoCloseable {

    private final CallStats stats;
    /** Handler slot per fully-qualified method topic (last registration wins). */
    private final CallTargetRegistry targets = new CallTargetRegistry();
    /** Call-chain advices in registration order; last link is raw dispatch. */
    private final CallAdviceChain adviceChain = new CallAdviceChain();
    /** Calls dispatched but not yet completed — failed explicitly on close. */
    private final ConcurrentLinkedQueue<CompletableFuture<Object>> pending =
        new ConcurrentLinkedQueue<>();
    private volatile boolean closed;

    /**
     * Creates a call bus observing the container's {@link Metrics} builtin.
     */
    public CallBus(Container container) {
        Objects.requireNonNull(container, "container");
        this.stats = new CallStats(container.get(Metrics.class));
    }

    // ==================== consumer side ====================

    /**
     * Creates a typed consumer proxy for the given interface: every method
     * invocation becomes a synchronous call to
     * {@code topicMapping + "." + methodName}, positional arguments carried
     * as-is. The proxy blocks until the reply arrives (cheap on virtual
     * threads); for async or timeout-bounded calls use {@link #call}
     * directly.
     *
     * <p>If no handler exists and the method has a default implementation,
     * the default runs instead — degradation without a provider. Proxy
     * instances are cheap but stateless; bind once via
     * {@code binder.bind(Api.class).to(c -> c.get(CallBus.class).consumer("user", Api.class))}
     * rather than creating one per call site.</p>
     *
     * @param topicMapping topic prefix shared with the provider
     * @param api          consumer-side interface (must be an interface)
     */
    public <T> T consumer(String topicMapping, Class<T> api) {
        requireOpen();
        return CallProxyFactory.INSTANCE.create(this, topicMapping, api);
    }

    // ==================== call ====================

    /**
     * Calls the no-argument handler registered for {@code topic}.
     *
     * @param topic fully-qualified call topic (typically produced by a
     *              consumer proxy)
     */
    public CompletableFuture<Object> call(String topic) {
        return beginCall(topic, null, null);
    }

    /**
     * Calls the handler registered for {@code topic} and returns a stage
     * completing with its result. See the class javadoc for error and
     * transaction semantics.
     *
     * @param topic   fully-qualified call topic (typically produced by a
     *                consumer proxy)
     * @param payload positional arguments; {@code null} means no arguments
     */
    public CompletableFuture<Object> call(String topic, List<?> payload) {
        return beginCall(topic, payload, null);
    }

    /**
     * As {@link #call(String, List)}, bounding the reply: the returned
     * stage completes exceptionally with {@code TimeoutException} if the
     * handler has not produced a result within {@code timeout}. The clock
     * starts at invocation — before dispatch — so it also covers handlers
     * that block the calling thread; their eventual late results are
     * silently discarded once the stage has timed out. For manually
     * deferred calls ({@code Defer.defer(...)}) the budget likewise keeps
     * running from the enqueue point — a call held back by a long
     * transaction may expire before it ever drains. Treat the deadline as
     * the caller's patience, not the handler's allowance.
     */
    public CompletableFuture<Object> call(
        String topic,
        List<?> payload,
        Duration timeout
    ) {
        Objects.requireNonNull(timeout, "timeout");
        return beginCall(topic, payload, timeout);
    }

    private CompletableFuture<Object> beginCall(
        String topic,
        List<?> payload,
        Duration timeout
    ) {
        requireOpen();
        Objects.requireNonNull(topic, "topic"); // fail at the call site
        CompletableFuture<Object> sink = new CompletableFuture<>();
        if (timeout != null) {
            // Attach the budget BEFORE dispatch: a handler that blocks this
            // very thread must still hit the deadline.
            sink.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        pending.add(sink);
        sink.whenComplete((r, t) -> pending.remove(sink));
        stats.called();

        // Dispatch inline — always, Defer scope or not: a call is a
        // question asked now. Its reply is typically consumed inline (the
        // proxy joins immediately), so buffering until commit would
        // deadlock the very transaction it came from. Within a DB
        // transaction a call therefore behaves exactly like a local method
        // call. Post-commit side effects are facts — publish them on the
        // EventBus, whose Defer buffering exists precisely for
        // fire-and-forget. Truly deferred calls stay expressible
        // explicitly: Defer.defer(() -> bus.call(...)).
        dispatch(topic, payload, sink);
        return sink;
    }

    private void dispatch(String topic, List<?> payload, CompletableFuture<Object> sink) {
        if (closed) {
            sink.completeExceptionally(new IllegalStateException("CallBus is closed"));
            return;
        }
        try {
            Object result = adviceChain.invoke(topic, payload, targets, stats);
            // Counted inside the terminal link: an advice short-circuit is
            // a completed call but not a served one.
            sink.complete(result);
        } catch (DeadCallException e) {
            // Already counted at lookup; advices may observe or translate it.
            sink.completeExceptionally(e);
        } catch (Throwable t) {
            // Advice failures and handler failures reach the caller; the
            // JDK wraps non-Completion types per join()/get() (Completion-
            // Exception / ExecutionException), keeping one unwrapping rule.
            stats.failed();
            sink.completeExceptionally(t);
        }
    }

    // ==================== provider side ====================

    /**
     * Registers every eligible public method of {@code target} as the handler
     * for {@code topicMapping + "." + methodName}. Eligible methods exclude
     * static, synthetic and {@code Object}-declared members; two public
     * methods sharing a name (overloads) fail fast — the positional contract
     * cannot disambiguate them.
     *
     * <p>Registering over an existing method topic replaces the previous
     * handler (hot swap). {@link #close} drops all registrations.</p>
     *
     * <p><b>Registration timing:</b> a provider bound as a lazy service
     * registers only when something resolves it — a listener nobody injects
     * never registers, and its topics answer dead. For eager startup
     * registration, contribute a {@link RuntimeHook}:
     * <pre>{@code
     * binder.contribute(RuntimeHook.class).add("user.rpc", new RuntimeHook() {
     *     public void start(Container c) {
     *         c.get(CallBus.class).register("user", c.get(UserListener.class));
     *     }
     * });
     * }</pre></p>
     *
     * @param topicMapping topic prefix shared with the consumer
     * @param target       provider object
     */
    public void register(String topicMapping, Object target) {
        requireOpen();
        Objects.requireNonNull(topicMapping, "topicMapping");
        Objects.requireNonNull(target, "target");
        targets.register(topicMapping, target);
    }

    /**
     * Removes the method handlers that {@link #register(String, Object)}
     * registered for the same {@code topicMapping}/{@code target} pair.
     * Handlers registered by other targets are left intact.
     */
    public void unregister(String topicMapping, Object target) {
        Objects.requireNonNull(topicMapping, "topicMapping");
        Objects.requireNonNull(target, "target");
        targets.unregister(topicMapping, target);
    }

    /** Returns true if some handler is currently registered for {@code topic}. */
    public boolean handles(String topic) {
        return targets.handles(topic);
    }

    // ==================== stats / lifecycle ====================

    /**
     * Immutable snapshot of cumulative counters.
     *
     * @param called call attempts (including deferred, not yet drained)
     * @param served successful handler executions (advice short-circuits
     *               are excluded)
     * @param failed handler or advice executions that threw
     * @param dead   calls with no registered handler
     */
    public record CallBusStats(long called, long served, long failed, long dead) {}

    /** Snapshot of cumulative dispatch counters, mirroring {@link EventBus#stats}. */
    public CallBusStats stats() {
        return new CallBusStats(
            stats.calledCount(), stats.servedCount(), stats.failedCount(), stats.deadCount());
    }

    /**
     * Closes the bus: further calls fail fast, all registrations are dropped,
     * and calls dispatched but not yet drained complete exceptionally with
     * {@link IllegalStateException} so no caller waits forever on shutdown.
     *
     * <p>The counterpart {@link EventBus} takes the opposite stance on
     * purpose: post-close broadcasts are silent no-ops, because a fact nobody
     * consumes must not abort shutdown — while an unanswered question here is
     * a caller actively blocked on the reply.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        targets.clear();
        adviceChain.clear();
        for (CompletableFuture<Object> stage : List.copyOf(pending)) {
            stage.completeExceptionally(
                new IllegalStateException("CallBus is closed"));
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("CallBus is closed");
        }
    }

    // ==================== call-chain advice ====================

    /**
     * Attaches an around-advice to the call chain: tracing spans, audit
     * logs, circuit breakers, response caches — cross-cutting policies that
     * must not live inside providers or consumers. Advices run in
     * registration order; the last implicit link performs the actual
     * handler dispatch.
     *
     * <p><b>Value space:</b> advices see and return plain results, never
     * stages. Returning without {@link CallChain#proceed()} short-circuits:
     * a returned value becomes the call result (e.g. a cache hit), a thrown
     * exception fails it (e.g. an open breaker). The stage carries the
     * original throwable, so callers unwrap it exactly like handler
     * failures.</p>
     *
     * <p><b>Timing:</b> advice wraps each dispatch, and dispatch is inline
     * and immediate — including inside transactions (see the transactions
     * note in the class javadoc). Manually deferred calls
     * ({@code Defer.defer(() -> bus.call(...))}) run their whole chain at
     * drain time.</p>
     *
     * <p>Short-circuited calls count in {@link #stats()} as called only,
     * not served/dead/failed-by-handler.</p>
     *
     * @param advice the advice to append to the chain
     */
    public void advise(CallAdvice advice) {
        advise(topic -> true, advice);
    }

    /**
     * As {@link #advise(CallAdvice)}, scoped to a topic subset: the
     * selector is tested against each dispatched topic, so breakers,
     * caches and tracing can target one mapping without filtering inside
     * the advice body.
     *
     * @param topics matches the topics this advice wraps
     * @param advice the advice to append to the chain
     */
    public void advise(Predicate<String> topics, CallAdvice advice) {
        requireOpen();
        Objects.requireNonNull(topics, "topics");
        Objects.requireNonNull(advice, "advice");
        adviceChain.add(topics, advice);
    }

    /**
     * Represents one call travelling the advice chain. Calling
     * {@link #proceed()} continues toward the registered handler; skipping
     * it ends the call with whatever the advice returns or throws.
     */
    public interface CallChain {

        /** The fully-qualified call topic. */
        String topic();

        /** The positional arguments as sent by the caller (may be null). */
        Object payload();

        /**
         * Continues with the next advice or the handler itself.
         *
         * @return the call result
         * @throws Throwable if a later advice or the handler fails
         */
        Object proceed() throws Throwable;
    }

    /**
     * Around-advice over the call chain — the message-domain counterpart of
     * {@code MethodAdvice} on IoC bindings.
     *
     * @see CallBus#advise(CallAdvice)
     */
    @FunctionalInterface
    public interface CallAdvice {

        /**
         * Wraps one dispatch attempt. Call {@link CallChain#proceed()} to
         * continue the chain; return without proceeding to answer the call
         * directly, or throw to fail it.
         *
         * @param chain the call in flight
         * @return the value the caller receives (normally
         *         {@code chain.proceed()})
         * @throws Throwable if the advice or downstream fails
         */
        Object around(CallChain chain) throws Throwable;
    }

}
