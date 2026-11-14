package com.jujin.freeway.http.body;
import com.jujin.freeway.commons.error.Failure;

public final class BodyTooLargeException extends RuntimeException implements Failure {
    private final long maxSize;

    public BodyTooLargeException(long maxSize) {
        super("Request body exceeds maximum size of " + maxSize + " bytes");
        this.maxSize = maxSize;
    }

    public long maxSize() {
        return maxSize;
    }

    @Override
    public boolean outcomeUnknown() {
        return false; // the request was rejected before the handler produced an effect
    }
}
