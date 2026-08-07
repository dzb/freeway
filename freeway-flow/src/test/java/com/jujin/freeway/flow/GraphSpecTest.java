package com.jujin.freeway.flow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphSpecTest {

    private static FlowEngine newEngine(FlowDriver driver) {
        return FlowEngine.newInstance(Map.of("default", driver));
    }

    @Test
    void testBlueprintBuildsAndRuns() {
        GraphSpec blueprint = GraphSpec.create("blueprint_v2", bp -> {
            bp.entry("start");
            bp.metaPut("kind", "demo");
            bp.addStart("start").linkAdd("task");
            bp.addActivity("task").task("@counter").linkAdd("end");
            bp.addEnd("end");
        });

        assertEquals(2, blueprint.getVersion());
        assertEquals("start", blueprint.getEntry());

        Graph graph = blueprint.create();
        assertEquals("blueprint_v2", graph.getId());
        assertEquals("start", graph.getStart().getId());

        AtomicInteger counter = new AtomicInteger();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
                .container(name -> {
                    if ("counter".equals(name)) {
                        return (TaskComponent) (ctx, node) -> counter.incrementAndGet();
                    }
                    return null;
                })
                .build());

        engine.eval(blueprint, FlowContext.of());
        assertEquals(1, counter.get());
    }

    @Test
    void testLegacyLayoutFormatIsRejected() {
        String legacyJson = """
                {
                  "id": "legacy",
                  "layout": [
                    { "id": "s", "type": "start", "link": "a" },
                    { "id": "a", "type": "activity", "task": "@legacyTask", "link": "e" },
                    { "id": "e", "type": "end" }
                  ]
                }
                """;

        // The solon-flow v1 layout format was removed — only the canonical
        // v2 shape (nodes + links) loads.
        assertThrows(IllegalArgumentException.class, () -> Graph.fromText(legacyJson));
    }

    @Test
    void testV2JsonCanLoadThroughGraphApi() {
        GraphSpec blueprint = GraphSpec.create("v2_graph", bp -> {
            bp.entry("start");
            bp.addStart("start").linkAdd("task");
            bp.addActivity("task").task("@counter").linkAdd("end");
            bp.addEnd("end");
        });

        String json = blueprint.toJson();
        GraphSpec parsed = GraphSpec.fromText(json);
        assertEquals(2, parsed.getVersion());
        assertEquals("start", parsed.getEntry());
        assertEquals(3, parsed.getNodes().size());

        Graph graph = blueprint.create();
        assertEquals("v2_graph", graph.getId());
        assertEquals("start", graph.getStart().getId());
        assertEquals(3, graph.getNodes().size());
    }

    @Test
    void testBlueprintPromotesEntryNodeInRuntimeGraph() {
        GraphSpec blueprint = GraphSpec.create("entry_promote", bp -> {
            bp.entry("task");
            bp.metaPut("kind", "demo");
            bp.title("entry promote");
            bp.driver("default");
            bp.addActivity("task").task("@counter").linkAdd("end", link -> link.priority(7));
            bp.addEnd("end");
        });

        Graph graph = blueprint.create();
        assertEquals("entry_promote", graph.getId());
        assertEquals("entry promote", graph.getTitle());
        assertEquals("default", graph.getDriver());
        assertEquals("demo", graph.getMeta("kind"));
        assertEquals("task", graph.getStart().getId());
        assertEquals(NodeType.ACTIVITY, graph.getNode("task").getType());
        assertEquals("@counter", graph.getNode("task").getTask().getDescription());
        assertEquals(7, graph.getNode("task").getNextLinks().get(0).getPriority());
    }

    @Test
    void testBlueprintFromV2JsonReadsCanonicalFields() {
        String json = """
                {
                  "id": "compat",
                  "version": 2,
                  "entry": "start",
                  "nodes": [
                    { "id": "start", "type": "start" },
                    { "id": "task", "type": "activity", "task": "@counter", "when": "score > 0" },
                    { "id": "end", "type": "end" }
                  ],
                  "links": [
                    { "from": "start", "to": "task", "when": "score > 0", "priority": 3 },
                    { "from": "task", "to": "end" }
                  ]
                }
                """;

        GraphSpec blueprint = GraphSpec.fromText(json);
        assertEquals("compat", blueprint.getId());
        assertEquals("start", blueprint.getEntry());
        assertEquals("score > 0", blueprint.getNode("task").getWhen());
        assertEquals("score > 0", blueprint.getLinks().get(0).getWhen());
        assertEquals(3, blueprint.getLinks().get(0).getPriority());
        assertEquals("start", blueprint.getLinks().get(0).getFrom());
        assertEquals("task", blueprint.getLinks().get(0).getTo());
        assertEquals("task", blueprint.getLinks().get(1).getFrom());
        assertEquals("end", blueprint.getLinks().get(1).getTo());

        Graph graph = blueprint.create();
        assertEquals(2, graph.getLinks().size());
        assertTrue(graph.getLinks().stream().anyMatch(link ->
                "start".equals(link.getPrevId()) && "task".equals(link.getNextId()) && link.getPriority() == 3));
    }

    @Test
    void testBlueprintCopyPreservesPriority() {
        Graph graph = Graph.create("copy_priority", spec -> {
            spec.addStart("s").linkAdd("task", link -> link.priority(5));
            spec.addActivity("task").task("@counter").linkAdd("e");
            spec.addEnd("e");
        });

        GraphSpec blueprint = GraphSpec.copy(graph);
        assertEquals("s", blueprint.getEntry());
        assertEquals(2, blueprint.getLinks().size());
        assertTrue(blueprint.getLinks().stream().anyMatch(link ->
                "s".equals(link.getFrom()) && "task".equals(link.getTo()) && link.getPriority() == 5));

        Graph rebuilt = blueprint.create();
        assertTrue(rebuilt.getLinks().stream().anyMatch(link ->
                "s".equals(link.getPrevId()) && "task".equals(link.getNextId()) && link.getPriority() == 5));
    }

    @Test
    void testBlueprintRejectsMissingEntryNode() {
        GraphSpec blueprint = GraphSpec.create("broken", bp -> {
            bp.entry("missing");
            bp.addStart("start").linkAdd("end");
            bp.addEnd("end");
        });

        IllegalStateException ex = assertThrows(IllegalStateException.class, blueprint::create);
        assertTrue(ex.getMessage().contains("Entry node not found"));
    }

    @Test
    void testEngineCanLoadBlueprintDirectly() {
        GraphSpec blueprint = GraphSpec.create("engine_blueprint", bp -> {
            bp.entry("start");
            bp.addStart("start").linkAdd("task");
            bp.addActivity("task").task("@counter").linkAdd("end");
            bp.addEnd("end");
        });

        AtomicInteger counter = new AtomicInteger();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
                .container(name -> {
                    if ("counter".equals(name)) {
                        return (TaskComponent) (ctx, node) -> counter.incrementAndGet();
                    }
                    return null;
                })
                .build());

        engine.load(blueprint);
        assertNotNull(engine.getGraph("engine_blueprint"));
        engine.eval("engine_blueprint", FlowContext.of());
        assertEquals(1, counter.get());
    }

    @Test
    void testToMapDrainsPendingLinks() {
        GraphSpec bp = GraphSpec.create("map_test", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@counter").linkAdd("e");
            spec.addEnd("e");
        });
        Map<String, Object> map = bp.toMap();
        assertNotNull(map.get("id"));
        @SuppressWarnings("unchecked")
        var links = (List<?>) map.get("links");
        assertEquals(2, links.size());
    }

    @Test
    void testToMapPreservesUnreachableNodes() {
        GraphSpec bp = GraphSpec.create("round_trip", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").linkAdd("e");
            spec.addActivity("x");
            spec.addEnd("e");
        });

        Map<String, Object> map = bp.toMap();
        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) map.get("nodes");

        assertEquals(4, nodes.size());
        assertTrue(nodes.stream().anyMatch(node -> "x".equals(node.get("id"))),
            "unreachable nodes must still be serialized");
    }

    @Test
    void testNodeMutationInvalidatesCompileOrder() {
        GraphSpec bp = GraphSpec.create("mutation_order", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").linkAdd("e");
            spec.addActivity("x");
            spec.addEnd("e");
        });

        bp.toMap(); // prime cached normalization/BFS order
        bp.getNode("s").linkAdd("x");

        Map<String, Object> map = bp.toMap();
        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) map.get("nodes");

        assertEquals(
            List.of("s", "a", "x", "e"),
            nodes.stream().map(node -> (String) node.get("id")).toList()
        );
    }

    @Test
    void testThenChainAddsMoreNodes() {
        GraphSpec bp = GraphSpec.create("then_test", spec ->
            spec.entry("s").addStart("s")
        ).then(spec -> {
            spec.addActivity("a").task("@counter");
            spec.link("s", "a");
            spec.link("a", "e");
            spec.addEnd("e");
        });
        Graph g = bp.create();
        assertEquals(3, g.getNodes().size());
    }

    @Test
    void testNullLinkAddThrows() {
        GraphSpec bp = GraphSpec.create("null_link", spec -> {});
        var node = bp.addActivity("a");
        assertThrows(NullPointerException.class, () -> node.linkAdd(null));
    }

    @Test
    void testNullLinkThrows() {
        GraphSpec bp = GraphSpec.create("null_link", spec -> {});
        assertThrows(NullPointerException.class, () -> bp.link("a", null));
        assertThrows(NullPointerException.class, () -> bp.link(null, "b"));
    }

    @Test
    void testCyclicGraphRejected() {
        // a → b → a forms a cycle; both nodes are reachable from entry 'a'.
        // Cyclic graphs must fail fast at build time — the runtime depth
        // guard is a passive backstop, not validation.
        GraphSpec bp = GraphSpec.create("cycle", spec -> {
            spec.entry("a");
            spec.addActivity("a").task("@t1").linkAdd("b");
            spec.addActivity("b").task("@t2").linkAdd("a");
            spec.addActivity("c").task("@t3").linkAdd("end");
            spec.addEnd("end");
        });
        IllegalStateException ex = assertThrows(IllegalStateException.class, bp::create);
        assertTrue(ex.getMessage().contains("Cycle detected"),
            "expected cycle rejection, got: " + ex.getMessage());
    }

    @Test
    void testSelfLinkRejected() {
        // A node linking to itself is a degenerate cycle and must be rejected.
        GraphSpec bp = GraphSpec.create("self", spec -> {
            spec.entry("a");
            spec.addActivity("a").task("@t1").linkAdd("a");
            spec.addEnd("e");
        });
        IllegalStateException ex = assertThrows(IllegalStateException.class, bp::create);
        assertTrue(ex.getMessage().contains("Cycle detected"),
            "expected cycle rejection, got: " + ex.getMessage());
    }

    @Test
    void testToJsonAfterPartialBuild() {
        GraphSpec bp = GraphSpec.create("partial", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@counter");
        });
        String json = bp.toJson();
        assertTrue(json.contains("\"version\":2"));
    }
}
