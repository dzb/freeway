package com.jujin.freeway.ioc.event;

/**
 * Captures the submitting thread's ambient context for async dispatch.
 *
 * <p>JDK {@code ScopedValue} bindings (and thread-local fallbacks) do not
 * cross an {@code Executor} boundary by themselves: a task submitted from a
 * request thread runs bare on a pool thread. A carrier closes that gap:
 * {@link #capture} runs on the submitting thread and captures its ambient
 * context, and the returned runnable restores that context around the work
 * on the executor thread.
 *
 * <p>A carrier must never invent a context: when the submitting thread
 * carries nothing, the returned runnable runs the work directly and leaves
 * the executor thread's ambient untouched.
 */
@FunctionalInterface
public interface AsyncCarrier {

    /**
     * Captures the current thread's ambient context and returns a runnable
     * that restores it around {@code work} on whatever thread runs it.
     */
    Runnable capture(Runnable work);
}
