package com.jujin.freeway.flow;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单次流交换器。
 *
 * <p>迁移说明：
 * <ul>
 *   <li>把子图调用共享的执行态、上下文和步数计数器集中到一个运行期对象里，避免散落到图结构本身。</li>
 *   <li>{@code reverting}、{@code stopped}、{@code interrupted} 只表示当前执行过程中的控制信号，不写入图定义。</li>
 *   <li>提供 {@code copy()} 以便在子图跳转时复用同一执行态，但切换到不同的 {@link Graph} 或 {@link FlowContext}。</li>
 * </ul>
 * 这样做是为了让执行态可控、可回放，也避免把运行时控制标志污染到模型层。</p>
 *
 * @author noear
 * @since 3.0
 */
public class FlowExchanger {
    private final Graph graph;
    private final FlowEngine engine;
    private final FlowDriver driver;
    private FlowContext context;
    private final int steps;
    private final AtomicInteger stepCount;

    private final Temporary temporary;
    /** Shared recursion depth guard — see {@link FlowEngineImpl#MAX_EXECUTION_DEPTH}. */
    private final AtomicInteger depth;
    private volatile boolean interrupted = false;
    private volatile boolean stopped = false;
    private volatile boolean reverting = true;

    public FlowExchanger(Graph graph, FlowEngine engine, FlowDriver driver, FlowContext context, int steps, AtomicInteger stepCount) {
        this(graph, engine, driver, context, steps, stepCount, new Temporary(), new AtomicInteger());
    }

    private FlowExchanger(Graph graph, FlowEngine engine, FlowDriver driver, FlowContext context, int steps, AtomicInteger stepCount, Temporary temporary, AtomicInteger depth) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(temporary, "temporary");

        this.graph = graph;
        this.engine = engine;
        this.driver = driver;
        this.context = context;
        this.steps = steps;
        this.stepCount = stepCount;
        this.temporary = temporary;
        this.depth = depth;
    }

    public FlowExchanger copy(Graph graphNew) {
        return new FlowExchanger(graphNew, engine, driver, context, steps, stepCount, temporary, depth);
    }

    public FlowExchanger copy(Graph graphNew, FlowContext contextNew) {
        return new FlowExchanger(graphNew, engine, driver, contextNew, steps, stepCount, temporary, depth);
    }

    /** Enters a node — returns the current recursion depth. */
    int enterNode() {
        return depth.incrementAndGet();
    }

    /** Leaves a node. */
    void exitNode() {
        depth.decrementAndGet();
    }

    public Graph graph() { return graph; }
    public FlowEngine engine() { return engine; }
    public FlowDriver driver() { return driver; }
    public FlowContext context() { return context; }
    public Temporary temporary() { return temporary; }

    // --- trace ---

    public void recordNode(Graph graph, Node node) {
        context.trace().recordNode(graph, node);
    }


    // --- sub-graph ---

    public void runGraph(Graph graph) {
        prevStep(); // 回退步数（子图调用不算步数）
        // Resolve the sub-graph's own driver — don't blindly reuse the parent's driver
        FlowExchanger subEx = new FlowExchanger(graph, engine,
            engine.getDriver(graph), context, steps, stepCount, temporary, depth);
        engine.eval(graph, subEx, null);

        if (!isStopped()) {
            if (!context.trace().isEnd(graph.getId())) {
                interrupt(); // 子图未结束，中断当前分支
            }
        }
    }


    // --- step control ---

    public int getSteps() { return steps; }

    public void prevStep() {
        if (steps >= 0) stepCount.decrementAndGet();
    }

    public boolean nextStep(Node node) {
        if (steps < 0) return true;
        return stepCount.incrementAndGet() <= steps;
    }

    // --- stop / interrupt ---

    public boolean isStopped() {
        return stopped || context.isStopped();
    }

    public void stop() {
        stopped = true;
        context.stopped(true);
    }

    public boolean isInterrupted() { return interrupted; }

    public void interrupt() { this.interrupted = true; }

    public void interrupt(boolean interrupted) { this.interrupted = interrupted; }

    // --- reverting ---

    public boolean isReverting() { return reverting; }

    public FlowExchanger reverting(boolean reverting) {
        this.reverting = reverting;
        return this;
    }
}
