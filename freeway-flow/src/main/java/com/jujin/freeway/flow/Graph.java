package com.jujin.freeway.flow;

import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public Graph(GraphSpec blueprint) {
        this.id = blueprint.getId();
        this.title = blueprint.getTitle();
        this.driver = blueprint.getDriver();

        String entryId = blueprint.getEntry();
        if (entryId != null && !blueprint.getNodes().containsKey(entryId)) {
            throw new IllegalStateException("Entry node not found: " + entryId);
        }

        Map<String, Node> nodeMap = new LinkedHashMap<>(blueprint.getNodes().size());
        List<Link> linkAry = new ArrayList<>(blueprint.getLinks().size());
        Map<String, List<LinkSpec>> outgoing = new LinkedHashMap<>();
        for (LinkSpec link : blueprint.getLinks()) {
            outgoing.computeIfAbsent(link.getFrom(), k -> new ArrayList<>()).add(link);
        }

        for (Map.Entry<String, NodeSpec> kv : blueprint.getNodes().entrySet()) {
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

    public Map<String, Object> toMap() {
        return GraphSpec.copy(this).toMap();
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

    private void doAddNode(NodeSpec nodeSpec, String entryId,
                           Map<String, List<LinkSpec>> outgoing,
                           Map<String, Node> nodeMap, List<Link> linkAry) {
        List<LinkSpec> nodeLinks = outgoing.getOrDefault(nodeSpec.getId(), Collections.emptyList());
        List<Link> tmp = new ArrayList<>(nodeLinks.size());
        for (LinkSpec linkSpec : nodeLinks) {
            tmp.add(new Link(this, nodeSpec.getId(), linkSpec));
        }
        linkAry.addAll(tmp);

        Node node = new Node(this, nodeSpec, nodeSpec.getType(), tmp);
        nodeMap.put(node.getId(), node);
        if (entryId != null && entryId.equals(nodeSpec.getId())) {
            start = node;
        }
    }

    @Override
    public String toString() {
        return "Graph{id='" + id + "', title='" + title + "'}";
    }
}
