package com.jujin.freeway.flow;

import com.jujin.freeway.flow.v2.GraphSpec2;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流引擎（通用图编排引擎）
 *
 * <pre>{@code
 * FlowEngine engine = FlowEngine.newInstance();
 * engine.load(Graph.fromText(json));
 * engine.eval("graphId", FlowContext.of());
 * }</pre>
 *
 * @author noear
 * @since 3.0
 */
public interface FlowEngine {

    /**
     * Creates an engine with only the built-in default driver. Suitable for
     * simple standalone use where {@code @beanName} task resolution is not
     * needed (the default driver has no {@link FlowContainer}). For IoC-based
     * applications, let {@code FlowModule} build the engine with a proper
     * container and contributed drivers.
     */
    static FlowEngine newInstance() {
        return new FlowEngineImpl(Map.of("default", FlowDriverDefault.getInstance()));
    }

    /**
     * Creates an engine with the given driver map. Id {@code "default"}
     * (or a contributed override) is used when a graph has no explicit
     * driver or {@code driver=""}. {@code FlowModule} uses this entry
     * point after assembling drivers from contributions.
     */
    static FlowEngine newInstance(Map<String, FlowDriver> drivers) {
        return new FlowEngineImpl(drivers);
    }

    // --- driver ---

    FlowDriver getDriver(Graph graph);

    // --- task component ---

    /**
     * Register a task handler in the marker index for {@code !markerName} resolution.
     */
    void register(TaskComponent handler);

    // --- interceptor ---

    void addInterceptor(FlowInterceptor interceptor, int index);

    default void addInterceptor(FlowInterceptor interceptor) {
        addInterceptor(interceptor, 0);
    }

    void removeInterceptor(FlowInterceptor interceptor);

    // --- graph management ---
    // GraphSpec2 is the v2 authoring surface. These overloads keep the
    // runtime API backward-compatible while letting callers load/eval blueprints
    // directly during migration.

    void load(Graph graph);

    default void load(GraphSpec2 blueprint) {
        load(blueprint.create());
    }

    void unload(String graphId);

    Collection<Graph> getGraphs();

    Graph getGraph(String graphId);

    default Graph getGraphOrThrow(String graphId) {
        Graph graph = getGraph(graphId);
        if (graph == null) {
            throw new FlowException("Flow graph not found: " + graphId);
        }
        return graph;
    }

    // --- eval by graphId ---

    default void eval(String graphId) throws FlowException {
        eval(graphId, -1, FlowContext.of());
    }

    default void eval(String graphId, FlowContext context) throws FlowException {
        eval(graphId, -1, context);
    }

    default void eval(String graphId, int steps, FlowContext context) throws FlowException {
        Graph graph = getGraphOrThrow(graphId);
        eval(graph, steps, context);
    }

    // --- eval by graph ---

    default void eval(Graph graph) throws FlowException {
        eval(graph, FlowContext.of());
    }

    default void eval(Graph graph, FlowContext context) throws FlowException {
        eval(graph, -1, context);
    }

    default void eval(Graph graph, int steps, FlowContext context) throws FlowException {
        FlowDriver driver = getDriver(graph);
        eval(graph, new FlowExchanger(graph, this, driver, context, steps, new AtomicInteger(0)), null);
    }

    default void eval(GraphSpec2 blueprint) throws FlowException {
        eval(blueprint, FlowContext.of());
    }

    default void eval(GraphSpec2 blueprint, FlowContext context) throws FlowException {
        eval(blueprint, -1, context);
    }

    default void eval(GraphSpec2 blueprint, int steps, FlowContext context) throws FlowException {
        eval(blueprint.create(), steps, context);
    }

    /**
     * Returns the marker index for resolving tasks by {@code !markerName}
     * references. Populated automatically when modules contribute
     * {@link TaskComponent} instances annotated with {@link FlowMarker}.
     */
    FlowMarkerIndex markerIndex();

    // --- internal ---

    void eval(Graph graph, FlowExchanger exchanger, FlowOptions options) throws FlowException;
}
