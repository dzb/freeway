package com.jujin.freeway.flow;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The graph execution engine: load graphs, evaluate them against a
 * {@link FlowContext}, and resolve each graph's driver by name.
 *
 * <pre>{@code
 * FlowEngine engine = FlowEngine.create();
 * engine.load(Graph.fromText(json));
 * engine.eval("graphId", FlowContext.of());
 * }</pre>
 *
 * <p>The engine is IoC-free: {@code FlowModule} assembles the driver map and
 * the interceptor list from container contributions and hands them to
 * {@link #create(Map, List)}. Nothing here can be mutated after
 * construction — what an evaluation runs is decided once, at startup.</p>
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

    // --- driver ---

    FlowDriver driver(Graph graph);

    // --- graph management ---

    void load(Graph graph);

    default void load(GraphSpec blueprint) {
        load(blueprint.create());
    }

    void unload(String graphId);

    Collection<Graph> graphs();

    Graph graph(String graphId);

    default Graph graphOrThrow(String graphId) {
        Graph graph = graph(graphId);
        if (graph == null) {
            throw new FlowException("Flow graph not found: " + graphId);
        }
        return graph;
    }

    // --- eval ---

    default void eval(String graphId) throws FlowException {
        eval(graphOrThrow(graphId), FlowContext.of());
    }

    default void eval(String graphId, FlowContext context) throws FlowException {
        eval(graphOrThrow(graphId), context);
    }

    default void eval(Graph graph) throws FlowException {
        eval(graph, FlowContext.of());
    }

    /** Evaluates the graph against a fresh context and returns its data. */
    default Map<String, Object> evalAndGet(Graph graph) {
        FlowContext context = FlowContext.of();
        eval(graph, context);
        return context.data();
    }

    default void eval(GraphSpec blueprint) throws FlowException {
        eval(blueprint.create());
    }

    default void eval(GraphSpec blueprint, FlowContext context) throws FlowException {
        eval(blueprint.create(), context);
    }

    /**
     * Evaluates a loaded graph. A fresh evaluation reaches the graph's END
     * node or fails: an EXCLUSIVE gateway that matches nothing or a join that
     * never assembles throws instead of completing silently. A run stopped
     * on purpose (by a task or interceptor calling {@link FlowContext#stop()})
     * is a legal early end.
     */
    void eval(Graph graph, FlowContext context) throws FlowException;

    /**
     * @hidden Continues an in-flight evaluation on another graph — the entry
     *  {@link FlowEvaluation#runGraph} uses so the sub-run shares the parent's
     *  join state. Applications call {@link #eval(Graph, FlowContext)}.
     */
    void eval(Graph graph, FlowEvaluation evaluation) throws FlowException;
}
