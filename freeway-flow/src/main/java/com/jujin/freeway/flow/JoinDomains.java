package com.jujin.freeway.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Graph-shape checks made once, where the graph is built.
 *
 * <p>The one check today: a join whose branches come from different iteration
 * domains. A join waits for {@code prevLinks().size()} arrivals — a count that
 * is right when every branch reaches the join exactly once, and stops being
 * right the moment a LOOP sits upstream of one branch but not another: that
 * branch arrives once <em>per iteration</em>. No reset policy satisfies the
 * count (zeroing each iteration starves the outside branch; accumulating
 * over-activates and then re-marks the join dead), so the graph is refused
 * when it is built, with the join and its branches named, instead of
 * surfacing at the end of a run as "dead end at node 'j'" — a message that
 * describes a lost branch when no branch was lost.
 *
 * <p><b>Why this lives here and not in {@code FlowEngine.load}.</b> Validation
 * on one entry point is a fact that holds only while every caller goes through
 * that entry point; {@code eval(Graph)} and {@code eval(GraphSpec)} do not.
 * The fact belongs to the graph, so it is stated once, in the constructor
 * every graph goes through.
 *
 * <p>What is fine: every branch arriving once per the same unit — no loop
 * upstream, or a join fed entirely from within one iteration domain,
 * including directly from a loop node itself, since {@code loopRun} walks the
 * loop's matching successors once per item. A LOOP without {@code $for} fans
 * out once, so it counts as no loop at all.
 */
final class JoinDomains {

    private JoinDomains() {}

    /** Refuses a join fed from more than one iteration domain. */
    static void rejectMixed(Graph graph) {
        Domains domains = domains(graph);
        for (Node node : graph.nodes().values()) {
            if (node.type() != NodeType.INCLUSIVE && node.type() != NodeType.PARALLEL) continue;
            if (node.prevLinks().size() < 2) continue;
            String domain = null;
            boolean first = true;
            boolean mixed = false;
            for (Link link : node.prevLinks()) {
                String branch = domains.outermost().get(link.prevNode().id());
                // `first`, not `domain == null`: null is itself a legitimate
                // domain (a branch above no loop), so using it as the sentinel
                // would let a later branch overwrite it and the comparison would
                // never run.
                if (first) {
                    domain = branch;
                    first = false;
                } else if (!Objects.equals(domain, branch)) {
                    mixed = true;
                }
                // A branch that two loops repeat without either containing the
                // other belongs to neither exclusively, so no single domain can
                // describe it: the arrival count is wrong whichever way it is
                // read, and that is a rejection no matter what the other
                // branches say.
                if (domains.ambiguous().contains(link.prevNode().id())) {
                    mixed = true;
                }
            }
            if (mixed) {
                throw new IllegalArgumentException(
                    "Join '" + node.id() + "' in graph '" + graph.id() + "' is fed from "
                        + "different iteration domains (" + describe(node, domains)
                        + "): a join waits for " + node.prevLinks().size() + " arrivals, "
                        + "but a branch upstream of a LOOP arrives once per iteration "
                        + "while one outside every LOOP arrives once, so that count can "
                        + "line up with neither one iteration nor the whole run. Give "
                        + "every branch the same domain — all inside one loop, or all "
                        + "downstream of them."
                );
            }
        }
    }

    /** The domains a join's branches come from, named, for the error message. */
    private static String describe(Node join, Domains domains) {
        List<String> seen = new ArrayList<>();
        for (Link link : join.prevLinks()) {
            String id = link.prevNode().id();
            List<String> owning = domains.owners().get(id);
            String label;
            if (owning == null || owning.isEmpty()) {
                label = "'" + id + "' runs once";
            } else if (owning.size() == 1) {
                label = "'" + id + "' repeats per iteration of '" + owning.get(0) + "'";
            } else {
                label = "'" + id + "' repeats per iteration of "
                    + String.join(" and ", owning.stream().map(loop -> "'" + loop + "'").toList());
            }
            if (!seen.contains(label)) {
                seen.add(label);
            }
        }
        return String.join(", ", seen);
    }

    /**
     * Which iterating loop repeats each join branch, and where no single loop
     * can claim one.
     *
     * <p>Outermost is the granularity that matters, because the per-iteration
     * join reset is re-armed by the outermost loop's body: a join fed by a
     * nested loop's node and by a sibling inside the same outer body gets
     * exactly one arrival from each per outer iteration, which is countable.
     * Innermost would call that pair "different domains" and refuse a graph
     * the engine runs.
     *
     * <p>Ownership is never awarded to the first loop examined: that depends
     * on the order the loops were declared in (same-depth siblings are
     * otherwise unordered), and the verdict has to be a property of the graph,
     * not of the document that declares it. Instead each branch node is asked
     * which loops can reach it, walking backwards from the node; a node two
     * loops repeat where neither contains the other has no outermost owner at
     * all and is reported as ambiguous rather than awarded to whichever loop
     * came first.
     *
     * <p>Only join branches are asked, not every node: the walk is over the
     * branch's ancestors, so the cost is bounded by the shape that is actually
     * being validated rather than by loops x downstream. The straightforward
     * "every loop walks everything it reaches" version measured 1 651 ms on
     * 500 sibling loops over a 20 000-node shared tail; this way is 106 ms on
     * that graph, 38 ms on the 5 000-node one, and flat in the number of loops.
     */
    private static Domains domains(Graph graph) {
        Set<String> branches = new HashSet<>();
        for (Node node : graph.nodes().values()) {
            if (node.type() != NodeType.INCLUSIVE && node.type() != NodeType.PARALLEL) continue;
            if (node.prevLinks().size() < 2) continue;
            for (Link link : node.prevLinks()) {
                branches.add(link.prevNode().id());
            }
        }

        Map<String, List<String>> owners = new HashMap<>();
        for (String branch : branches) {
            List<Node> loops = loopsReaching(graph, branch);
            if (loops.isEmpty()) continue;
            // Deterministic, and deliberately not declaration order: the loop
            // id is what the message names, so ties cannot reach the verdict.
            loops.sort(Comparator.comparing(Node::id));
            owners.put(branch, loops.stream().map(Node::id).toList());
        }

        Map<String, Integer> depth = nestingDepth(graph);
        Map<String, String> outermost = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : owners.entrySet()) {
            List<String> owning = entry.getValue();
            String outer = owning.get(0);
            for (String candidate : owning) {
                if (depth.getOrDefault(candidate, 0) < depth.getOrDefault(outer, 0)) {
                    outer = candidate;
                }
            }
            outermost.put(entry.getKey(), outer);
            for (String other : owning) {
                if (other.equals(outer) || ambiguous.contains(entry.getKey())) {
                    continue;
                }
                // Nested either way is one domain: the outer loop repeats the
                // inner one, so the node has a single outermost owner after all.
                if (!reachesLoop(graph, outer, other) && !reachesLoop(graph, other, outer)) {
                    ambiguous.add(entry.getKey());
                }
            }
        }
        return new Domains(outermost, ambiguous, owners);
    }

    /** Whether {@code ancestor}'s body contains the loop {@code nodeId}. */
    private static boolean reachesLoop(Graph graph, String ancestor, String nodeId) {
        return loopsReaching(graph, nodeId).stream()
            .anyMatch(loop -> loop.id().equals(ancestor));
    }

    /**
     * The iterating loops that can reach {@code nodeId} — its own Loop ancestors,
     * found by walking links backwards. The node counts as its own owner when it
     * <em>is</em> a loop, since {@code loopRun} walks a loop's successors once per
     * item and a join that branches straight off the loop node is inside it.
     */
    private static List<Node> loopsReaching(Graph graph, String nodeId) {
        List<Node> loops = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<Node> pending = new ArrayDeque<>();
        Node start = graph.nodes().get(nodeId);
        if (start != null) {
            pending.push(start);
        }
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (!visited.add(node.id())) {
                continue;
            }
            if (node.iterates()) {
                loops.add(node);
            }
            for (Link link : node.prevLinks()) {
                pending.push(link.prevNode());
            }
        }
        return loops;
    }

    /** Which loop repeats each node, and where that is not a single answer. */
    private record Domains(
        Map<String, String> outermost,
        Set<String> ambiguous,
        Map<String, List<String>> owners
    ) {
    }

    /**
     * Iterating loops above each node, as a topological pass: {@code depth(n)}
     * is the deepest chain of iterating loops that repeats {@code n}.
     *
     * <p>Iterative on purpose. The memoized recursion this replaces was O(V)
     * deep, so a 100 000-node chain declared in non-topological order blew the
     * JVM stack during {@code load} — a crash where the check was supposed to
     * produce a clean verdict. Kahn's order visits each node once, after all
     * of its parents, so the same answer costs no stack at all.</p>
     */
    private static Map<String, Integer> nestingDepth(Graph graph) {
        Map<String, Integer> depth = new HashMap<>();
        Map<String, Integer> deepestParent = new HashMap<>();
        Map<String, Integer> waiting = new HashMap<>();
        Deque<Node> ready = new ArrayDeque<>();
        for (Node node : graph.nodes().values()) {
            waiting.put(node.id(), node.prevLinks().size());
            if (node.prevLinks().isEmpty()) {
                ready.add(node);
            }
        }
        while (!ready.isEmpty()) {
            Node node = ready.remove();
            int above = deepestParent.getOrDefault(node.id(), 0);
            int value = above + (node.iterates() ? 1 : 0);
            depth.put(node.id(), value);
            for (Node child : node.nextNodes()) {
                deepestParent.merge(child.id(), value, Math::max);
                int left = waiting.merge(child.id(), -1, Integer::sum);
                if (left == 0) {
                    ready.add(child);
                }
            }
        }
        return depth;
    }
}
