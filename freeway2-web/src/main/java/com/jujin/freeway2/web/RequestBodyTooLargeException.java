package com.jujin.freeway2.web;

public final class RequestBodyTooLargeException extends RuntimeException {
    private final long maxSize;

    public RequestBodyTooLargeException(long maxSize) {
        super("Request body exceeds maximum size of " + maxSize + " bytes");
        this.maxSize = maxSize;
    }

    public long getMaxSize() {
        return maxSize;
    }
}
