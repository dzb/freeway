package com.jujin.freeway.flow;

/**
 * PlantUML display mapping context
 */
public class PlantUmlDisplayContext {
    private final Node node;
    private final Link link;

    protected PlantUmlDisplayContext(Node node, Link link) {
        this.node = node;
        this.link = link;
    }

    public static PlantUmlDisplayContext ofNode(Node node) {
        return new PlantUmlDisplayContext(node, null);
    }

    public static PlantUmlDisplayContext ofLink(Link link) {
        return new PlantUmlDisplayContext(null, link);
    }

    public boolean isNode() {
        return node != null;
    }

    public boolean isLink() {
        return link != null;
    }

    public Node node() {
        return node;
    }

    public Link link() {
        return link;
    }

    public String id() {
        if (node != null) {
            return node.id();
        }
        return null;
    }

    public String title() {
        if (node != null) {
            return node.title();
        }
        if (link != null) {
            return link.title();
        }
        return null;
    }

    public String task() {
        if (node != null) {
            return node.task().description();
        }
        return null;
    }

    public String when() {
        if (link != null) {
            return link.when().description();
        }
        return null;
    }
}
