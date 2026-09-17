package com.jujin.freeway.flow;

/**
 * Node types — the seven semantics the engine dispatches (see
 * {@code FlowEngineDefault}). Names come from the graph definition and are
 * matched case-insensitively by {@link #of(String)}; an unknown name throws
 * with the valid list, and a missing or blank one never reaches here (the
 * parser requires a non-blank {@code type}).
 */
public enum NodeType {
    START,
    END,
    ACTIVITY,
    EXCLUSIVE,
    INCLUSIVE,
    PARALLEL,
    LOOP;

    /**
     * Resolves a node type by name, case-insensitively.
     *
     * @throws IllegalArgumentException when the name matches no type; the message
     *                                  lists the valid ones
     */
    public static NodeType of(String name) {
        if (name == null || name.isEmpty()) {
            // A blank name is a programmatic caller's mistake — not an
            // invitation to guess ACTIVITY (a silently wrong node type is
            // worse than none).
            throw new IllegalArgumentException(
                "Node type name must not be blank. Valid types: "
                    + "START, END, ACTIVITY, EXCLUSIVE, INCLUSIVE, PARALLEL, LOOP."
            );
        }
        for (NodeType v : values()) {
            if (v.name().equalsIgnoreCase(name)) {
                return v;
            }
        }

        throw new IllegalArgumentException(
            "Unknown node type '" + name + "'. "
                + "Valid types: START, END, ACTIVITY, EXCLUSIVE, INCLUSIVE, PARALLEL, LOOP."
        );
    }
}
