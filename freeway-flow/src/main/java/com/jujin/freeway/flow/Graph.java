package com.jujin.freeway.flow;

import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.flow.v1.GraphSpec;
import com.jujin.freeway.flow.v2.GraphSpec2;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 图（不可变运行时模型）
 *
 * @author noear
 * @since 3.0
 */
public class Graph {
    private final String id;
    private final String title;
    private final String driver;
    private final Map<String, Object> metas;
    private final Map<String, Node> nodes;
    private final List<Link> links;
    private Node start;

    public Graph(GraphSpec2 blueprint) {
        this.id = blueprint.getId();
        this.title = blueprint.getTitle();
        this.driver = blueprint.getDriver();

        String entryId = blueprint.getEntry();
        if (entryId != null && !blueprint.getNodes().containsKey(entryId)) {
            throw new IllegalStateException("Entry node not found: " + entryId);
        }

        Map<String, Node> nodeMap = new LinkedHashMap<>(blueprint.getNodes().size());
        List<Link> linkAry = new ArrayList<>(blueprint.getLinks().size());
        Map<String, List<GraphSpec2.LinkSpec2>> outgoing = new LinkedHashMap<>();
        for (GraphSpec2.LinkSpec2 link : blueprint.getLinks()) {
            outgoing.computeIfAbsent(link.getFrom(), k -> new ArrayList<>()).add(link);
        }

        for (Map.Entry<String, GraphSpec2.NodeSpec2> kv : blueprint.getNodes().entrySet()) {
            doAddNode(kv.getValue(), entryId, outgoing, nodeMap, linkAry);
        }

        this.nodes = Collections.unmodifiableMap(nodeMap);
        this.links = Collections.unmodifiableList(linkAry);
        this.metas = blueprint.getMeta().isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(blueprint.getMeta()));

        if (start == null) {
            for (Node node : nodes.values()) {
                if (node.getPrevLinks().isEmpty()) {
                    start = node;
                    break;
                }
            }
        }

        if (start == null) {
            throw new IllegalStateException("No start node found, graph: " + blueprint.getId());
        }
    }

    public static Graph fromText(String text) {
        JsonObject dom = JsonUtils.parseObject(text);
        // Route to v2 when either version==2 is declared, or the structural
        // signature (top-level nodes + links) is present. v1 layout format
        // never has a top-level "links" array.
        Integer version = dom.getInt("version");
        boolean isV2 = (version != null && version == GraphSpec2.VERSION)
            || (dom.containsKey("nodes") && dom.containsKey("links"));
        if (isV2) {
            return GraphSpec2.fromDom(dom).create();
        }
        return GraphSpec.fromDom(dom).create();
    }

    // --- getters ---
    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDriver() { return driver; }
    public Map<String, Object> getMetas() { return metas; }
    public Object getMeta(String key) { return metas.get(key); }

    @SuppressWarnings("unchecked")
    public <T> T getMetaAs(String key) { return (T) metas.get(key); }

    @SuppressWarnings("unchecked")
    public <T> T getMetaOrDefault(String key, T def) { return (T) metas.getOrDefault(key, def); }

    public Node getStart() { return start; }
    public Map<String, Node> getNodes() { return nodes; }
    public List<Link> getLinks() { return links; }
    public Node getNode(String id) { return nodes.get(id); }

    public Node getNodeOrThrow(String id) {
        Node node = getNode(id);
        if (node == null) {
            throw new IllegalArgumentException("Node not found, id: " + id);
        }
        return node;
    }

    // --- serialization ---

    public String toJson() {
        return JsonUtils.stringify(toMap());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        Map<String, Object> domRoot = new LinkedHashMap<>();
        domRoot.put("id", id);
        if (title != null && !title.isEmpty()) domRoot.put("title", title);
        if (driver != null && !driver.isEmpty()) domRoot.put("driver", driver);
        if (!metas.isEmpty()) domRoot.put("meta", metas);

        List<Map<String, Object>> domNodes = new ArrayList<>();
        domRoot.put("layout", domNodes);

        for (Map.Entry<String, Node> kv : nodes.entrySet()) {
            Node node = kv.getValue();
            Map<String, Object> domNode = new LinkedHashMap<>();
            domNodes.add(domNode);

            domNode.put("id", node.getId());
            domNode.put("type", node.getType().toString().toLowerCase());
            if (node.getTitle() != null && !node.getTitle().isEmpty()) domNode.put("title", node.getTitle());
            if (!node.getMetas().isEmpty()) domNode.put("meta", node.getMetas());
            if (ConditionDesc.isNotEmpty(node.getWhen())) domNode.put("when", node.getWhen().getDescription());
            if (TaskDesc.isNotEmpty(node.getTask())) domNode.put("task", node.getTask().getDescription());

            if (!node.getNextLinks().isEmpty()) {
                List<Map<String, Object>> domLinks = new ArrayList<>();
                domNode.put("link", domLinks);
                for (Link link : node.getNextLinks()) {
                    Map<String, Object> domLink = new LinkedHashMap<>();
                    domLinks.add(domLink);
                    domLink.put("nextId", link.getNextId());
                    if (link.getTitle() != null && !link.getTitle().isEmpty()) domLink.put("title", link.getTitle());
                    if (!link.getMetas().isEmpty()) domLink.put("meta", link.getMetas());
                    if (ConditionDesc.isNotEmpty(link.getWhen())) domLink.put("when", link.getWhen().getDescription());
                }
            }
        }

        return domRoot;
    }

    // --- PlantUML ---

    public String toPlantuml() {
        return toPlantuml(PlantumlOptions.DEFAULT, null);
    }

    public String toPlantuml(PlantumlOptions options) {
        return toPlantuml(options != null ? options : PlantumlOptions.DEFAULT, null);
    }

    public String toPlantuml(Function<PlantumlDisplayContext, PlantumlDisplayResult> displayMappingFunc) {
        return toPlantuml(PlantumlOptions.DEFAULT, displayMappingFunc);
    }

    public String toPlantuml(PlantumlOptions options,
                              Function<PlantumlDisplayContext, PlantumlDisplayResult> displayMappingFunc) {
        if (options == null) options = PlantumlOptions.DEFAULT;

        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        sb.append("skinparam shadowing false\n");
        sb.append("skinparam state {\n")
                .append("  BackgroundColor White\n")
                .append("  BorderColor #333333\n")
                .append("  FontName SansSerif\n")
                .append("  BackgroundColor<<Gateway>> #fff9c4\n")
                .append("  BorderColor<<Gateway>> #fbc02d\n")
                .append("}\n");

        if (title != null && !title.isEmpty()) {
            if (options.isShowIdInTitle()) {
                sb.append("title ").append(title).append(" (").append(id).append(")\n");
            } else {
                sb.append("title ").append(title).append("\n");
            }
        } else if (options.isShowIdInTitle()) {
            sb.append("title ").append(id).append("\n");
        }

        // 声明节点
        for (Node node : nodes.values()) {
            String nodeId = node.getId();
            switch (node.getType()) {
                case START:
                    sb.append("state ").append(nodeId).append(" <<start>>\n");
                    appendNodeTitle(sb, nodeId, node.getTitle());
                    break;
                case END:
                    sb.append("state ").append(nodeId).append(" <<end>>\n");
                    appendNodeTitle(sb, nodeId, node.getTitle());
                    break;
                case EXCLUSIVE, INCLUSIVE, PARALLEL, LOOP:
                    sb.append("state ").append(nodeId).append(" <<choice>> <<Gateway>>\n");
                    appendNodeTitle(sb, nodeId, node.getTitle());
                    if (options.isShowGatewayType()) {
                        sb.append(nodeId).append(" : ").append(node.getType().name()).append("\n");
                    }
                    break;
                default:
                    sb.append("state ").append(nodeId).append("\n");
                    appendNodeTitle(sb, nodeId, node.getTitle());
                    appendNodeTask(sb, nodeId, node, displayMappingFunc);
                    break;
            }
        }

        // 声明连接
        for (Link link : links) {
            sb.append(link.getPrevId()).append(" --> ").append(link.getNextId());
            List<String> labels = new ArrayList<>();
            if (link.getTitle() != null && !link.getTitle().isEmpty()) {
                labels.add(link.getTitle());
            }
            String whenText = buildLinkWhenText(link, displayMappingFunc);
            if (whenText != null && !whenText.isEmpty()) {
                labels.add("[" + whenText + "]");
            }
            if (!labels.isEmpty()) {
                sb.append(" : ").append(String.join(" ", labels));
            }
            sb.append("\n");
        }

        sb.append("@enduml");
        return sb.toString();
    }

    private void appendNodeTitle(StringBuilder sb, String nodeId, String title) {
        if (title != null && !title.isEmpty()) {
            sb.append(nodeId).append(" : ").append(title).append("\n");
        }
    }

    private void appendNodeTask(StringBuilder sb, String nodeId, Node node,
                                 Function<PlantumlDisplayContext, PlantumlDisplayResult> displayMappingFunc) {
        String task = node.getTask().getDescription();
        if (task == null || task.isEmpty()) return;

        if (displayMappingFunc != null) {
            try {
                PlantumlDisplayResult result = displayMappingFunc.apply(PlantumlDisplayContext.ofNode(node));
                if (result != null) {
                    if (!result.isVisible()) return;
                    if (result.isUseDefault()) {
                        sb.append(nodeId).append(" : ").append(task).append("\n");
                    } else {
                        sb.append(nodeId).append(" : ").append(result.getText()).append("\n");
                    }
                    return;
                }
            } catch (Exception ignored) {
                // 异常时使用默认处理
            }
        }
        sb.append(nodeId).append(" : ").append(task).append("\n");
    }

    private String buildLinkWhenText(Link link,
                                      Function<PlantumlDisplayContext, PlantumlDisplayResult> displayMappingFunc) {
        String when = link.getWhen().getDescription();
        if (when == null || when.isEmpty()) return null;

        if (displayMappingFunc != null) {
            try {
                PlantumlDisplayResult result = displayMappingFunc.apply(PlantumlDisplayContext.ofLink(link));
                if (result != null) {
                    if (!result.isVisible()) return null;
                    if (result.isUseDefault()) return when;
                    return result.getText();
                }
            } catch (Exception ignored) {
                // 异常时使用默认处理
            }
        }
        return when;
    }

    // --- static factories ---

    public static Graph create(String id, Consumer<GraphSpec> definition) {
        GraphSpec spec = new GraphSpec(id);
        definition.accept(spec);
        return spec.create();
    }

    public static Graph create(String id, String title, Consumer<GraphSpec> definition) {
        GraphSpec spec = new GraphSpec(id, title);
        definition.accept(spec);
        return spec.create();
    }

    public static Graph create(String id, String title, String driver, Consumer<GraphSpec> definition) {
        GraphSpec spec = new GraphSpec(id, title, driver);
        definition.accept(spec);
        return spec.create();
    }

    public static Graph copy(Graph graph, Consumer<GraphSpec> modification) {
        GraphSpec spec = GraphSpec.copy(graph);
        modification.accept(spec);
        return spec.create();
    }

    private void doAddNode(GraphSpec2.NodeSpec2 nodeSpec, String entryId,
                           Map<String, List<GraphSpec2.LinkSpec2>> outgoing,
                           Map<String, Node> nodeMap, List<Link> linkAry) {
        NodeType type = (entryId != null && entryId.equals(nodeSpec.getId()))
                ? NodeType.START
                : nodeSpec.getType();

        List<GraphSpec2.LinkSpec2> nodeLinks = outgoing.getOrDefault(nodeSpec.getId(), Collections.emptyList());
        List<Link> tmp = new ArrayList<>(nodeLinks.size());
        for (GraphSpec2.LinkSpec2 linkSpec : nodeLinks) {
            tmp.add(new Link(this, nodeSpec.getId(), linkSpec));
        }
        linkAry.addAll(tmp);

        Node node = new Node(this, nodeSpec, type, tmp);
        nodeMap.put(node.getId(), node);
        if (type == NodeType.START) {
            start = node;
        }
    }

    @Override
    public String toString() {
        return "Graph{id='" + id + "', title='" + title + "'}";
    }
}
