package com.jujin.freeway.cloud.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RetryBudgetTest {

    @Test
    void tokenBucketDrainsOnRetriesAndRefillsOnSuccess() {
        RetryBudgetDefault budget = new RetryBudgetDefault(2);
        assertEquals(2, budget.availableTokens());

        assertTrue(budget.tryAcquire());
        assertTrue(budget.tryAcquire());
        assertFalse(budget.tryAcquire(), "the bucket is empty");
        assertEquals(0, budget.availableTokens());

        budget.onSuccess();
        assertEquals(1, budget.availableTokens());
        assertTrue(budget.tryAcquire());

        budget.onSuccess();
        budget.onSuccess();
        budget.onSuccess();
        assertEquals(2, budget.availableTokens(), "refill is capped at capacity");
    }

    @Test
    void eachShardHasItsOwnTokens() {
        RetryBudget first = new RetryBudgetDefault(1);
        RetryBudget second = first.newShard();

        assertTrue(first.tryAcquire());
        assertFalse(first.tryAcquire());
        assertTrue(second.tryAcquire(), "a sibling service must not consume this budget");
    }

    @Test
    void noLimitNeverRefusesAndCapacityMustBePositive() {
        assertTrue(RetryBudget.NO_LIMIT.tryAcquire());
        assertThrows(IllegalArgumentException.class, () -> new RetryBudgetDefault(0));
    }
}
