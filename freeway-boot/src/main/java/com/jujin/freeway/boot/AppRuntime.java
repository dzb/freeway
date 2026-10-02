package com.jujin.freeway.boot;

/**
 * A started application: its config, its state and its lifecycle.
 *
 * <p><b>Lifecycle contract.</b> {@link #start()} is idempotent — calling it
 * while already {@link AppState#RUNNING} returns without doing anything, so a
 * framework entry point may call it defensively. {@link #close()} is
 * idempotent and at-most-once: after the first call every later call is a
 * no-op. A failure during startup leaves the runtime
 * {@link AppState#FAILED} and the container still open — unless a hook already
 * triggered and completed shutdown while starting, in which case the state that
 * shutdown reached stands. The failure is reported by the thrown exception, and
 * releasing the container is the separate, idempotent {@link #close()} step
 * (which leaves {@link AppState#STOPPED}).
 * {@code FreewayApp.start()} performs that close while unwinding; a runtime you
 * constructed yourself is yours to close.</p>
 *
 * <p><b>Closing during {@link AppState#STARTING}.</b> A {@code close()} on the
 * thread that is running {@code start()} unwinds the startup: the hook sees
 * the shutdown, and {@code start()} returns without publishing
 * {@code AppStartedEvent} or overwriting the state the shutdown left behind.
 * From <em>another</em> thread {@code close()} blocks until startup finishes —
 * {@code start()} holds the runtime monitor for its whole body, so there is no
 * interleaving to race against. That wait is the safe direction: an
 * unwinding startup would leave the runtime briefly {@code STARTING} with its
 * hooks half-run and nothing to close them.</p>
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
