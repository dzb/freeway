package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.RuntimeHooks;
import com.jujin.freeway.ioc.Container;
import java.util.Objects;

final class AppRuntimeDefault implements AppRuntime {
    private final Container container;
    private final AppConfig config;
    private volatile AppState state = AppState.CREATED;

    AppRuntimeDefault(Container container, AppConfig config) {
        this.container = Objects.requireNonNull(container, "container");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public Container container() {
        return container;
    }

    @Override
    public AppConfig config() {
        return config;
    }

    @Override
    public AppState state() {
        return state;
    }

    @Override
    public synchronized void start() {
        if (state == AppState.RUNNING) {
            return;
        }
        if (state != AppState.CREATED) {
            throw new IllegalStateException("Application cannot start from state " + state);
        }
        state = AppState.STARTING;
        try {
            container.get(RuntimeHooks.class).start(container);
            state = AppState.RUNNING;
        } catch (RuntimeException ex) {
            state = AppState.FAILED;
            throw new IllegalStateException("Application startup failed", ex);
        }
    }

    @Override
    public synchronized void close() {
        if (state == AppState.STOPPED) {
            return;
        }
        AppState previous = state;
        state = AppState.STOPPING;
        RuntimeException failure = null;
        if (previous == AppState.RUNNING || previous == AppState.STARTING || previous == AppState.FAILED) {
            try {
                container.get(RuntimeHooks.class).stop(container);
            } catch (RuntimeException ex) {
                failure = ex;
            }
        }
        try {
            container.close();
        } catch (RuntimeException ex) {
            if (failure == null) {
                failure = ex;
            } else {
                failure.addSuppressed(ex);
            }
        }
        if (failure != null) {
            state = AppState.FAILED;
            throw failure;
        }
        state = AppState.STOPPED;
    }
}
