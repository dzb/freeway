package com.jujin.freeway.ioc.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The one state object behind an {@link Ordering} handle: four edge lists and
 * their fluent mutators.
 *
 * <p>Contribution entries and deferred class/factory contributions differ only
 * in what may still mutate and when the constraints are replayed onto a real
 * contribution. This class owns the lists and the replay; callers own the
 * lifecycle gate. That gate receives the operation name so the existing
 * "accepted only during composition" diagnostics stay at the call site that
 * knows the phase.</p>
 *
 * <p>Framework support, public because the container's deferred-contribution
 * registry lives in a sibling package. Applications consume the returned
 * {@link Ordering} interface, not this type.</p>
 */
public final class OrderingSupport implements Ordering {

    private final Consumer<String> requireMutable;
    private final List<String> beforeIds = new ArrayList<>();
    private final List<String> afterIds = new ArrayList<>();
    private final List<String> beforeIfPresentIds = new ArrayList<>();
    private final List<String> afterIfPresentIds = new ArrayList<>();

    /**
     * @param requireMutable called with the fluent operation name before every
     *                       mutation; throws when constraints may no longer be
     *                       declared
     */
    public OrderingSupport(Consumer<String> requireMutable) {
        this.requireMutable = Objects.requireNonNull(requireMutable, "requireMutable");
    }

    @Override
    public Ordering before(String... ids) {
        requireMutable.accept("before()");
        addAll(beforeIds, ids);
        return this;
    }

    @Override
    public Ordering after(String... ids) {
        requireMutable.accept("after()");
        addAll(afterIds, ids);
        return this;
    }

    @Override
    public Ordering beforeIfPresent(String... ids) {
        requireMutable.accept("beforeIfPresent()");
        addAll(beforeIfPresentIds, ids);
        return this;
    }

    @Override
    public Ordering afterIfPresent(String... ids) {
        requireMutable.accept("afterIfPresent()");
        addAll(afterIfPresentIds, ids);
        return this;
    }

    /**
     * Replays every declared edge onto the real contribution ordering, in the
     * same order the four fluent operations recorded them.
     */
    public void applyTo(Ordering target) {
        Objects.requireNonNull(target, "target");
        if (!beforeIds.isEmpty()) target.before(beforeIds.toArray(String[]::new));
        if (!afterIds.isEmpty()) target.after(afterIds.toArray(String[]::new));
        if (!beforeIfPresentIds.isEmpty()) {
            target.beforeIfPresent(beforeIfPresentIds.toArray(String[]::new));
        }
        if (!afterIfPresentIds.isEmpty()) {
            target.afterIfPresent(afterIfPresentIds.toArray(String[]::new));
        }
    }

    /** True when no edge is declared — the contribution ordering fast path. */
    public boolean isEmpty() {
        return beforeIds.isEmpty() && afterIds.isEmpty()
            && beforeIfPresentIds.isEmpty() && afterIfPresentIds.isEmpty();
    }

    public List<String> beforeIds() {
        return List.copyOf(beforeIds);
    }

    public List<String> afterIds() {
        return List.copyOf(afterIds);
    }

    public List<String> beforeIfPresentIds() {
        return List.copyOf(beforeIfPresentIds);
    }

    public List<String> afterIfPresentIds() {
        return List.copyOf(afterIfPresentIds);
    }

    private static void addAll(List<String> target, String... ids) {
        for (String id : ids) {
            target.add(Objects.requireNonNull(id, "id").trim());
        }
    }
}
