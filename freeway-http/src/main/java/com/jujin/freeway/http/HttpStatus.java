package com.jujin.freeway.http;

/**
 * HTTP status code constants (RFC 9110).
 */
public final class HttpStatus {
    public static final int OK = 200;
    public static final int NO_CONTENT = 204;
    public static final int NOT_MODIFIED = 304;
    public static final int BAD_REQUEST = 400;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int PAYLOAD_TOO_LARGE = 413;
    public static final int INTERNAL_ERROR = 500;

    private HttpStatus() {}
}
