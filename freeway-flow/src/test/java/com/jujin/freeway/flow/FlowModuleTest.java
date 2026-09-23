package com.jujin.freeway.flow;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.Container;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The FlowModule wiring: a container-assembled engine, contributed
 * drivers overriding the built-in default, and contributed interceptors
 * chained into every eval — all decided at load, none mutable after.
 */
class FlowModuleTest {

    /** A task handler the driver resolves via {@code @worker}. */
    public static final class WorkerTask implements TaskHandler {
        @Override
        public void run(FlowContext context, Node node) {
            context.put("worked", true);
        }
    }

    /** A driver that stamps the context with its own label. */
    public static final class LabelDriver implements FlowDriver {
        @Override
        public boolean handleCondition(FlowEvaluation evaluation, ConditionDesc condition) {
            return true;
        }

        @Override
        public void handleTask(FlowEvaluation evaluation, TaskDesc task) {
            if (!task.isEmpty()) evaluation.context().put("driver", "label");
        }
    }

    private static Graph simpleGraph(String task, String driver) {
        return GraphSpec.create("g", "", driver, s -> {
            s.entry("s");
            s.addStart("s").linkAdd("a");
            s.addActivity("a").task(task).linkAdd("e");
            s.addEnd("e");
        }).create();
    }

    @Test
    void contributedTaskResolvesByNameThroughContainer() {
        Container container = Freeway.create(new FlowModule(), binder ->
            binder.bind(WorkerTask.class).id("worker").to(WorkerTask.class));

        FlowEngine engine = container.get(FlowEngine.class);
        engine.load(simpleGraph("@worker", null));
        FlowContext ctx = FlowContext.of();
        engine.eval("g", ctx);
        assertEquals(Boolean.TRUE, ctx.get("worked"));
    }

    @Test
    void contributedDriverOverridesTheBuiltInDefault() {
        Container container = Freeway.create(new FlowModule(), binder ->
            binder.contribute(FlowDriver.class).add("default", new LabelDriver()));

        FlowEngine engine = container.get(FlowEngine.class);
        engine.load(simpleGraph("@anything", null));
        FlowContext ctx = FlowContext.of();
        engine.eval("g", ctx);
        assertEquals("label", ctx.get("driver"),
            "the contributed default must replace FlowDriverDefault (warn-override contract)");
    }

    @Test
    void contributedInterceptorChainsIntoEveryEval() {
        AtomicInteger starts = new AtomicInteger(0);
        Container container = Freeway.create(new FlowModule(), binder -> {
            binder.bind(TaskHandler.class).to(c -> new WorkerTask()).id("anything");
            binder.contribute(FlowInterceptor.class).add("counter", new FlowInterceptor() {
                @Override
                public void onNodeStart(FlowContext ctx, Node node) {
                    starts.incrementAndGet();
                }
            });
        });

        FlowEngine engine = container.get(FlowEngine.class);
        engine.load(simpleGraph("@anything", null));
        engine.eval("g", FlowContext.of());

        // s, a, e each get a node-start: three visits for the simple graph.
        assertEquals(3, starts.get(),
            "the contributed interceptor must observe every node");
    }

    @Test
    void engineSingletonIsSharedAndIoCFreeInternally() {
        Container container = Freeway.create(new FlowModule());
        FlowEngine first = container.get(FlowEngine.class);
        FlowEngine second = container.get(FlowEngine.class);
        assertSame(first, second, "the engine is a container singleton");
        assertNotNull(first.driver(simpleGraph("@x", null)),
            "the default driver resolves against the container");
    }
}
