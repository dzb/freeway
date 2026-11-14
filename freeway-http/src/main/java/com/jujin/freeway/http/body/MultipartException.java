package com.jujin.freeway.http.body;
import com.jujin.freeway.commons.error.Failure;

/** Indicates a malformed multipart request body. */
public final class MultipartException extends RuntimeException implements Failure {
    public MultipartException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public boolean outcomeUnknown() {
        return false; // the request was rejected before the handler produced an effect
    }
}
