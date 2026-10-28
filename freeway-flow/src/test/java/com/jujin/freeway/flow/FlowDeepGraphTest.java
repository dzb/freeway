package com.jujin.freeway.flow;

import org.junit.jupiter.api.Test;

/**
 * The graph-shape checks run at build time, and build time must not depend on
 * the JVM stack. A 50 000-node chain feeding a join used to overflow it: the
 * join-domain check computed each branch's nesting depth by recursing up its
 * ancestors, so a deep graph crashed {@code GraphSpec.create()} instead of
 * producing a verdict. The check is iterative now.
 */
class FlowDeepGraphTest {

    @Test
    void aDeepChainFeedingAJoinBuildsWithoutRecursing() {
        int chain = 50_000;
        Graph graph = GraphSpec.create("deep", spec -> {
            spec.entry("start");
            var cursor = spec.addStart("start");
            for (int i = 0; i < chain; i++) {
                String id = "a" + i;
                cursor.linkAdd(id);
                cursor = spec.addActivity(id).task("@noop");
            }
            cursor.linkAdd("fork");
            var fork = spec.addParallel("fork").task("@noop");
            fork.linkAdd("left");
            fork.linkAdd("right");
            spec.addActivity("left").task("@noop").linkAdd("join");
            spec.addActivity("right").task("@noop").linkAdd("join");
            spec.addInclusive("join").task("@noop").linkAdd("end");
            spec.addEnd("end");
        }).create();

        // The join's two branches are both "runs once", so the graph is valid;
        // reaching this assertion at all is the point (the old shape threw
        // StackOverflowError inside create()).
        org.junit.jupiter.api.Assertions.assertEquals("deep", graph.id());
        org.junit.jupiter.api.Assertions.assertEquals(chain + 6, graph.nodes().size());
    }
}
