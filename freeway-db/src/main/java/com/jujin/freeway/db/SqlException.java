package com.jujin.freeway.db;
import com.jujin.freeway.commons.error.Failure;

/**
 * Unchecked exception thrown for database access failures.
 */
public class SqlException extends RuntimeException implements Failure {
    public SqlException(String message) {
        super(message);
    }

    public SqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
