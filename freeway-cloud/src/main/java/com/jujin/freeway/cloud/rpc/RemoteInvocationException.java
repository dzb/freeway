package com.jujin.freeway.cloud.rpc;

/**
 * Thrown when a remote CallBus handler completed but threw a business
 * exception. Rebuilt from the {@code X-RPC-Exception} / {@code X-RPC-Message}
 * headers — the original class is deliberately <b>not</b> reconstructed:
 * the class may not exist on this side, and faking an inheritance chain
 * would make {@code instanceof} catch blocks lie. Catch this type (or check
 * {@link #remoteClass()}) instead.
 *
 * <p>Both inputs are peer-authored wire text, so the constructor sanitizes
 * them (control characters stripped, 200-character cap — see
 * {@link RemoteCaller#sanitizePeerText(String)}): whatever site builds this
 * exception from remote values, its rendering cannot forge a log line.
 */
public final class RemoteInvocationException extends RuntimeException {

    private final String remoteClass;

    public RemoteInvocationException(String remoteClass, String message) {
        super("Remote handler '" + text(remoteClass)
            + "' failed: " + text(message));
        this.remoteClass = remoteClass == null ? null
            : RemoteCaller.sanitizePeerText(remoteClass);
    }

    /** Fully-qualified name of the exception thrown by the remote handler. */
    public String remoteClass() {
        return remoteClass;
    }

    /** Null keeps the historical "null" string-concatenation rendering. */
    private static String text(String value) {
        return value == null ? "null" : RemoteCaller.sanitizePeerText(value);
    }
}
