package com.jujin.freeway.flow;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;

import java.util.*;
import java.util.function.Consumer;

/**
 * 图定义（Builder + JSON 解析）
 *
 * @author noear
 * @since 3.0
 */
public class GraphSpec {
    private final String id;
    private final String title;
    private final String driver;
    private final Map<String, Object> meta = new LinkedHashMap<>();
    private final Map<String, NodeSpec> nodes = new LinkedHashMap<>();

    public GraphSpec(String id) {
        this(id, null, null);
    }

    public GraphSpec(String id, String title) {
        this(id, title, null);
    }

    public GraphSpec(String id, String title, String driver) {
        this.id = id;
        this.title = (title == null ? id : title);
        this.driver = (driver == null ? "" : driver);
    }

    public GraphSpec then(Consumer<GraphSpec> definition) {
        definition.accept(this);
        return this;
    }

    public Graph create() {
        return new Graph(this);
    }

    public NodeSpec removeNode(String nodeId) {
        return nodes.remove(nodeId);
    }

    public NodeSpec addNode(NodeSpec nodeSpec) {
        nodes.put(nodeSpec.getId(), nodeSpec);
        return nodeSpec;
    }

    public NodeSpec getNode(String id) {
        return nodes.get(id);
    }

    // --- getters ---
    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDriver() { return driver; }
    public Map<String, Object> getMeta() { return Collections.unmodifiableMap(meta); }
    public Map<String, NodeSpec> getNodes() { return Collections.unmodifiableMap(nodes); }

    public void clearNodes() { nodes.clear(); }

    // --- meta ---
    public GraphSpec metaPut(String key, Object value) {
        meta.put(key, value);
        return this;
    }

    // --- convenience adders ---
    public NodeSpec addStart(String id) { return addNode(new NodeSpec(id, NodeType.START)); }
    public NodeSpec addEnd(String id) { return addNode(new NodeSpec(id, NodeType.END)); }
    public NodeSpec addActivity(String id) { return addNode(new NodeSpec(id, NodeType.ACTIVITY)); }

    public NodeSpec addActivity(NamedTaskComponent com) {
        Objects.requireNonNull(com.name(), "name");
        return addNode(new NodeSpec(com.name(), NodeType.ACTIVITY)).task(com).title(com.title());
    }

    public NodeSpec addInclusive(String id) { return addNode(new NodeSpec(id, NodeType.INCLUSIVE)); }
    public NodeSpec addExclusive(String id) { return addNode(new NodeSpec(id, NodeType.EXCLUSIVE)); }
    public NodeSpec addParallel(String id) { return addNode(new NodeSpec(id, NodeType.PARALLEL)); }
    public NodeSpec addLoop(String id) { return addNode(new NodeSpec(id, NodeType.LOOP)); }

    // --- serialization ---

    public String toJson() {
        return JsonUtils.stringify(toMap());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> domRoot = new LinkedHashMap<>();
        domRoot.put("id", id);
        if (title != null && !title.isEmpty()) domRoot.put("title", title);
        if (driver != null && !driver.isEmpty()) domRoot.put("driver", driver);
        if (!meta.isEmpty()) domRoot.put("meta", meta);

        List<Map<String, Object>> domNodes = new ArrayList<>();
        domRoot.put("layout", domNodes);

        for (Map.Entry<String, NodeSpec> kv : nodes.entrySet()) {
            NodeSpec node = kv.getValue();
            Map<String, Object> domNode = new LinkedHashMap<>();
            domNodes.add(domNode);

            domNode.put("id", node.getId());
            domNode.put("type", node.getType().toString().toLowerCase());
            if (node.getTitle() != null && !node.getTitle().isEmpty()) domNode.put("title", node.getTitle());
            if (!node.getMeta().isEmpty()) domNode.put("meta", node.getMeta());
            if (node.getWhen() != null && !node.getWhen().isEmpty()) domNode.put("when", node.getWhen());
            if (node.getTask() != null && !node.getTask().isEmpty()) domNode.put("task", node.getTask());

            if (!node.getLinks().isEmpty()) {
                List<Map<String, Object>> domLinks = new ArrayList<>();
                domNode.put("link", domLinks);
                for (LinkSpec link : node.getLinks()) {
                    Map<String, Object> domLink = new LinkedHashMap<>();
                    domLinks.add(domLink);
                    domLink.put("nextId", link.getNextId());
                    if (link.getTitle() != null && !link.getTitle().isEmpty()) domLink.put("title", link.getTitle());
                    if (link.getMeta() != null && !link.getMeta().isEmpty()) domLink.put("meta", link.getMeta());
                    if (link.getWhen() != null && !link.getWhen().isEmpty()) domLink.put("when", link.getWhen());
                }
            }
        }

        return domRoot;
    }

    // --- JSON parsing ---

    public static GraphSpec copy(Graph graph) {
        return fromText(graph.toJson());
    }

    public static GraphSpec fromText(String text) {
        Object dom = JsonUtils.parse(text);
        if (dom instanceof JsonObject jo) {
            return fromDom(jo);
        }
        throw new IllegalArgumentException("Expected a JSON object for graph definition");
    }

    /**
     * 从 JsonObject 解析图定义（适配自 solon-flow 的 ONode 版本）
     */
    @SuppressWarnings("unchecked")
    public static GraphSpec fromDom(JsonObject dom) {
        String id = dom.getString("id");
        String title = dom.getString("title");
        String driver = dom.getString("driver");

        GraphSpec spec = new GraphSpec(id, title, driver);

        // 元数据
        JsonObject metaObj = dom.getObject("meta");
        if (metaObj != null && !metaObj.isEmpty()) {
            spec.meta.putAll(metaObj.toMap());
        }

        // 节点（倒序加载，方便自动构建 link）
        JsonArray layoutArr;
        if (dom.containsKey("layout")) {
            layoutArr = dom.getArray("layout");
        } else {
            // 兼容旧版 v3.1 "nodes"
            layoutArr = dom.getArray("nodes");
        }

        if (layoutArr == null) {
            throw new IllegalArgumentException("No 'layout' or 'nodes' found in graph definition");
        }

        List<NodeSpec> nodeSpecList = new ArrayList<>();
        NodeSpec nodesLat = null;
        for (int i = layoutArr.size(); i > 0; i--) {
            Object item = layoutArr.get(i - 1);
            if (!(item instanceof JsonObject n1)) {
                continue;
            }

            // 自动构建：如果没有 id，生成 id
            String n1_id = n1.getString("id");
            if (n1_id == null || n1_id.isEmpty()) {
                n1_id = "n-" + i;
            }

            String n1_typeStr = n1.getString("type");
            NodeType n1_type = NodeType.nameOf(n1_typeStr);

            NodeSpec nodeSpec = new NodeSpec(n1_id, n1_type);
            nodeSpec.title(n1.getString("title"));
            nodeSpec.meta(toMap(n1.getObject("meta")));
            nodeSpec.when(n1.getString("when"));
            nodeSpec.task(n1.getString("task"));

            // 处理 link
            Object linkNode = n1.get("link");
            if (linkNode instanceof JsonArray linkArr) {
                // 数组模式（多个）
                for (int j = 0; j < linkArr.size(); j++) {
                    Object l1 = linkArr.get(j);
                    if (l1 instanceof JsonObject) {
                        addLink(nodeSpec, (JsonObject) l1);
                    } else if (l1 instanceof String) {
                        nodeSpec.linkAdd((String) l1);
                    }
                }
            } else if (linkNode instanceof JsonObject linkObj) {
                // 对象模式（单个）
                addLink(nodeSpec, linkObj);
            } else if (linkNode instanceof String linkStr) {
                // 单值模式（单个）
                nodeSpec.linkAdd(linkStr);
            } else if (linkNode == null) {
                // 自动构建：如果没有 link，使用前一个节点
                if (nodesLat != null) {
                    nodeSpec.linkAdd(nodesLat.getId());
                }
            }

            nodesLat = nodeSpec;
            nodeSpecList.add(nodeSpec);
        }

        // 倒排加入图
        for (int i = nodeSpecList.size(); i > 0; i--) {
            spec.addNode(nodeSpecList.get(i - 1));
        }

        return spec;
    }

    private static void addLink(NodeSpec nodeSpec, JsonObject l1) {
        String whenStr;
        if (l1.containsKey("when")) {
            whenStr = l1.getString("when");
        } else {
            // 兼容旧版 v3.3 "condition"
            whenStr = l1.getString("condition");
        }

        nodeSpec.linkAdd(l1.getString("nextId"), ld -> ld
                .title(l1.getString("title"))
                .meta(toMap(l1.getObject("meta")))
                .when(whenStr));
    }

    /**
     * 将 JsonObject 转为普通 Map（null 安全）
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonObject obj) {
        if (obj == null || obj.isEmpty()) return null;
        return new LinkedHashMap<>(obj.toMap());
    }

    @Override
    public String toString() {
        return "GraphSpec{id='" + id + "', title='" + title + "'}";
    }
}
