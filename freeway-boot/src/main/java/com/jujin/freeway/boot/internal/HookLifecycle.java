package com.jujin.freeway.boot.internal;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.RuntimeHooks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HookLifecycle {
    private final Container container;
    private final List<RuntimeHook> started = new ArrayList<>();
    private volatile List<RuntimeHook> hooks;

    public HookLifecycle(Container container) {
        this.container = container;
    }

    private List<RuntimeHook> resolveHooks() {
        if (hooks != null) return hooks;
        hooks = container.get(RuntimeHooks.class).all();
        return hooks;
    }

    public synchronized void start(Container container) {
        start(resolveHooks(), container);
    }

    synchronized void start(List<RuntimeHook> list, Container container) {
        if (!started.isEmpty()) {
            return;
        }
        try {
            for (RuntimeHook hook : list) {
                hook.start(container);
                started.add(hook);
            }
        } catch (Exception ex) {
            RuntimeException failure = new RuntimeException("Runtime hook start failed", ex);
            RuntimeException rollback = stopStarted(container);
            if (rollback != null) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    public synchronized void stop(Container container) {
        RuntimeException failure = stopStarted(container);
        if (failure != null) {
            throw failure;
        }
    }

    private RuntimeException stopStarted(Container container) {
        if (started.isEmpty()) {
            return null;
        }
        List<RuntimeHook> hooksToStop = new ArrayList<>(started);
        Collections.reverse(hooksToStop);
        started.clear();

        RuntimeException failure = null;
        for (RuntimeHook hook : hooksToStop) {
            try {
                hook.stop(container);
            } catch (Exception ex) {
                RuntimeException next = new RuntimeException("Runtime hook stop failed", ex);
                if (failure == null) {
                    failure = next;
                } else {
                    failure.addSuppressed(next);
                }
            }
        }
        return failure;
    }
}
