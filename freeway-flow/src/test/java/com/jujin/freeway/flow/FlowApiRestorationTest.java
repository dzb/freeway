package com.jujin.freeway.flow;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the public flow API surface that the
 * "remove dead accessors" refactor dropped. These are capabilities for
 * user-side code (custom drivers, nodes, graph tooling), not internal
 * dead code — deleting them broke callers outside this repository.
 */
class FlowApiRestorationTest {

    @Test
    void markerIndexKnownMarkersIsSnapshot() {
        FlowMarkerIndex index = new FlowMarkerIndex();
        index.register(comp("one"), Set.of("a", "b"));
        index.register(comp("two"), Set.of("c"));

        Set<String> snapshot = index.knownMarkers();
        assertEquals(Set.of("a", "b", "c"), snapshot);

        index.register(comp("three"), Set.of("d"));
        assertEquals(Set.of("a", "b", "c"), snapshot,
            "knownMarkers() must be a snapshot, not a live view");
        assertEquals(Set.of("a", "b", "c", "d"), index.knownMarkers());
        assertEquals(index.markers(), index.knownMarkers(),
            "the new and legacy names must agree");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("x"));
    }

    @Test
    void execStateRootCountersAndVars() {
        ExecState state = new ExecState();
        assertEquals(1, state.countIncr("hits"));
        assertEquals(2, state.countIncr("hits"));
        assertEquals(2, state.count("hits"));
        assertEquals(5, state.countIncr("hits", 3));
        state.countSet("hits", 10);
        assertEquals(10, state.count("hits"));

        state.vars().put("k", "v"); // live write path
        assertEquals("v", state.varAs("k"));
    }

    @Test
    void execStateDeadEndResetClears() {
        Graph graph = Graph.create("g", spec -> {
            spec.addStart("a").linkAdd("b");
            spec.addEnd("b");
        });
        ExecState state = new ExecState();
        state.deadEnd(graph, "a");
        assertNotNull(state.deadEnd());
        state.deadEndReset();
        assertNull(state.deadEnd(), "deadEndReset() must clear recorded dead-ends");
    }

    @Test
    void flowTraceSnapshotAndEndQuery() {
        Graph graph = Graph.create("g", spec -> {
            spec.addStart("start").linkAdd("end");
            spec.addEnd("end");
        });
        FlowTrace trace = new FlowTrace();
        trace.recordNode(graph, graph.getStart());
        trace.recordNode(graph, graph.getNodeOrThrow("end"));

        List<NodeRecord> snapshot = List.copyOf(trace.lastRecords());
        assertEquals(1, snapshot.size());
        assertEquals("g", snapshot.getFirst().getGraphId());
        assertEquals("end", snapshot.getFirst().getId());
        assertTrue(trace.isEnd("g"),
            "isEnd() must report the END node as the last record");
        assertFalse(trace.isEnd("missing"));

        trace.clear();
        assertTrue(snapshot.getFirst().getTimestamp() > 0,
            "record timestamp must be readable after the trace is cleared");
    }

    @Test
    void nodeRecordTypedAccessors() {
        Graph graph = Graph.create("g", spec -> {
            spec.addStart("start").title("The Start").linkAdd("end");
            spec.addEnd("end");
        });
        NodeRecord record = new NodeRecord(graph.getNodeOrThrow("start"));
        assertEquals("g", record.getGraphId());
        assertEquals("The Start", record.getTitle());
        assertTrue(record.getTimestamp() > 0);
    }

    @Test
    void exchangerRunTaskAndCopy() throws Throwable {
        Graph graph = Graph.create("g", spec -> {
            spec.addStart("a").linkAdd("b");
            spec.addEnd("b");
        });
        FlowEngine engine = FlowEngine.newInstance();
        FlowContext context = FlowContext.of();
        FlowExchanger ex = new FlowExchanger(graph, engine,
            engine.getDriver(graph), context, 0, new AtomicInteger(0));

        ex.recordClear();
        assertTrue(ex.getSteps() >= 0);

        FlowExchanger copy = ex.copy(graph, FlowContext.of());
        assertNotNull(copy);
        assertSame(engine, copy.engine());

        // runTask reaches the driver's task handling; an empty task is a no-op.
        ex.runTask(graph.getNodeOrThrow("b"), null);
    }

    @Test
    void nodeMetaTypedAccessorsAndTopology() {
        Graph graph = Graph.create("g", spec -> {
            spec.addStart("start").linkAdd("mid");
            spec.addActivity("mid")
                .metaPut("flag", "true")
                .metaPut("num", "42")
                .metaPut("label", "x")
                .linkAdd("end");
            spec.addEnd("end");
        });
        Node mid = graph.getNodeOrThrow("mid");
        assertTrue(mid.hasMeta("flag"));
        assertTrue(mid.getMetaAsBool("flag"));
        assertEquals(42.0, mid.getMetaAsNumber("num").doubleValue());
        assertEquals("x", mid.<String>getMetaAs("label"));
        assertEquals("fallback", mid.getMetaOrDefault("absent", "fallback"));

        mid.attachment = "tag";
        assertEquals("tag", mid.attachment);

        List<Node> prev = mid.getPrevNodes();
        assertEquals(List.of("start"), prev.stream().map(Node::getId).toList());
        assertEquals("start", mid.getPrevLinks().getFirst().getPrevNode().getId());
    }

    @Test
    void descIsNotEmptyAndAttachments() {
        ConditionDesc empty = new ConditionDesc(null, (String) null);
        ConditionDesc full = new ConditionDesc(null, "x != null");
        assertFalse(ConditionDesc.isNotEmpty(empty));
        assertTrue(ConditionDesc.isNotEmpty(full));
        full.attachment = "c";
        assertEquals("c", full.attachment);

        TaskDesc emptyTask = new TaskDesc(null, (String) null);
        TaskDesc fullTask = new TaskDesc(null, "@bean");
        assertFalse(TaskDesc.isNotEmpty(emptyTask));
        assertTrue(TaskDesc.isNotEmpty(fullTask));
        fullTask.attachment = "t";
        assertEquals("t", fullTask.attachment);
    }

    @Test
    void graphCopyWithModification() {
        Graph original = Graph.create("g", spec -> {
            spec.addStart("a").linkAdd("b");
            spec.addEnd("b");
        });
        Graph copy = Graph.copy(original, spec -> spec.addEnd("c"));
        assertNotNull(copy.getNodeOrThrow("c"),
            "Graph.copy must apply the modification to the copy");
        assertThrows(IllegalArgumentException.class,
            () -> original.getNodeOrThrow("c"),
            "the original graph must not be touched");
    }

    private static TaskComponent comp(String name) {
        return new TaskComponent() {
            @Override
            public void run(FlowContext context, Node node) {
            }
        };
    }
}
