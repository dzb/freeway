package com.jujin.freeway.flow;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流交换器，表示一个流在一次运行时的可交换数据和状态（对内，不支持序列化）
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

    private final Temporary temporary = new Temporary();
    private volatile boolean interrupted = false;
    private volatile boolean stopped = false;
    private volatile boolean reverting = true;

    public FlowExchanger(Graph graph, FlowEngine engine, FlowDriver driver, FlowContext context, int steps, AtomicInteger stepCount) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(context, "context");

        this.graph = graph;
        this.engine = engine;
        this.driver = driver;
        this.context = context;
        this.steps = steps;
        this.stepCount = stepCount;
    }

    public FlowExchanger copy(Graph graphNew) {
        return new FlowExchanger(graphNew, engine, driver, context, steps, stepCount);
    }

    public FlowExchanger copy(Graph graphNew, FlowContext contextNew) {
        return new FlowExchanger(graphNew, engine, driver, contextNew, steps, stepCount);
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

    public void recordClear() {
        context.trace().clear();
    }

    // --- sub-graph ---

    public void runGraph(Graph graph) {
        prveSetp(); // 回退步数（子图调用不算步数）
        engine.eval(graph, copy(graph), null);

        if (!isStopped()) {
            if (!context.trace().isEnd(graph.getId())) {
                interrupt(); // 子图未结束，中断当前分支
            }
        }
    }

    public void runTask(Node node, String description) throws FlowException {
        Objects.requireNonNull(node, "node");
        try {
            engine.getDriver(node.getGraph()).handleTask(this, new TaskDesc(node, description));
        } catch (FlowException e) {
            throw e;
        } catch (Throwable e) {
            throw new FlowException("The task handle failed: " + node.getGraph().getId() + " / " + node.getId(), e);
        }
    }

    // --- step control ---

    public int getSteps() { return steps; }

    public void prveSetp() {
        if (steps >= 0) stepCount.decrementAndGet();
    }

    public boolean nextSetp(Node node) {
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
