package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.config.CloudConfigModule;
import com.jujin.freeway.cloud.secret.CloudSecretModule;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Symbol precedence is declared ({@code SecretSymbolSource} order 10 vs
 * {@code CloudConfigSymbolProvider} order 20), never derived from module
 * install order: even a reversed installation must keep resolving secrets
 * over config, and startup must not fail either way.
 */
class CloudSymbolPrecedenceTest {

    @Test
    void secretsOutrankConfigRegardlessOfInstallOrder() throws Exception {
        Path dir = Files.createTempDirectory("freeway-precedence");
        Path configFile =
            Files.writeString(dir.resolve("cloud.properties"), "db.password=from-config\n");
        Path secretFile =
            Files.writeString(dir.resolve("secrets.properties"), "db.password=from-secret\n");
        System.setProperty(CloudConfigKeys.CONFIG_FILE, configFile.toString());
        System.setProperty(CloudConfigKeys.SECRET_FILE, secretFile.toString());
        try {
            // Reversed install — the config module first, which used to flip
            // the precedence silently before SymbolProvider.order() existed.
            assertSecretWins(new CloudConfigModule(), new CloudSecretModule());
            // Umbrella order.
            assertSecretWins(new CloudSecretModule(), new CloudConfigModule());
        } finally {
            System.clearProperty(CloudConfigKeys.CONFIG_FILE);
            System.clearProperty(CloudConfigKeys.SECRET_FILE);
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(secretFile);
            Files.deleteIfExists(dir);
        }
    }

    private static void assertSecretWins(ModuleEx first, ModuleEx second) throws Exception {
        try (Container container = Freeway.create(first, second)) {
            List<RuntimeHook> hooks = container.extension(RuntimeHook.class).all();
            for (RuntimeHook hook : hooks) {
                hook.start(container);
            }
            assertEquals("from-secret",
                container.get(SymbolSource.class).resolve("db.password"),
                "secrets must outrank config in any install order");
            for (int i = hooks.size() - 1; i >= 0; i--) {
                hooks.get(i).stop(container); // stop the config watcher
            }
        }
    }
}
