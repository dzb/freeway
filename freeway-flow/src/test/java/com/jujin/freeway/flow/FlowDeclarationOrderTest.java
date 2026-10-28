package com.jujin.freeway.flow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seeded random graphs, built twice with the nodes declared in two different
 * orders, must produce the same verdict.
 *
 * <p>This is the invariant that the old join-domain check broke: its recursion
 * walked {@code prevLinks()} lazily, so which nodes had a complete predecessor
 * set depended on declaration order — the same logical graph was accepted in
 * one order and rejected in another. The generator mixes plain activities,
 * forks and joins, and every third graph carries a deliberate defect so the
 * rejection path is exercised too, not just the happy one. Fixed seeds make the
 * run deterministic.</p>
 */
class FlowDeclarationOrderTest {

    /** One logical graph: node ids in topological order, their types and links. */
    private record Plan(List<String> ids, Map<String, NodeType> types,
                        List<String[]> links, boolean valid) {}

    /** A build attempt: the graph, or the exception the builder raised. */
    private record Outcome(Graph graph, RuntimeException error) {
        boolean accepted() {
            return error == null;
        }
    }

    @Test
    void declarationOrderDoesNotChangeWhatTheBuilderAccepts() {
        for (int seed = 0; seed < 100; seed++) {
            Plan plan = plan(seed, new Random(seed));
            Outcome inOrder = attempt(plan, seed, false, new Random(seed));
            Outcome shuffled = attempt(plan, seed, true, new Random(seed * 31L + 7));

            if (plan.valid()) {
                assertTrue(inOrder.accepted(),
                    "seed " + seed + " produced an invalid graph by construction: "
                        + describe(inOrder));
            }
            assertOrderIndependent(seed, inOrder, shuffled);
        }
    }

    private static void assertOrderIndependent(int seed, Outcome a, Outcome b) {
        assertEquals(a.accepted(), b.accepted(),
            "seed " + seed + ": declaration order changed the verdict — "
                + describe(a) + " vs " + describe(b));
        if (!a.accepted()) {
            assertEquals(a.error().getClass(), b.error().getClass(),
                "seed " + seed + ": same rejection expected in both orders");
            assertEquals(a.error().getMessage(), b.error().getMessage(),
                "seed " + seed + ": the message must not depend on declaration order");
        } else {
            assertEquals(a.graph().nodes().size(), b.graph().nodes().size(),
                "seed " + seed + ": accepted graphs must carry the same nodes");
            assertEquals(a.graph().links().size(), b.graph().links().size(),
                "seed " + seed + ": accepted graphs must carry the same links");
        }
    }

    private static String describe(Outcome outcome) {
        return outcome.accepted()
            ? "accepted(" + outcome.graph().nodes().size() + " nodes)"
            : outcome.error().getClass().getSimpleName() + ": " + outcome.error().getMessage();
    }

    /**
     * A valid plan is a chain from START to END with random extra forward edges
     * (so forks and joins appear). Every third seed appends one defect: a
     * duplicate unconditional link, a cycle, or an unresolved target.
     */
    private static Plan plan(int seed, Random random) {
        List<String> ids = new ArrayList<>();
        ids.add("s");
        int middle = 3 + random.nextInt(8);
        for (int i = 0; i < middle; i++) {
            ids.add("m" + i);
        }
        ids.add("e");

        List<String[]> links = new ArrayList<>();
        Set<String> pairs = new LinkedHashSet<>();
        for (int i = 0; i < ids.size() - 1; i++) {
            addLink(links, pairs, ids.get(i), ids.get(i + 1));
        }
        int extra = random.nextInt(ids.size());
        for (int i = 0; i < extra; i++) {
            int from = random.nextInt(ids.size() - 1);
            int to = from + 1 + random.nextInt(ids.size() - 1 - from);
            addLink(links, pairs, ids.get(from), ids.get(to));
        }

        Map<String, NodeType> types = new LinkedHashMap<>();
        types.put("s", NodeType.START);
        types.put("e", NodeType.END);
        for (String id : ids) {
            if (types.containsKey(id)) {
                continue;
            }
            int out = 0;
            for (String[] link : links) {
                if (link[0].equals(id)) {
                    out++;
                }
            }
            // A fork with one edge is legal but uninteresting; only make a
            // PARALLEL when it really forks.
            types.put(id, out >= 2 && random.nextBoolean()
                ? NodeType.PARALLEL
                : (random.nextBoolean() ? NodeType.INCLUSIVE : NodeType.ACTIVITY));
        }

        boolean valid = seed % 3 != 2;
        if (!valid) {
            switch (seed / 3 % 3) {
                case 0 -> links.add(new String[]{ids.get(1), ids.get(2)}); // duplicate
                case 1 -> links.add(new String[]{"e", ids.get(1)});      // cycle
                default -> links.add(new String[]{ids.get(1), "ghost"}); // unknown target
            }
        }
        return new Plan(List.copyOf(ids), Map.copyOf(types), List.copyOf(links), valid);
    }

    private static void addLink(List<String[]> links, Set<String> pairs,
            String from, String to) {
        if (pairs.add(from + '\u0000' + to)) {
            links.add(new String[]{from, to});
        }
    }

    private static Outcome attempt(Plan plan, int seed, boolean shuffle, Random random) {
        List<String> order = new ArrayList<>(plan.ids());
        if (shuffle) {
            // The two builds must differ in declaration order, not in content.
            do {
                Collections.shuffle(order, random);
            } while (order.equals(plan.ids()));
        }
        Map<String, NodeSpec> specs = new LinkedHashMap<>();
        try {
            Graph graph = GraphSpec.create("probe-" + seed, spec -> {
                spec.entry("s");
                for (String id : order) {
                    NodeSpec node = switch (plan.types().get(id)) {
                        case START -> spec.addStart(id);
                        case END -> spec.addEnd(id);
                        case PARALLEL -> spec.addParallel(id);
                        case INCLUSIVE -> spec.addInclusive(id);
                        default -> spec.addActivity(id);
                    };
                    if (plan.types().get(id) != NodeType.START
                            && plan.types().get(id) != NodeType.END) {
                        node.task("@noop");
                    }
                    specs.put(id, node);
                }
                for (String[] link : plan.links()) {
                    specs.get(link[0]).linkAdd(link[1]);
                }
            }).create();
            return new Outcome(graph, null);
        } catch (RuntimeException e) {
            return new Outcome(null, e);
        }
    }
}
