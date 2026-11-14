package com.jujin.freeway.commons.error;

/**
 * A framework failure with an explicit retry/outcome classification.
 *
 * <p>The two axes answer the questions an operation boundary must ask before
 * it acts: may the operation be replayed automatically, and is the effect of
 * the failed attempt already known? They are deliberately independent —
 * a connect failure is retryable with a known outcome (nothing was sent),
 * while a timeout is retryable but ambiguous (the peer may have applied it).
 *
 * <p>The defaults are the conservative answers, used by failures the framework
 * does not retry and cannot reason about: {@link #retryable()} is false and
 * {@link #outcomeUnknown()} is true. A type that knows better overrides them —
 * {@code RpcException} maps every {@code Kind} onto these axes exactly; SQL,
 * flow and storage failures stay conservative because the operation's side
 * effects are not visible to the framework.
 *
 * <p>This is a classification view, not an exception hierarchy: it adds no
 * common superclass and does not replace the domain exceptions.
 */
public interface Failure {

    /**
     * Whether the framework may replay the operation automatically. The
     * default is {@code false}: a failure with no stated classification is
     * never retried. A caller that knows the operation is idempotent may still
     * choose to retry, but that is the caller's decision, not this contract's.
     */
    default boolean retryable() {
        return false;
    }

    /**
     * Whether the failed attempt's effect is unknown. The default is
     * {@code true}: in the absence of a stated guarantee, assume the peer or
     * database may have applied the operation. That makes blind replay unsafe
     * even for a caller that accepts retries.
     */
    default boolean outcomeUnknown() {
        return true;
    }
}
