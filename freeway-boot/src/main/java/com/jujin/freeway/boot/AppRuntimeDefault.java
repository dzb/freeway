package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.HookLifecycle;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AppRuntimeDefault implements AppRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(
        AppRuntimeDefault.class
    );
    private final Container container;
    private final AppConfig config;
    private volatile AppState state = AppState.CREATED;

    AppRuntimeDefault(Container container, AppConfig config) {
        this.container = Objects.requireNonNull(container, "container");
        this.config = Objects.requireNonNull(config, "config");
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
    public <T> T get(Class<T> type) {
        return container.get(type);
    }

    @Override
    public <T> T get(Class<T> type, String id) {
        return container.get(type, id);
    }

    @Override
    public synchronized void start() {
        if (state == AppState.RUNNING) {
            return;
        }
        if (state != AppState.CREATED) {
            throw new IllegalStateException(
                "Application cannot start from state " + state
            );
        }
        state = AppState.STARTING;
        LOG.info("Application starting");
        try {
            container.get(HookLifecycle.class).start();
            state = AppState.RUNNING;
            LOG.info("Application started");
            publish(new AppStartedEvent(container));
        } catch (RuntimeException ex) {
            state = AppState.FAILED;
            LOG.error("Application startup failed", ex);
            throw new IllegalStateException("Application startup failed", ex);
        }
    }

    @Override
    public synchronized void close() {
        if (state == AppState.STOPPED) return;
        var previous = state;
        state = AppState.STOPPING;
        RuntimeException failure = null;
        LOG.info("Application stopping");
        try {
            container.get(EventBus.class).publish(new AppStoppingEvent(container));
        } catch (Exception ex) {
            failure = accumulate(failure, "Failed to publish AppStoppingEvent", ex);
            LOG.warn("Failed to publish AppStoppingEvent", ex);
        }
        if (previous == AppState.RUNNING || previous == AppState.STARTING || previous == AppState.FAILED) {
            try {
                container.get(HookLifecycle.class).stop();
            } catch (RuntimeException ex) {
                failure = accumulate(failure, "Error during hook shutdown", ex);
                LOG.error("Error during hook shutdown", ex);
            }
        }
        try {
            container.close();
        } catch (RuntimeException ex) {
            failure = accumulate(failure, "Error closing container", ex);
            LOG.error("Error closing container", ex);
        }
        LOG.info("Application stopped");
        if (failure != null) {
            state = AppState.FAILED;
            LOG.error("Application shutdown failed", failure);
            throw failure;
        }
        state = AppState.STOPPED;
    }

    private void publish(Object event) {
        try {
            container.get(EventBus.class).publish(event);
        } catch (Exception ex) {
            LOG.warn(
                "Failed to publish lifecycle event: {}",
                event.getClass().getSimpleName(),
                ex
            );
        }
    }

    private static RuntimeException accumulate(
        RuntimeException failure, String message, Exception ex
    ) {
        if (failure == null) return new RuntimeException(message, ex);
        failure.addSuppressed(ex);
        return failure;
    }
}
