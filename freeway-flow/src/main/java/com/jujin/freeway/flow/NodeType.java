package com.jujin.freeway.flow;

/**
 * Node types.
 *
 * <p>Names come from the graph definition and are matched case-insensitively by
 * {@link #of(String)}; an unknown name throws with the valid list, and a missing
 * or blank one never reaches here (the v2 parser requires a non-blank
 * {@code type}). {@link #UNKNOWN} is reserved: declaring it in a graph
 * definition is rejected, and the engine throws if such a node ever appears.
 */
public enum NodeType {
    UNKNOWN(0),
    START(1),
    END(2),
    ACTIVITY(11),
    EXCLUSIVE(21),
    INCLUSIVE(31),
    PARALLEL(32),
    LOOP(33);

    private final int code;

    NodeType(int code) {
        this.code = code;
    }

    /** Stable numeric code of this type (0/1/2/11/21/31/32/33) — the value
     *  carried by the plan/trace documents, so it survives enum reordering. */
    public int code() {
        return code;
    }

    /**
     * Resolves a node type by name, case-insensitively.
     *
     * @throws IllegalArgumentException when the name matches no type; the message
     *                                  lists the valid ones
     */
    public static NodeType of(String name) {
        if (name == null || name.isEmpty()) {
            // The v2 parser requires a non-blank type before calling this, so a
            // blank name is a programmatic caller's mistake — not an invitation
            // to guess ACTIVITY (a silently wrong node type is worse than none).
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
            "Unknown node type '" + name + "'. " +
            "Valid types: START, END, ACTIVITY, EXCLUSIVE, INCLUSIVE, PARALLEL, LOOP."
        );
    }
}
