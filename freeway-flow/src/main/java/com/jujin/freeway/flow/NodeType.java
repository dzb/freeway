package com.jujin.freeway.flow;

/**
 * Node types.
 *
 * <p>Migration notes:
 * <ul>
 *   <li>Missing/empty type names fall back to {@link #ACTIVITY} (migration compatibility); unknown type names throw an error.</li>
 *   <li>{@link #UNKNOWN} is an internal migration value; explicitly declaring UNKNOWN in a v2 graph definition is rejected.</li>
 * </ul>
 *
 * @author noear
 * @since 3.0
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

    public int getCode() {
        return code;
    }

    /**
     * Gets the type by name (case-insensitive)
     */
    public static NodeType nameOf(String name) {
        if (name == null || name.isEmpty()) {
            return ACTIVITY; // defaults to an activity node
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
