package com.jujin.freeway.db;

public record DatabaseStats(
    int active,
    int idle,
    int total,
    int waiting,
    int maxSize,
    int longLeased
) {
}
