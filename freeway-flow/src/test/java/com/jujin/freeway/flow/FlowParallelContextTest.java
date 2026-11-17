package com.jujin.freeway.flow;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.util.ContextCarrier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Parallel branch workers inherit the submitting thread's ambient context
 * through the same {@link ContextCarrier} the event bus uses; without it a
 * branch runs bare and loses trace/principal/MDC.
 */
class FlowParallelContextTest {

    @Test
    void parallelBranchesInheritTheSubmittingContext() {
        ThreadLocal<String> ambient = new ThreadLocal<>();
        ContextCarrier carrier = work -> {
            String captured = ambient.get();
            return () -> {
                String previous = ambient.get();
                ambient.set(captured);
                try {
                    work.run();
                } finally {
                    if (previous == null) {
                        ambient.remove();
                    } else {
                        ambient.set(previous);
                    }
                }
            };
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<String> seen = new CopyOnWriteArrayList<>();
        FlowDriver driver = new FlowDriver() {
            @Override
            public boolean handleCondition(FlowExecution execution, ConditionDesc condition) {
                return true;
            }

            @Override
            public void handleTask(FlowExecution execution, TaskDesc task) {
                seen.add(task.node().id() + "=" + ambient.get()
                    + "@" + Thread.currentThread().getName());
            }

            @Override
            public ExecutorService executor() {
                return executor;
            }
        };
        Graph graph = GraphSpec.create("context", spec -> {
            spec.entry("s");
            spec.addStart("s").linkTo("p");
            spec.addParallel("p").linkTo("a").linkTo("b");
            spec.addActivity("a").task("@noop").linkTo("j");
            spec.addActivity("b").task("@noop").linkTo("j");
            spec.addInclusive("j").linkTo("e");
            spec.addEnd("e");
        }).create();
        FlowEngine engine = FlowEngine.create(
            Map.of("default", driver), List.of(), null, carrier);
        engine.load(graph);

        ambient.set("request");
        try {
            engine.run(graph, FlowContext.of());
        } finally {
            ambient.remove();
            executor.shutdownNow();
        }

        assertTrue(seen.stream().anyMatch(entry -> entry.startsWith("a=request@")),
            "branch a lost the submitting context: " + seen);
        assertTrue(seen.stream().anyMatch(entry -> entry.startsWith("b=request@")),
            "branch b lost the submitting context: " + seen);
    }

    @Test
    void aTaskThreadThatEscapesTheForkIsRefusedInsteadOfWritingTheRoot() {
        // The frame follows the work, not the thread: a branch task that starts
        // its own thread loses it. That thread's write used to land in the root
        // map with no buffer, no conflict check and no error, so the fork's
        // isolation disappeared silently. The write is now refused on the
        // thread that attempted it, and the value never reaches the root.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> fromOwnThread = new AtomicReference<>();
        FlowDriver driver = new FlowDriver() {
            @Override
            public boolean handleCondition(FlowExecution execution, ConditionDesc condition) {
                return true;
            }

            @Override
            public void handleTask(FlowExecution execution, TaskDesc task) {
                if (!"a".equals(task.node().id())) {
                    return;
                }
                // A task that fans out to its own thread has to wait for it;
                // the refusal travels back the way the task does it.
                Thread own = new Thread(() -> {
                    try {
                        execution.context().put("escaped", "from a task thread");
                    } catch (Throwable t) {
                        fromOwnThread.set(t);
                    }
                }, "task-owned-thread");
                own.start();
                try {
                    own.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (fromOwnThread.get() instanceof RuntimeException refused) {
                    throw refused;
                }
            }

            @Override
            public ExecutorService executor() {
                return executor;
            }
        };
        Graph graph = GraphSpec.create("escaped-write", spec -> {
            spec.entry("s");
            spec.addStart("s").linkTo("p");
            spec.addParallel("p").linkTo("a").linkTo("b");
            spec.addActivity("a").task("@noop").linkTo("j");
            spec.addActivity("b").task("@noop").linkTo("j");
            spec.addInclusive("j").linkTo("e");
            spec.addEnd("e");
        }).create();
        FlowEngine engine = FlowEngine.create(Map.of("default", driver), List.of(), null, null);
        engine.load(graph);
        FlowContext ctx = FlowContext.of();

        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> engine.run(graph, ctx),
            "the refusal must travel back through the task, not vanish");
        boolean named = false;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (String.valueOf(t.getMessage()).contains("Branch write isolation was lost")) {
                named = true;
            }
        }
        assertTrue(named, "the failure must say what was lost: " + failure);
        assertNull(ctx.get("escaped"),
            "the refused write must not reach the root map");
        executor.shutdownNow();
    }
}
