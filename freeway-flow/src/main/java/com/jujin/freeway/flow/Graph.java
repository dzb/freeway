package com.jujin.freeway.flow;

import com.jujin.freeway.commons.json.JsonUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Graph (immutable runtime model)
 */
public class Graph {
    private final String id;
    private final String title;
    private final String driver;
    private final Map<String, Object> metas;
    private final Map<String, Node> nodes;
    private final List<Link> links;
    private Node start;

    Graph(GraphSpec blueprint) {
        // Same validation as GraphSpec.create(): link references, cycles,
        // duplicate unconditional links and entry resolution must not differ
        // between the two construction paths.
        blueprint.drainNodeLinks();
        blueprint.normalize();

        this.id = blueprint.id();
        this.title = blueprint.title();
        this.driver = blueprint.driver();

        String entryId = blueprint.entry();
        if (entryId != null && !blueprint.nodes().containsKey(entryId)) {
            throw new IllegalStateException("Entry node not found: " + entryId);
        }

        Map<String, Node> nodeMap = new LinkedHashMap<>(blueprint.nodes().size());
        List<Link> linkAry = new ArrayList<>(blueprint.links().size());
        Map<String, List<LinkSpec>> outgoing = new LinkedHashMap<>();
        for (LinkSpec link : blueprint.links()) {
            outgoing.computeIfAbsent(link.from(), k -> new ArrayList<>()).add(link);
        }

        for (Map.Entry<String, NodeSpec> kv : blueprint.nodes().entrySet()) {
            doAddNode(kv.getValue(), entryId, outgoing, nodeMap, linkAry);
        }

        this.nodes = Collections.unmodifiableMap(nodeMap);
        this.links = List.copyOf(linkAry);
        this.metas = blueprint.meta().isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(blueprint.meta()));

        if (start == null) {
            for (Node node : nodes.values()) {
                if (node.prevLinks().isEmpty()) {
                    start = node;
                    break;
                }
            }
        }

        if (start == null) {
            throw new IllegalStateException("No start node found, graph: " + blueprint.id());
        }
    }

    public static Graph fromText(String text) {
        // Route through GraphSpec.fromText so the version gate is shared:
        // only canonical v3 documents (version=3 with nodes+links) load,
        // anything else fails with the same clear error as GraphSpec.
        return GraphSpec.fromText(text).create();
    }

    // --- getters ---
    public String id() { return id; }
    public String title() { return title; }
    public String driver() { return driver; }
    public Map<String, Object> metas() { return metas; }
    public Object meta(String key) { return metas.get(key); }

    /** Returns the meta value cast to the requested type. */
    @SuppressWarnings("unchecked")
    public <T> T metaAs(String key) { return (T) metas.get(key); }

    /** Returns the meta value cast to the requested type, or {@code def}. */
    @SuppressWarnings("unchecked")
    public <T> T metaOrDefault(String key, T def) { return (T) metas.getOrDefault(key, def); }

    public Node start() { return start; }
    public Map<String, Node> nodes() { return nodes; }
    public List<Link> links() { return links; }
    public Node node(String id) { return nodes.get(id); }

    public Node nodeOrThrow(String id) {
        Node node = node(id);
        if (node == null) {
            throw new IllegalArgumentException(
                "Graph '" + this.id + "' has no node '" + id
                    + "' — ids are declared in the graph definition (Graph.nodes())"
            );
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

    public String toPlantUml() {
        return toPlantUml(PlantUmlOptions.defaults(), null);
    }

    public String toPlantUml(PlantUmlOptions options) {
        return toPlantUml(options != null ? options : PlantUmlOptions.defaults(), null);
    }

    public String toPlantUml(Function<PlantUmlDisplayContext, PlantUmlDisplayResult> displayMappingFunc) {
        return toPlantUml(PlantUmlOptions.defaults(), displayMappingFunc);
    }

    public String toPlantUml(PlantUmlOptions options,
                              Function<PlantUmlDisplayContext, PlantUmlDisplayResult> displayMappingFunc) {
        if (options == null) options = PlantUmlOptions.defaults();

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
            String safeTitle = escapePlantUmlText(title);
            if (options.showIdInTitle()) {
                sb.append("title ").append(safeTitle).append(" (").append(id).append(")\n");
            } else {
                sb.append("title ").append(safeTitle).append("\n");
            }
        } else if (options.showIdInTitle()) {
            sb.append("title ").append(escapePlantUmlText(id)).append("\n");
        }

        // declare nodes
        for (Node node : nodes.values()) {
            String nodeId = node.id();
            // PlantUML state ids must be bare identifiers — a raw id with
            // spaces or special chars produces an invalid/ambiguous diagram.
            requirePlantUmlId(nodeId);
            switch (node.type()) {
                case START:
                    sb.append("state ").append(nodeId).append(" <<start>>\n");
                    appendNodeTitle(sb, nodeId, node.title());
                    break;
                case END:
                    sb.append("state ").append(nodeId).append(" <<end>>\n");
                    appendNodeTitle(sb, nodeId, node.title());
                    break;
                case EXCLUSIVE, INCLUSIVE, PARALLEL, LOOP:
                    sb.append("state ").append(nodeId).append(" <<choice>> <<Gateway>>\n");
                    appendNodeTitle(sb, nodeId, node.title());
                    if (options.showGatewayType()) {
                        sb.append(nodeId).append(" : ").append(node.type().name()).append("\n");
                    }
                    break;
                default:
                    sb.append("state ").append(nodeId).append("\n");
                    appendNodeTitle(sb, nodeId, node.title());
                    appendNodeTask(sb, nodeId, node, displayMappingFunc);
                    break;
            }
        }

        // declare links
        for (Link link : links) {
            sb.append(link.prevId()).append(" --> ").append(link.nextId());
            List<String> labels = new ArrayList<>();
            if (link.title() != null && !link.title().isEmpty()) {
                labels.add(link.title());
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

    private static void requirePlantUmlId(String nodeId) {
        if (!nodeId.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                "Node id '" + nodeId + "' is not a valid PlantUML identifier "
                    + "(letters, digits, underscore, not starting with a digit)");
        }
    }

    /**
     * Neutralizes PlantUML syntax in label text: a title/task/when containing
     * a newline would otherwise inject diagram statements, and an unescaped
     * quote breaks the label.
     */
    private static String escapePlantUmlText(String s) {
        return s.replace("\r", " ")
                .replace("\n", "\\n")
                .replace("\"", "\\\"");
    }

    private void appendNodeTitle(StringBuilder sb, String nodeId, String title) {
        if (title != null && !title.isEmpty()) {
            sb.append(nodeId).append(" : ").append(escapePlantUmlText(title)).append("\n");
        }
    }

    private void appendNodeTask(StringBuilder sb, String nodeId, Node node,
                                 Function<PlantUmlDisplayContext, PlantUmlDisplayResult> displayMappingFunc) {
        String task = node.task().description();
        if (task == null || task.isEmpty()) return;

        if (displayMappingFunc != null) {
            try {
                PlantUmlDisplayResult result = displayMappingFunc.apply(PlantUmlDisplayContext.ofNode(node));
                if (result != null) {
                    if (!result.isVisible()) return;
                    if (result.isUseDefault()) {
                        sb.append(nodeId).append(" : ").append(escapePlantUmlText(task)).append("\n");
                    } else {
                        sb.append(nodeId).append(" : ").append(escapePlantUmlText(result.text())).append("\n");
                    }
                    return;
                }
            } catch (Exception ignored) {
                // on exception, fall back to default handling
            }
        }
        sb.append(nodeId).append(" : ").append(escapePlantUmlText(task)).append("\n");
    }

    private String buildLinkWhenText(Link link,
                                      Function<PlantUmlDisplayContext, PlantUmlDisplayResult> displayMappingFunc) {
        String when = link.when().description();
        if (when == null || when.isEmpty()) return null;

        if (displayMappingFunc != null) {
            try {
                PlantUmlDisplayResult result = displayMappingFunc.apply(PlantUmlDisplayContext.ofLink(link));
                if (result != null) {
                    if (!result.isVisible()) return null;
                    if (result.isUseDefault()) return escapePlantUmlText(when);
                    return escapePlantUmlText(result.text());
                }
            } catch (Exception ignored) {
                // on exception, fall back to default handling
            }
        }
        return escapePlantUmlText(when);
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

    /**
     * Copies an existing graph, applying a modification to the copy's spec
     * before building. The original graph is not touched.
     */
    public static Graph copy(Graph graph, Consumer<GraphSpec> modification) {
        GraphSpec spec = GraphSpec.copy(graph);
        modification.accept(spec);
        return spec.create();
    }

    private void doAddNode(NodeSpec nodeSpec, String entryId,
                           Map<String, List<LinkSpec>> outgoing,
                           Map<String, Node> nodeMap, List<Link> linkAry) {
        List<LinkSpec> nodeLinks = outgoing.getOrDefault(nodeSpec.id(), Collections.emptyList());
        List<Link> tmp = new ArrayList<>(nodeLinks.size());
        for (LinkSpec linkSpec : nodeLinks) {
            tmp.add(new Link(this, nodeSpec.id(), linkSpec));
        }
        linkAry.addAll(tmp);

        Node node = new Node(this, nodeSpec, nodeSpec.type(), tmp);
        nodeMap.put(node.id(), node);
        if (entryId != null && entryId.equals(nodeSpec.id())) {
            start = node;
        }
    }

    @Override
    public String toString() {
        return "Graph{id='" + id + "', title='" + title + "'}";
    }
}
