package com.jujin.freeway.boot.internal;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Extension;
import com.jujin.freeway.ioc.RuntimeHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HookLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(HookLifecycle.class);
    private final Container container;
    private final List<RuntimeHook> started = new ArrayList<>();
    private volatile List<RuntimeHook> hooks;

    public HookLifecycle(Container container) {
        this.container = container;
    }

    private List<RuntimeHook> resolveHooks() {
        if (hooks != null) return hooks;
        try {
            hooks = container.get(Extension.class, RuntimeHook.class.getName()).all();
        } catch (IllegalArgumentException e) {
            hooks = List.of();
        }
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
                LOG.debug("Starting hook: {}", hook.getClass().getSimpleName());
                hook.start(container);
                started.add(hook);
            }
        } catch (Exception ex) {
            LOG.error("Hook start failed: {}", ex.getMessage(), ex);
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
            LOG.debug("Stopping hook: {}", hook.getClass().getSimpleName());
            try {
                hook.stop(container);
            } catch (Exception ex) {
                LOG.warn("Hook stop failed: {}", ex.getMessage(), ex);
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
