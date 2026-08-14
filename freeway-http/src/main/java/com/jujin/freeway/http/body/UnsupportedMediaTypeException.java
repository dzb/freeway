package com.jujin.freeway.http.body;

/**
 * Indicates a request body whose Content-Type cannot be interpreted by the
 * handler that read it (e.g. {@code text/plain} where {@code application/json}
 * was required). A client error — mapped to {@code 415 Unsupported Media
 * Type} by {@link com.jujin.freeway.http.filter.ExceptionMappers}, never a 500.
 */
public final class UnsupportedMediaTypeException extends RuntimeException {
    public UnsupportedMediaTypeException(String message) {
        super(message);
    }
}
