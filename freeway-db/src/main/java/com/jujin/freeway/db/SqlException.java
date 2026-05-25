package com.jujin.freeway.db;

public class SqlException extends RuntimeException {
    public SqlException(String message) {
        super(message);
    }

    public SqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
