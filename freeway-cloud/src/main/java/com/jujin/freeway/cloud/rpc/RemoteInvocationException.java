package com.jujin.freeway.cloud.rpc;

/**
 * Thrown when a remote CallBus handler completed but threw a business
 * exception. Rebuilt from the {@code X-RPC-Exception} / {@code X-RPC-Message}
 * headers — the original class is deliberately <b>not</b> reconstructed:
 * the class may not exist on this side, and faking an inheritance chain
 * would make {@code instanceof} catch blocks lie. Catch this type (or check
 * {@link #remoteClass()}) instead.
 */
public final class RemoteInvocationException extends RuntimeException {

    private final String remoteClass;

    public RemoteInvocationException(String remoteClass, String message) {
        super("Remote handler '" + remoteClass
            + "' failed: " + message);
        this.remoteClass = remoteClass;
    }

    /** Fully-qualified name of the exception thrown by the remote handler. */
    public String remoteClass() {
        return remoteClass;
    }
}
