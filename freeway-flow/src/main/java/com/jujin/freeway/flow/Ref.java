package com.jujin.freeway.flow;

/**
 * The shape {@link TaskDesc} and {@link ConditionDesc} share: an owning graph
 * element, a description, and an optional inline handler.
 *
 * <p>Package-private and typed by the handler family, so the two public
 * descriptors keep their own accessors while the trim/empty rules live once.
 */
final class Ref<O, H> {

    private final O owner;
    private final String description;
    private final H handler;

    Ref(O owner, String description, H handler) {
        this.owner = owner;
        this.description = description == null ? null : description.trim();
        this.handler = handler;
    }

    O owner() {
        return owner;
    }

    String description() {
        return description;
    }

    H handler() {
        return handler;
    }

    boolean isEmpty() {
        return (description == null || description.isEmpty()) && handler == null;
    }
}
