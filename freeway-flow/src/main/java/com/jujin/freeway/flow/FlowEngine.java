package com.jujin.freeway.flow;

import java.util.Collection;
import com.jujin.freeway.commons.util.ContextCarrier;
import java.util.Optional;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.Map;

/**
 * The graph execution engine: load graphs, evaluate them against a
 * {@link FlowContext}, and resolve each graph's driver by name.
 *
 * <pre>{@code
 * FlowEngine engine = FlowEngine.create();
 * engine.load(Graph.fromText(json));
 * engine.run("graphId", FlowContext.of());
 * }</pre>
 *
 * <p>The engine is IoC-free: {@code FlowModule} assembles the driver map and
 * the interceptor list from container contributions and hands them to
 * {@link #create(Map, List)}. Nothing here can be mutated after
 * construction — what an execution runs is decided once, at startup.</p>
 */
public interface FlowEngine {

    /**
     * Creates an engine with only the built-in default driver. Suitable for
     * standalone use where {@code @beanName} resolution is not needed (the
     * default driver has no container). For IoC-based applications, let
     * {@code FlowModule} build the engine.
     */
    static FlowEngine create() {
        return new FlowEngineDefault(Map.of("default", FlowDriverDefault.instance()), List.of());
    }

    static FlowEngine create(Map<String, FlowDriver> drivers) {
        return create(drivers, List.of());
    }

    /**
     * Creates an engine with the given drivers and interceptors. The id
     * {@code "default"} (or a contributed override) serves graphs without an
     * explicit driver. Interceptors run in list order (the extension chain
     * of the same name is topologically ordered by the container) and wrap
     * every {@code eval} — including sub-graph calls, which share the run.
     */
    static FlowEngine create(Map<String, FlowDriver> drivers, List<FlowInterceptor> interceptors) {
        return new FlowEngineDefault(drivers, interceptors);
    }

    /**
     * Creates an engine whose executions also forward every
     * {@link FlowContext#eventBus()} publish to {@code eventSink}, in addition
     * to the execution's own subscribers. This is the core's exit: it knows
     * nothing about a container, so the container face ({@code FlowModule})
     * supplies a sink that publishes on the application's {@code EventBus}
     * under {@code "flow." + topic}. A standalone engine passes nothing and
     * keeps events inside the execution.
     */
    static FlowEngine create(Map<String, FlowDriver> drivers,
            List<FlowInterceptor> interceptors,
            BiConsumer<String, Object> eventSink) {
        return new FlowEngineDefault(drivers, interceptors, eventSink);
    }

    /**
     * Creates an engine whose parallel branches run under {@code carrier}.
     * The carrier captures the submitting thread's ambient work context
     * (trace, principal, MDC, ...) and restores it in each branch worker, so
     * a branch logs and calls out with the same context as inline code.
     * A {@code null} carrier keeps the worker's ambient state untouched.
     */
    static FlowEngine create(Map<String, FlowDriver> drivers,
            List<FlowInterceptor> interceptors,
            BiConsumer<String, Object> eventSink,
            ContextCarrier carrier) {
        return new FlowEngineDefault(drivers, interceptors, eventSink, carrier);
    }

    // --- driver ---

    FlowDriver driver(Graph graph);

    // --- graph management ---

    void load(Graph graph);

    default void load(GraphSpec blueprint) {
        load(blueprint.create());
    }

    void unload(String graphId);

    Collection<Graph> graphs();

    Optional<Graph> find(String graphId);

    default Graph require(String graphId) {
        return find(graphId).orElseThrow(
            () -> new FlowException("Flow graph not found: " + graphId));
    }

    // --- eval ---

    default void run(String graphId) throws FlowException {
        run(require(graphId), FlowContext.of());
    }

    default void run(String graphId, FlowContext context) throws FlowException {
        run(require(graphId), context);
    }

    default void run(Graph graph) throws FlowException {
        run(graph, FlowContext.of());
    }

    /** Evaluates the graph against a fresh context and returns its data. */
    default Map<String, Object> runAndGet(Graph graph) {
        FlowContext context = FlowContext.of();
        run(graph, context);
        return context.data();
    }

    default void run(GraphSpec blueprint) throws FlowException {
        run(blueprint.create());
    }

    default void run(GraphSpec blueprint, FlowContext context) throws FlowException {
        run(blueprint.create(), context);
    }

    /**
     * Runs a loaded graph. A fresh execution reaches the graph's END
     * node or fails: an EXCLUSIVE gateway that matches nothing or a join that
     * never assembles throws instead of completing silently. A run stopped
     * on purpose (by a task or interceptor calling {@link FlowContext#stop()})
     * is a legal early end.
     */
    void run(Graph graph, FlowContext context) throws FlowException;

    /**
     * @hidden Continues an in-flight execution on another graph — the entry
     *  {@link FlowExecution#runGraph} uses so the sub-run shares the parent's
     *  join state. Applications call {@link #run(Graph, FlowContext)}.
     */
    void run(Graph graph, FlowExecution execution) throws FlowException;
}
