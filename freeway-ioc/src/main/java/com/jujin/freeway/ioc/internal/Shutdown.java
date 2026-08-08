package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.coercion.CoercerDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Shutdown {
    /**
     * Upper bound on drain passes; a healthy shutdown stabilizes in 1-2.
     */
    private static final int MAX_DRAIN_ITERATIONS = 10_000;

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
        // Drain until stable: @PreDestroy callbacks may realize new services
        // (the container stays open during shutdown), and those must receive
        // their own lifecycle callbacks instead of being orphaned after the
        // caches are cleared. Each target is processed once (identity-deduped).
        // The iteration cap guards against a pathological callback chain that
        // realizes a fresh service on every pass.
        Set<Object> preDestroyed = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> closed = Collections.newSetFromMap(new IdentityHashMap<>());
        int iterations = 0;
        List<Object> batch = snapshotTargets();
        while (!batch.isEmpty()) {
            if (++iterations > MAX_DRAIN_ITERATIONS) {
                failure = accumulateFailure(failure,
                    "Container shutdown exceeded the drain iteration limit; "
                        + "remaining targets were skipped", null);
                break;
            }
            boolean processedAny = false;
            for (Object value : batch) {
                if (!preDestroyed.add(value)) {
                    continue;
                }
                processedAny = true;
                try {
                    Lifecycle.invokePreDestroy(value);
                } catch (Exception ex) {
                    failure = accumulateFailure(failure, "Unable to invoke @PreDestroy", ex);
                }
            }
            if (!processedAny) {
                break;
            }
            batch = snapshotTargets();
        }
        batch = snapshotTargets();
        while (!batch.isEmpty()) {
            if (++iterations > MAX_DRAIN_ITERATIONS) {
                failure = accumulateFailure(failure,
                    "Container shutdown exceeded the drain iteration limit; "
                        + "remaining targets were skipped", null);
                break;
            }
            boolean anyNew = false;
            for (Object value : batch) {
                if (!closed.add(value)) {
                    continue;
                }
                anyNew = true;
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
            if (!anyNew) {
                break;
            }
            batch = snapshotTargets();
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
        if (ex != null) {
            failure.addSuppressed(ex);
        }
        return failure;
    }
}
