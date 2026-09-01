package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.AmbiguousBindingException;
import com.jujin.freeway.ioc.MissingBindingException;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of bindings keyed by (type, id) plus a per-type view.
 *
 * <p><b>Threading model.</b> Writes ({@link #register}, {@link #updateId},
 * {@link #clear}) are mutually exclusive via the monitor; lookups
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

    /** True when {@code binding} is registered under its current type+id key. */
    boolean contains(BindingImpl<?> binding) {
        return bindings.get(new ServiceKey(binding.type(), binding.id())) == binding;
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

    /**
     * Re-keys a registered binding from {@code previousId} to {@code newId}.
     * Returns {@code true} when the binding was actually re-keyed — the caller
     * must then migrate any realized service/target cache entries so a late
     * {@code .id()} change does not orphan the old instance.
     */
    synchronized boolean updateId(BindingImpl<?> binding, String previousId, String newId) {
        if (Objects.equals(previousId, newId)) {
            return false;
        }
        ServiceKey previousKey = new ServiceKey(binding.type(), previousId);
        if (bindings.get(previousKey) != binding) {
            return false;
        }
        ServiceKey newKey = new ServiceKey(binding.type(), newId);
        BindingImpl<?> existing = bindings.get(newKey);
        if (existing != null && existing != binding) {
            throw duplicateBinding(binding.type().getName(), newId);
        }
        List<ServiceKey> reordered = new ArrayList<>(bindingOrder.size());
        boolean replaced = false;
        for (ServiceKey key : bindingOrder) {
            if (key.equals(previousKey)) {
                reordered.add(newKey);
                replaced = true;
            } else {
                reordered.add(key);
            }
        }
        if (!replaced) {
            return false;
        }
        bindingOrder.clear();
        bindingOrder.addAll(reordered);
        bindings.remove(previousKey);
        bindings.put(newKey, binding);
        // Update type index
        List<BindingImpl<?>> typeBindings = typeIndex.get(binding.type());
        if (typeBindings != null) {
            int idx = -1;
            for (int i = 0; i < typeBindings.size(); i++) {
                if (typeBindings.get(i) == binding) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                typeBindings.set(idx, binding);
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    <T> BindingImpl<T> find(Class<T> type, String id) {
        BindingImpl<?> exact = bindings.get(new ServiceKey(type, id));
        if (exact != null) {
            return (BindingImpl<T>) exact;
        }
        ScanResult<T> scan = scanBindings(
            type,
            binding -> id.equals(binding.id()) && type.isAssignableFrom(binding.type()),
            false
        );
        if (scan.multiple()) {
            throw new AmbiguousBindingException(
                "Multiple services match type " + type.getName() + " and id " + id
            );
        }
        return scan.first();
    }

    @SuppressWarnings("unchecked")
    <T> BindingImpl<T> findUnique(Class<T> type) {
        List<BindingImpl<?>> typeBindings = typeIndex.get(type);
        if (typeBindings != null && !typeBindings.isEmpty()) {
            if (typeBindings.size() == 1) {
                return (BindingImpl<T>) typeBindings.getFirst();
            }
            return selectUnique(
                type,
                scanBindings(type, binding -> binding.type().equals(type), true)
            );
        }
        return selectUnique(
            type,
            scanBindings(type, binding -> type.isAssignableFrom(binding.type()), true)
        );
    }

    private static <T> BindingImpl<T> selectUnique(Class<T> type, ScanResult<T> scan) {
        if (scan.first() == null) {
            return null;
        }
        if (!scan.multiple()) {
            return scan.first();
        }
        if (scan.primaryConflict()) {
            throw new AmbiguousBindingException(
                "Multiple primary services match type " + type.getName()
            );
        }
        if (scan.primary() != null) {
            return scan.primary();
        }
        throw new AmbiguousBindingException(
            "Multiple services match type " + type.getName()
                + "; mark one binding as primary()"
        );
    }

    @SuppressWarnings("unchecked")
    private <T> ScanResult<T> scanBindings(
        Class<T> type,
        Predicate<BindingImpl<?>> predicate,
        boolean trackPrimary
    ) {
        BindingImpl<T> first = null;
        BindingImpl<T> primary = null;
        boolean multiple = false;
        boolean primaryConflict = false;
        for (ServiceKey key : bindingOrder) {
            BindingImpl<?> binding = bindings.get(key);
            if (binding == null || !predicate.test(binding)) {
                continue;
            }
            if (first != null) {
                multiple = true;
            } else {
                first = (BindingImpl<T>) binding;
            }
            if (trackPrimary && binding.isPrimary()) {
                if (primary != null && primary != binding) {
                    primaryConflict = true;
                } else {
                    primary = (BindingImpl<T>) binding;
                }
            }
        }
        return new ScanResult<>(first, primary, multiple, primaryConflict);
    }

    private static IllegalStateException duplicateBinding(String typeName, String id) {
        return new IllegalStateException(
            "Duplicate binding for type " + typeName + " and id " + id
        );
    }

    private record ScanResult<T>(
        BindingImpl<T> first,
        BindingImpl<T> primary,
        boolean multiple,
        boolean primaryConflict
    ) {}
}
