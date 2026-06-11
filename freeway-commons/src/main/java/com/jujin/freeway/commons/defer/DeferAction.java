package com.jujin.freeway.commons.defer;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A named deferred action with ordering controls. Created via
 * {@link Defer#defer(String, Runnable)} or {@link Defer#supply(String, Callable)}.
 */
public final class DeferAction {
    static final DeferAction NOOP = new DeferAction(null, () -> {});

    private final String id;
    private final Runnable action;
    private final Set<String> before = new LinkedHashSet<>();
    private final Set<String> after = new LinkedHashSet<>();

    DeferAction(String id, Runnable action) {
        this.id = id;
        this.action = action;
    }

    String id() { return id; }
    Runnable action() { return action; }
    Set<String> before() { return Set.copyOf(before); }
    Set<String> after() { return Set.copyOf(after); }

    /** Declare that this action must run before the named action(s). */
    public DeferAction before(String... ids) {
        for (String id : ids) before.add(Objects.requireNonNull(id, "id"));
        return this;
    }

    /** Declare that this action must run after the named action(s). */
    public DeferAction after(String... ids) {
        for (String id : ids) after.add(Objects.requireNonNull(id, "id"));
        return this;
    }
}
