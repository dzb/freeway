package com.jujin.freeway.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * A LOOP without {@code $for} is not a loop for this check — and for the
     * engine either: {@code loopRun} treats it as a plain activity gateway and
     * fans out once, so nothing downstream arrives repeatedly.
     *
     * <p>This is the case the check's first version got wrong in the most
     * damaging direction: it walked every {@code NodeType.LOOP} regardless of
     * {@code $for}, so a graph that used a LOOP as a gateway — a shape the class
     * javadoc explicitly allows — was refused at load while running perfectly
     * well. The judgement is now {@code iterates(node)}, shared with
     * {@code loopRun}, and this is what pins that.
     *
     * <p>The graph is deliberately identical to the rejected one below except
     * that {@code l} has no {@code $for}: same shape, opposite verdict, which is
     * the whole claim.
     */
    @Test
    void aLoopWithoutForIsAGatewaySoItsBranchesShareOneDomain() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            AtomicInteger joinRuns = new AtomicInteger();
            FlowEngine engine = engine(executor, node -> {
                if ("j".equals(node.id())) {
                    joinRuns.incrementAndGet();
                }
            });
            Graph graph = GraphSpec.create("gatewayshaped", spec -> {
                spec.entry("s");
                spec.addStart("s").linkAdd("fork");
                var fork = spec.addParallel("fork").task("@noop");
                fork.linkAdd("l");
                fork.linkAdd("q");
                spec.addLoop("l").task("@dummy").linkAdd("x");   // no $for: a gateway
                spec.addActivity("x").task("@dummy").linkAdd("j");
                spec.addActivity("q").task("@dummy").linkAdd("j");
                spec.addInclusive("j").task("@dummy").linkAdd("e");
                spec.addEnd("e");
            }).create();

            assertDoesNotThrow(() -> engine.load(graph),
                "a LOOP without $for fans out once, so both branches arrive once "
                    + "and the join is countable");
            engine.eval("gatewayshaped", FlowContext.of());

            assertEquals(1, joinRuns.get(),
                "one arrival from each branch, so the join fires exactly once");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aJoinWithBranchesInsideAndOutsideALoopIsRefusedAtLoad() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            FlowEngine engine = engine(executor, node -> { });

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.load(loopWithExternalIncomingJoin()));

            assertTrue(ex.getMessage().contains("Join 'j'"), ex.getMessage());
            assertTrue(ex.getMessage().contains("iteration domains"), ex.getMessage());
            assertTrue(ex.getMessage().contains("'q' runs once"), ex.getMessage());
            assertTrue(ex.getMessage().contains("loopjoin"), ex.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A join fed directly by the LOOP node is countable, and a check that
     * refuses it would be refusing a graph the engine runs perfectly.
     *
     * <p>{@code loopRun} walks the loop's matching successors once per item, so
     * the loop's own outgoing edge arrives once per iteration — exactly like a
     * node deeper in its body. Attributing a branch to the loop that repeats it
     * therefore has to place the LOOP node <em>in its own domain</em>; treating
     * it as "outside" made this load-time rejection while eval succeeded.
     */
    @Test
    void aJoinFedByTheLoopNodeItselfIsAccepted() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            AtomicInteger joinRuns = new AtomicInteger();
            FlowEngine engine = engine(executor, node -> {
                if ("j".equals(node.id())) {
                    joinRuns.incrementAndGet();
                }
            });
            Graph graph = GraphSpec.create("loopfeedsjoin", spec -> {
                spec.entry("s");
                spec.addStart("s").linkAdd("l");
                spec.addLoop("l").metaPut("$for", "item").metaPut("$in", List.of(1, 2, 3))
                    .task("@dummy").linkAdd("a").linkAdd("j");
                spec.addActivity("a").task("@dummy").linkAdd("j");
                spec.addInclusive("j").task("@dummy").linkAdd("e");
                spec.addEnd("e");
            }).create();

            engine.load(graph);
            engine.eval("loopfeedsjoin", FlowContext.of());

            assertEquals(3, joinRuns.get(),
                "one arrival from the loop and one from 'a' per iteration, so "
                    + "the join fires once per item — 3 items, 3 activations");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Nested loops must not make the <em>load-time verdict</em> depend on
     * declaration order.
     *
     * <p>A node in an inner loop's body also sits in the outer loop's body — the
     * outer reaches it transitively — so "first declared body that contains this
     * node" answers by spec order rather than by structure. These two graphs are
     * identical except for the order the two {@code addLoop} calls appear in, and
     * both must load.
     *
     * <p>Only the load is asserted. Whether the run itself completes is the
     * engine's business, and it has its own order sensitivity that this check
     * neither introduces nor claims to fix.
     */
    @Test
    void nestedLoopDeclarationOrderDoesNotChangeTheLoadVerdict() {
        for (Graph graph : List.of(nestedLoops("outerFirst"), nestedLoops("innerFirst"))) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                FlowEngine engine = engine(executor, node -> { });
                assertDoesNotThrow(() -> engine.load(graph),
                    "the same graph must load the same way whichever order its two "
                        + "loops are declared in: " + graph.id());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static Graph nestedLoops(String order) {
        return GraphSpec.create("nested" + order, spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("outer");
            if ("innerFirst".equals(order)) {
                spec.addLoop("inner").metaPut("$for", "k").metaPut("$in", List.of(7))
                    .task("@dummy").linkAdd("a");
                spec.addLoop("outer").metaPut("$for", "i").metaPut("$in", List.of(1, 2))
                    .task("@dummy").linkAdd("fork");
            } else {
                spec.addLoop("outer").metaPut("$for", "i").metaPut("$in", List.of(1, 2))
                    .task("@dummy").linkAdd("fork");
                spec.addLoop("inner").metaPut("$for", "k").metaPut("$in", List.of(7))
                    .task("@dummy").linkAdd("a");
            }
            var fork = spec.addParallel("fork").task("@noop");
            fork.linkAdd("inner");
            fork.linkAdd("b");
            spec.addActivity("a").task("@dummy").linkAdd("j");
            spec.addActivity("b").task("@dummy").linkAdd("j");
            spec.addInclusive("j").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
    }

    /**
     * Two loops that do not enclose one another: each repeats only its own body,
     * so the join's arrival count lines up with neither.
     */
    @Test
    void twoIndependentLoopsFeedingOneJoinIsRejected() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            FlowEngine engine = engine(executor, node -> { });
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.load(twoIndependentLoops()));
            assertTrue(ex.getMessage().contains("iteration domains"), ex.getMessage());
            assertTrue(ex.getMessage().contains("'a' repeats per iteration"), ex.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    private static Graph twoIndependentLoops() {
        return GraphSpec.create("twoloops", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("fork");
            var fork = spec.addParallel("fork").task("@noop");
            fork.linkAdd("l1");
            fork.linkAdd("l2");
            spec.addLoop("l1").metaPut("$for", "i").metaPut("$in", List.of(1, 2))
                .task("@dummy").linkAdd("a");
            spec.addLoop("l2").metaPut("$for", "j").metaPut("$in", List.of(3, 4))
                .task("@dummy").linkAdd("b");
            spec.addActivity("a").task("@dummy").linkAdd("j");
            spec.addActivity("b").task("@dummy").linkAdd("j");
            spec.addInclusive("j").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
    }

    /**
     * Sibling loops whose bodies overlap: {@code a} is repeated by both, so no
     * single domain describes it and the join cannot be counted — whichever loop
     * the document happens to declare first.
     *
     * <p>This is the shape that used to slip through when a loop's claim on a
     * node was awarded to the first loop examined: declaring {@code l2} first let
     * it claim {@code a}, both of {@code j}'s branches then read as {@code l2},
     * and an uncountable graph loaded. The verdict has to be a property of the
     * graph, so both declaration orders are asserted, and both must refuse.
     */
    @Test
    void siblingLoopsSharingANodeAreRejectedWhicheverOrderTheyAreDeclared() {
        for (String first : List.of("l1", "l2")) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                FlowEngine engine = engine(executor, node -> { });
                String second = "l1".equals(first) ? "l2" : "l1";
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> engine.load(sharedDownstreamLoopBodies(first, second)),
                    "declaring '" + first + "' first must not let an uncountable "
                        + "join load");
                assertTrue(ex.getMessage().contains("iteration domains"), ex.getMessage());
                assertTrue(ex.getMessage().contains("'a' repeats per iteration"),
                    "must name the shared node: " + ex.getMessage());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * A rejected graph is never registered, so retrying the same id is judged
     * again rather than reported as "already loaded". Validating before anything
     * is published is what makes that true; a registration that had to be undone
     * afterwards would leave both answers in play.
     */
    @Test
    void aRejectedLoadLeavesTheIdFreeToRetry() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            FlowEngine engine = engine(executor, node -> { });

            IllegalArgumentException first = assertThrows(IllegalArgumentException.class,
                () -> engine.load(twoIndependentLoops()));
            assertTrue(first.getMessage().contains("iteration domains"), first.getMessage());

            IllegalArgumentException again = assertThrows(IllegalArgumentException.class,
                () -> engine.load(twoIndependentLoops()));
            assertTrue(again.getMessage().contains("iteration domains"),
                "the retry must be judged again, not refused as a duplicate: "
                    + again.getMessage());
            assertNull(engine.graph("twoloops"),
                "a rejected graph must not be left registered");
        } finally {
            executor.shutdownNow();
        }
    }

    private static Graph sharedDownstreamLoopBodies(String first, String second) {
        return GraphSpec.create("sharedbody", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("fork");
            var fork = spec.addParallel("fork").task("@noop");
            fork.linkAdd("l1");
            fork.linkAdd("l2");
            for (String loop : List.of(first, second)) {
                if ("l1".equals(loop)) {
                    spec.addLoop("l1").metaPut("$for", "i").metaPut("$in", List.of(1, 2))
                        .task("@dummy").linkAdd("a");
                } else {
                    spec.addLoop("l2").metaPut("$for", "j").metaPut("$in", List.of(3, 4))
                        .task("@dummy").linkAdd("a").linkAdd("b");
                }
            }
            spec.addActivity("a").task("@dummy").linkAdd("j");
            spec.addActivity("b").task("@dummy").linkAdd("j");
            spec.addInclusive("j").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
    }

    @Test
    void aJoinWhoseBranchesAreAllInsideTheLoopIsAccepted() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            AtomicInteger joinRuns = new AtomicInteger();
            FlowEngine engine = engine(executor, node -> {
                if ("j".equals(node.id())) {
                    joinRuns.incrementAndGet();
                }
            });
            engine.load(joinInsideLoopBody());
            engine.eval("loopjoinok", FlowContext.of());

            // The count matters as much as the absence of a load-time throw:
            // "accepted" alone would still pass if the join never activated.
            assertEquals(2, joinRuns.get(),
                "both branches are in the body, so each iteration delivers the two "
                    + "arrivals the join waits for — once per iteration");
        } finally {
            executor.shutdownNow();
        }
    }

    private static Graph loopWithExternalIncomingJoin() {
        return GraphSpec.create("loopjoin", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("p");
            // Two paths out of the entry: the loop body, and a branch outside it.
            var fork = spec.addParallel("p").task("@noop");
            fork.linkAdd("l");
            fork.linkAdd("q");
            spec.addLoop("l").metaPut("$for", "item").metaPut("$in", List.of(1, 2))
                .task("@dummy").linkAdd("a");
            spec.addActivity("a").task("@dummy").linkAdd("j");
            spec.addActivity("q").task("@dummy").linkAdd("j");
            spec.addInclusive("j").task("@dummy").linkAdd("end");
            spec.addEnd("end");
        }).create();
    }

    private static Graph joinInsideLoopBody() {
        return GraphSpec.create("loopjoinok", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("l");
            // Both of the join's branches live in the body, so each iteration
            // delivers exactly the two arrivals it waits for.
            spec.addLoop("l").metaPut("$for", "item").metaPut("$in", List.of(1, 2))
                .task("@dummy").linkAdd("f");
            var fork = spec.addParallel("f").task("@noop");
            fork.linkAdd("a");
            fork.linkAdd("q");
            spec.addActivity("a").task("@dummy").linkAdd("j");
            spec.addActivity("q").task("@dummy").linkAdd("j");
            spec.addInclusive("j").task("@dummy").linkAdd("end");
            spec.addEnd("end");
        }).create();
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
