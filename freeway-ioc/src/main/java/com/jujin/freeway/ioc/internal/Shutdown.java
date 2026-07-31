package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.coercion.CoercerDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Shutdown {
    private final Map<ServiceKey, Object> serviceCache;
    private final Map<ServiceKey, Object> targetCache;
    private final BindingIndex bindingIndex;
    private final CoercerDefault coercer;

    Shutdown(
        Map<ServiceKey, Object> serviceCache,
        Map<ServiceKey, Object> targetCache,
        BindingIndex bindingIndex,
        CoercerDefault coercer
    ) {
        this.serviceCache = serviceCache;
        this.targetCache = targetCache;
        this.bindingIndex = bindingIndex;
        this.coercer = coercer;
    }

    RuntimeException close() {
        RuntimeException failure = null;
        List<Object> targets = snapshotTargets();
        for (Object value : targets) {
            try {
                Lifecycle.invokePreDestroy(value);
            } catch (Exception ex) {
                failure = accumulateFailure(failure, "Unable to invoke @PreDestroy", ex);
            }
        }
        for (Object value : targets) {
            if (!(value instanceof AutoCloseable closeable)) {
                continue;
            }
            try {
                closeable.close();
            } catch (Exception ex) {
                failure = accumulateFailure(
                    failure,
                    "Unable to close container-managed resource",
                    ex
                );
            }
        }
        serviceCache.clear();
        targetCache.clear();
        bindingIndex.clear();
        coercer.clearRules();
        return failure;
    }

    private List<Object> snapshotTargets() {
        List<Object> targets = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object value : targetCache.values()) {
            if (seen.add(value)) {
                targets.add(value);
            }
        }
        return targets;
    }

    private static RuntimeException accumulateFailure(
        RuntimeException failure,
        String message,
        Exception ex
    ) {
        if (failure == null) {
            return new RuntimeException(message, ex);
        }
        failure.addSuppressed(ex);
        return failure;
    }
}
