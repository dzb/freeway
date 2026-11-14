package com.jujin.freeway.flow;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.util.ContextCarrier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
}
