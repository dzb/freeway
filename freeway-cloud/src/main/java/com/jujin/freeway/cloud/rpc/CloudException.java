package com.jujin.freeway.cloud.rpc;

/**
 * Remote invocation failure. {@link #retryable()} distinguishes transport
 * failures (connect/timeout, 5xx — retryable) from client errors (4xx) and
 * local rejections (no instance / circuit open / rate limited / interrupted —
 * not retryable), and {@link #status()} carries the HTTP status when the
 * failure crossed the wire ({@code -1} for transport failures).
 */
public class CloudException extends RuntimeException {

    private final boolean retryable;
    private final int status;

    private CloudException(String message, boolean retryable, int status, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.status = status;
    }

    /** Generic factory for module-internal failure mapping (e.g. the remote-CallBus bridge). */
    public static CloudException of(String message, boolean retryable, int status, Throwable cause) {
        return new CloudException(message, retryable, status, cause);
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
        return timeout(serviceId, null);
    }

    public static CloudException timeout(String serviceId, Throwable cause) {
        return new CloudException("Request timeout for service '" + serviceId + "'",
            true, -1, cause);
    }

    /** The calling thread was interrupted — never retried (the caller asked to stop). */
    public static CloudException interrupted(String serviceId, Throwable cause) {
        return new CloudException("Request interrupted for service '" + serviceId + "'",
            false, -1, cause);
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
