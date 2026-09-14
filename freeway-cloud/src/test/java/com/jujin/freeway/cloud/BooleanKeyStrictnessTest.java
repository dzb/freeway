package com.jujin.freeway.cloud;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Cloud's boolean keys are coerced, not {@code Boolean.parseBoolean}'d: a
 * value nobody can read as a boolean fails startup naming the key, instead of
 * silently becoming {@code false} — which for a key like
 * {@code auth.extract.enabled} means silently turning a security switch off.
 * The accepted vocabulary is the framework's ({@code true/false}, {@code yes/no},
 * {@code on/off}, {@code 1/0}).
 */
class BooleanKeyStrictnessTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty(CloudConfigKeys.AUTH_EXTRACT_ENABLED);
        System.clearProperty(CloudConfigKeys.RPC_TRACE_ENABLED);
    }

    @Test
    void unreadableBooleanFailsStartupNamingTheKey() {
        System.setProperty(CloudConfigKeys.AUTH_EXTRACT_ENABLED, "maybe");
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> FreewayApp.of().add(CloudModule.class).start());
        assertTrue(chainMessages(failure).contains("auth.extract.enabled"),
            "the failure must name the key: " + chainMessages(failure));
        assertTrue(chainMessages(failure).contains("maybe"),
            "the failure must show the offending value: " + chainMessages(failure));
    }

    @Test
    void readableBooleanSpellingsAreAccepted() {
        System.setProperty(CloudConfigKeys.AUTH_EXTRACT_ENABLED, "yes");
        System.setProperty(CloudConfigKeys.RPC_TRACE_ENABLED, "off");
        try (AppRuntime app = FreewayApp.of().add(CloudModule.class).start()) {
            assertNotNull(app, "the framework's boolean vocabulary (yes/off) boots");
        }
    }

    /** Every message on the cause chain — the key is named by the spec, the
     *  offending value by the coercer, and neither is guaranteed to be the root. */
    private static String chainMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append(" | ");
        }
        return messages.toString();
    }
}
