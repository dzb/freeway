package com.jujin.freeway.boot.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.annotation.Symbol;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hot reload replaces the file-tier snapshot; it does not re-run module
 * binding or re-inject an already-realized singleton. This pins the half the
 * docs matrix states, so "the file changed" and "the running component
 * changed" stay separate answers.
 */
class ConfigReloadContractTest {

    private static final String KEY = "reload.contract.probe";

    @TempDir
    Path dir;

    static final class StartupProbe {
        final String value;

        @Inject
        StartupProbe(@Symbol(KEY) String value) {
            this.value = value;
        }
    }

    @Test
    void hotReloadReachesTheSourceButNotAnAlreadyInjectedValue() throws Exception {
        Path file = Files.writeString(dir.resolve("application.properties"),
            KEY + "=v1\n");
        System.setProperty("freeway.config.file", file.toString());
        AppConfig config = ConfigCascade.load(
            ConfigReloadContractTest.class.getClassLoader());
        ModuleEx probe = binder -> binder.bind(StartupProbe.class);
        try (Container container = Freeway.create(new BootModule(config), probe)) {
            StartupProbe injected = container.get(StartupProbe.class);
            SymbolSource symbols = container.get(SymbolSource.class);
            assertEquals("v1", injected.value);

            Files.writeString(file, KEY + "=v2\n");
            await(() -> "v2".equals(symbols.resolve(KEY)),
                "the live source must see the new file value");
            assertEquals("v1", injected.value,
                "an already-realized singleton keeps the value injected at startup");
        } finally {
            config.close();
            System.clearProperty("freeway.config.file");
        }
    }

    private static void await(java.util.function.BooleanSupplier condition,
            String message) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError(message);
    }
}
