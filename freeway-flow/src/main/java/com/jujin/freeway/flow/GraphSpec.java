package com.jujin.freeway.flow;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Canonical graph blueprint — the single DAG authoring surface.
 *
 * <p>Accepts the canonical {@code id/title/driver/version/entry/meta/nodes/links}
 * shape only. The legacy solon-flow {@code layout} format was dropped in favor
 * of this explicit (V, E) representation.</p>
 */
public class GraphSpec {
    public static final int VERSION = 3;
    private static final Logger LOG = LoggerFactory.getLogger(GraphSpec.class);

    private final String id;
    private String title;
    private String driver;
    private String entry;
    private final Map<String, Object> meta = new LinkedHashMap<>();
    private final Map<String, NodeSpec> nodes = new LinkedHashMap<>();
    private final List<LinkSpec> links = new ArrayList<>();
    private Set<String> bfsOrder;

    public GraphSpec(String id) {
        this(id, null, null);
    }

    public GraphSpec(String id, String title) {
        this(id, title, null);
    }

    public GraphSpec(String id, String title, String driver) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Graph id must not be blank");
        }
        this.id = id;
        this.title = title;
        this.driver = driver;
    }

    public static GraphSpec create(String id, Consumer<GraphSpec> definition) {
        GraphSpec blueprint = new GraphSpec(id);
        definition.accept(blueprint);
        return blueprint;
    }

    public static GraphSpec create(String id, String title, Consumer<GraphSpec> definition) {
        GraphSpec blueprint = new GraphSpec(id, title);
        definition.accept(blueprint);
        return blueprint;
    }

    public static GraphSpec create(String id, String title, String driver, Consumer<GraphSpec> definition) {
        GraphSpec blueprint = new GraphSpec(id, title, driver);
        definition.accept(blueprint);
        return blueprint;
    }

    public GraphSpec then(Consumer<GraphSpec> definition) {
        definition.accept(this);
        invalidate();
        return this;
    }

    void invalidate() {
        this.bfsOrder = null;
    }

    public GraphSpec title(String title) {
        this.title = title;
        invalidate();
        return this;
    }

    public GraphSpec driver(String driver) {
        this.driver = driver;
        invalidate();
        return this;
    }

    public GraphSpec entry(String entry) {
        this.entry = entry;
        invalidate();
        return this;
    }

    public GraphSpec metaPut(String key, Object value) {
        if (key != null && !key.isEmpty()) {
            meta.put(key, value);
        }
        invalidate();
        return this;
    }

    public GraphSpec meta(Map<String, Object> meta) {
        if (meta != null && !meta.isEmpty()) {
            this.meta.putAll(meta);
        }
        invalidate();
        return this;
    }

    public NodeSpec addNode(String id, NodeType type) {
        if (nodes.containsKey(id)) {
            throw new IllegalArgumentException(
                "Duplicate node id '" + id + "' in graph: " + this.id);
        }
        NodeSpec node = new NodeSpec(this, id, type);
        nodes.put(id, node);
        invalidate();
        return node;
    }

    public NodeSpec addStart(String id) {
        NodeSpec node = addNode(id, NodeType.START);
        if (entry == null) {
            entry = id;
        }
        return node;
    }

    public NodeSpec addEnd(String id) {
        return addNode(id, NodeType.END);
    }

    public NodeSpec addActivity(String id) {
        return addNode(id, NodeType.ACTIVITY);
    }

    public NodeSpec addActivity(NamedTaskHandler handler) {
        Objects.requireNonNull(handler, "handler");
        NodeSpec node = addActivity(handler.name());
        node.title(handler.title());
        node.task(handler);
        return node;
    }

    public NodeSpec addInclusive(String id) {
        return addNode(id, NodeType.INCLUSIVE);
    }

    public NodeSpec addExclusive(String id) {
        return addNode(id, NodeType.EXCLUSIVE);
    }

    public NodeSpec addParallel(String id) {
        return addNode(id, NodeType.PARALLEL);
    }

    public NodeSpec addLoop(String id) {
        return addNode(id, NodeType.LOOP);
    }

    public NodeSpec node(String id) {
        return nodes.get(id);
    }

    public LinkSpec link(String from, String to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        LinkSpec link = new LinkSpec(this, from, to);
        links.add(link);
        invalidate();
        return link;
    }

    public Graph create() {
        drainNodeLinks();
        normalize();
        return new Graph(this);
    }

    /** Flushes pending links from all nodes into top-level links list. */
    void drainNodeLinks() {
        for (NodeSpec node : nodes.values()) {
            for (var pending : node.drainPendingLinks()) {
                LinkSpec link = link(node.id(), pending.to());
                if (pending.configure() != null) {
                    pending.configure().accept(link);
                }
            }
        }
    }

    public Map<String, Object> toMap() {
        drainNodeLinks();
        validateEntry();
        GraphSpec normalized = normalize();
        Map<String, Object> domRoot = new LinkedHashMap<>();
        domRoot.put("id", id);
        if (title != null && !title.isEmpty()) {
            domRoot.put("title", title);
        }
        if (driver != null && !driver.isEmpty()) {
            domRoot.put("driver", driver);
        }
        domRoot.put("version", VERSION);

        String resolvedEntry = normalized.resolveEntry();
        if (resolvedEntry != null && !resolvedEntry.isEmpty()) {
            domRoot.put("entry", resolvedEntry);
        }

        if (!meta.isEmpty()) {
            domRoot.put("meta", meta);
        }

        List<Map<String, Object>> domNodes = new ArrayList<>();
        domRoot.put("nodes", domNodes);
        for (NodeSpec node : normalized.nodesInCompileOrder()) {
            Map<String, Object> domNode = new LinkedHashMap<>();
            domNode.put("id", node.id);
            domNode.put("type", node.type.toString());
            if (node.title != null && !node.title.isEmpty()) {
                domNode.put("title", node.title);
            }
            if (!node.meta.isEmpty()) {
                domNode.put("meta", node.meta);
            }
            if (node.when != null && !node.when.isEmpty()) {
                domNode.put("when", node.when);
            }
            if (node.whenHandler != null) {
                throw new IllegalStateException(
                    "Node '" + node.id + "' uses an inline ConditionHandler "
                        + "which cannot be serialized — bind it via the "
                        + "container and reference it by name");
            }
            if (node.task != null && !node.task.isEmpty()) {
                domNode.put("task", node.task);
            }
            if (!node.data.isEmpty()) {
                domNode.put("data", node.data);
            }
            if (node.taskHandler != null) {
                throw new IllegalStateException(
                    "Node '" + node.id + "' uses an inline TaskHandler which "
                        + "cannot be serialized — bind it via the container and "
                        + "reference it by name");
            }
            domNodes.add(domNode);
        }

        List<Map<String, Object>> domLinks = new ArrayList<>();
        domRoot.put("links", domLinks);
        for (LinkSpec link : normalized.links) {
            Map<String, Object> domLink = new LinkedHashMap<>();
            domLink.put("from", link.from);
            domLink.put("to", link.to);
            if (link.title != null && !link.title.isEmpty()) {
                domLink.put("title", link.title);
            }
            if (!link.meta.isEmpty()) {
                domLink.put("meta", link.meta);
            }
            if (link.when != null && !link.when.isEmpty()) {
                domLink.put("when", link.when);
            }
            if (link.whenHandler != null) {
                throw new IllegalStateException(
                    "Link '" + link.from + "' -> '" + link.to + "' uses an "
                        + "inline ConditionHandler which cannot be serialized "
                        + "— bind it via the container and reference it by name");
            }
            if (link.priority != 0) {
                domLink.put("priority", link.priority);
            }
            domLinks.add(domLink);
        }

        return domRoot;
    }

    public String toJson() {
        return JsonUtils.stringify(toMap());
    }

    public static GraphSpec fromText(String text) {
        JsonObject dom = JsonUtils.parseObject(text);
        Integer version = dom.containsKey("version") ? dom.getInt("version") : null;
        if (version != null && version == VERSION && dom.containsKey("nodes") && dom.containsKey("links")) {
            return fromDom(dom);
        }

        throw new IllegalArgumentException(
            "Expected a v3 graph definition (version=" + VERSION
                + " with 'nodes' and 'links'), found: "
                + (version == null ? "no version field" : "version " + version)
                + (dom.containsKey("nodes") ? "" : ", missing 'nodes'")
                + (dom.containsKey("links") ? "" : ", missing 'links'"));
    }

    /** Package-private: {@link #fromText(String)} owns the version gate, and a
     *  second public entry point would let a caller build a spec that skips it. */
    static GraphSpec fromDom(JsonObject dom) {
        GraphSpec blueprint = new GraphSpec(
                dom.getString("id"),
                dom.getString("title"),
                dom.getString("driver"));

        if (dom.containsKey("entry")) {
            blueprint.entry(dom.getString("entry"));
        }
        blueprint.meta(toMap(dom.getObject("meta")));

        JsonArray nodesDom = dom.getArray("nodes");
        if (nodesDom == null) {
            throw new IllegalArgumentException("No 'nodes' found in graph definition");
        }

        for (int i = 0; i < nodesDom.size(); i++) {
            JsonObject nodeDom = requireObject(nodesDom, i, "Node");

            String nodeId = requireString(nodeDom, "id", "Node at index " + i);
            String typeStr = requireString(nodeDom, "type", "Node '" + nodeId + "'");
            NodeType nodeType = NodeType.of(typeStr);
            NodeSpec node = blueprint.addNode(nodeId, nodeType);
            node.title(nodeDom.getString("title"));
            node.meta(toMap(nodeDom.getObject("meta")));
            node.data(toMap(nodeDom.getObject("data")));
            node.when(nodeDom.getString("when"));
            node.task(nodeDom.getString("task"));
        }

        JsonArray linksDom = dom.getArray("links");
        if (linksDom != null) {
            for (int i = 0; i < linksDom.size(); i++) {
                JsonObject linkDom = requireObject(linksDom, i, "Link");

                String from = requireString(linkDom, "from", "Link at index " + i);
                String to = requireString(linkDom, "to", "Link at index " + i);
                LinkSpec link = blueprint.link(from, to);
                link.title(linkDom.getString("title"));
                link.meta(toMap(linkDom.getObject("meta")));
                link.when(linkDom.getString("when"));
                Integer priority = linkDom.containsKey("priority") ? linkDom.getInt("priority") : null;
                if (priority != null) {
                    link.priority(priority);
                }
            }
        }

        return blueprint;
    }

    public static GraphSpec copy(Graph graph) {
        GraphSpec blueprint = new GraphSpec(graph.id(), graph.title(), graph.driver());

        if (graph.start() != null) {
            blueprint.entry(graph.start().id());
        }
        blueprint.meta(graph.metas());

        for (Node node : graph.nodes().values()) {
            NodeSpec nodeBlueprint = blueprint.addNode(node.id(), node.type());
            nodeBlueprint.title(node.title());
            nodeBlueprint.meta(node.metas());
            nodeBlueprint.data(node.data());
            if (node.when() != null) {
                if (node.when().handler() != null) {
                    nodeBlueprint.when(node.when().handler());
                } else {
                    nodeBlueprint.when(node.when().description());
                }
            }
            if (node.task() != null) {
                if (node.task().handler() != null) {
                    nodeBlueprint.task(node.task().handler());
                } else {
                    nodeBlueprint.task(node.task().description());
                }
            }
        }

        for (Link link : graph.links()) {
            LinkSpec linkBlueprint = blueprint.link(link.prevId(), link.nextId());
            linkBlueprint.title(link.title());
            linkBlueprint.meta(link.metas());
            if (link.when() != null) {
                if (link.when().handler() != null) {
                    linkBlueprint.when(link.when().handler());
                } else {
                    linkBlueprint.when(link.when().description());
                }
            }
            linkBlueprint.priority(link.priority());
        }

        return blueprint;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String driver() {
        return driver;
    }

    public int version() {
        return VERSION;
    }

    public String entry() {
        return resolveEntry();
    }

    public Map<String, Object> meta() {
        return Collections.unmodifiableMap(meta);
    }

    public Map<String, NodeSpec> nodes() {
        return Collections.unmodifiableMap(nodes);
    }

    public List<LinkSpec> links() {
        return Collections.unmodifiableList(links);
    }

    /**
     * Validate and prepare the blueprint for graph construction.
     *
     * <p>Checks that every link references real nodes, performs BFS from
     * entry to determine reachable nodes, and warns about disconnected
     * subgraphs. Idempotent — subsequent calls are no-ops.</p>
     */
    GraphSpec normalize() {
        // 0. Explicit entry must exist — fail with the dedicated message
        //    before entry-candidate resolution.
        validateEntry();

        // 1. Validate all link references resolve, and index the outgoing
        //    edges once — cycle detection and the reachability BFS below both
        //    walk them, and a per-scan re-filter of every link would make
        //    each O(V·E) for no reason on a graph that is immutable after
        //    this point anyway.
        Map<String, List<LinkSpec>> outgoing = new LinkedHashMap<>();
        for (LinkSpec link : links) {
            if (!nodes.containsKey(link.from)) {
                throw new IllegalStateException(
                        "Link references unknown source node '" + link.from
                        + "' -> '" + link.to + "' in graph: " + id);
            }
            if (!nodes.containsKey(link.to)) {
                throw new IllegalStateException(
                        "Link references unknown target node '" + link.from
                        + "' -> '" + link.to + "' in graph: " + id);
            }
            outgoing.computeIfAbsent(link.from, k -> new ArrayList<>()).add(link);
        }

        // 1.5 Reject cycles — a cyclic graph never completes its walk. LOOP
        //     iteration is driven by the engine's $in loop, never by a link
        //     back to the loop node, so no cycle is legitimate here.
        List<String> cycle = findCycle(outgoing);
        if (cycle != null) {
            throw new IllegalStateException(
                "Cycle detected in graph '" + id + "': " + String.join(" -> ", cycle)
                + ". Flow graphs must be acyclic (LOOP iteration is driven by $in,"
                + " not by link back-edges)."
            );
        }

        // 1.6 Reject duplicate unconditional links: two edges with the same
        //     from+to where at least one carries no condition would execute
        //     the target node twice (double task execution, double dead-end
        //     bookkeeping). Multi-edges are legitimate only when every edge
        //     is condition-guarded. The first unconditional edge claims the
        //     pair; a second edge over the same pair (conditional either way)
        //     collides — one hash-set touch per edge, so a 20k-link chain
        //     validates in linear time.
        Set<String> unconditionalPairs = new HashSet<>();
        Set<String> anyPairs = new HashSet<>();
        for (LinkSpec link : links) {
            String pair = link.from + '\u0000' + link.to;
            boolean unconditional = link.when == null || link.when.isEmpty();
            if (unconditional) {
                if (!unconditionalPairs.add(pair)) {
                    throw duplicateUnconditional(link);
                }
                if (anyPairs.contains(pair)) {
                    throw duplicateUnconditional(link);
                }
            } else if (unconditionalPairs.contains(pair)) {
                throw duplicateUnconditional(link);
            }
            anyPairs.add(pair);
        }

        // 1.7 Closed vocabulary, expressions, join declaration and data keys —
        //     everything that can be known statically is known here, at
        //     startup: a bad task reference, a malformed condition, a
        //     misplaced or unknown `join`, or a blank data key fails the
        //     build instead of surfacing at the first run that happens to
        //     route through the node.
        for (NodeSpec node : nodes.values()) {
            validateTask(node);
            validateCondition(node.when, node.whenHandler,
                "Node '" + node.id() + "' when");
            validateJoin(node);
            for (String key : node.data.keySet()) {
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException(
                        "Node '" + node.id() + "' in graph '" + id
                            + "' has a blank data key — data keys are context"
                            + " keys and must not be blank");
                }
            }
        }
        for (LinkSpec link : links) {
            validateCondition(link.when, link.whenHandler,
                "Link '" + link.from + "' -> '" + link.to + "' when");
        }

        // 2. Validate entry — v3 requires exactly one entry point.
        //    The entry node keeps its original type in the runtime graph;
        //    execution starts from that node regardless of type.
        String resolvedEntry = resolveEntry();
        List<String> starts = nodes.values().stream()
            // When entry is explicit, it is the ONLY start candidate: a
            // stray START node must not reject the graph (the old check
            // counted every START, making the error's own remedy — "use
            // 'entry'" — impossible).
            .filter(n -> entry != null && !entry.isEmpty()
                ? n.id.equals(resolvedEntry)
                : n.type == NodeType.START)
            .map(n -> n.id)
            .distinct()
            .toList();
        if (starts.isEmpty()) {
            throw new IllegalStateException(
                "No entry node found in graph: " + id
                + ". Set 'entry' or add a START node."
            );
        }
        if (starts.size() > 1) {
            String detail = entry != null
                ? "Explicit entry is '" + entry + "', but multiple START nodes exist: " + starts
                : "Multiple START nodes (" + starts + ") without explicit 'entry'";
            throw new IllegalStateException(
                detail + " in graph: " + id
                + ". Use 'entry' to specify which START is the graph entry point."
            );
        }

        // 3. BFS from entry to discover reachable nodes in traversal order
        bfsOrder = new LinkedHashSet<>();
        if (resolvedEntry != null && nodes.containsKey(resolvedEntry)) {
            Deque<String> queue = new ArrayDeque<>();
            queue.addLast(resolvedEntry);
            while (!queue.isEmpty()) {
                String nodeId = queue.pollFirst();
                if (!bfsOrder.add(nodeId)) {
                    continue;
                }
                for (LinkSpec link : outgoing.getOrDefault(nodeId, List.of())) {
                    queue.addLast(link.to);
                }
            }
        }

        // 3. Warn about unreachable nodes (don't break — they may be
        //    referenced by subgraph calls or future graph composition)
        if (bfsOrder.size() < nodes.size()) {
            for (String nodeId : nodes.keySet()) {
                if (!bfsOrder.contains(nodeId)) {
                    LOG.warn("Unreachable node '{}' (not reachable from entry '{}') in graph: {}",
                            nodeId, resolvedEntry, id);
                }
            }
        }

        return this;
    }

    private List<String> findCycle(Map<String, List<LinkSpec>> outgoing) {
        // Iterative three-color DFS — recursion depth here would match the
        // chain length and blow the stack on a 20k-link path, defeating the
        // engine's own iterative walk. Each frame is a node plus its child
        // cursor; the path stack is the current DFS branch, colored 1, and
        // recolored 2 on unwind.
        Map<String, Integer> state = new LinkedHashMap<>();
        Deque<String> path = new ArrayDeque<>();
        Deque<java.util.Iterator<LinkSpec>> cursors = new ArrayDeque<>();
        for (String root : nodes.keySet()) {
            if (state.getOrDefault(root, 0) == 2) {
                continue;
            }
            if (!dfsCycleIterative(root, outgoing, state, path, cursors)) {
                path.clear();
                cursors.clear();
                continue;
            }
            return cycleDescription(path);
        }
        return null;
    }

    /** Returns true when a back-edge was found; path then holds the DFS branch. */
    private boolean dfsCycleIterative(
        String root,
        Map<String, List<LinkSpec>> outgoing,
        Map<String, Integer> state,
        Deque<String> path,
        Deque<java.util.Iterator<LinkSpec>> cursors
    ) {
        state.put(root, 1);
        path.addLast(root);
        cursors.push(outgoing.getOrDefault(root, List.of()).iterator());
        while (!cursors.isEmpty()) {
            java.util.Iterator<LinkSpec> cursor = cursors.peek();
            if (!cursor.hasNext()) {
                // Fully explored: unwind this frame.
                cursors.pop();
                state.put(path.removeLast(), 2);
                continue;
            }
            String next = cursor.next().to;
            int s = state.getOrDefault(next, 0);
            if (s == 2) {
                continue;                       // already closed
            }
            if (s == 1) {                       // back-edge → cycle
                path.addLast(next);
                return true;
            }
            state.put(next, 1);
            path.addLast(next);
            cursors.push(outgoing.getOrDefault(next, List.of()).iterator());
        }
        return false;
    }

    /** Extracts the cyclic segment from the DFS path (ends with the repeated node). */
    private List<String> cycleDescription(Deque<String> path) {
        List<String> list = new ArrayList<>(path);
        String closing = list.getLast();
        int start = list.indexOf(closing);
        return List.copyOf(list.subList(start, list.size()));
    }

    private List<NodeSpec> nodesInCompileOrder() {
        // Reachable first (BFS order), then unreachable (insertion order) — never
        // silently drop nodes; toMap()/toJson() must round-trip faithfully.
        List<NodeSpec> ordered = new ArrayList<>(nodes.size());
        Set<String> placed = new HashSet<>();
        if (bfsOrder != null && !bfsOrder.isEmpty()) {
            for (String nodeId : bfsOrder) {
                NodeSpec node = nodes.get(nodeId);
                if (node != null && placed.add(nodeId)) ordered.add(node);
            }
        }
        for (NodeSpec node : nodes.values()) {
            if (placed.add(node.id)) ordered.add(node);
        }
        return ordered;
    }

    private String resolveEntry() {
        if (entry != null && !entry.isEmpty()) {
            return entry;
        }

        // Auto-detect: single START node wins
        String singleStart = null;
        for (NodeSpec node : nodes.values()) {
            if (node.type == NodeType.START) {
                if (singleStart != null) {
                    return null; // ambiguous — user must set entry explicitly
                }
                singleStart = node.id;
            }
        }
        return singleStart;
    }

    private void validateEntry() {
        if (entry != null && !entry.isEmpty() && !nodes.containsKey(entry)) {
            throw new IllegalStateException(
                "Entry node '" + entry + "' is not declared in graph '" + id
                    + "' — entry must name one of the graph's nodes"
            );
        }
    }

    /**
     * The v3 task vocabulary: {@code @name}, {@code #graphId} or an inline
     * handler. A {@code $} reference is now the node's data field; a
     * {@code !} reference is now an {@code @name} with a contributed id —
     * both fail at build with the migration named, not at first execution.
     */
    private void validateTask(NodeSpec node) {
        String task = node.task;
        if (task == null || task.isBlank()) {
            return; // inline handler or no task
        }
        String t = task.trim();
        if (t.length() > 1 && (t.startsWith("@") || t.startsWith("#"))) {
            return;
        }
        String hint = "";
        if (t.startsWith("$")) {
            hint = " A '$' reference was dropped in v3: static values are"
                + " the node's 'data' field.";
        } else if (t.startsWith("!")) {
            hint = " A '!' reference was dropped in v3: contribute the"
                + " handler with an id and reference it as '@name'.";
        }
        throw new IllegalStateException(
            "Node '" + node.id + "' in graph '" + id + "' has unsupported"
                + " task '" + task + "'. The vocabulary is '@name', '#graphId'"
                + " or an inline handler." + hint
        );
    }

    private void validateCondition(String when, ConditionHandler handler, String what) {
        if (handler != null || when == null || when.isBlank()) {
            return;
        }
        String w = when.trim();
        if (w.startsWith("@") && w.length() > 1) {
            return; // @name handler reference — resolved at run
        }
        try {
            ExprEvaluator.validate(when);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                what + " expression is invalid in graph '" + id + "': " + e.getMessage(), e);
        }
    }

    /** The {@code join} meta is a PARALLEL fork field, merge (default) or shared. */
    private void validateJoin(NodeSpec node) {
        Object join = node.meta.get("join");
        if (join == null) {
            return;
        }
        if (node.type != NodeType.PARALLEL) {
            throw new IllegalStateException(
                "Node '" + node.id + "' declares a 'join' meta but is " + node.type
                    + " — join is a PARALLEL fork field in graph: " + id);
        }
        String v = String.valueOf(join);
        if (!v.equals("merge") && !v.equals("shared")) {
            throw new IllegalStateException(
                "PARALLEL node '" + node.id + "' has join '" + v
                    + "' — expected 'merge' (branch-isolated writes, conflict-checked)"
                    + " or 'shared' (single-writer); in graph: " + id);
        }
    }

    private IllegalStateException duplicateUnconditional(LinkSpec link) {
        return new IllegalStateException(
            "Duplicate unconditional link '" + link.from + "' -> '" + link.to
                + "' in graph: " + id
                + ". Multiple edges between the same nodes must"
                + " carry distinct 'when' conditions.");
    }

    private static Map<String, Object> toMap(JsonObject obj) {
        if (obj == null || obj.isEmpty()) {
            return null;
        }
        return new LinkedHashMap<>(obj.toMap());
    }

    /** Fetches array element {@code index} as a JsonObject, or fails with the shared "must be an object" error. */
    private static JsonObject requireObject(JsonArray array, int index, String kind) {
        Object item = array.get(index);
        if (!(item instanceof JsonObject obj)) {
            throw new IllegalArgumentException(
                kind + " at index " + index + " must be an object, got: "
                    + (item == null ? "null" : item.getClass().getSimpleName()));
        }
        return obj;
    }

    /** Reads a required non-blank string field, or fails with the shared "missing required field" error. */
    private static String requireString(JsonObject obj, String field, String what) {
        String value = obj.getString(field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                what + " is missing required '" + field + "' field");
        }
        return value;
    }

}