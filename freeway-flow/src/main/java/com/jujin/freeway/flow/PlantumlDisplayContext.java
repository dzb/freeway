package com.jujin.freeway.flow;

/**
 * PlantUML display mapping context
 *
 * @author noear
 * @since 3.10
 */
public class PlantumlDisplayContext {
    private final Node node;
    private final Link link;

    protected PlantumlDisplayContext(Node node, Link link) {
        this.node = node;
        this.link = link;
    }

    public static PlantumlDisplayContext ofNode(Node node) {
        return new PlantumlDisplayContext(node, null);
    }

    public static PlantumlDisplayContext ofLink(Link link) {
        return new PlantumlDisplayContext(null, link);
    }

    public boolean isNode() {
        return node != null;
    }

    public boolean isLink() {
        return link != null;
    }

    public Node getNode() {
        return node;
    }

    public Link getLink() {
        return link;
    }

    public String getId() {
        if (node != null) {
            return node.getId();
        }
        return null;
    }

    public String getTitle() {
        if (node != null) {
            return node.getTitle();
        }
        if (link != null) {
            return link.getTitle();
        }
        return null;
    }

    public String getTask() {
        if (node != null) {
            return node.getTask().getDescription();
        }
        return null;
    }

    public String getWhen() {
        if (link != null) {
            return link.getWhen().getDescription();
        }
        return null;
    }
}
