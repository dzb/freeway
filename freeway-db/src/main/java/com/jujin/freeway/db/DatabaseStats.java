package com.jujin.freeway.db;

import java.time.Duration;

public record DatabaseStats(
    int active,
    int idle,
    int total,
    int waiting,
    int maxSize,
    int longLeased,
    long borrowCount,
    long borrowWaitNanos
) {
    public long averageBorrowWaitNanos() {
        return borrowCount == 0 ? 0 : borrowWaitNanos / borrowCount;
    }

    public Duration averageBorrowWait() {
        return Duration.ofNanos(averageBorrowWaitNanos());
    }
}
