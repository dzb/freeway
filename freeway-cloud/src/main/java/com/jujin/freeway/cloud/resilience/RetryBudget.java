package com.jujin.freeway.cloud.resilience;

/**
 * Caps the retries one service may consume across all callers.
 *
 * <p>A retry is not free: when a dependency is broadly unhealthy, every caller
 * retrying independently multiplies the load exactly when the dependency is
 * least able to take it. A retry budget is the per-service circuit breaker for
 * that behaviour — the {@link Retryer} still decides whether one call may be
 * retried, and this budget decides whether the service as a whole can afford
 * another attempt right now.
 *
 * <p>Budgets are sharded per {@code serviceId}. The client calls
 * {@link #newShard()} once per service; the default implementation returns an
 * independent token bucket, while a custom implementation may share state if
 * that is its policy.
 */
public interface RetryBudget {

    /**
     * Consumes one retry token.
     *
     * @return true when the retry may proceed; false means fail the original
     *         failure immediately instead of backing off for another attempt
     */
    boolean tryAcquire();

    /** Records a successful call, replenishing the service's budget. */
    void onSuccess();

    /**
     * Creates the budget shard for one service. The default shares this
     * instance; a per-service token bucket overrides it.
     */
    default RetryBudget newShard() {
        return this;
    }

    /** No budget: every retry is admitted. */
    RetryBudget NO_LIMIT = new RetryBudget() {
        @Override
        public boolean tryAcquire() {
            return true;
        }

        @Override
        public void onSuccess() {
        }
    };
}
