package com.jujin.freeway.cloud.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P1-4 regression (log-forgery surface): a hostile peer answers a remote
 * call with form-encoded CRLF (and unbounded text) in its
 * {@code X-RPC-Exception}/{@code X-RPC-Message} headers. Every layer of the
 * exception chain the caller sees — the outer {@link CloudException} message
 * and the {@link RemoteInvocationException} cause — must render sanitized
 * peer text only: no control characters, no unbounded payload.
 *
 * <p>The responses are stubbed at the {@link CloudHttpClient} boundary so the
 * forged headers reach {@code RemoteCaller.businessException} without a real
 * (header-refusing) HTTP server in between.
 */
class RemoteCallerSanitizationTest {

    private static final String SERVICE = "target";

    /** A transport that always answers the remote call with forged headers. */
    private static RemoteCaller callerWith(int status, String exClass, String message) {
        CloudHttpClient hostile = (serviceId, request) -> new CloudResponse(status,
            Map.of("x-rpc-exception", List.of(exClass), "x-rpc-message", List.of(message)),
            new byte[0]);
        return new RemoteCaller(hostile, new JsonCodecDefault());
    }

    private static CloudException invokeWith(String exClass, String message) {
        return assertThrows(CloudException.class, () -> callerWith(400, exClass, message)
            .invoke(SERVICE, "user", "charge", List.of(), String.class));
    }

    @Test
    void forgedCrlfInExceptionHeadersNeverReachesAnyChainLevel() {
        CloudException ex = invokeWith(
            "com.evil.Boom%0d%0aX-Injected:%20pwned",
            "overdrawn%0d%0aX-Injected:%20fake");

        // ① Outer CloudException message carries no CRLF.
        assertControlFree(ex.getMessage());
        assertTrue(ex.getMessage().contains("overdrawnX-Injected: fake"),
            "the message must survive with the control characters stripped: "
                + ex.getMessage());

        // ② The cause (RemoteInvocationException) is clean too — message and
        //    remoteClass alike, not only the outer wrapper.
        RemoteInvocationException cause = assertInstanceOf(
            RemoteInvocationException.class, ex.getCause());
        assertControlFree(cause.getMessage());
        assertControlFree(cause.remoteClass());
        assertEquals("com.evil.BoomX-Injected: pwned", cause.remoteClass());
        assertTrue(cause.getMessage().contains("overdrawnX-Injected: fake"),
            "cause must carry the sanitized message: " + cause.getMessage());
    }

    @Test
    void overlongMessageIsCappedInTheCauseAsWellAsTheOuterMessage() {
        CloudException ex = invokeWith("com.evil.Boom", "m".repeat(400));

        RemoteInvocationException cause = assertInstanceOf(
            RemoteInvocationException.class, ex.getCause());
        assertFalse(cause.getMessage().contains("m".repeat(201)),
            "cause must cap the peer text at 200 characters: " + cause.getMessage());
        assertTrue(cause.getMessage().endsWith("..."));
        assertFalse(ex.getMessage().contains("m".repeat(201)),
            "outer message must cap the peer text at 200 characters: " + ex.getMessage());
        assertControlFree(ex.getMessage());
    }

    @Test
    void undecodableHeaderDegradesToRawTextAndStillPassesSanitization() {
        // A bare "%" is not a valid escape: decode() degrades to the raw
        // value, which must then flow through sanitization unchanged (this
        // value carries no control characters and stays under the cap).
        CloudException ex = invokeWith("com.evil.Boom", "100% sure");
        assertTrue(ex.getMessage().contains("100% sure"));
        RemoteInvocationException cause = assertInstanceOf(
            RemoteInvocationException.class, ex.getCause());
        assertTrue(cause.getMessage().contains("100% sure"));
        assertControlFree(cause.getMessage());
    }

    @Test
    void remoteInvocationExceptionSanitizesDirectConstructionToo() {
        // The constructor is public API; wire-derived values may reach it from
        // sites other than RemoteCaller, so it must enforce the same rule.
        RemoteInvocationException ex = new RemoteInvocationException(
            "com.evil.Boom\r\nX-Injected: pwned", "overdrawn\r\nX-Injected: fake");
        assertControlFree(ex.getMessage());
        assertEquals("com.evil.BoomX-Injected: pwned", ex.remoteClass());
        assertTrue(ex.getMessage().contains("overdrawnX-Injected: fake"));

        RemoteInvocationException capped =
            new RemoteInvocationException("c", "m".repeat(300));
        assertFalse(capped.getMessage().contains("m".repeat(201)));
        assertTrue(capped.getMessage().endsWith("..."));
    }

    /** Mirrors {@code \p{Cntrl}}: C0 controls plus DEL. */
    private static void assertControlFree(String text) {
        boolean clean = text.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
        assertTrue(clean, "no control characters allowed, got: "
            + text.replace("\r", "\\r").replace("\n", "\\n"));
    }
}
