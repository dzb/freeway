package com.jujin.freeway.db;

import java.sql.Connection;

/**
 * A handle around a borrowed JDBC {@link Connection}.
 *
 * <p>Return the underlying connection to the pool by calling
 * {@link Pool#release(PooledConnection)}, or {@link #connection()}
 * to access the raw JDBC connection.
 */
public interface PooledConnection {

    /**
     * Returns the underlying JDBC connection.
     */
    Connection connection();
}
