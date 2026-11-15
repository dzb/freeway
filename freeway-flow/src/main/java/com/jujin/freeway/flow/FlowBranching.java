package com.jujin.freeway.flow;

/**
 * Optional capability of a {@link FlowContext}: branch-local write isolation
 * for a PARALLEL fork.
 *
 * <p>Not every context needs this. A plain {@link FlowContext} still supports
 * sequential graphs and stop; a parallel fork requires a context that
 * implements this interface and fails with a clear {@link FlowException}
 * otherwise. The engine reads the capability, never a hidden
 * {@code FlowContextState} subtype.</p>
 */
public interface FlowBranching {

    /** Opens a branch-local write buffer for one PARALLEL branch. */
    Branch beginBranch();

    /** One branch's write buffer. Commit exactly once; close discards. */
    interface Branch extends AutoCloseable {
        /** Runs the branch body with this scope current. */
        void run(Runnable body);

        /** Folds the buffer into the parent; fails on write-write conflict. */
        void commit();

        /** Discards the buffer unless committed. Idempotent. */
        @Override
        void close();
    }
}
