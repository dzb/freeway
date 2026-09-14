package com.jujin.freeway.boot;

/**
 * A started application: its config, its state and its lifecycle.
 *
 * <p><b>Lifecycle contract.</b> {@link #start()} is idempotent — calling it
 * while already {@link AppState#RUNNING} returns without doing anything, so a
 * framework entry point may call it defensively. {@link #close()} is
 * idempotent and at-most-once: after the first call every later call is a
 * no-op, and closing while {@link AppState#STARTING} unwinds the startup
 * instead of racing it. A failure during startup leaves the runtime
 * {@link AppState#FAILED} and closed.</p>
 *
 * <p>Shutdown publishes {@code AppStoppingEvent} as a <b>reliability point</b>
 * (hooks that must run before the container closes), while
 * {@code AppStartedEvent} is best-effort: a failing subscriber cannot abort a
 * startup that already succeeded.</p>
 */
public interface AppRuntime extends AutoCloseable {

    /** The configuration this runtime was started with. */
    AppConfig config();

    /** Current lifecycle state. */
    AppState state();

    /**
     * Starts the application. Idempotent while {@link AppState#RUNNING};
     * throws if the runtime already failed or stopped.
     */
    void start();

    default boolean isRunning() {
        return state() == AppState.RUNNING;
    }

    /** Convenience: resolve a service by type. */
    <T> T get(Class<T> type);

    /** Convenience: resolve a named service by type and id. */
    <T> T get(Class<T> type, String id);

    @Override
    void close();
}
