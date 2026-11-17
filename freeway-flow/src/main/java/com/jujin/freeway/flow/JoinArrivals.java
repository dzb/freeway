package com.jujin.freeway.flow;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graph-shape check made once, where the graph is built: every incoming flow
 * of a PARALLEL join must carry a token on every run.
 *
 * <p>A join waits for {@code prevLinks().size()} arrivals. {@link JoinDomains}
 * refuses the graphs where that count repeats per iteration; this check
 * refuses the graphs where a conditional split leaves one of those flows
 * empty. The engine can account for an empty INCLUSIVE input — a dead path
 * tells the inclusive joins it would have fed ({@code FlowEngineDefault
 * #skipAhead}) — but a PARALLEL join is deliberately not told: it waits for
 * every branch. A run that skips one therefore ends at the join as
 * {@code dead end at node 'j'}, a message that names the join while the
 * mistake sits at the split that skipped the branch.</p>
 *
 * <p>Why here and not in the executor: {@code skipDestinations} knows the same
 * fact at run time, but by then the run has already started and the report
 * points at the wrong node. Graph construction is the one place every graph
 * goes through ({@code GraphSpec.create}, {@code Graph.fromText},
 * {@code eval(Graph)}), so the fact is stated there — the same reasoning that
 * puts {@link JoinDomains} beside it.</p>
 *
 * <p>What this does not catch, stated so nobody assumes it does: a LOOP whose
 * iteration set is empty leaves its downstream flows empty too, and that is a
 * runtime data property, not a graph shape. A condition that happens to
 * evaluate true is still "may be skipped" here — the check reads the shape,
 * never an outcome.</p>
 */
final class JoinArrivals {

    private JoinArrivals() {}

    /** Refuses a PARALLEL join whose static in-degree cannot be met. */
    static void rejectSkippableInputs(Graph graph) {
        Reach reach = reach(graph);
        // Id order, not declaration order: a rejection message must not depend
        // on how the author happened to order the nodes.
        List<Node> joins = graph.nodes().values().stream()
            .filter(node -> node.type() == NodeType.PARALLEL && node.prevLinks().size() > 1)
            .sorted(Comparator.comparing(Node::id))
            .toList();
        for (Node join : joins) {
            for (Link input : join.prevLinks()) {
                if (reach.always().contains(input.prevNode().id()) && !skippable(input)) {
                    continue;
                }
                throw new IllegalArgumentException(
                    "PARALLEL join '" + join.id() + "' in graph '" + graph.id()
                        + "' has an input that may carry no token: " + describe(input, reach)
                        + ". The join waits for all " + join.prevLinks().size()
                        + " incoming flows, so a run that skips that branch ends here as a"
                        + " dead end — the mistake is at the split, not at the join."
                        + " Keep every input unconditional, move the condition downstream of"
                        + " the join, or use an INCLUSIVE join, which waits only for the flows"
                        + " that are active.");
            }
        }
    }

    /**
     * Two answers about the same walk from the entry: which nodes a token
     * always reaches, and for the rest, the first conditional step on the way
     * in — enough to name the split instead of the victim.
     */
    private record Reach(Set<String> always, Map<String, Link> firstBlocker) {}

    private static Reach reach(Graph graph) {
        Node start = graph.start();
        // Pass 1: unconditional reachability. A node here receives a token on
        // every run, whatever the conditions are.
        Set<String> always = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        always.add(start.id());
        queue.add(start);
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            for (Link link : node.nextLinks()) {
                if (skippable(link)) {
                    continue;
                }
                if (always.add(link.nextNode().id())) {
                    queue.add(link.nextNode());
                }
            }
        }
        // Pass 2: every node, with the conditional step that gates it. Only the
        // nodes pass 1 did not cover need an answer; the first one found wins,
        // and it never overwrites an unconditional arrival.
        Map<String, Link> firstBlocker = new HashMap<>();
        Set<String> seen = new HashSet<>();
        queue.add(start);
        seen.add(start.id());
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            Link inherited = firstBlocker.get(node.id());
            for (Link link : node.nextLinks()) {
                String next = link.nextNode().id();
                if (!seen.add(next)) {
                    continue;
                }
                Link blocker = inherited != null ? inherited : (skippable(link) ? link : null);
                if (!always.contains(next) && blocker != null) {
                    firstBlocker.put(next, blocker);
                }
                queue.add(link.nextNode());
            }
        }
        return new Reach(always, firstBlocker);
    }

    /** The two ways a link's condition can leave it without a token. */
    private static boolean skippable(Link link) {
        if (!link.when().isEmpty()) {
            // fanOut and loopRun skip a matching-false link.
            return true;
        }
        Node prev = link.prevNode();
        // exclusiveOut picks exactly one branch, so every other link of a
        // multi-branch EXCLUSIVE may be skipped.
        return prev.type() == NodeType.EXCLUSIVE && prev.nextLinks().size() > 1;
    }

    /** Where the empty flow comes from, in the graph's own vocabulary. */
    private static String describe(Link input, Reach reach) {
        if (!input.when().isEmpty()) {
            return "link '" + input.prevId() + "->" + input.nextId()
                + "' carries a condition";
        }
        Node prev = input.prevNode();
        if (prev.type() == NodeType.EXCLUSIVE && prev.nextLinks().size() > 1) {
            return "EXCLUSIVE '" + prev.id() + "' chooses one of its "
                + prev.nextLinks().size() + " branches";
        }
        Link blocker = reach.firstBlocker().get(prev.id());
        if (blocker == null) {
            return "'" + prev.id() + "' is not reachable from the entry";
        }
        return "'" + prev.id() + "' is reachable only through " + gate(blocker);
    }

    /** The conditional step itself: a conditioned link, or the split it leaves. */
    private static String gate(Link link) {
        if (!link.when().isEmpty()) {
            return "conditional link '" + link.prevId() + "->" + link.nextId() + "'";
        }
        Node from = link.prevNode();
        if (from.type() == NodeType.EXCLUSIVE && from.nextLinks().size() > 1) {
            return "EXCLUSIVE '" + from.id() + "', which chooses one of its "
                + from.nextLinks().size() + " branches";
        }
        return "link '" + link.prevId() + "->" + link.nextId() + "'";
    }
}
