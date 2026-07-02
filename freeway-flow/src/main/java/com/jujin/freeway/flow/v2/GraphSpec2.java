package com.jujin.freeway.flow.v2;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.flow.ConditionComponent;
import com.jujin.freeway.flow.Graph;
import com.jujin.freeway.flow.Link;
import com.jujin.freeway.flow.NamedTaskComponent;
import com.jujin.freeway.flow.Node;
import com.jujin.freeway.flow.NodeType;
import com.jujin.freeway.flow.TaskComponent;

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
 * Canonical graph blueprint for v2.
 *
 * <p>This is the new authoring surface. It only accepts the canonical
 * {@code id/title/driver/version/entry/meta/nodes/links} shape. Legacy layout
 * Legacy graphs stay on {@code com.jujin.freeway.flow.v1.GraphSpec}; v2 does
 * not accept legacy aliases.</p>
 */
public class GraphSpec2 {
    public static final int VERSION = 2;
    private static final System.Logger LOG = System.getLogger(GraphSpec2.class.getName());

    private final String id;
    private String title;
    private String driver;
    private String entry;
    private final Map<String, Object> meta = new LinkedHashMap<>();
    private final Map<String, NodeSpec2> nodes = new LinkedHashMap<>();
    private final List<LinkSpec2> links = new ArrayList<>();
    private boolean normalized;
    private Set<String> bfsOrder;

    public GraphSpec2(String id) {
        this(id, null, null);
    }

    public GraphSpec2(String id, String title) {
        this(id, title, null);
    }

    public GraphSpec2(String id, String title, String driver) {
        this.id = id;
        this.title = title;
        this.driver = driver;
    }

    public static GraphSpec2 create(String id, Consumer<GraphSpec2> definition) {
        GraphSpec2 blueprint = new GraphSpec2(id);
        definition.accept(blueprint);
        return blueprint;
    }

    public static GraphSpec2 create(String id, String title, Consumer<GraphSpec2> definition) {
        GraphSpec2 blueprint = new GraphSpec2(id, title);
        definition.accept(blueprint);
        return blueprint;
    }

    public static GraphSpec2 create(String id, String title, String driver, Consumer<GraphSpec2> definition) {
        GraphSpec2 blueprint = new GraphSpec2(id, title, driver);
        definition.accept(blueprint);
        return blueprint;
    }

    public GraphSpec2 then(Consumer<GraphSpec2> definition) {
        definition.accept(this);
        return this;
    }

    public GraphSpec2 title(String title) {
        this.title = title;
        return this;
    }

    public GraphSpec2 driver(String driver) {
        this.driver = driver;
        return this;
    }

    public GraphSpec2 entry(String entry) {
        this.entry = entry;
        return this;
    }

    public GraphSpec2 metaPut(String key, Object value) {
        if (key != null && !key.isEmpty()) {
            meta.put(key, value);
        }
        return this;
    }

    public GraphSpec2 meta(Map<String, Object> meta) {
        if (meta != null && !meta.isEmpty()) {
            this.meta.putAll(meta);
        }
        return this;
    }

    public NodeSpec2 addNode(String id, NodeType type) {
        NodeSpec2 node = new NodeSpec2(this, id, type);
        nodes.put(id, node);
        return node;
    }

    public NodeSpec2 addStart(String id) {
        if (entry == null) {
            entry = id;
        }
        return addNode(id, NodeType.START);
    }

    public NodeSpec2 addEnd(String id) {
        return addNode(id, NodeType.END);
    }

    public NodeSpec2 addActivity(String id) {
        return addNode(id, NodeType.ACTIVITY);
    }

    public NodeSpec2 addActivity(NamedTaskComponent component) {
        Objects.requireNonNull(component, "component");
        NodeSpec2 node = addActivity(component.name());
        node.title(component.title());
        node.task(component);
        return node;
    }

    public NodeSpec2 addInclusive(String id) {
        return addNode(id, NodeType.INCLUSIVE);
    }

    public NodeSpec2 addExclusive(String id) {
        return addNode(id, NodeType.EXCLUSIVE);
    }

    public NodeSpec2 addParallel(String id) {
        return addNode(id, NodeType.PARALLEL);
    }

    public NodeSpec2 addLoop(String id) {
        return addNode(id, NodeType.LOOP);
    }

    public NodeSpec2 getNode(String id) {
        return nodes.get(id);
    }

    public LinkSpec2 link(String from, String to) {
        LinkSpec2 link = new LinkSpec2(from, to);
        links.add(link);
        NodeSpec2 source = nodes.get(from);
        if (source != null) {
            source.links.add(link);
        }
        return link;
    }

    public Graph create() {
        normalize();
        return new Graph(this);
    }

    public Map<String, Object> toMap() {
        validateEntry();
        GraphSpec2 normalized = normalize();
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
        for (NodeSpec2 node : normalized.nodesInCompileOrder()) {
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
            if (node.task != null && !node.task.isEmpty()) {
                domNode.put("task", node.task);
            }
            domNodes.add(domNode);
        }

        List<Map<String, Object>> domLinks = new ArrayList<>();
        domRoot.put("links", domLinks);
        for (LinkSpec2 link : normalized.links) {
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

    public static GraphSpec2 fromText(String text) {
        JsonObject dom = JsonUtils.parseObject(text);
        Integer version = dom.containsKey("version") ? dom.getInt("version") : null;
        if (version != null && version == VERSION && dom.containsKey("nodes") && dom.containsKey("links")) {
            return fromDom(dom);
        }

        throw new IllegalArgumentException("Expected a v2 graph definition");
    }

    public static GraphSpec2 fromDom(JsonObject dom) {
        GraphSpec2 blueprint = new GraphSpec2(
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
            Object item = nodesDom.get(i);
            if (!(item instanceof JsonObject nodeDom)) {
                continue;
            }

            String nodeId = nodeDom.getString("id");
            if (nodeId == null || nodeId.isBlank()) {
                throw new IllegalArgumentException(
                    "Node at index " + i + " is missing required 'id' field");
            }
            String typeStr = nodeDom.getString("type");
            if (typeStr == null || typeStr.isBlank()) {
                throw new IllegalArgumentException(
                    "Node '" + nodeId + "' is missing required 'type' field");
            }
            NodeType nodeType = NodeType.nameOf(typeStr);
            if (nodeType == NodeType.ACTIVITY && !"activity".equalsIgnoreCase(typeStr)) {
                throw new IllegalArgumentException(
                    "Unknown node type '" + typeStr + "' for node '" + nodeId + "'");
            }
            NodeSpec2 node = blueprint.addNode(nodeId, nodeType);
            node.title(nodeDom.getString("title"));
            node.meta(toMap(nodeDom.getObject("meta")));
            node.when(nodeDom.getString("when"));
            node.task(nodeDom.getString("task"));
        }

        JsonArray linksDom = dom.getArray("links");
        if (linksDom != null) {
            for (int i = 0; i < linksDom.size(); i++) {
                Object item = linksDom.get(i);
                if (!(item instanceof JsonObject linkDom)) {
                    continue;
                }

                String from = linkDom.getString("from");
                if (from == null || from.isBlank()) {
                    throw new IllegalArgumentException(
                        "Link at index " + i + " is missing required 'from' field");
                }
                String to = linkDom.getString("to");
                if (to == null || to.isBlank()) {
                    throw new IllegalArgumentException(
                        "Link at index " + i + " is missing required 'to' field");
                }
                LinkSpec2 link = blueprint.link(from, to);
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

    public static GraphSpec2 copy(Graph graph) {
        GraphSpec2 blueprint = new GraphSpec2(graph.getId(), graph.getTitle(), graph.getDriver());

        if (graph.getStart() != null) {
            blueprint.entry(graph.getStart().getId());
        }
        blueprint.meta(graph.getMetas());

        for (Node node : graph.getNodes().values()) {
            NodeSpec2 nodeBlueprint = blueprint.addNode(node.getId(), node.getType());
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
            LinkSpec2 linkBlueprint = blueprint.link(link.getPrevId(), link.getNextId());
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

    public Map<String, NodeSpec2> getNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    public List<LinkSpec2> getLinks() {
        return Collections.unmodifiableList(links);
    }

    /**
     * Validate and prepare the blueprint for graph construction.
     *
     * <p>Checks that every link references real nodes, performs BFS from
     * entry to determine reachable nodes, and warns about disconnected
     * subgraphs. Idempotent — subsequent calls are no-ops.</p>
     */
    private GraphSpec2 normalize() {
        if (normalized) {
            return this;
        }

        // 1. Validate all link references resolve
        for (LinkSpec2 link : links) {
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

        // 2. Validate entry — v2 requires exactly one entry point
        String resolvedEntry = resolveEntry();
        if (resolvedEntry == null) {
            List<String> starts = nodes.values().stream()
                .filter(n -> n.type == NodeType.START)
                .map(n -> n.id)
                .toList();
            if (starts.isEmpty()) {
                throw new IllegalStateException(
                    "No entry node found in graph: " + id
                    + ". Set 'entry' or add a START node."
                );
            }
            if (starts.size() > 1) {
                throw new IllegalStateException(
                    "Multiple START nodes (" + starts
                    + ") without explicit 'entry' in graph: " + id
                    + ". Set 'entry' to disambiguate."
                );
            }
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
                for (LinkSpec2 link : links) {
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

        normalized = true;
        return this;
    }

    private List<NodeSpec2> nodesInCompileOrder() {
        if (bfsOrder != null && !bfsOrder.isEmpty()) {
            List<NodeSpec2> ordered = new ArrayList<>(bfsOrder.size());
            for (String nodeId : bfsOrder) {
                NodeSpec2 node = nodes.get(nodeId);
                if (node != null) {
                    ordered.add(node);
                }
            }
            return ordered;
        }
        // Fallback: insertion order with entry first
        List<NodeSpec2> ordered = new ArrayList<>(nodes.values());
        String resolvedEntry = resolveEntry();
        if (resolvedEntry != null) {
            for (int i = 0; i < ordered.size(); i++) {
                if (resolvedEntry.equals(ordered.get(i).id)) {
                    ordered.add(0, ordered.remove(i));
                    break;
                }
            }
        }
        return ordered;
    }

    private String resolveEntry() {
        if (entry != null && !entry.isEmpty()) {
            return entry;
        }

        // Auto-detect: single START node wins
        String singleStart = null;
        for (NodeSpec2 node : nodes.values()) {
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

    public static final class NodeSpec2 {
        private final GraphSpec2 owner;
        private final String id;
        private final NodeType type;
        private String title;
        private final Map<String, Object> meta = new LinkedHashMap<>();
        private final List<LinkSpec2> links = new ArrayList<>();
        private String when;
        private ConditionComponent whenComponent;
        private String task;
        private TaskComponent taskComponent;

        private NodeSpec2(GraphSpec2 owner, String id, NodeType type) {
            this.owner = owner;
            this.id = id;
            this.type = type == null ? NodeType.ACTIVITY : type;
        }

        public NodeSpec2 title(String title) {
            this.title = title;
            return this;
        }

        public NodeSpec2 meta(Map<String, Object> meta) {
            if (meta != null && !meta.isEmpty()) {
                this.meta.putAll(meta);
            }
            return this;
        }

        public NodeSpec2 metaPut(String key, Object value) {
            if (key != null && !key.isEmpty()) {
                this.meta.put(key, value);
            }
            return this;
        }

        public NodeSpec2 when(String when) {
            this.when = when;
            this.whenComponent = null;
            return this;
        }

        public NodeSpec2 when(ConditionComponent whenComponent) {
            this.whenComponent = whenComponent;
            this.when = null;
            return this;
        }

        public NodeSpec2 task(String task) {
            this.task = task;
            this.taskComponent = null;
            return this;
        }

        public NodeSpec2 task(TaskComponent taskComponent) {
            this.taskComponent = taskComponent;
            this.task = null;
            return this;
        }

        public NodeSpec2 linkAdd(String to) {
            owner.link(id, to);
            return this;
        }

        public NodeSpec2 linkAdd(String to, Consumer<LinkSpec2> configure) {
            LinkSpec2 link = owner.link(id, to);
            if (configure != null) {
                configure.accept(link);
            }
            return this;
        }

        public String getId() {
            return id;
        }

        public NodeType getType() {
            return type;
        }

        public String getTitle() {
            return title;
        }

        public Map<String, Object> getMeta() {
            return Collections.unmodifiableMap(meta);
        }

        public List<LinkSpec2> getLinks() {
            return Collections.unmodifiableList(links);
        }

        public String getWhen() {
            return when;
        }

        public ConditionComponent getWhenComponent() {
            return whenComponent;
        }

        public String getTask() {
            return task;
        }

        public TaskComponent getTaskComponent() {
            return taskComponent;
        }
    }

    public static final class LinkSpec2 {
        private final String from;
        private final String to;
        private String title;
        private final Map<String, Object> meta = new LinkedHashMap<>();
        private String when;
        private ConditionComponent whenComponent;
        private int priority;

        private LinkSpec2(String from, String to) {
            this.from = from;
            this.to = to;
        }

        public LinkSpec2 title(String title) {
            this.title = title;
            return this;
        }

        public LinkSpec2 meta(Map<String, Object> meta) {
            if (meta != null && !meta.isEmpty()) {
                this.meta.putAll(meta);
            }
            return this;
        }

        public LinkSpec2 metaPut(String key, Object value) {
            if (key != null && !key.isEmpty()) {
                this.meta.put(key, value);
            }
            return this;
        }

        public LinkSpec2 when(String when) {
            this.when = when;
            this.whenComponent = null;
            return this;
        }

        public LinkSpec2 when(ConditionComponent whenComponent) {
            this.whenComponent = whenComponent;
            this.when = null;
            return this;
        }

        public LinkSpec2 priority(int priority) {
            this.priority = priority;
            return this;
        }

        public String getFrom() {
            return from;
        }

        public String getTo() {
            return to;
        }

        public String getTitle() {
            return title;
        }

        public Map<String, Object> getMeta() {
            return Collections.unmodifiableMap(meta);
        }

        public String getWhen() {
            return when;
        }

        public ConditionComponent getWhenComponent() {
            return whenComponent;
        }

        public int getPriority() {
            return priority;
        }
    }
}
