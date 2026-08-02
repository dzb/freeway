package com.jujin.freeway.commons.scoped;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A named deferred action with ordering controls. Created via
 * {@link Defer#defer(String, Runnable)} or {@link Defer#supply(String, Callable)}.
 */
public final class DeferAction {
    static final DeferAction NOOP = new DeferAction(null, () -> {});

    private final String id;
    private final Runnable action;
    private final Supplier<?> valueSupplier;
    private final Set<String> before = new LinkedHashSet<>();
    private final Set<String> after = new LinkedHashSet<>();

    DeferAction(String id, Runnable action) {
        this(id, action, null);
    }

    DeferAction(String id, Runnable action, Supplier<?> valueSupplier) {
        this.id = id;
        this.action = action;
        this.valueSupplier = valueSupplier;
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

    /**
     * Returns the value produced by a handle created via
     * {@link Defer#supply(String, java.util.concurrent.Callable)}. Inside a
     * scope the value is computed on first access (or at commit if never
     * accessed); outside a scope it was computed when the handle was created.
     * Plain {@code defer()} handles have no value and return {@code null}.
     */
    @SuppressWarnings("unchecked")
    public <T> T value() {
        return valueSupplier == null ? null : (T) valueSupplier.get();
    }
}
