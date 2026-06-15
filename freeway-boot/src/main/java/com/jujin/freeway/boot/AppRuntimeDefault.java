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
        if (state == AppState.STOPPED) {
            return;
        }
        AppState previous = state;
        state = AppState.STOPPING;
        LOG.info("Application stopping");
        publish(new AppStoppingEvent(container));

        RuntimeException failure = null;
        if (
            previous == AppState.RUNNING ||
            previous == AppState.STARTING ||
            previous == AppState.FAILED
        ) {
            try {
                container.get(HookLifecycle.class).stop();
            } catch (RuntimeException ex) {
                failure = ex;
                LOG.error("Error during hook shutdown", ex);
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
            LOG.error("Error closing container", ex);
        }
        if (failure != null) {
            state = AppState.FAILED;
            throw failure;
        }
        state = AppState.STOPPED;
        LOG.info("Application stopped");
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
}
