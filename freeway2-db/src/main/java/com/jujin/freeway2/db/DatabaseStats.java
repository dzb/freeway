package com.jujin.freeway2.db;

public record DatabaseStats(
    int active,
    int idle,
    int total,
    int waiting,
    int maxSize
) {
}
