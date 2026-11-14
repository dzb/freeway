package com.jujin.freeway.commons.util;

/**
 * Captures the submitting thread's ambient work context for execution on
 * another thread.
 *
 * <p>Scoped values and thread-local state do not cross an {@link
 * java.util.concurrent.Executor} boundary by themselves: work submitted from a
 * request thread runs bare on a pool thread. A carrier closes that gap:
 * {@link #capture} runs on the submitting thread, and the returned runnable
 * restores the captured context around the work on the executor thread.
 *
 * <p>This is the single contract every offload point uses — an in-process
 * event bus, a workflow engine's parallel branches, an asynchronous RPC
 * transport, a background job. A module that owns ambient state (the cloud
 * context module for trace/principal/baggage and MDC, for example) binds the
 * carrier once; consumers never learn which contexts exist.
 *
 * <p>A carrier must never invent a context: when the submitting thread carries
 * nothing, the returned runnable runs the work directly and leaves the
 * executor thread's ambient untouched. Implementations that do capture state
 * must also restore the executor thread's previous state afterwards, so a
 * pooled thread cannot leak one task's context into the next.
 */
@FunctionalInterface
public interface ContextCarrier {

    /**
     * Captures the current thread's ambient context and returns a runnable
     * that restores it around {@code work} on whatever thread runs it.
     */
    Runnable capture(Runnable work);
}
