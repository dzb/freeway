package com.jujin.freeway.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The join decision and the provisional dead-end marker must move as one step.
 *
 * <p>Six branches converging on an INCLUSIVE gateway failed intermittently:
 * {@code FlowException: dead end at node 'gw'} — on a graph that had in fact
 * run to completion. The gateway activated, its successor executed, and the
 * run still reported a dead end.
 *
 * <p>The cause is that the counter and the marker are two structures updated
 * without a common step. A branch that incremented the counter to 5 and was
 * descheduled before writing its provisional marker can resume
 * <em>after</em> the sixth branch incremented to 6, cleared the marker and
 * activated the gateway — writing a stale marker onto a join that had already
 * completed. The end-of-run check then reports the dead end, and its message
 * ("a join gateway never received all its incoming branches") describes a lost
 * branch rather than a bookkeeping order.
 *
 * <p>Repeated rounds because the window is narrow — one interleaving in
 * thousands, which is why it survived as a rare failure instead of a constant
 * one.
 */
class JoinMarkerAtomicityTest {

    private static final int BRANCHES = 6;
    private static final int ROUNDS = 400;

    @Test
    void concurrentBranchesNeverLeaveAStaleDeadEndOnAJoinThatRan() {
        ExecutorService executor = Executors.newFixedThreadPool(BRANCHES);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                AtomicInteger gatewayRuns = new AtomicInteger();
                FlowEngine engine = engine(executor, node -> {
                    if ("gw".equals(node.id())) {
                        gatewayRuns.incrementAndGet();
                    }
                });
                engine.load(graph());
                // Before the fix this throws on some rounds:
                // "dead end at node 'gw' in graph 'parinc'".
                engine.eval("parinc", FlowContext.of());
                assertEquals(1, gatewayRuns.get(),
                    "the inclusive gateway must run exactly once, round " + round);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static Graph graph() {
        List<String> branchIds = new ArrayList<>();
        for (int i = 0; i < BRANCHES; i++) {
            branchIds.add(String.valueOf((char) ('a' + i)));
        }
        return GraphSpec.create("parinc", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("p");
            var fork = spec.addParallel("p").task("@noop");
            for (String id : branchIds) {
                fork.linkAdd(id);
            }
            for (String id : branchIds) {
                spec.addActivity(id).task("@dummy").linkAdd("gw");
            }
            spec.addInclusive("gw").task("@dummy").linkAdd("end");
            spec.addEnd("end");
        }).create();
    }

    private static FlowEngine engine(ExecutorService executor, Consumer<Node> body) {
        return FlowEngine.create(Map.of("default", new FlowDriver() {
            @Override
            public ExecutorService executor() {
                return executor;
            }

            @Override
            public boolean handleCondition(
                    FlowEvaluation evaluation, ConditionDesc condition) throws Throwable {
                return ExprEvaluator.evalCondition(
                    condition.description(), evaluation.context().data());
            }

            @Override
            public void handleTask(FlowEvaluation evaluation, TaskDesc task) throws Throwable {
                if (!task.isEmpty()) {
                    body.accept(task.node());
                }
            }
        }));
    }
}
