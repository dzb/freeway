package com.jujin.freeway.db;

import java.sql.Connection;

/**
 * 事务隔离级别，映射 JDBC {@link Connection} 常量。
 */
public enum IsolationLevel {
    DEFAULT(Connection.TRANSACTION_NONE, -1),
    READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED, 1),
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED, 2),
    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ, 4),
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE, 8);

    private final int jdbcLevel;

    IsolationLevel(int jdbcLevel, int sqlLevel) {
        this.jdbcLevel = jdbcLevel;
    }

    public int jdbcLevel() {
        return jdbcLevel;
    }
}
