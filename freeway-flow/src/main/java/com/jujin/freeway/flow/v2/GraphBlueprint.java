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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Canonical graph blueprint for v2.
 *
 * <p>This is the new authoring surface. It only accepts the canonical
 * {@code id/title/driver/version/entry/meta/nodes/links} shape. Legacy layout
 * Legacy graphs stay on {@code com.jujin.freeway.flow.v1.GraphSpec}; v2 does
 * not accept legacy aliases.</p>
 */
public class GraphBlueprint {
    public static final int VERSION = 2;

    private final String id;
    private String title;
    private String driver;
    private String entry;
    private final Map<String, Object> meta = new LinkedHashMap<>();
    private final Map<String, NodeBlueprint> nodes = new LinkedHashMap<>();
    private final List<LinkBlueprint> links = new ArrayList<>();

    public GraphBlueprint(String id) {
        this(id, null, null);
    }

    public GraphBlueprint(String id, String title) {
        this(id, title, null);
    }

    public GraphBlueprint(String id, String title, String driver) {
        this.id = id;
        this.title = title;
        this.driver = driver;
    }

    public static GraphBlueprint create(String id, Consumer<GraphBlueprint> definition) {
        GraphBlueprint blueprint = new GraphBlueprint(id);
        definition.accept(blueprint);
        return blueprint;
    }

    public static GraphBlueprint create(String id, String title, Consumer<GraphBlueprint> definition) {
        GraphBlueprint blueprint = new GraphBlueprint(id, title);
        definition.accept(blueprint);
        return blueprint;
    }

    public static GraphBlueprint create(String id, String title, String driver, Consumer<GraphBlueprint> definition) {
        GraphBlueprint blueprint = new GraphBlueprint(id, title, driver);
        definition.accept(blueprint);
        return blueprint;
    }

    public GraphBlueprint then(Consumer<GraphBlueprint> definition) {
        definition.accept(this);
        return this;
    }

    public GraphBlueprint title(String title) {
        this.title = title;
        return this;
    }

    public GraphBlueprint driver(String driver) {
        this.driver = driver;
        return this;
    }

    public GraphBlueprint entry(String entry) {
        this.entry = entry;
        return this;
    }

    public GraphBlueprint metaPut(String key, Object value) {
        if (key != null && !key.isEmpty()) {
            meta.put(key, value);
        }
        return this;
    }

    public GraphBlueprint meta(Map<String, Object> meta) {
        if (meta != null && !meta.isEmpty()) {
            this.meta.putAll(meta);
        }
        return this;
    }

    public NodeBlueprint addNode(String id, NodeType type) {
        NodeBlueprint node = new NodeBlueprint(this, id, type);
        nodes.put(id, node);
        return node;
    }

    public NodeBlueprint addStart(String id) {
        if (entry == null) {
            entry = id;
        }
        return addNode(id, NodeType.START);
    }

    public NodeBlueprint addEnd(String id) {
        return addNode(id, NodeType.END);
    }

    public NodeBlueprint addActivity(String id) {
        return addNode(id, NodeType.ACTIVITY);
    }

    public NodeBlueprint addActivity(NamedTaskComponent component) {
        Objects.requireNonNull(component, "component");
        NodeBlueprint node = addActivity(component.name());
        node.title(component.title());
        node.task(component);
        return node;
    }

    public NodeBlueprint addInclusive(String id) {
        return addNode(id, NodeType.INCLUSIVE);
    }

    public NodeBlueprint addExclusive(String id) {
        return addNode(id, NodeType.EXCLUSIVE);
    }

    public NodeBlueprint addParallel(String id) {
        return addNode(id, NodeType.PARALLEL);
    }

    public NodeBlueprint addLoop(String id) {
        return addNode(id, NodeType.LOOP);
    }

    public NodeBlueprint getNode(String id) {
        return nodes.get(id);
    }

    public LinkBlueprint link(String from, String to) {
        LinkBlueprint link = new LinkBlueprint(from, to);
        links.add(link);
        NodeBlueprint source = nodes.get(from);
        if (source != null) {
            source.links.add(link);
        }
        return link;
    }

    public Graph create() {
        return new Graph(this);
    }

    public Map<String, Object> toMap() {
        validateEntry();
        GraphBlueprint normalized = normalize();
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
        for (NodeBlueprint node : normalized.nodesInCompileOrder()) {
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
        for (LinkBlueprint link : normalized.links) {
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

    public static GraphBlueprint fromText(String text) {
        JsonObject dom = JsonUtils.parseObject(text);
        Integer version = dom.containsKey("version") ? dom.getInt("version") : null;
        if (version != null && version == VERSION && dom.containsKey("nodes") && dom.containsKey("links")) {
            return fromDom(dom);
        }

        throw new IllegalArgumentException("Expected a v2 graph definition");
    }

    public static GraphBlueprint fromDom(JsonObject dom) {
        GraphBlueprint blueprint = new GraphBlueprint(
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
            NodeType nodeType = NodeType.nameOf(nodeDom.getString("type"));
            NodeBlueprint node = blueprint.addNode(nodeId, nodeType);
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
                String to = linkDom.getString("to");
                LinkBlueprint link = blueprint.link(from, to);
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

    public static GraphBlueprint copy(Graph graph) {
        GraphBlueprint blueprint = new GraphBlueprint(graph.getId(), graph.getTitle(), graph.getDriver());

        if (graph.getStart() != null) {
            blueprint.entry(graph.getStart().getId());
        }
        blueprint.meta(graph.getMetas());

        for (Node node : graph.getNodes().values()) {
            NodeBlueprint nodeBlueprint = blueprint.addNode(node.getId(), node.getType());
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
            LinkBlueprint linkBlueprint = blueprint.link(link.getPrevId(), link.getNextId());
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

    public Map<String, NodeBlueprint> getNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    public List<LinkBlueprint> getLinks() {
        return Collections.unmodifiableList(links);
    }

    private GraphBlueprint normalize() {
        return this;
    }

    private List<NodeBlueprint> nodesInCompileOrder() {
        List<NodeBlueprint> ordered = new ArrayList<>(nodes.values());
        String resolvedEntry = resolveEntry();
        if (resolvedEntry != null) {
            for (int i = 0; i < ordered.size(); i++) {
                if (resolvedEntry.equals(ordered.get(i).id)) {
                    NodeBlueprint entryNode = ordered.remove(i);
                    ordered.add(entryNode);
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

        String singleStart = null;
        for (NodeBlueprint node : nodes.values()) {
            if (node.type == NodeType.START) {
                if (singleStart != null) {
                    return null;
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

    public static final class NodeBlueprint {
        private final GraphBlueprint owner;
        private final String id;
        private final NodeType type;
        private String title;
        private final Map<String, Object> meta = new LinkedHashMap<>();
        private final List<LinkBlueprint> links = new ArrayList<>();
        private String when;
        private ConditionComponent whenComponent;
        private String task;
        private TaskComponent taskComponent;

        private NodeBlueprint(GraphBlueprint owner, String id, NodeType type) {
            this.owner = owner;
            this.id = id;
            this.type = type == null ? NodeType.ACTIVITY : type;
        }

        public NodeBlueprint title(String title) {
            this.title = title;
            return this;
        }

        public NodeBlueprint meta(Map<String, Object> meta) {
            if (meta != null && !meta.isEmpty()) {
                this.meta.putAll(meta);
            }
            return this;
        }

        public NodeBlueprint metaPut(String key, Object value) {
            if (key != null && !key.isEmpty()) {
                this.meta.put(key, value);
            }
            return this;
        }

        public NodeBlueprint when(String when) {
            this.when = when;
            this.whenComponent = null;
            return this;
        }

        public NodeBlueprint when(ConditionComponent whenComponent) {
            this.whenComponent = whenComponent;
            this.when = null;
            return this;
        }

        public NodeBlueprint task(String task) {
            this.task = task;
            this.taskComponent = null;
            return this;
        }

        public NodeBlueprint task(TaskComponent taskComponent) {
            this.taskComponent = taskComponent;
            this.task = null;
            return this;
        }

        public NodeBlueprint linkAdd(String to) {
            owner.link(id, to);
            return this;
        }

        public NodeBlueprint linkAdd(String to, Consumer<LinkBlueprint> configure) {
            LinkBlueprint link = owner.link(id, to);
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

        public List<LinkBlueprint> getLinks() {
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

    public static final class LinkBlueprint {
        private final String from;
        private final String to;
        private String title;
        private final Map<String, Object> meta = new LinkedHashMap<>();
        private String when;
        private ConditionComponent whenComponent;
        private int priority;

        private LinkBlueprint(String from, String to) {
            this.from = from;
            this.to = to;
        }

        public LinkBlueprint title(String title) {
            this.title = title;
            return this;
        }

        public LinkBlueprint meta(Map<String, Object> meta) {
            if (meta != null && !meta.isEmpty()) {
                this.meta.putAll(meta);
            }
            return this;
        }

        public LinkBlueprint metaPut(String key, Object value) {
            if (key != null && !key.isEmpty()) {
                this.meta.put(key, value);
            }
            return this;
        }

        public LinkBlueprint when(String when) {
            this.when = when;
            this.whenComponent = null;
            return this;
        }

        public LinkBlueprint when(ConditionComponent whenComponent) {
            this.whenComponent = whenComponent;
            this.when = null;
            return this;
        }

        public LinkBlueprint priority(int priority) {
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
