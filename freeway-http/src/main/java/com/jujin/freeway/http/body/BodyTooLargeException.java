package com.jujin.freeway.http.body;

public final class BodyTooLargeException extends RuntimeException {
    private final long maxSize;

    public BodyTooLargeException(long maxSize) {
        super("Request body exceeds maximum size of " + maxSize + " bytes");
        this.maxSize = maxSize;
    }

    public long getMaxSize() {
        return maxSize;
    }
}
