package com.jujin.freeway.http.body;
import com.jujin.freeway.commons.error.Failure;

/**
 * Indicates the request's {@code Content-Type} is not supported for the
 * requested operation (for example, a JSON body binding received a
 * non-JSON Content-Type).
 */
public final class UnsupportedMediaTypeException extends RuntimeException implements Failure {

    public UnsupportedMediaTypeException(String message) {
        super(message);
    }

    @Override
    public boolean outcomeUnknown() {
        return false; // the request was rejected before the handler produced an effect
    }
}
