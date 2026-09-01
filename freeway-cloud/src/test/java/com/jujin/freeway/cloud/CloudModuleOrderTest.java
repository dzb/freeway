package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.config.CloudConfigModule;
import com.jujin.freeway.cloud.secret.CloudSecretModule;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.RuntimeHook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Install-order contract of the cloud sub-modules: symbol resolution is
 * first-match-wins over {@code SymbolProvider} contributions, so a
 * misordered manual assembly must fail startup instead of silently flipping
 * the secret/config precedence (the umbrella {@code CloudModule} installs
 * correctly and never hits this).
 */
class CloudModuleOrderTest {

    @Test
    void secretAfterConfigFailsStartupWithClearMessage() {
        Container container = Freeway.create(new CloudConfigModule(), new CloudSecretModule());
        try {
            RuntimeHook secretHook = container.extension(RuntimeHook.class)
                .get(CloudHooks.SECRET).orElseThrow();
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> secretHook.start(container));
            assertTrue(ex.getMessage().contains(
                    "CloudSecretModule must be installed before CloudConfigModule"),
                "the message must name the violated order: " + ex.getMessage());
        } finally {
            container.close();
        }
    }

    @Test
    void secretBeforeConfigStartsCleanly() throws Exception {
        Container container = Freeway.create(new CloudSecretModule(), new CloudConfigModule());
        try {
            for (RuntimeHook hook : container.extension(RuntimeHook.class).all()) {
                hook.start(container);
            }
        } finally {
            container.close();
        }
    }

    @Test
    void secretAloneIsUnaffected() {
        Container container = Freeway.create(new CloudSecretModule());
        try {
            RuntimeHook secretHook = container.extension(RuntimeHook.class)
                .get(CloudHooks.SECRET).orElseThrow();
            assertDoesNotThrow(() -> secretHook.start(container),
                "no config module → nothing to be misordered against");
        } finally {
            container.close();
        }
    }
}
