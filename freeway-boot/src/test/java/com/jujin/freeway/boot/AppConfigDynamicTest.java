package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.BootConfigModule;
import com.jujin.freeway.boot.internal.ConfigLoaderDefault;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The framework file tier: classpath baseline + filesystem overrides, hot
 * reloaded through a WatchService. Reload is pull-based — a modified file is
 * visible to the next {@code @Value}/{@code @Symbol} resolution because the
 * file-tier {@code SymbolProvider} re-reads the live snapshot on every lookup.
 */
class AppConfigDynamicTest {

    /** Unique key — must not collide with the boot test classpath resources. */
    private static final String KEY = "hot.reload.probe";
    private static final String FILE_KEY = "freeway.config.file";

    @TempDir
    Path dir;

    private static AppConfig load(Path configFile) {
        System.setProperty(FILE_KEY, configFile.toString());
        try {
            return new ConfigLoaderDefault().load(AppConfigDynamicTest.class.getClassLoader());
        } finally {
            System.clearProperty(FILE_KEY);
        }
    }

    @Test
    void overrideFileValuesAreVisible() throws Exception {
        Path file = Files.writeString(dir.resolve("override.properties"), KEY + "=from-file\n");
        AppConfig config = load(file);
        try {
            assertEquals("from-file", config.get(KEY),
                "a freeway.config.file override must merge into the file tier");
        } finally {
            config.close();
        }
    }

    @Test
    void fileTierIsReReadOnModification() throws Exception {
        Path file = Files.writeString(dir.resolve("override.properties"), KEY + "=v1\n");
        AppConfigDynamic config = (AppConfigDynamic) load(file);
        try {
            assertEquals("v1", config.get(KEY));
            Files.writeString(file, KEY + "=a-longer-v2\n");
            await(() -> "a-longer-v2".equals(config.get(KEY)),
                "a modified override file must be re-read without a restart");
            // The live source: the file-tier SymbolProvider reads the current
            // snapshot on every lookup — hot reload with no push API.
            SymbolProvider files = config.symbolProviders().get(2);
            assertEquals("a-longer-v2", files.lookup(KEY),
                "the files source must expose the current snapshot, not a stale one");
        } finally {
            config.close();
        }
    }

    @Test
    void deletedOverrideFallsBackToBaseline() throws Exception {
        Path file = Files.writeString(dir.resolve("override.properties"), KEY + "=v1\n");
        AppConfigDynamic config = (AppConfigDynamic) load(file);
        try {
            assertEquals("v1", config.get(KEY));
            Files.delete(file);
            await(() -> config.get(KEY) == null,
                "a deleted override must drop its values (no baseline for this key)");
        } finally {
            config.close();
        }
    }

    @Test
    void hotReloadReachesTheSymbolChain() throws Exception {
        Path file = Files.writeString(dir.resolve("override.properties"), KEY + "=v1\n");
        System.setProperty(FILE_KEY, file.toString());
        try {
            AppConfig config = new ConfigLoaderDefault().load(getClass().getClassLoader());
            try (Container container = Freeway.create(new BootConfigModule(config))) {
                SymbolSource symbols = container.get(SymbolSource.class);
                assertEquals("v1", symbols.resolve(KEY));
                Files.writeString(file, KEY + "=v2\n");
                await(() -> "v2".equals(symbols.resolve(KEY)),
                    "hot reload must reach the symbol chain: the file-tier provider "
                        + "reads the live snapshot on every lookup");
            } finally {
                config.close();
            }
        } finally {
            System.clearProperty(FILE_KEY);
        }
    }

    @Test
    void customLoaderConfigContributesASingleTopTierSource() {
        AppConfig config = new AppConfigDefault(Map.of("k", "static"), List.<String>of());
        List<SymbolProvider> providers = config.symbolProviders();
        assertEquals(1, providers.size(),
            "a custom loader's merged config is one symbol source");
        assertEquals(SymbolProvider.TIER_CLI, providers.get(0).order(),
            "custom loader config keeps the legacy top-of-cascade precedence");
        assertEquals("static", providers.get(0).lookup("k"));
    }

    @Test
    void dynamicConfigDeclaresThreeTieredSources() {
        AppConfigDynamic config = new AppConfigDynamic(
            Map.of(), Map.of(), Map.of("k", "from-file"), List.of(), List.of());
        try {
            List<SymbolProvider> providers = config.symbolProviders();
            assertEquals(
                List.of(SymbolProvider.TIER_CLI, SymbolProvider.TIER_ENV, SymbolProvider.TIER_FILES),
                providers.stream().map(SymbolProvider::order).toList(),
                "cli, env and files tiers in declared order");
            assertEquals("from-file", providers.get(2).lookup("k"));
        } finally {
            config.close();
        }
    }

    private static void await(ThrowingBoolean condition, String message) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for: " + message);
    }

    @FunctionalInterface
    private interface ThrowingBoolean {
        boolean get() throws Exception;
    }
}
