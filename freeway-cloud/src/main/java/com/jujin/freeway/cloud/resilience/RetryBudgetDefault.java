package com.jujin.freeway.cloud.resilience;

import com.jujin.freeway.cloud.CloudModule.ConfigKeys;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-service token bucket: starts full, every successful call adds one token
 * (capped at {@code capacity}), every retry consumes one. There is deliberately
 * no time-based refill: the budget is replenished by healthy traffic, not by
 * waiting while the dependency stays broken.
 *
 * <p>A drained budget fails fast with the original failure. The breaker still
 * tracks the failure; this class only decides whether another attempt is
 * affordable.
 */
public final class RetryBudgetDefault implements RetryBudget {

    /** Library default, shared with the config layer via {@link ConfigKeys}. */
    public static final int DEFAULT_CAPACITY = 20;

    private final int capacity;
    private final AtomicInteger tokens;

    public RetryBudgetDefault(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                "retry budget capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.tokens = new AtomicInteger(capacity);
    }

    /** The framework default: {@link #DEFAULT_CAPACITY} tokens per service. */
    public static RetryBudgetDefault withDefaults() {
        return new RetryBudgetDefault(ConfigKeys.RPC_RETRY_BUDGET_DEFAULT);
    }

    @Override
    public boolean tryAcquire() {
        while (true) {
            int current = tokens.get();
            if (current <= 0) {
                return false;
            }
            if (tokens.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    @Override
    public void onSuccess() {
        tokens.updateAndGet(current -> Math.min(capacity, current + 1));
    }

    @Override
    public RetryBudget newShard() {
        return new RetryBudgetDefault(capacity);
    }

    /** Visible for tests and diagnostics. */
    int availableTokens() {
        return tokens.get();
    }
}
