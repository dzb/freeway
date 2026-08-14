package com.jujin.freeway.flow;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Canonical graph blueprint — the single DAG authoring surface.
 *
 * <p>Accepts the canonical {@code id/title/driver/version/entry/meta/nodes/links}
 * shape only. The legacy solon-flow {@code layout} format was dropped in favor
 * of this explicit (V, E) representation.</p>
 */
public class GraphSpec {
    public static final int VERSION = 2;
    private static final System.Logger LOG = System.getLogger(GraphSpec.class.getName());

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

    public NodeSpec addActivity(NamedTaskComponent component) {
        Objects.requireNonNull(component, "component");
        NodeSpec node = addActivity(component.name());
        node.title(component.title());
        node.task(component);
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

    public NodeSpec getNode(String id) {
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
                LinkSpec link = link(node.getId(), pending.to());
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
            if (node.whenComponent != null) {
                throw new IllegalStateException(
                    "Node '" + node.id + "' uses an inline ConditionComponent "
                        + "which cannot be serialized — bind it via the "
                        + "container and reference it by name");
            }
            if (node.task != null && !node.task.isEmpty()) {
                domNode.put("task", node.task);
            }
            if (node.taskComponent != null) {
                throw new IllegalStateException(
                    "Node '" + node.id + "' uses an inline TaskComponent which "
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
            if (link.whenComponent != null) {
                throw new IllegalStateException(
                    "Link '" + link.from + "' -> '" + link.to + "' uses an "
                        + "inline ConditionComponent which cannot be serialized "
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
            "Expected a v2 graph definition (version=" + VERSION
                + " with 'nodes' and 'links'), found: "
                + (version == null ? "no version field" : "version " + version)
                + (dom.containsKey("nodes") ? "" : ", missing 'nodes'")
                + (dom.containsKey("links") ? "" : ", missing 'links'"));
    }

    public static GraphSpec fromDom(JsonObject dom) {
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
            NodeType nodeType = NodeType.nameOf(typeStr);
            if (nodeType == NodeType.UNKNOWN) {
                throw new IllegalArgumentException(
                    "Unknown node type '" + typeStr + "' for node '" + nodeId
                        + "'. Valid types: START, END, ACTIVITY, EXCLUSIVE, "
                        + "INCLUSIVE, PARALLEL, LOOP."
                );
            }
            NodeSpec node = blueprint.addNode(nodeId, nodeType);
            node.title(nodeDom.getString("title"));
            node.meta(toMap(nodeDom.getObject("meta")));
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
        GraphSpec blueprint = new GraphSpec(graph.getId(), graph.getTitle(), graph.getDriver());

        if (graph.getStart() != null) {
            blueprint.entry(graph.getStart().getId());
        }
        blueprint.meta(graph.getMetas());

        for (Node node : graph.getNodes().values()) {
            NodeSpec nodeBlueprint = blueprint.addNode(node.getId(), node.getType());
            nodeBlueprint.title(node.getTitle());
            nodeBlueprint.meta(node.getMetas());
            if (node.getWhen() != null) {
                if (node.getWhen().getComponent() != null) {
                    nodeBlueprint.when(node.getWhen().getComponent());
                } else {
                    nodeBlueprint.when(node.getWhen().getDescription());
                }
            }
            if (node.getTask() != null) {
                if (node.getTask().getComponent() != null) {
                    nodeBlueprint.task(node.getTask().getComponent());
                } else {
                    nodeBlueprint.task(node.getTask().getDescription());
                }
            }
        }

        for (Link link : graph.getLinks()) {
            LinkSpec linkBlueprint = blueprint.link(link.getPrevId(), link.getNextId());
            linkBlueprint.title(link.getTitle());
            linkBlueprint.meta(link.getMetas());
            if (link.getWhen() != null) {
                if (link.getWhen().getComponent() != null) {
                    linkBlueprint.when(link.getWhen().getComponent());
                } else {
                    linkBlueprint.when(link.getWhen().getDescription());
                }
            }
            linkBlueprint.priority(link.getPriority());
        }

        return blueprint;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDriver() {
        return driver;
    }

    public int getVersion() {
        return VERSION;
    }

    public String getEntry() {
        return resolveEntry();
    }

    public Map<String, Object> getMeta() {
        return Collections.unmodifiableMap(meta);
    }

    public Map<String, NodeSpec> getNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    public List<LinkSpec> getLinks() {
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

        // 1. Validate all link references resolve
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
        }

        // 1.5 Reject cycles — a cyclic graph recurses forever at runtime and
        //     the execution depth guard is a passive backstop, not validation.
        //     LOOP iteration is driven by the engine's $in loop, never by a
        //     link back to the loop node, so no cycle is legitimate here.
        List<String> cycle = findCycle();
        if (cycle != null) {
            throw new IllegalStateException(
                "Cycle detected in graph '" + id + "': " + String.join(" -> ", cycle)
                + ". Flow graphs must be acyclic (LOOP iteration is driven by $in,"
                + " not by link back-edges)."
            );
        }

        // 1.6 Reject duplicate unconditional links: two edges with the same
        //     from+to where at least one carries no condition would execute
        //     the target node twice (double task execution, double trace
        //     records). Multi-edges are legitimate only when every edge is
        //     condition-guarded. The scan checks all pairs regardless of
        //     declaration order — an unconditional edge first or second both
        //     double-execute.
        for (int i = 0; i < links.size(); i++) {
            LinkSpec a = links.get(i);
            if (a.when == null || a.when.isEmpty()) {
                for (int j = 0; j < links.size(); j++) {
                    if (j == i) {
                        continue;
                    }
                    LinkSpec b = links.get(j);
                    if (b.from.equals(a.from) && b.to.equals(a.to)) {
                        throw new IllegalStateException(
                            "Duplicate unconditional link '" + a.from
                                + "' -> '" + a.to + "' in graph: " + id
                                + ". Multiple edges between the same nodes must"
                                + " carry distinct 'when' conditions."
                        );
                    }
                }
            }
        }

        // 2. Validate entry — v2 requires exactly one entry point.
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
                for (LinkSpec link : links) {
                    if (link.from.equals(nodeId)) {
                        queue.addLast(link.to);
                    }
                }
            }
        }

        // 3. Warn about unreachable nodes (don't break — they may be
        //    referenced by subgraph calls or future graph composition)
        if (bfsOrder.size() < nodes.size()) {
            for (String nodeId : nodes.keySet()) {
                if (!bfsOrder.contains(nodeId)) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Unreachable node '" + nodeId
                            + "' (not reachable from entry '" + resolvedEntry
                            + "') in graph: " + id);
                }
            }
        }

        return this;
    }

    private List<String> findCycle() {
        // Three-color DFS: 0 = unvisited, 1 = on current path, 2 = fully explored.
        Map<String, Integer> state = new LinkedHashMap<>();
        Deque<String> path = new ArrayDeque<>();
        for (String nodeId : nodes.keySet()) {
            if (dfsCycle(nodeId, state, path)) {
                return cycleDescription(path);
            }
        }
        return null;
    }

    private boolean dfsCycle(
        String nodeId,
        Map<String, Integer> state,
        Deque<String> path
    ) {
        int s = state.getOrDefault(nodeId, 0);
        if (s == 2) {
            return false;
        }
        if (s == 1) {
            // Back-edge: nodeId is already on the current path.
            path.addLast(nodeId);
            return true;
        }
        state.put(nodeId, 1);
        path.addLast(nodeId);
        for (LinkSpec link : links) {
            if (link.from.equals(nodeId) && dfsCycle(link.to, state, path)) {
                return true;
            }
        }
        path.removeLast();
        state.put(nodeId, 2);
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
        if (bfsOrder != null && !bfsOrder.isEmpty()) {
            for (String nodeId : bfsOrder) {
                NodeSpec node = nodes.get(nodeId);
                if (node != null) ordered.add(node);
            }
        }
        for (NodeSpec node : nodes.values()) {
            if (!ordered.contains(node)) ordered.add(node);
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
            throw new IllegalStateException("Entry node not found: " + entry);
        }
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