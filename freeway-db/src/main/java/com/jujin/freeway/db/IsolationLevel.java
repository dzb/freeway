package com.jujin.freeway.db;

import java.sql.Connection;

/**
 * Transaction isolation levels, mapped from JDBC {@link Connection} constants.
 */
public enum IsolationLevel {
    DEFAULT(Connection.TRANSACTION_NONE),
    READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

    private final int jdbcLevel;

    IsolationLevel(int jdbcLevel) {
        this.jdbcLevel = jdbcLevel;
    }

    public int jdbcLevel() {
        return jdbcLevel;
    }
}
