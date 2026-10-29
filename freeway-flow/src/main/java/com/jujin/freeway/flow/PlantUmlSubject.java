package com.jujin.freeway.flow;

import java.util.Objects;

/**
 * What a PlantUML display function is asked to label: a node's task, or a link's
 * condition. A sealed pair instead of one context holding either (with the other's
 * accessors returning null), so a function narrows with a pattern match and the
 * compiler knows which fields exist.
 *
 * <p>The function returns the label {@code String}: {@code null} keeps the default
 * rendering, an empty string hides the element, and any other text replaces the
 * default. One rule, stated once, instead of a three-state result type.</p>
 */
public sealed interface PlantUmlSubject
        permits PlantUmlSubject.NodeSubject, PlantUmlSubject.LinkSubject {

    /** The subject's title, or null when it has none. */
    String title();

    /** A node's task (its display label). */
    record NodeSubject(Node node) implements PlantUmlSubject {
        public NodeSubject {
            Objects.requireNonNull(node, "node");
        }

        public String id() {
            return node.id();
        }

        @Override
        public String title() {
            return node.title();
        }

        public String task() {
            return node.task().description();
        }
    }

    /** A link's condition (its bracketed label). */
    record LinkSubject(Link link) implements PlantUmlSubject {
        public LinkSubject {
            Objects.requireNonNull(link, "link");
        }

        @Override
        public String title() {
            return link.title();
        }

        public String when() {
            return link.when().description();
        }
    }
}
