package com.jujin.freeway.boot.internal;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.extension.Extension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HookLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(
        HookLifecycle.class
    );
    private final Container container;
    private final List<RuntimeHook> started = new ArrayList<>();
    private List<RuntimeHook> hooks;
    /** Set once a stop pass has run. A reentrant close() from inside a
     *  hook's own start() re-enters stop() on this thread (the monitor is
     *  reentrant); {@link #start()} observes the seal and aborts the loop —
     *  later hooks must not start against a sealed runtime, and the closer
     *  itself (not yet in {@code started} when the nested stop ran) still
     *  gets its stop. */
    private boolean sealed;

    public HookLifecycle(Container container) {
        this.container = container;
    }

    private List<RuntimeHook> resolveHooks() {
        if (hooks != null) return hooks;
        Extension<RuntimeHook> extension = container.extension(
            RuntimeHook.class
        );
        // Fail startup on invalid ordering references (unknown before/after
        // ids) instead of silently running hooks in insertion order — a typo
        // like after("freeway.http.serve") would otherwise execute hooks in
        // the wrong order. The generic Extension ordering stays lenient for
        // other extension points; this strict check is runtime-hook specific.
        extension.validateOrdering();
        hooks = extension.all();
        return hooks;
    }

    public synchronized void start() {
        if (!started.isEmpty() || sealed) {
            return;
        }
        List<RuntimeHook> list = resolveHooks();
        RuntimeHook current = null;
        try {
            for (RuntimeHook hook : list) {
                current = hook;
                LOG.debug("Starting hook: {}", hook.getClass().getSimpleName());
                hook.start(container);
                if (sealed) {
                    // THIS hook's start() called close() (the only thread that
                    // can seal during the loop is this one, and it does so
                    // inside start()): the nested stop pass could not see it —
                    // it is recorded only after a clean start — so give it its
                    // stop now, and do not start the rest against a sealed
                    // runtime.
                    LOG.info(
                        "Hook startup aborted — shutdown requested by hook: {}",
                        hook.getClass().getSimpleName());
                    stopQuietly(hook);
                    return;
                }
                current = null;
                started.add(hook);
            }
        } catch (Throwable ex) {
            LOG.error("Hook start failed: {}", ex.getMessage(), ex);
            // The failing hook may have acquired resources before throwing —
            // give it a chance to release them, then roll back the started ones.
            RuntimeException failure = null;
            if (current != null) {
                failure = stopFailedHook(current);
            }
            failure = merge(failure, stopStarted());
            // Errors (AssertionError, OOM, ...) propagate as-is with any
            // rollback failures suppressed, so the caller can distinguish
            // e.g. AssertionError from an ordinary startup failure. Everything
            // else keeps the original wrapping behavior.
            if (ex instanceof Error error) {
                if (failure != null) {
                    error.addSuppressed(failure);
                }
                throw error;
            }
            RuntimeException wrapper = new RuntimeException(
                "Runtime hook start failed",
                ex
            );
            if (failure != null) {
                wrapper.addSuppressed(failure);
            }
            throw wrapper;
        }
    }

    private RuntimeException stopFailedHook(RuntimeHook hook) {
        try {
            hook.stop(container);
            return null;
        } catch (Throwable ex) {
            LOG.warn("Hook stop failed after failed start: {}", ex.getMessage(), ex);
            return new RuntimeException("Runtime hook stop failed", ex);
        }
    }

    /** Stop a hook whose own start() requested the shutdown: the nested stop
     *  pass ran before this hook was recorded, so it never saw it. Failures
     *  are logged, not propagated — the shutdown the hook requested is the
     *  caller's concern, not a reason to fail the aborted start. */
    private void stopQuietly(RuntimeHook hook) {
        try {
            hook.stop(container);
        } catch (Throwable ex) {
            LOG.warn("Hook stop failed after requesting shutdown: {}",
                ex.getMessage(), ex);
        }
    }

    /** The one failure-merge rule for hook shutdown: first failure wins,
     *  later ones ride along as suppressed. */
    private static RuntimeException merge(
        RuntimeException first, RuntimeException second
    ) {
        if (first == null) return second;
        if (second != null) first.addSuppressed(second);
        return first;
    }

    public synchronized void stop() {
        RuntimeException failure = stopStarted();
        if (failure != null) {
            throw failure;
        }
    }

    private RuntimeException stopStarted() {
        sealed = true;
        if (started.isEmpty()) {
            return null;
        }
        List<RuntimeHook> hooksToStop = new ArrayList<>(started);
        Collections.reverse(hooksToStop);
        started.clear();

        RuntimeException failure = null;
        for (RuntimeHook hook : hooksToStop) {
            LOG.debug("Stopping hook: {}", hook.getClass().getSimpleName());
            try {
                hook.stop(container);
            } catch (Throwable ex) {
                LOG.warn("Hook stop failed: {}", ex.getMessage(), ex);
                failure = merge(failure, new RuntimeException(
                    "Runtime hook stop failed", ex));
            }
        }
        return failure;
    }
}
