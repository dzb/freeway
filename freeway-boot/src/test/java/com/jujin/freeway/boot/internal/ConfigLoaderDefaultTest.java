package com.jujin.freeway.boot.internal;
import java.util.Arrays;

import com.jujin.freeway.boot.AppConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderDefaultTest {
    @Test
    void keepsSourcesSeparateAndAppliesPrecedenceOnMerge() {
        ConfigLoaderDefault.BootConfigLayers layers = ConfigLoaderDefault.loadLayers(
            Thread.currentThread().getContextClassLoader(),
            "--freeway.profile=dev",
            "--app.name=Overridden",
            "--server.port=7070"
        );

        assertEquals(List.of("dev"), layers.profiles());
        assertEquals("Freeway Boot", layers.properties().get("app.name"));
        assertEquals("9090", layers.properties().get("server.port"));
        assertEquals("Standalone IoC container", layers.json().get("app.description"));
        assertEquals("1.0.0", layers.json().get("app.version"));
        assertEquals("localhost", layers.json().get("server.host"));
        assertEquals("Dev Boot", layers.profileProperties().get("app.name"));
        assertEquals("9191", layers.profileProperties().get("server.port"));
        assertEquals("Profiled IoC container", layers.profileJson().get("app.description"));
        assertEquals("dev.localhost", layers.profileJson().get("server.host"));
        assertEquals("Overridden", layers.args().get("app.name"));
        assertEquals("7070", layers.args().get("server.port"));
        assertEquals("Overridden", merged(layers).get("app.name"));
        assertEquals("7070", merged(layers).get("server.port"));
        assertEquals("Profiled IoC container", merged(layers).get("app.description"));
        assertEquals("dev.localhost", merged(layers).get("server.host"));
    }

    @Test
    void autoPrefixesSimpleCliKeysWithFreewayNamespace() {
        Map<String, String> args = ConfigLoaderDefault.parseArgs(
            "--profile=dev",
            "--verbose",
            "--app.name=Overridden",
            "--server.port=7070",
            "-Dlog.color=always"
        );

        // Simple keys (no dot) get the freeway. prefix
        assertEquals("dev", args.get("freeway.profile"));
        assertEquals("true", args.get("freeway.verbose"));

        // Dotted keys are preserved as-is
        assertEquals("Overridden", args.get("app.name"));
        assertEquals("7070", args.get("server.port"));
        assertEquals("always", args.get("log.color"));

        // No unprefixed simple keys leak through
        assertEquals(5, args.size());
    }

    @Test
    void explicitFreewayPrefixStillWorks() {
        Map<String, String> args = ConfigLoaderDefault.parseArgs(
            "--freeway.profile=dev"
        );

        assertEquals("dev", args.get("freeway.profile"));
        assertEquals(1, args.size());
    }

    @Test
    void parsesNegativeNumberValues() {
        Map<String, String> args = ConfigLoaderDefault.parseArgs(
            "--offset=-1",
            "--port", "-1",
            "--ratio", "-2.5"
        );

        assertEquals("-1", args.get("freeway.offset"));
        assertEquals("-1", args.get("freeway.port"),
            "--port -1 must consume -1 as the value, not treat --port as a boolean");
        assertEquals("-2.5", args.get("freeway.ratio"));
    }

    @Test
    void rejectsNullArgument() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> ConfigLoaderDefault.parseArgs(new String[]{"--ok=1", null}));
        assertTrue(ex.getMessage().contains("must not be null"),
            "got: " + ex.getMessage());
    }

    @Test
    void rejectsBareDoubleDash() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> ConfigLoaderDefault.parseArgs("--"));
        assertTrue(ex.getMessage().contains("--"),
            "the error must name the offending argument, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("must not be empty"),
            "got: " + ex.getMessage());
    }

    @Test
    void rejectsDoubleDashWithEmptyKey() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> ConfigLoaderDefault.parseArgs("--=x"));
        assertTrue(ex.getMessage().contains("--=x"),
            "the error must name the offending argument, got: " + ex.getMessage());
    }

    @Test
    void rejectsBarePropertyStyleDashD() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> ConfigLoaderDefault.parseArgs("-D"));
        assertTrue(ex.getMessage().contains("-D"),
            "the error must name the offending argument, got: " + ex.getMessage());
    }

    @Test
    void rejectsKeyContainingEqualsSign() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> ConfigLoaderDefault.parseArgs("-D=x"));
        assertTrue(ex.getMessage().contains("-D=x"),
            "the error must name the offending argument, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("must not contain '='"),
            "got: " + ex.getMessage());
    }

    @Test
    void equalsInValueIsAllowed() {
        // '=' inside the VALUE (after the first '=') is legitimate.
        Map<String, String> args = ConfigLoaderDefault.parseArgs(
            "--app.url=http://h/p?a=b&c=d");

        assertEquals("http://h/p?a=b&c=d", args.get("app.url"));
        assertEquals(1, args.size());
    }

    @Test
    void misspelledFlagBecomesHarmlessUnknownKey() {
        // A typo like --profle=dev must not silently activate the wrong
        // profile; it stays an unknown (harmless) key — frozen behavior.
        Map<String, String> args = ConfigLoaderDefault.parseArgs("--profle=dev");

        assertEquals("dev", args.get("freeway.profle"));
        assertFalse(args.containsKey("freeway.profile"));
        assertEquals(1, args.size());
    }

    @Test
    void positionalArgumentsAreWarnedAndIgnored() {
        // Positional args are not config — they must not crash the parse,
        // but they must no longer be silently swallowed without a trace.
        Map<String, String> args = ConfigLoaderDefault.parseArgs(
            "positional", "--ok=1", "another");

        assertEquals("1", args.get("freeway.ok"));
        assertEquals(1, args.size());
    }

    @Test
    void followingFlagTurnsKeyIntoBoolean() {
        Map<String, String> args = ConfigLoaderDefault.parseArgs(
            "--port", "--verbose"
        );

        assertEquals("true", args.get("freeway.port"),
            "a following flag must turn the key into a boolean");
        assertEquals("true", args.get("freeway.verbose"));
    }

    @Test
    void rejectsOversizedPropertiesResource() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            ConfigLoaderDefault.loadLayers(new OversizedPropertiesLoader()));

        assertTrue(ex.getMessage().contains("Unable to load application.properties"));
        assertTrue(ex.getCause().getMessage().contains("exceeds"));
    }

    @Test
    void rejectsOversizedJsonResource() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            ConfigLoaderDefault.loadLayers(new OversizedJsonLoader()));

        assertTrue(ex.getMessage().contains("Unable to load application.json"));
        // The read cap is the direct cause — no intermediate wrapper.
        assertTrue(ex.getCause() != null
                && ex.getCause().getMessage().contains("exceeds"));
    }

    @Test
    void emptyJsonResourceIsTreatedAsNoConfig() {
        ConfigLoaderDefault.BootConfigLayers layers =
            ConfigLoaderDefault.loadLayers(new FixedContentLoader("application.json", ""));

        assertTrue(layers.json().isEmpty(),
            "an empty application.json must be skipped, not crash the load");
        assertEquals("Freeway Boot", layers.properties().get("app.name"),
            "the properties layer must still load normally");
        assertEquals("Freeway Boot", merged(layers).get("app.name"));
    }

    @Test
    void blankJsonResourceIsTreatedAsNoConfig() {
        ConfigLoaderDefault.BootConfigLayers layers =
            ConfigLoaderDefault.loadLayers(new FixedContentLoader(
                "application.json", "  \n\t \r\n  "));

        assertTrue(layers.json().isEmpty(),
            "a whitespace-only application.json must be skipped, not crash the load");
        assertEquals("Freeway Boot", merged(layers).get("app.name"));
    }

    @Test
    void malformedJsonResourceStillFails() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            ConfigLoaderDefault.loadLayers(new FixedContentLoader(
                "application.json", "{\"bad\": ")));

        assertTrue(ex.getMessage().contains("Unable to load application.json"),
            "malformed JSON must still fail startup, got: " + ex.getMessage());
    }

    @Test
    void propertyStyleAndShortFlagsFollowPrefixingRules() {
        Map<String, String> args = ConfigLoaderDefault.parseArgs(
            "-Dverbose",
            "-p", "dev",
            "-Dserver.port=9090"
        );

        assertEquals("true", args.get("freeway.verbose"));
        assertEquals("dev", args.get("freeway.p"));
        assertEquals("9090", args.get("server.port"));
        assertEquals(3, args.size());
    }

    @Test
    void nonNumericNegativeArgumentIsNotConsumedAsValue() {
        Map<String, String> args = ConfigLoaderDefault.parseArgs(
            "--port", "-1x"
        );

        assertEquals("true", args.get("freeway.port"),
            "a non-numeric dash argument must turn the key into a boolean");
        assertEquals(1, args.size());
    }

    @Test
    void rejectsProfileNamesThatCanAddressOtherResources() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            ConfigLoaderDefault.loadLayers(ConfigLoaderDefaultTest.class.getClassLoader(), "--freeway.profile=../secret"));

        assertTrue(ex.getMessage().contains("Invalid freeway.profile value"));
    }

    @Test
    void envVarNamesConvertToFreewayConfigKeys() {
        // Default FREEWAY_ prefix maps into the freeway.* namespace.
        assertEquals("freeway.server.port",
            ConfigLoaderDefault.convertEnvKey("FREEWAY_SERVER_PORT", "FREEWAY_", true));
        assertEquals("freeway.log.level",
            ConfigLoaderDefault.convertEnvKey("FREEWAY_LOG_LEVEL", "FREEWAY_", true));
        assertEquals("freeway.db.url",
            ConfigLoaderDefault.convertEnvKey("FREEWAY_DB_URL", "FREEWAY_", true));
        // Underscores inside a key segment become dots.
        assertEquals("freeway.log.file.max.size",
            ConfigLoaderDefault.convertEnvKey("FREEWAY_LOG_FILE_MAX_SIZE", "FREEWAY_", true));
    }

    @Test
    void customEnvPrefixPassesThroughToAppNamespace() {
        // Replacing the prefix hands the whole env-to-config mapping to the
        // app: prefix stripped, `_` → `.`, no namespace inference. The app
        // can reach freeway.* keys too (APP_FREEWAY_HTTP_PORT →
        // freeway.http.port) and its own keys directly (APP_SERVER_PORT →
        // server.port).
        assertEquals("server.port",
            ConfigLoaderDefault.convertEnvKey("APP_SERVER_PORT", "APP_", false));
        assertEquals("name",
            ConfigLoaderDefault.convertEnvKey("APP_NAME", "APP_", false),
            "passthrough strips only the prefix — no namespace inference");
        assertEquals("freeway.http.port",
            ConfigLoaderDefault.convertEnvKey("APP_FREEWAY_HTTP_PORT", "APP_", false),
            "freeway.* keys remain reachable under a custom prefix");
        // The freeway.* namespace is bound to the FREEWAY_ prefix itself.
        assertEquals("freeway.server.port",
            ConfigLoaderDefault.convertEnvKey("APP_SERVER_PORT", "APP_", true),
            "namespace choice is explicit, not inferred from the prefix");
    }

    @Test
    void jvmSystemPropertyDrivesEnvPrefix() {
        // freeway.env.prefix is read from the JVM system property only
        // (-Dfreeway.env.prefix=APP_): a custom prefix then maps APP_* vars
        // and ignores FREEWAY_*.
        System.setProperty("freeway.env.prefix", "APP_");
        try {
            Map<String, String> mapped = ConfigLoaderDefault.loadEnvironment(
                Map.of("APP_X", "1", "FREEWAY_Y", "2", "OTHER", "3"));
            assertEquals(Map.of("x", "1"), mapped,
                "with -Dfreeway.env.prefix=APP_ only APP_* vars map, in passthrough namespace");
        } finally {
            System.clearProperty("freeway.env.prefix");
        }
    }

    @Test
    void envPrefixDefaultsToFreewayWithoutSystemProperty() {
        // No -Dfreeway.env.prefix: the mapping uses FREEWAY_ regardless of
        // anything else — APP_* vars are ignored, FREEWAY_* map into the
        // freeway.* namespace.
        Map<String, String> mapped = ConfigLoaderDefault.loadEnvironment(
            Map.of("APP_X", "1", "FREEWAY_Y", "2"));
        assertEquals(Map.of("freeway.y", "2"), mapped);
    }

    @Test
    void envPrefixInConfigFileDoesNotChangeEnvMapping() {
        // Regression (frozen behavior): freeway.env.prefix configured in a
        // config file is an ordinary config key — it must NOT change how the
        // environment layer maps vars (still FREEWAY_, JVM-property-driven).
        ClassLoader loader = new FixedContentLoader(
            "application.properties", "freeway.env.prefix=APP_\n");
        ConfigLoaderDefault.BootConfigLayers layers =
            ConfigLoaderDefault.loadLayers(loader);

        assertEquals("APP_", merged(layers).get("freeway.env.prefix"),
            "the file value is a normal config key");
        for (String key : layers.environment().keySet()) {
            assertTrue(key.startsWith("freeway."),
                "env mapping must still use the FREEWAY_ prefix, got: " + key);
        }
    }

    @Test
    void multipleProfilesParseInOrder() {
        ConfigLoaderDefault.BootConfigLayers layers = ConfigLoaderDefault.loadLayers(
            Thread.currentThread().getContextClassLoader(),
            "--freeway.profile=dev,prod"
        );
        assertEquals(List.of("dev", "prod"), layers.profiles(),
            "comma-separated profiles must be parsed in order");
        // dev profile resources exist in the test classpath; prod does not —
        // the missing profile must be skipped, not fail the load.
        assertTrue(merged(layers).containsKey("app.name"));
    }

    @Test
    void profileLayerFreewayProfileKeyIsStrippedFromMergedView() {
        // Regression: profiles are selected from the base layers only, but the
        // profile layer used to outrank base application.properties in
        // merged(). A profile file that (re)declares freeway.profile=prod then
        // made config().get("freeway.profile") report "prod" while profiles()
        // is ["dev"] — two authoritative views contradicting each other. The
        // merged view must strip the activation key from the profile layers;
        // the raw layer keeps it.
        ClassLoader loader = new ProfileForkLoader(
            "application.properties",
            "app.name=Freeway Boot\nfreeway.profile=dev\n",
            "application-dev.properties",
            "app.name=Dev Boot\nfreeway.profile=prod\n");

        // Activation via the base properties layer (no CLI override): without
        // the fix the profile layer's "prod" outranks base "dev" in merged().
        ConfigLoaderDefault.BootConfigLayers layers =
            ConfigLoaderDefault.loadLayers(loader);
        assertEquals(List.of("dev"), layers.profiles());
        assertEquals("dev", merged(layers).get("freeway.profile"),
            "merged() must report the base-layer activation value, not the profile layer's");
        assertEquals("Dev Boot", merged(layers).get("app.name"),
            "the profile file's other keys must still apply");
        assertEquals("prod", layers.profileProperties().get("freeway.profile"),
            "the raw profile layer keeps the key; only the merged view strips it");

        // Activation via CLI --profile=dev: config().profiles() and
        // config().get("freeway.profile") must agree.
        AppConfig config = new ConfigLoaderDefault().load(loader, "--profile=dev");
        assertEquals(List.of("dev"), config.profiles());
        assertEquals("dev", config.snapshot().get("freeway.profile"),
            "config().get(\"freeway.profile\") must agree with config().profiles()");
    }

    /**
     * The full layered view the tests assert on: file baseline + env + args —
     * the merge production code performs in {@code AppConfigDefault.reload()}.
     */
    private static Map<String, String> merged(ConfigLoaderDefault.BootConfigLayers layers) {
        Map<String, String> merged = new LinkedHashMap<>(layers.fileBaseline());
        merged.putAll(layers.environment());
        merged.putAll(layers.args());
        return merged;
    }

    private static final class OversizedPropertiesLoader extends ClassLoader {
        @Override
        public InputStream getResourceAsStream(String name) {
            if ("application.properties".equals(name)) {
                return new RepeatingInputStream(16L * 1024 * 1024 + 1);
            }
            return null;
        }
    }

    private static final class OversizedJsonLoader extends ClassLoader {
        @Override
        public InputStream getResourceAsStream(String name) {
            if ("application.json".equals(name)) {
                return new RepeatingInputStream(16L * 1024 * 1024 + 1);
            }
            return null;
        }
    }

    /** Serves fixed content for one named resource, delegating everything else. */
    private static final class FixedContentLoader extends ClassLoader {
        private final String resourceName;
        private final byte[] content;

        private FixedContentLoader(String resourceName, String content) {
            this.resourceName = resourceName;
            this.content = content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (resourceName.equals(name)) {
                return new ByteArrayInputStream(content);
            }
            return super.getResourceAsStream(name);
        }
    }

    /**
     * Serves fixed properties content for two named resources (e.g. base and
     * profile files), delegating everything else to the real classpath.
     */
    private static final class ProfileForkLoader extends ClassLoader {
        private final Map<String, byte[]> overrides = new LinkedHashMap<>();

        private ProfileForkLoader(
            String firstName, String firstContent,
            String secondName, String secondContent
        ) {
            overrides.put(firstName, firstContent.getBytes(StandardCharsets.UTF_8));
            overrides.put(secondName, secondContent.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            byte[] content = overrides.get(name);
            if (content != null) {
                return new ByteArrayInputStream(content);
            }
            return super.getResourceAsStream(name);
        }
    }

    private static final class RepeatingInputStream extends InputStream {
        private long remaining;

        private RepeatingInputStream(long size) {
            this.remaining = size;
        }

        @Override
        public int read() {
            if (remaining == 0) {
                return -1;
            }
            remaining--;
            return 'a';
        }

        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int read = (int) Math.min(len, remaining);
            Arrays.fill(bytes, off, off + read, (byte) 'a');
            remaining -= read;
            return read;
        }
    }
}
