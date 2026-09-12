package com.jujin.freeway.cloud.rpc;

/**
 * Remote invocation failure. {@link #retryable()} distinguishes transport
 * failures (connect/timeout, 5xx — retryable) from client errors (4xx) and
 * local rejections (no instance / circuit open / rate limited / interrupted —
 * not retryable), and {@link #status()} carries the HTTP status when the
 * failure crossed the wire ({@code -1} for transport failures).
 *
 * <p>{@link #outcomeUnknown()} splits the retryable class further: timeouts,
 * mid-flight I/O failures and 5xx answers leave the request's effect on the
 * peer unknown — replaying them is only safe for idempotent operations
 * ({@link CloudRequest#idempotent()}). Connect failures are false — the
 * request never left this process, so a retry is safe regardless.</p>
 */
public class CloudException extends RuntimeException {

    private final boolean retryable;
    private final boolean outcomeUnknown;
    private final int status;

    private CloudException(
            String message, boolean retryable, boolean outcomeUnknown, int status, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.outcomeUnknown = outcomeUnknown;
        this.status = status;
    }

    /** Generic factory for module-internal failure mapping (e.g. the RPC client).
     *  Mapped failures are deterministic — the outcome is known, never ambiguous. */
    public static CloudException of(String message, boolean retryable, int status, Throwable cause) {
        return new CloudException(message, retryable, false, status, cause);
    }

    public static CloudException noInstance(String serviceId) {
        return new CloudException("No live instance for service '" + serviceId + "'", false, false, -1, null);
    }

    public static CloudException circuitOpen(String serviceId) {
        return new CloudException("Circuit breaker OPEN for service '" + serviceId + "'", false, false, -1, null);
    }

    public static CloudException rateLimited(String serviceId) {
        return new CloudException("Rate limit exceeded for service '" + serviceId + "'", false, false, -1, null);
    }

    /** The request never left this process (connect refused / connect-phase
     *  timeout): the peer cannot have applied anything, so a retry is safe
     *  for every operation. */
    public static CloudException connect(String serviceId, Throwable cause) {
        return new CloudException("Connect failure for service '" + serviceId + "': "
            + cause.getMessage(), true, false, -1, cause);
    }

    public static CloudException timeout(String serviceId) {
        return timeout(serviceId, null);
    }

    /** The response did not arrive in time: the peer may have applied the
     *  request — ambiguous outcome. */
    public static CloudException timeout(String serviceId, Throwable cause) {
        return new CloudException("Request timeout for service '" + serviceId + "'",
            true, true, -1, cause);
    }

    /** Mid-flight I/O failure (connection reset after send, broken pipe, SSL
     *  error): the request may have reached the peer — ambiguous outcome. */
    public static CloudException transport(String serviceId, Throwable cause) {
        return new CloudException("Transport failure for service '" + serviceId + "': "
            + cause.getMessage(), true, true, -1, cause);
    }

    /** The calling thread was interrupted — never retried (the caller asked to stop). */
    public static CloudException interrupted(String serviceId, Throwable cause) {
        return new CloudException("Request interrupted for service '" + serviceId + "'",
            false, false, -1, cause);
    }

    /**
     * A local failure that never reached the transport (bad URL/header,
     * discovery backend bug) — deterministic, so never retried. Keeps the
     * caller's failure surface uniform: every {@code call()} outcome is a
     * {@code CloudException}, and a half-open probe still gets an outcome.
     */
    public static CloudException dispatch(String serviceId, Throwable cause) {
        return new CloudException("Dispatch failure for service '" + serviceId + "': " + cause,
            false, false, -1, cause);
    }

    public static CloudException http(String serviceId, int status) {
        return new CloudException("Service '" + serviceId + "' returned HTTP " + status,
            status >= 500, status >= 500, status, null);
    }

    /** True when a retry may succeed (connect/timeout/5xx); false for 4xx. */
    public boolean retryable() {
        return retryable;
    }

    /**
     * True when the request may have reached the peer and its effect is
     * unknown — timeouts, mid-flight I/O failures and 5xx answers. Retrying
     * these is only safe for idempotent operations
     * ({@link CloudRequest#idempotent()}); the resilience loop enforces this.
     * False for connect failures (the request never left this process) and
     * deterministic failures (4xx, business errors, local rejections).
     */
    public boolean outcomeUnknown() {
        return outcomeUnknown;
    }

    /** HTTP status when the failure crossed the wire; {@code -1} for transport failures. */
    public int status() {
        return status;
    }
}
