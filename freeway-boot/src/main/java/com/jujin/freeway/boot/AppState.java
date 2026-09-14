package com.jujin.freeway.boot;

/**
 * Lifecycle states of an {@link AppRuntime}.
 *
 * <p>{@code CREATED → STARTING → RUNNING → STOPPING → STOPPED} is the happy
 * path; {@code FAILED} is terminal and means startup threw (the runtime is
 * closed at that point, so a retry needs a new builder).
 */
public enum AppState {
    /** Built but not started. */
    CREATED,
    /** Hooks are running; a concurrent {@code close()} unwinds instead of racing. */
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
