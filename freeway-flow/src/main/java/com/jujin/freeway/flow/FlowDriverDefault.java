package com.jujin.freeway.flow;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.MissingBindingException;
import java.util.concurrent.ExecutorService;

/**
 * Default flow driver — resolves the v3 task/condition vocabulary:
 *
 * <ul>
 *   <li>inline {@link TaskComponent}/{@link ConditionComponent} → executed directly</li>
 *   <li>{@code @name} → the container binding {@code (TaskComponent|ConditionComponent, name)}</li>
 *   <li>{@code #graphId} → run the sub-graph</li>
 * </ul>
 *
 * <p>Conditions additionally accept a bare expression (evaluated by
 * {@link ExprEvaluator}); tasks do not. Anything else fails — the vocabulary
 * is closed, and a bad reference is a boot-time graph error, never a runtime
 * guess. Static values are not tasks: nodes carry them in {@code data}.</p>
 */
public final class FlowDriverDefault implements FlowDriver {
    private static final FlowDriverDefault INSTANCE = new FlowDriverDefault(null, null);

    private final Container container;
    private final ExecutorService executor;

    public FlowDriverDefault(Container container, ExecutorService executor) {
        this.container = container;
        this.executor = executor;
    }

    /** Standalone instance: inline components and {@code #graphId} only. */
    public static FlowDriverDefault instance() {
        return INSTANCE;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for standalone {@link FlowDriverDefault} instances. */
    public static final class Builder {
        private Container container;
        private ExecutorService executor;

        /** Sets the container for {@code @name} resolution. */
        public Builder container(Container container) { this.container = container; return this; }

        /**
         * Sets a custom executor for {@code PARALLEL} node fan-out.
         *
         * <p><b>Pool sizing warning:</b> the parallel join awaits on the
         * calling thread, so a fixed-size pool deadlocks when graphs nest
         * {@code PARALLEL} nodes (outer branches occupy all workers while
         * inner branches sit queued). Use a cached/unbounded executor, or
         * size the pool to at least the worst-case number of concurrently
         * running branches.
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
        if (condition.component() != null) {
            return condition.component().test(exchanger.context());
        }
        String description = condition.description();
        if (description != null && description.startsWith("@")) {
            return resolveComponent(description, ConditionComponent.class, "condition")
                .test(exchanger.context());
        }
        return ExprEvaluator.evalCondition(description, exchanger.context().data());
    }

    // --- task ---

    @Override
    public void handleTask(FlowExchanger exchanger, TaskDesc task) throws Throwable {
        if (task.isEmpty()) {
            return;
        }
        if (task.component() != null) {
            task.component().run(exchanger.context(), task.node());
            return;
        }
        String description = task.description();
        if (task.isGraphRef()) {
            exchanger.runGraph(exchanger.engine().graphOrThrow(description.substring(1)));
            return;
        }
        if (task.isComponentRef()) {
            resolveComponent(description, TaskComponent.class, "task")
                .run(exchanger.context(), task.node());
            return;
        }
        throw new IllegalArgumentException(
            "Unsupported task description '" + description + "' on node '"
                + task.node().id() + "' — the vocabulary is @name, #graphId or"
                + " an inline component; static values belong in the node's"
                + " data field");
    }

    /**
     * Resolves an {@code @name} description to a component of the required
     * type. Shared by condition and task resolution — they differ only in
     * the error wording (kind) and the target interface.
     */
    private <T> T resolveComponent(String description, Class<T> type, String kind) {
        String beanName = description.substring(1);
        if (container == null) {
            throw new IllegalStateException(
                "No container configured — @name resolution requires one. "
                    + "Use FlowDriverDefault.builder().container(...), or "
                    + "install FlowModule for IoC-based resolution.");
        }
        Object component;
        try {
            component = container.get(type, beanName);
        } catch (MissingBindingException e) {
            throw new IllegalStateException(
                "No " + kind + " component is bound with id '" + beanName
                    + "' — bind it with binder.bind(" + type.getSimpleName()
                    + ".class).id(\"" + beanName + "\") or contribute it with"
                    + " an explicit id", e);
        }
        return type.cast(component);
    }
}
