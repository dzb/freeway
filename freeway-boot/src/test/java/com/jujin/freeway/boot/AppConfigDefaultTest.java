package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.AppConfigDefault;
import com.jujin.freeway.boot.internal.BootModule;
import com.jujin.freeway.boot.internal.ConfigLoaderImpl;
import com.jujin.freeway.boot.internal.ConfigSources;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigDefaultTest {

    /**
     * Reads through the contract the config actually offers: providers in
     * declared order, first non-null value wins — the same rule the symbol
     * chain applies. There is no map view to shortcut through.
     */
    private static String value(AppConfig config, String key) {
        for (SymbolProvider provider : config.providers()) {
            String value = provider.lookup(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Test
    void skipsNullKeysAndValuesFromCustomLoaders() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("good", "value");
        input.put("null-value", null);
        input.put(null, "null-key");

        AppConfig config = AppConfigDefault.of(input, List.of());

        assertEquals("value", value(config, "good"));
        assertNull(value(config, "null-value"));
    }

    @Test
    void nullValuesAndProfilesAreTolerated() {
        AppConfig config = AppConfigDefault.of(null, null);
        assertNull(value(config, "anything"), "a null config carries no values");
        assertTrue(config.profiles().isEmpty());
    }

    @Test
    void coercerParsedSpecResolvesContainerTypes() {
        // No per-key parser: the container Coercer resolves the value —
        // "2s" duration syntax and user-registered rules apply. This is the
        // post-processing step over a resolved raw value.
        SymbolSpec<Duration> timeout = SymbolSpec.of(
            "pool.timeout", Duration.class,
            Duration.ofSeconds(5));
        Coercer coercer =
            new CoercerDefault();

        assertEquals(Duration.ofSeconds(2),
            timeout.parse("2s", coercer),
            "the coercer resolves duration syntax");
        assertEquals(Duration.ofSeconds(5),
            timeout.parse("", coercer),
            "blank input falls back to the default");
    }

    @Test
    void requiredKeyFailsFastWhenAbsentOrBlank() {
        SymbolSpec<String> password = SymbolSpec.required(
            "db.password", String.class, String::valueOf);
        AppConfig absent = AppConfigDefault.of(
            new LinkedHashMap<>(), List.of());
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> password.parse(value(absent, "db.password")));
        org.junit.jupiter.api.Assertions.assertTrue(
            ex.getMessage().contains("Missing required") && ex.getMessage().contains("db.password"),
            "got: " + ex.getMessage());

        AppConfig blank = AppConfigDefault.of(
            new LinkedHashMap<>(Map.of("db.password", " ")), List.of());
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> password.parse(value(blank, "db.password")),
            "a blank required value is equally missing");

        AppConfig present = AppConfigDefault.of(
            new LinkedHashMap<>(Map.of("db.password", "s3cret")), List.of());
        assertEquals("s3cret", password.parse(value(present, "db.password")));
    }

    // ==================== symbol sources (tiered declaration) ====================

    @Test
    void bothFormsContributeTheTieredSources() {
        // Static form: the given map becomes the file tier.
        assertTieredSources(AppConfigDefault.of(Map.of("k", "static"), List.of()));
        // Tiered form: cli/env sources plus a files baseline.
        assertTieredSources(new AppConfigDefault(
            new ConfigSources(
                Map.of("k", "cli"), Map.of("k", "env"), Map.of("k", "from-file"),
                List.of()),
            List.of()));
    }

    private static void assertTieredSources(AppConfigDefault config) {
        try {
            List<SymbolProvider> providers = config.providers();
            assertEquals(
                List.of(SymbolProvider.TIER_CLI, SymbolProvider.TIER_ENV,
                    SymbolProvider.TIER_FILES),
                providers.stream().map(SymbolProvider::order).toList(),
                "cli, env and files tiers in declared order");
        } finally {
            config.close();
        }
    }

    @Test
    void staticFormValuesSitOnTheFileTier() {
        AppConfigDefault config = AppConfigDefault.of(Map.of("k", "from-file"), List.of());
        try {
            SymbolProvider files = config.providers().get(2);
            assertEquals("from-file", files.lookup("k"),
                "an undifferentiated config behaves like the file tier — env/CLI "
                    + "and module sources (e.g. secrets) outrank it");
        } finally {
            config.close();
        }
    }

    // ==================== hot reload (file tier) ====================

    /** Unique key — must not collide with the boot test classpath resources. */
    private static final String HOT_KEY = "hot.reload.probe";
    private static final String FILE_KEY = "freeway.config.file";

    @TempDir
    Path dir;

    private static AppConfig load(Path configFile) {
        System.setProperty(FILE_KEY, configFile.toString());
        try {
            return ConfigLoaderImpl.load(AppConfigDefaultTest.class.getClassLoader());
        } finally {
            System.clearProperty(FILE_KEY);
        }
    }

    @Test
    void overrideFileValuesAreVisible() throws Exception {
        Path file = Files.writeString(dir.resolve("override.properties"), HOT_KEY + "=from-file\n");
        AppConfig config = load(file);
        try {
            assertEquals("from-file", value(config, HOT_KEY),
                "a freeway.config.file override must merge into the file tier");
        } finally {
            config.close();
        }
    }

    @Test
    void jsonOverrideFileIsParsedAsJson() throws Exception {
        // Regression: overrides were parsed as properties regardless of
        // extension — a JSON override file produced mangled keys like
        // '{"app.name"' and its real keys resolved to null.
        Path file = Files.writeString(dir.resolve("override.json"),
            "{\"" + HOT_KEY + "\": \"from-json\", \"nested\": {\"key\": \"flat\"}}");
        AppConfig config = load(file);
        try {
            assertEquals("from-json", value(config, HOT_KEY),
                "a .json override must parse as JSON, not properties");
            assertEquals("flat", value(config, "nested.key"),
                "nested JSON objects flatten to dotted keys");
        } finally {
            config.close();
        }
    }

    @Test
    void jsonOverrideFileHotReloadsAsJson() throws Exception {
        Path file = Files.writeString(dir.resolve("override.json"),
            "{\"" + HOT_KEY + "\": \"v1\"}");
        AppConfigDefault config = (AppConfigDefault) load(file);
        try {
            assertEquals("v1", value(config, HOT_KEY));
            Files.writeString(file, "{\"" + HOT_KEY + "\": \"v2\"}");
            await(() -> "v2".equals(value(config, HOT_KEY)),
                "a modified JSON override must re-read as JSON, not properties");
        } finally {
            config.close();
        }
    }

    @Test
    void fileTierIsReReadOnModification() throws Exception {
        Path file = Files.writeString(dir.resolve("override.properties"), HOT_KEY + "=v1\n");
        AppConfigDefault config = (AppConfigDefault) load(file);
        try {
            assertEquals("v1", value(config, HOT_KEY));
            Files.writeString(file, HOT_KEY + "=a-longer-v2\n");
            await(() -> "a-longer-v2".equals(value(config, HOT_KEY)),
                "a modified override file must be re-read without a restart");
            // The live source: the file-tier SymbolProvider reads the current
            // snapshot on every lookup — hot reload with no push API.
            SymbolProvider files = config.providers().get(2);
            assertEquals("a-longer-v2", files.lookup(HOT_KEY),
                "the files source must expose the current snapshot, not a stale one");
        } finally {
            config.close();
        }
    }

    @Test
    void deletedOverrideFallsBackToBaseline() throws Exception {
        Path file = Files.writeString(dir.resolve("override.properties"), HOT_KEY + "=v1\n");
        AppConfigDefault config = (AppConfigDefault) load(file);
        try {
            assertEquals("v1", value(config, HOT_KEY));
            Files.delete(file);
            await(() -> value(config, HOT_KEY) == null,
                "a deleted override must drop its values (no baseline for this key)");
        } finally {
            config.close();
        }
    }

    @Test
    void fileCreatedAfterStartIsPickedUp() throws Exception {
        // The directory exists at construction, so it is registered even though
        // the file does not — dropping the file in later is the standard way to
        // externalize config next to a deployed jar.
        Path file = dir.resolve("late.properties");
        AppConfigDefault config = new AppConfigDefault(
            new ConfigSources(Map.of(), Map.of(), Map.of("k", "v"), List.of()),
            List.of(file));
        try {
            assertNull(value(config, HOT_KEY));
            Files.writeString(file, HOT_KEY + "=late\n");
            await(() -> "late".equals(value(config, HOT_KEY)),
                "a file created in a watched directory must be picked up");
        } finally {
            config.close();
        }
    }

    @Test
    void closeIsIdempotent() {
        // The runtime hook closes the config, and a failed startup closes it
        // too — a second close must not throw or corrupt the file tier.
        AppConfigDefault config = new AppConfigDefault(
            new ConfigSources(Map.of(), Map.of(), Map.of("k", "v"), List.of()),
            List.of(dir.resolve("missing.properties")));
        config.close();
        config.close();
        assertEquals("v", value(config, "k"));
    }

    @Test
    void hotReloadReachesTheSymbolChain() throws Exception {
        Path file = Files.writeString(dir.resolve("override.properties"), HOT_KEY + "=v1\n");
        System.setProperty(FILE_KEY, file.toString());
        try {
            AppConfig config = ConfigLoaderImpl.load(AppConfigDefaultTest.class.getClassLoader());
            try (Container container = Freeway.create(new BootModule(config))) {
                SymbolSource symbols = container.get(SymbolSource.class);
                assertEquals("v1", symbols.resolve(HOT_KEY));
                Files.writeString(file, HOT_KEY + "=v2\n");
                await(() -> "v2".equals(symbols.resolve(HOT_KEY)),
                    "hot reload must reach the symbol chain: the file-tier provider "
                        + "reads the live snapshot on every lookup");
            } finally {
                config.close();
            }
        } finally {
            System.clearProperty(FILE_KEY);
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
