package com.jujin.freeway.cloud.rpc;

import java.util.Objects;

/**
 * Remote invocation failure.
 *
 * <p>{@link #kind()} answers <em>why</em> structurally — one value per distinct
 * caller action — instead of leaving it to the message text:
 * {@link Kind#NO_INSTANCE} (fix the deployment / discovery), {@link Kind#CIRCUIT_OPEN}
 * and {@link Kind#RATE_LIMITED} (backpressure, try later), {@link Kind#CONNECT}
 * (nothing was sent, safe to replay), {@link Kind#TIMEOUT}/{@link Kind#TRANSPORT}
 * (ambiguous: replay only idempotent operations), {@link Kind#INTERRUPTED} (the
 * caller asked to stop), {@link Kind#HTTP} (the peer answered an unmapped
 * status), {@link Kind#BUSINESS} (the handler threw — the cause carries
 * {@link RemoteInvocationException}), {@link Kind#REPLY_UNREADABLE} (the reply
 * did not match the declared type) and {@link Kind#REJECTED} (the peer refused
 * the call's shape — version, mapping or method).</p>
 *
 * <p>{@link #retryable()} distinguishes transport failures (connect/timeout,
 * 5xx — retryable) from client errors (4xx) and local rejections (no instance /
 * circuit open / rate limited / interrupted — not retryable), and
 * {@link #status()} carries the HTTP status when the failure crossed the wire
 * ({@code -1} for transport failures).
 *
 * <p>{@link #outcomeUnknown()} splits the retryable class further: timeouts,
 * mid-flight I/O failures and 5xx answers leave the request's effect on the
 * peer unknown — replaying them is only safe for idempotent operations
 * ({@link CloudRequest#idempotent()}). Connect failures are false — the
 * request never left this process, so a retry is safe regardless.</p>
 */
public class CloudException extends RuntimeException {

    /**
     * Why the call failed — the row of the error table this failure came from.
     * Values exist for distinctions a caller acts on, not for every internal
     * step: {@link #CIRCUIT_OPEN} and {@link #RATE_LIMITED} differ because a
     * breaker opens on a failing dependency while a limiter is this node's own
     * ceiling.
     */
    public enum Kind {
        /** Discovery found no live instance (deployment state, never retried). */
        NO_INSTANCE,
        /** The breaker is open for this service: nothing was sent. */
        CIRCUIT_OPEN,
        /** The local rate limiter refused: nothing was sent. */
        RATE_LIMITED,
        /** Connect refused or connect-phase timeout: the request never left this process. */
        CONNECT,
        /** No reply within the budget: the peer may have applied it. */
        TIMEOUT,
        /** Mid-flight I/O failure (reset, broken pipe, TLS error): outcome unknown. */
        TRANSPORT,
        /** The calling thread was interrupted — the caller asked to stop. */
        INTERRUPTED,
        /** The peer answered with a status carrying no RPC failure structure. */
        HTTP,
        /** The remote handler threw; the cause carries {@link RemoteInvocationException}. */
        BUSINESS,
        /** The reply did not decode into the declared return type. */
        REPLY_UNREADABLE,
        /** The peer refused the call's shape (version, mapping or method). */
        REJECTED,
        /** A local failure before the transport (bad URL or header, discovery bug). */
        DISPATCH,
        /** A mapped failure with no more specific kind — prefer a named factory. */
        OTHER
    }

    private final Kind kind;
    private final boolean retryable;
    private final boolean outcomeUnknown;
    private final int status;

    private CloudException(
            String message, Kind kind, boolean retryable, boolean outcomeUnknown, int status,
            Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.retryable = retryable;
        this.outcomeUnknown = outcomeUnknown;
        this.status = status;
    }

    /** Why this call failed; see {@link Kind} for the action each value implies. */
    public Kind kind() {
        return kind;
    }

    /** Generic factory for module-internal failure mapping (e.g. the RPC client).
     *  Mapped failures are deterministic — the outcome is known, never ambiguous. */
    public static CloudException of(String message, boolean retryable, int status, Throwable cause) {
        return new CloudException(message, Kind.OTHER, retryable, false, status, cause);
    }

    /** A business failure raised by the remote handler. */
    public static CloudException business(String message, int status, Throwable cause) {
        return new CloudException(message, Kind.BUSINESS, false, false, status, cause);
    }

    /** The reply could not be decoded into the declared return type. */
    public static CloudException unreadableReply(String message, Throwable cause) {
        return new CloudException(message, Kind.REPLY_UNREADABLE, false, false, 200, cause);
    }

    /** The peer refused the call's shape (unknown version, undeclared mapping, unknown method). */
    public static CloudException rejected(String message, int status, Throwable cause) {
        return new CloudException(message, Kind.REJECTED, false, false, status, cause);
    }

    public static CloudException noInstance(String serviceId) {
        return new CloudException("No live instance for service '" + serviceId + "'",
            Kind.NO_INSTANCE, false, false, -1, null);
    }

    public static CloudException circuitOpen(String serviceId) {
        return new CloudException("Circuit breaker OPEN for service '" + serviceId + "'",
            Kind.CIRCUIT_OPEN, false, false, -1, null);
    }

    public static CloudException rateLimited(String serviceId) {
        return new CloudException("Rate limit exceeded for service '" + serviceId + "'",
            Kind.RATE_LIMITED, false, false, -1, null);
    }

    /** The request never left this process (connect refused / connect-phase
     *  timeout): the peer cannot have applied anything, so a retry is safe
     *  for every operation. */
    public static CloudException connect(String serviceId, Throwable cause) {
        return new CloudException("Connect failure for service '" + serviceId + "': "
            + cause.getMessage(), Kind.CONNECT, true, false, -1, cause);
    }

    public static CloudException timeout(String serviceId) {
        return timeout(serviceId, null);
    }

    /** The response did not arrive in time: the peer may have applied the
     *  request — ambiguous outcome. */
    public static CloudException timeout(String serviceId, Throwable cause) {
        return new CloudException("Request timeout for service '" + serviceId + "'",
            Kind.TIMEOUT, true, true, -1, cause);
    }

    /** Mid-flight I/O failure (connection reset after send, broken pipe, SSL
     *  error): the request may have reached the peer — ambiguous outcome. */
    public static CloudException transport(String serviceId, Throwable cause) {
        return new CloudException("Transport failure for service '" + serviceId + "': "
            + cause.getMessage(), Kind.TRANSPORT, true, true, -1, cause);
    }

    /** The calling thread was interrupted — never retried (the caller asked to stop). */
    public static CloudException interrupted(String serviceId, Throwable cause) {
        return new CloudException("Request interrupted for service '" + serviceId + "'",
            Kind.INTERRUPTED, false, false, -1, cause);
    }

    /**
     * A local failure that never reached the transport (bad URL/header,
     * discovery backend bug) — deterministic, so never retried. Keeps the
     * caller's failure surface uniform: every {@code call()} outcome is a
     * {@code CloudException}, and a half-open probe still gets an outcome.
     */
    public static CloudException dispatch(String serviceId, Throwable cause) {
        return new CloudException("Dispatch failure for service '" + serviceId + "': " + cause,
            Kind.DISPATCH, false, false, -1, cause);
    }

    public static CloudException http(String serviceId, int status) {
        return new CloudException("Service '" + serviceId + "' returned HTTP " + status,
            Kind.HTTP, status >= 500, status >= 500, status, null);
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
