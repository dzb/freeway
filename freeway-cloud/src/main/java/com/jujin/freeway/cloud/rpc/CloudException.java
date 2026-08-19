package com.jujin.freeway.cloud.rpc;

/**
 * Remote invocation failure. {@link #retryable()} distinguishes transport
 * failures (connect/timeout, 5xx — retryable) from client errors (4xx — not
 * retryable), and {@link #status()} carries the HTTP status when the failure
 * crossed the wire ({@code -1} for transport failures).
 */
public class CloudException extends RuntimeException {

    private final boolean retryable;
    private final int status;

    private CloudException(String message, boolean retryable, int status, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.status = status;
    }

    public static CloudException noInstance(String serviceId) {
        return new CloudException("No live instance for service '" + serviceId + "'", false, -1, null);
    }

    public static CloudException circuitOpen(String serviceId) {
        return new CloudException("Circuit breaker OPEN for service '" + serviceId + "'", false, -1, null);
    }

    public static CloudException rateLimited(String serviceId) {
        return new CloudException("Rate limit exceeded for service '" + serviceId + "'", false, -1, null);
    }

    public static CloudException connect(String serviceId, Throwable cause) {
        return new CloudException("Connect failure for service '" + serviceId + "': "
            + cause.getMessage(), true, -1, cause);
    }

    public static CloudException timeout(String serviceId) {
        return new CloudException("Request timeout for service '" + serviceId + "'", true, -1, null);
    }

    public static CloudException http(String serviceId, int status) {
        return new CloudException("Service '" + serviceId + "' returned HTTP " + status,
            status >= 500, status, null);
    }

    /** True when a retry may succeed (connect/timeout/5xx); false for 4xx. */
    public boolean retryable() {
        return retryable;
    }

    /** HTTP status when the failure crossed the wire; {@code -1} for transport failures. */
    public int status() {
        return status;
    }
}
