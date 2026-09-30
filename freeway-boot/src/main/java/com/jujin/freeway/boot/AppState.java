package com.jujin.freeway.boot;

/**
 * Lifecycle states of an {@link AppRuntime}.
 *
 * <p>{@code CREATED → STARTING → RUNNING → STOPPING → STOPPED} is the happy
 * path; {@code FAILED} means startup threw, which is terminal for
 * {@code start()} (a retry needs a new launcher) but not for {@code close()}:
 * releasing a failed runtime is still required, and moves the state to
 * {@code STOPPED}.
 */
public enum AppState {
    /** Built but not started. */
    CREATED,
    /** Hooks are running. A {@code close()} on the starting thread unwinds it; from another thread it waits for {@code start()}, which holds the runtime monitor. */
    STARTING,
    /** Serving; {@code start()} is a no-op and {@code isRunning()} is true. */
    RUNNING,
    /** Drain window: the registry already answers 503 and shutdown hooks run. */
    STOPPING,
    /** Closed, successfully or after an unwind. */
    STOPPED,
    /** Startup threw — terminal. */
    FAILED
}
