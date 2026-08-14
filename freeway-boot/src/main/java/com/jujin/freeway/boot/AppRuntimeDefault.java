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
    private boolean shutdownAttempted;

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
            // A hook may have triggered shutdown (reentrant close()) while
            // startup was still in progress: the nested close() already ran
            // the full shutdown sequence and left the state at STOPPED/FAILED.
            // Do not override it with RUNNING and do not publish AppStartedEvent.
            if (shutdownAttempted) {
                LOG.info(
                    "Application start aborted — shutdown requested during hook startup"
                );
                return;
            }
            state = AppState.RUNNING;
            LOG.info("Application started");
            // AppStartedEvent is a best-effort "you are up" signal, by design:
            // a subscriber exception is isolated by the EventBus (logged and
            // counted, never propagated), and even a publish-level failure
            // (e.g. a closed bus) is logged below and swallowed. Startup has
            // already succeeded at this point (hooks started, state RUNNING),
            // so the signal must not be able to abort it. Contrast with
            // AppStoppingEvent in close(), where a publish failure marks
            // shutdown FAILED — shutdown must be reliable, startup is not.
            publish(new AppStartedEvent(container));
        } catch (Throwable ex) {
            state = AppState.FAILED;
            LOG.error("Application startup failed", ex);
            throw new IllegalStateException("Application startup failed", ex);
        }
    }

    /**
     * Stops the application: publishes {@link AppStoppingEvent}, stops
     * runtime hooks in reverse order, closes the container.
     *
     * <p>Design: shutdown is attempted at most once. Even if the first
     * attempt fails (state {@code FAILED}), a repeated {@code close()} is a
     * no-op — re-running it would republish lifecycle events and
     * double-close the container. Resolve shutdown failures at the source.</p>
     *
     * <p>Lifecycle-event failure semantics are intentionally asymmetric:
     * publishing {@link AppStoppingEvent} is a reliability point — a publish
     * failure marks shutdown {@code FAILED} and {@code close()} rethrows.
     * Publishing {@link AppStartedEvent} during {@link #start()} is
     * best-effort — a failure is logged and startup continues.</p>
     */
    @Override
    public synchronized void close() {
        // Once shutdown has been attempted, repeated close() is a no-op even
        // if the first attempt failed (state FAILED) — re-running it would
        // republish lifecycle events and double-close the container.
        if (state == AppState.STOPPED || shutdownAttempted) return;
        shutdownAttempted = true;
        var previous = state;
        state = AppState.STOPPING;
        RuntimeException failure = null;
        LOG.info("Application stopping");
        // Lifecycle events only make sense for an application that actually
        // ran — a FAILED (startup-aborted) runtime gets no AppStoppingEvent.
        if (previous == AppState.RUNNING || previous == AppState.STARTING) {
            // AppStoppingEvent delivery is a reliability point, by design:
            // shutdown must not silently complete while the stopping signal
            // failed to go out. Any failure to publish — a subscriber
            // exception is normally isolated by the EventBus, but a
            // publish-level failure (e.g. a closed bus) surfaces here — is
            // accumulated and marks the shutdown FAILED below. Contrast with
            // AppStartedEvent in start(), which is best-effort and can never
            // abort an already-succeeded startup.
            try {
                container.get(EventBus.class).publish(new AppStoppingEvent(container));
            } catch (Exception ex) {
                failure = accumulate(failure, "Failed to publish AppStoppingEvent", ex);
                LOG.warn("Failed to publish AppStoppingEvent", ex);
            }
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

    /**
     * Publishes a lifecycle event, logging (never propagating) any failure.
     *
     * <p>Used only for the best-effort startup signal ({@link AppStartedEvent}):
     * a failure here is logged and startup continues — the event is
     * advisory. The shutdown signal ({@link AppStoppingEvent}) deliberately
     * does NOT use this helper: its publish failure marks shutdown FAILED,
     * because shutdown must be reliable while startup is not.</p>
     */
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
