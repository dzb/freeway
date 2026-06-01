package com.jujin.freeway.ioc.internal;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Predicate;

final class BindingIndex {
    private final java.util.Map<ServiceKey, BindingImpl<?>> bindings = new ConcurrentHashMap<>();
    private final Deque<ServiceKey> bindingOrder = new ConcurrentLinkedDeque<>();

    void clear() {
        bindings.clear();
        bindingOrder.clear();
    }

    <T> void register(BindingImpl<T> binding) {
        ServiceKey key = new ServiceKey(binding.type(), binding.id());
        if (bindings.putIfAbsent(key, binding) != null) {
            throw duplicateMessage(binding.type().getName(), binding.id());
        }
        bindingOrder.addLast(key);
    }

    synchronized void updateId(BindingImpl<?> binding, String previousId, String newId) {
        if (Objects.equals(previousId, newId)) {
            return;
        }
        ServiceKey previousKey = new ServiceKey(binding.type(), previousId);
        if (bindings.get(previousKey) != binding) {
            return;
        }
        ServiceKey newKey = new ServiceKey(binding.type(), newId);
        BindingImpl<?> existing = bindings.get(newKey);
        if (existing != null && existing != binding) {
            throw duplicateMessage(binding.type().getName(), newId);
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
            return;
        }
        bindingOrder.clear();
        bindingOrder.addAll(reordered);
        bindings.remove(previousKey);
        bindings.put(newKey, binding);
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
            throw new IllegalArgumentException(
                "Multiple services match type " + type.getName() + " and id " + id
            );
        }
        return scan.first();
    }

    @SuppressWarnings("unchecked")
    <T> BindingImpl<T> findUnique(Class<T> type) {
        ScanResult<T> scan = scanBindings(type, binding -> type.isAssignableFrom(binding.type()), true);
        if (!scan.multiple()) {
            return scan.first();
        }
        if (scan.primaryConflict()) {
            throw new IllegalArgumentException(
                "Multiple primary services match type " + type.getName()
            );
        }
        if (scan.primary() != null) {
            return scan.primary();
        }
        throw new IllegalArgumentException(
            "Multiple services match type " + type.getName() + "; mark one binding as primary()"
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

    private static IllegalStateException duplicateMessage(String typeName, String id) {
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
