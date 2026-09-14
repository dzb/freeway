package com.jujin.freeway.flow;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Default flow driver
 *
 * @author noear
 * @since 3.0
 *
 * <p>Task descriptor resolution rules (consistent with solon-flow):
 * <ul>
 *   <li>Inline TaskComponent/ConditionComponent → executed directly</li>
 *   <li>{@code @beanName} → look up the component from the FlowContainer</li>
 *   <li>{@code #graphId} → run the sub-graph</li>
 *   <li>{@code $metaKey} → read the value from the Graph meta</li>
 *   <li>{@code !markerName} → resolved by marker intersection via {@link FlowMarkerIndex}</li>
 * </ul>
 * <p>Task descriptors only support the prefixes above; bare expressions are only used for conditions ({@code ExprEvaluator}).
 * Unrecognized task descriptors throw {@link FlowException}.
 */
public class FlowDriverDefault implements FlowDriver {
    private static final FlowDriverDefault INSTANCE = new FlowDriverDefault(null, null);

    private final FlowContainer container;
    private final ExecutorService executor;

    public FlowDriverDefault(FlowContainer container, ExecutorService executor) {
        this.container = container;
        this.executor = executor;
    }

    public static FlowDriverDefault instance() {
        return INSTANCE;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for standalone {@link FlowDriverDefault} instances. */
    public static class Builder {
        private FlowContainer container;
        private ExecutorService executor;

        /** Sets the {@link FlowContainer} for {@code @beanName} resolution. */
        public Builder container(FlowContainer container) { this.container = container; return this; }
        /**
         * Sets a custom executor for {@code PARALLEL} node fan-out.
         *
         * <p><b>Pool sizing warning:</b> the parallel join awaits on the
         * calling thread, so a fixed-size pool deadlocks when graphs nest
         * {@code PARALLEL} nodes (outer branches occupy all workers while
         * inner branches sit queued). Use a cached/unbounded executor, or
         * size the pool to at least the worst-case number of concurrently
         * running branches. Branches also share one {@code FlowContext} —
         * see docs/freeway-flow-parallel-context-isolation.md.
         */
        public Builder executor(ExecutorService executor) { this.executor = executor; return this; }
        public FlowDriverDefault build() { return new FlowDriverDefault(container, executor); }
    }

    @Override
    public ExecutorService executor() {
        return executor;
    }

    // --- condition ---

    @Override
    public boolean handleCondition(FlowExchanger exchanger, ConditionDesc condition) throws Throwable {
        return handleConditionDo(exchanger, condition, condition.description());
    }

    protected boolean handleConditionDo(FlowExchanger exchanger, ConditionDesc condition, String description) throws Throwable {
        // inline component
        if (condition.component() != null) {
            return condition.component().test(exchanger.context());
        }

        // @beanName component reference
        if (isComponent(description)) {
            return tryAsComponentCondition(exchanger, description);
        }

        // expression evaluation
        return tryAsExprCondition(exchanger, description);
    }

    protected boolean tryAsComponentCondition(FlowExchanger exchanger, String description) throws Throwable {
        return resolveComponent(description, ConditionComponent.class, "condition").test(exchanger.context());
    }

    protected boolean tryAsExprCondition(FlowExchanger exchanger, String description) {
        return ExprEvaluator.evalCondition(description, exchanger.context().data());
    }

    // --- task ---

    @Override
    public void postHandleTask(FlowExchanger exchanger, TaskDesc task) throws Throwable {
        if (task.isEmpty()) return;
        handleTaskDo(exchanger, task);
    }

    protected void handleTaskDo(FlowExchanger exchanger, TaskDesc task) throws Throwable {
        try {
            // inline component
            if (task.component() != null) {
                task.component().run(exchanger.context(), task.node());
                return;
            }

            // !markerName marker matching
            if (task.isMarkerRef()) {
                tryAsMarkerTask(exchanger, task);
                return;
            }

            // #graphId sub-graph call
            if (isGraph(task.description())) {
                tryAsGraphTask(exchanger, task, task.description());
                return;
            }

            // @beanName component reference
            if (isComponent(task.description())) {
                tryAsComponentTask(exchanger, task, task.description());
                return;
            }

            // $metaKey meta reference
            if (task.description().startsWith("$")) {
                tryAsMetaTask(exchanger, task, task.description());
                return;
            }

            throw new FlowException("Unsupported task description: '" + task.description()
                    + "'. Supported: @beanName, #graphId, $metaKey, !markerName");
        } finally {
            exchanger.context().exchanger(exchanger);
        }
    }

    /**
     * Resolves a task by marker intersection. Uses the engine's
     * {@link FlowMarkerIndex} to find the best-matching
     * {@link TaskComponent} for the required marker set.
     */
    protected void tryAsMarkerTask(FlowExchanger exchanger, TaskDesc task) throws Throwable {
        FlowMarkerIndex index = exchanger.engine().markerIndex();
        TaskComponent handler = index.resolve(task.markerNames());
        if (handler == null) {
            throw new FlowException(
                    "No TaskComponent matches markers " + task.markerNames()
                        + " — annotate the component class with @FlowMarker or register it"
                        + " with explicit markers"
            );
        }
        handler.run(exchanger.context(), task.node());
    }

    protected void tryAsGraphTask(FlowExchanger exchanger, TaskDesc task, String description) throws Throwable {
        String graphId = description.substring(1);
        Graph graph = exchanger.engine().graphOrThrow(graphId);
        exchanger.runGraph(graph);
    }

    protected void tryAsComponentTask(FlowExchanger exchanger, TaskDesc task, String description) throws Throwable {
        resolveComponent(description, TaskComponent.class, "task").run(exchanger.context(), task.node());
    }

    protected void tryAsMetaTask(FlowExchanger exchanger, TaskDesc task, String description) throws Throwable {
        String metaName = description.substring(1);
        Object val = depthMeta(task.node().graph().metas(), metaName);

        if (val instanceof String strVal && !strVal.isEmpty()) {
            // if it is a string, set it as a simple value on the context
            exchanger.context().put("_meta_" + metaName, strVal);
        } else if (val != null) {
            exchanger.context().put("_meta_" + metaName, val);
        } else {
            throw new FlowException("Graph meta not found: " + metaName);
        }
    }

    protected Object depthMeta(Map<String, Object> metas, String key) {
        String[] fragments = key.split("\\.");
        Object rst = null;

        for (int i = 0; i < fragments.length; i++) {
            String key1 = fragments[i];
            if (i == 0) {
                rst = metas.get(key1);
            } else if (rst instanceof Map<?, ?> nested) {
                rst = nested.get(key1);
            } else {
                break;
            }

            if (rst == null) break;
        }

        return rst;
    }

    // --- helpers ---

    /**
     * Resolves an {@code @beanName} description to a component of the required
     * type. Shared by condition and task resolution — they differ only in the
     * error wording (kind) and the target interface.
     */
    protected <T> T resolveComponent(String description, Class<T> type, String kind) throws Throwable {
        String beanName = description.substring(1);
        Object component = container().component(beanName);

        if (component == null) {
            throw new IllegalStateException(
                "No " + kind + " component is bound with id '" + beanName
                    + "' — bind one with binder.bind(" + type.getSimpleName()
                    + ".class).id(\"" + beanName + "\")"
            );
        }
        if (!type.isInstance(component)) {
            throw new IllegalStateException(
                "The component '" + beanName + "' is " + component.getClass().getName()
                    + ", not a " + type.getSimpleName()
                    + " — point the binding at an implementation of " + type.getSimpleName()
            );
        }
        return type.cast(component);
    }

    /**
     * Returns the FlowContainer for {@code @beanName} resolution.
     * Throws a clear error when no container was configured (standalone usage).
     */
    protected FlowContainer container() {
        if (container == null) {
            throw new IllegalStateException(
                "No FlowContainer configured — @beanName task/condition resolution requires one. " +
                "Use FlowDriverDefault.builder().container(...) to supply one, " +
                "or install FlowModule for IoC-based resolution.");
        }
        return container;
    }

    protected boolean isGraph(String description) {
        return description != null && description.startsWith("#");
    }

    protected boolean isComponent(String description) {
        return description != null && description.startsWith("@");
    }
}
