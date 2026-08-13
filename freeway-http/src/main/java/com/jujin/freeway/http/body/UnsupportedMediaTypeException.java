package com.jujin.freeway.http.body;

/**
 * Indicates the request's {@code Content-Type} is not supported for the
 * requested operation (for example, a JSON body binding received a
 * non-JSON Content-Type).
 */
public final class UnsupportedMediaTypeException extends RuntimeException {

    public UnsupportedMediaTypeException(String message) {
        super(message);
    }
}
