package com.jujin.freeway.ioc.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ScopeSession {
    private final Map<ServiceKey, Object> values = new LinkedHashMap<>();
    private volatile boolean closed;

    ScopeSession() {
    }

    boolean isClosed() {
        return closed;
    }

    synchronized <T> T realize(BindingImpl<T> binding) {
        if (closed) {
            throw new IllegalStateException("Scope is closed");
        }
        ServiceKey key = new ServiceKey(binding.type(), binding.id());
        Object existing = values.get(key);
        if (existing != null) {
            return binding.type().cast(existing);
        }
        Object created = binding.directInstance();
        values.put(key, created);
        return binding.type().cast(created);
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        Set<Object> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Object value : List.copyOf(values.values())) {
            if (!seen.add(value)) {
                continue;
            }
            try {
            Lifecycle.invokePreDestroy(value);
            } catch (Exception ex) {
                if (failure == null) {
                    failure = new RuntimeException("Unable to invoke @PreDestroy", ex);
                } else {
                    failure.addSuppressed(ex);
                }
            }
        }
        seen.clear();
        for (Object value : List.copyOf(values.values())) {
            if (!(value instanceof AutoCloseable closeable) || !seen.add(value)) {
                continue;
            }
            try {
                closeable.close();
            } catch (Exception ex) {
                if (failure == null) {
                    failure = new RuntimeException("Unable to close scoped resource", ex);
                } else {
                    failure.addSuppressed(ex);
                }
            }
        }
        values.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
