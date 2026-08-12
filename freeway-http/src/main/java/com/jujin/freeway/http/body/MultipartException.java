package com.jujin.freeway.http.body;

/** Indicates a malformed multipart request body. */
public final class MultipartException extends RuntimeException {
    public MultipartException(String message, Throwable cause) {
        super(message, cause);
    }
}
