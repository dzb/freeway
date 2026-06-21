package com.jujin.freeway.db;

/**
 * Unchecked exception thrown for database access failures.
 */
public class SqlException extends RuntimeException {
    public SqlException(String message) {
        super(message);
    }

    public SqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
