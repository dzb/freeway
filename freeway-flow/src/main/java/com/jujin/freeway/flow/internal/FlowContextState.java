package com.jujin.freeway.flow.internal;

/**
 * Internal write side of a flow context's stop signal.
 *
 * <p>The public {@code FlowContext} exposes {@code stop()} and
 * {@code isStopped()} only. The engine and {@code FlowExecution} use this
 * package's state interface to reset or set that signal without making a
 * general-purpose setter part of the application-facing context contract.</p>
 */
public interface FlowContextState {

    /** Sets the stop signal; engine and execution control only. */
    void setStopped(boolean stopped);
}
