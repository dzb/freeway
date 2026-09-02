package com.jujin.freeway.cloud;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.boot.AppConfigDefault;
import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Symbol precedence is declared, never positional: the cloud secret store
 * (order 15) sits between the framework's env tier (10) and file tier (20),
 * so secrets win over every file-based source regardless of install order.
 * Config files themselves are framework-owned ({@code AppConfigDefault}) —
 * the cloud module no longer reads its own config file.
 */
class CloudSymbolPrecedenceTest {

    private static final String KEY = "db.password";

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0"); // random free port per test
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
    }

    @Test
    void secretsOutrankFrameworkFilesThroughTheRealLoader() throws Exception {
        Path dir = Files.createTempDirectory("freeway-precedence");
        Path configFile = Files.writeString(dir.resolve("extra.properties"),
            KEY + "=from-file\napp.feature=true\n");
        Path secretFile = Files.writeString(dir.resolve("secrets.properties"),
            KEY + "=from-secret\n");
        System.setProperty("freeway.config.file", configFile.toString());
        System.setProperty(CloudConfigKeys.SECRET_FILE, secretFile.toString());
        try (AppRuntime app = FreewayApp.run(new CloudModule())) {
            SymbolSource symbols = app.get(SymbolSource.class);
            assertEquals("from-secret", symbols.resolve(KEY),
                "secrets (order 15) must outrank the framework file tier (order 20)");
            assertEquals("true", symbols.resolve("app.feature"),
                "non-secret keys fall through to the framework file tier");
        } finally {
            System.clearProperty("freeway.config.file");
            System.clearProperty(CloudConfigKeys.SECRET_FILE);
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(secretFile);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void cliAndEnvOutrankSecretsAndFilesByDeclaredOrder() throws Exception {
        Path dir = Files.createTempDirectory("freeway-precedence");
        Path secretFile = Files.writeString(dir.resolve("secrets.properties"),
            KEY + "=from-secret\n");
        System.setProperty(CloudConfigKeys.SECRET_FILE, secretFile.toString());
        try {
            assertResolvesInApp("from-cli", Map.of(KEY, "from-cli"), Map.of(KEY, "from-env"));
            assertResolvesInApp("from-env", Map.of(), Map.of(KEY, "from-env"));
            assertResolvesInApp("from-secret", Map.of(), Map.of());
        } finally {
            System.clearProperty(CloudConfigKeys.SECRET_FILE);
            Files.deleteIfExists(secretFile);
            Files.deleteIfExists(dir);
        }
    }

    private static void assertResolvesInApp(
        String expected, Map<String, String> cli, Map<String, String> env
    ) throws Exception {
        // A framework-tiered config: cli/env sources plus a files baseline.
        // The custom config replaces the default cascade, so the ephemeral
        // HTTP port has to ride the file tier instead of a system property.
        AppConfig config = new AppConfigDefault(
            cli, env, Map.of(KEY, "from-file", HttpConfigKeys.SERVER_PORT, "0"),
            List.of(), List.of());
        try (AppRuntime app = FreewayApp.of(new CloudModule())
                .config((loader, args) -> config)
                .shutdownHook(false)
                .start()) {
            assertEquals(expected, app.get(SymbolSource.class).resolve(KEY),
                "declared tier order must hold across boot + cloud providers");
        } finally {
            config.close();
        }
    }
}
