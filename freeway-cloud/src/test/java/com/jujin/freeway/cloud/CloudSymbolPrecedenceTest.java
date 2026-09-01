package com.jujin.freeway.cloud;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.boot.AppConfigDefault;
import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
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
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Symbol precedence is declared ({@code SecretSymbolSource} order 10 vs
 * {@code CloudConfigSymbolProvider} order 20), never derived from module
 * install order: even a reversed installation must keep resolving secrets
 * over config, and startup must not fail either way.
 */
class CloudSymbolPrecedenceTest {

    private static final String KEY = "db.password";

    @Test
    void secretsOutrankConfigRegardlessOfInstallOrder() throws Exception {
        Path dir = Files.createTempDirectory("freeway-precedence");
        Path configFile =
            Files.writeString(dir.resolve("cloud.properties"), KEY + "=from-config\n");
        Path secretFile =
            Files.writeString(dir.resolve("secrets.properties"), KEY + "=from-secret\n");
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

    @Test
    void bootTiersInterleaveWithCloudByDeclaredOrder() throws Exception {
        // Full cascade: CLI → env → secret → dynamic config → local files.
        // Local files used to sit at the top (single boot blob); they now
        // lose to the secret store and the dynamic config center.
        Path dir = Files.createTempDirectory("freeway-precedence");
        Path configFile =
            Files.writeString(dir.resolve("cloud.properties"), KEY + "=from-config\n");
        Path secretFile =
            Files.writeString(dir.resolve("secrets.properties"), KEY + "=from-secret\n");
        System.setProperty(CloudConfigKeys.CONFIG_FILE, configFile.toString());
        System.setProperty(CloudConfigKeys.SECRET_FILE, secretFile.toString());
        try {
            assertResolvesInApp("from-cli", Map.of(KEY, "from-cli"), Map.of(KEY, "from-env"));
            // CLI silent → env wins over the secret store.
            assertResolvesInApp("from-env", Map.of(), Map.of(KEY, "from-env"));
            // CLI and env silent → secret beats dynamic config and local files.
            assertResolvesInApp("from-secret", Map.of(), Map.of());
        } finally {
            System.clearProperty(CloudConfigKeys.CONFIG_FILE);
            System.clearProperty(CloudConfigKeys.SECRET_FILE);
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(secretFile);
            Files.deleteIfExists(dir);
        }
    }

    private static void assertResolvesInApp(
        String expected, Map<String, String> cli, Map<String, String> env
    ) throws Exception {
        AppConfig config = new AppConfigDefault(
            Map.of(), List.of(),
            List.of(
                new AppConfig.ConfigLayer("cli", cli),
                new AppConfig.ConfigLayer("env", env),
                new AppConfig.ConfigLayer("files", Map.of(KEY, "from-file"))));
        try (AppRuntime app = FreewayApp.of(new CloudModule())
                .config((loader, args) -> config)
                .shutdownHook(false)
                .start()) {
            assertEquals(expected, app.get(SymbolSource.class).resolve(KEY),
                "declared tier order must hold across boot + cloud providers");
        }
    }

    private static void assertSecretWins(ModuleEx first, ModuleEx second) throws Exception {
        try (Container container = Freeway.create(first, second)) {
            List<RuntimeHook> hooks = container.extension(RuntimeHook.class).all();
            for (RuntimeHook hook : hooks) {
                hook.start(container);
            }
            assertEquals("from-secret",
                container.get(SymbolSource.class).resolve(KEY),
                "secrets must outrank config in any install order");
            for (int i = hooks.size() - 1; i >= 0; i--) {
                hooks.get(i).stop(container); // stop the config watcher
            }
        }
    }
}
