package com.jujin.freeway.db;

/**
 * Snapshot of connection-pool statistics.
 *
 * @param active           number of connections currently borrowed
 * @param idle             number of idle connections available
 * @param total            total number of connections managed by the pool
 * @param waiting          number of threads waiting to borrow a connection
 * @param maxSize          maximum pool size
 * @param longLeased       connections borrowed for longer than the leak threshold
 * @param borrowCount      total number of successful borrows since pool creation
 * @param borrowWaitNanos  cumulative time spent waiting to borrow, in nanoseconds
 */
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
}
