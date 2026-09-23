package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.AmbiguousBindingException;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of bindings keyed by (type, id) plus a per-type view.
 *
 * <p><b>Threading model.</b> Writes ({@link #register}, {@link #clear}) are
 * mutually exclusive via the monitor; lookups
 * ({@code find}/{@code findUnique}) are deliberately lock-free because they
 * sit on every {@code container.get()} path. This split is safe under one
 * contract: <b>bindings are registered during module composition and looked
 * up only afterwards</b> — the container publishes itself to application code
 * strictly after composition completes, so readers always see a frozen index
 * through the map/deque's own safe-publication semantics. If registration is
 * ever made concurrent with lookups, the read paths need synchronization too.
 */
final class BindingIndex {
    private static final Logger LOG = LoggerFactory.getLogger(BindingIndex.class);

    private final Map<ServiceKey, BindingImpl<?>> bindings = new ConcurrentHashMap<>();
    private final Deque<ServiceKey> bindingOrder = new ConcurrentLinkedDeque<>();
    private final Map<Class<?>, List<BindingImpl<?>>> typeIndex = new ConcurrentHashMap<>();

    void clear() {
        bindings.clear();
        bindingOrder.clear();
        typeIndex.clear();
    }

    synchronized <T> void register(BindingImpl<T> binding) {
        ServiceKey key = new ServiceKey(binding.type(), binding.id());
        if (bindings.putIfAbsent(key, binding) != null) {
            throw duplicateBinding(binding.type().getName(), binding.id());
        }
        bindingOrder.addLast(key);
        List<BindingImpl<?>> typeBindings = typeIndex.computeIfAbsent(
            binding.type(), k -> new ArrayList<>());
        typeBindings.add(binding);
        if (typeBindings.size() > 1) {
            String ids = typeBindings.stream()
                .map(BindingImpl::id)
                .collect(Collectors.joining(", "));
            boolean anyExplicit = typeBindings.stream().anyMatch(BindingImpl::hasExplicitId);
            if (anyExplicit) {
                LOG.info("Multiple bindings registered for type {}: [{}]",
                    binding.type().getName(), ids);
            } else {
                LOG.warn("Multiple bindings registered for type {}: [{}] — " +
                    "injection by type requires one .primary() or injection by id",
                    binding.type().getName(), ids);
            }
        }
    }

    @SuppressWarnings("unchecked")
    <T> BindingImpl<T> find(Class<T> type, String id) {
        BindingImpl<?> exact = bindings.get(new ServiceKey(type, id));
        if (exact != null) {
            return (BindingImpl<T>) exact;
        }
        List<BindingImpl<?>> matches =
            scan(binding -> id.equals(binding.id()) && type.isAssignableFrom(binding.type()));
        if (matches.size() > 1) {
            throw new AmbiguousBindingException(
                "Multiple services match type " + type.getName() + " and id " + id
            );
        }
        return matches.isEmpty() ? null : (BindingImpl<T>) matches.getFirst();
    }

    /**
     * The binding {@code get(type)} selects: the bindings registered under
     * exactly {@code type}, or — when there are none — every binding whose
     * type is assignable to it, narrowed by {@link #select}.
     */
    <T> BindingImpl<T> findUnique(Class<T> type) {
        List<BindingImpl<?>> exact = typeIndex.get(type);
        List<BindingImpl<?>> candidates = exact != null && !exact.isEmpty()
            ? exact
            : scan(binding -> type.isAssignableFrom(binding.type()));
        return select(candidates, type, null);
    }

    /**
     * The one selection rule, shared by type and marker lookups: no candidate
     * is a miss ({@code null}), a single candidate wins, several are narrowed
     * to their unique primary — two primaries, or none, is ambiguous.
     *
     * @param markers the requested markers, named in the failure; {@code null}
     *                for a lookup by type alone
     */
    @SuppressWarnings("unchecked")
    static <T> BindingImpl<T> select(
        List<BindingImpl<?>> candidates,
        Class<T> type,
        Class<? extends Annotation>[] markers
    ) {
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return (BindingImpl<T>) candidates.getFirst();
        }
        BindingImpl<?> primary = null;
        for (BindingImpl<?> candidate : candidates) {
            if (!candidate.isPrimary()) {
                continue;
            }
            if (primary != null) {
                throw new AmbiguousBindingException(
                    "Multiple primary services match " + describe(type, markers));
            }
            primary = candidate;
        }
        if (primary == null) {
            throw new AmbiguousBindingException(
                "Multiple services match " + describe(type, markers)
                    + "; mark one binding as primary()");
        }
        return (BindingImpl<T>) primary;
    }

    private static String describe(Class<?> type, Class<? extends Annotation>[] markers) {
        String described = "type " + type.getName();
        if (markers == null) {
            return described;
        }
        StringBuilder sb = new StringBuilder(described).append(" with markers [");
        for (int i = 0; i < markers.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('@').append(markers[i].getSimpleName());
        }
        return sb.append(']').toString();
    }

    /** Registered bindings matching {@code predicate}, in registration order. */
    private List<BindingImpl<?>> scan(Predicate<BindingImpl<?>> predicate) {
        List<BindingImpl<?>> matches = new ArrayList<>(2);
        for (ServiceKey key : bindingOrder) {
            BindingImpl<?> binding = bindings.get(key);
            if (binding != null && predicate.test(binding)) {
                matches.add(binding);
            }
        }
        return matches;
    }

    private static IllegalStateException duplicateBinding(String typeName, String id) {
        return new IllegalStateException(
            "Duplicate binding for type " + typeName + " and id " + id
        );
    }
}
