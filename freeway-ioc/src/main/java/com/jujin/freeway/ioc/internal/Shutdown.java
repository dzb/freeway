package com.jujin.freeway.ioc.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jujin.freeway.ioc.CallBus;
import com.jujin.freeway.ioc.EventBus;

final class Shutdown {
    /**
     * Upper bound on drain passes; a healthy shutdown stabilizes in 1-2.
     */
    private static final int MAX_DRAIN_ITERATIONS = 10_000;

    private final Map<ServiceKey, Object> targetCache;
    private final Set<Object> preDestroyed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> closed = Collections.newSetFromMap(new IdentityHashMap<>());
    /**
     * Container-managed message services ({@link EventBus}, {@link CallBus}),
     * closed only after every lifecycle callback has run: {@code @PreDestroy}
     * code may still publish events and make calls (the documented "look up
     * services during close" contract), and a bus closed mid-drain would turn
     * those into failures that abort the whole shutdown.
     */
    private final List<AutoCloseable> deferredMessageServices = new ArrayList<>();

    Shutdown(Map<ServiceKey, Object> targetCache) {
        this.targetCache = targetCache;
    }

    /**
     * Drains lifecycle callbacks until stable: {@code @PreDestroy} callbacks
     * may realize new services (the container stays open during shutdown), and
     * those must receive their own lifecycle callbacks instead of being
     * orphaned after the caches are cleared. Each target is processed once
     * (identity-deduped). The iteration cap guards against a pathological
     * callback chain that realizes a fresh service on every pass.
     *
     * <p>Deliberately does NOT clear the caches or seal the container — the
     * caller does that atomically under {@link ServiceRuntime#REALIZE_LOCK}
     * after this drain (see {@link ContainerImpl#close()}), so a realization
     * racing {@code close()} cannot insert a fresh singleton past the last
     * snapshot and escape lifecycle cleanup.
     */
    RuntimeException close() {
        RuntimeException failure = drainRemaining(null);
        // The message services are the last things to close: every
        // @PreDestroy/close callback has already run, so no code can publish
        // into a closed event bus or call into a closed call bus.
        for (AutoCloseable service : deferredMessageServices) {
            try {
                service.close();
            } catch (RuntimeException | Error ex) {
                failure = accumulateFailure(failure,
                    "Unable to close container-managed message service", ex);
            } catch (Exception ex) {
                // AutoCloseable.close() declares checked Exception
                failure = accumulateFailure(failure,
                    "Unable to close container-managed message service", ex);
            }
        }
        deferredMessageServices.clear();
        return failure;
    }

    /**
     * Final drain pass for targets realized concurrently with the main drain.
     * The caller must hold {@link ServiceRuntime#REALIZE_LOCK} while invoking
     * this: under the lock no new realization can add targets after this
     * pass's last snapshot, so the pass stabilizes in at most two iterations
     * and the subsequent cache clear cannot orphan anything. Targets already
     * processed by {@link #close()} are skipped via the shared dedup sets.
     */
    RuntimeException drainRemaining(RuntimeException failure) {
        failure = drainPhase(failure, true);
        failure = drainPhase(failure, false);
        return failure;
    }

    private RuntimeException drainPhase(RuntimeException failure, boolean preDestroy) {
        Set<Object> done = preDestroy ? preDestroyed : closed;
        List<Object> batch = snapshotTargets();
        // Iteration budget per phase: the cap guards ONE loop against a
        // pathological callback chain that realizes a fresh service on every
        // pass. Phases count independently so a healthy first phase can never
        // consume the budget a later phase needs (the final locked drain is
        // bounded to two passes anyway).
        int iterations = 0;
        while (!batch.isEmpty()) {
            if (++iterations > MAX_DRAIN_ITERATIONS) {
                return accumulateFailure(failure,
                    "Container shutdown exceeded the drain iteration limit; "
                        + "remaining targets were skipped", null);
            }
            boolean processedAny = false;
            for (Object value : batch) {
                if (!done.add(value)) {
                    continue;
                }
                processedAny = true;
                try {
                    if (preDestroy) {
                        Lifecycle.invokePreDestroy(value);
                    } else if (value instanceof EventBus || value instanceof CallBus) {
                        // Deferred until every lifecycle callback has run —
                        // @PreDestroy code may still publish/call during the
                        // drain.
                        deferredMessageServices.add((AutoCloseable) value);
                    } else if (value instanceof AutoCloseable closeable) {
                        closeable.close();
                    }
                } catch (Throwable ex) {
                    // Errors included (AssertionError, OOM, ...): one failing
                    // callback must not abort the drain — closed=true, the
                    // cache clear and the remaining services' cleanup all
                    // depend on drain running to completion. Matches the
                    // DeferScope/ScopedCache Throwable handling.
                    failure = accumulateFailure(
                        failure,
                        preDestroy
                            ? "Unable to invoke @PreDestroy"
                            : "Unable to close container-managed resource",
                        ex
                    );
                }
            }
            if (!processedAny) {
                break;
            }
            batch = snapshotTargets();
        }
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
        Throwable ex
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
