package com.jujin.freeway.boot.internal;
import java.util.Arrays;

import com.jujin.freeway.boot.AppConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderImplTest {

    @TempDir
    Path dir;

    @Test
    void loadsEverySourceSeparatelyAndFoldsTheFilesBaseline() {
        ConfigSources sources = ConfigLoaderImpl.loadLayers(
            Thread.currentThread().getContextClassLoader(),
            "--freeway.profile=dev",
            "--app.name=Overridden",
            "--server.port=7070"
        );

        assertEquals(List.of("dev"), sources.profiles());
        assertEquals("Overridden", sources.cli().get("app.name"));
        assertEquals("7070", sources.cli().get("server.port"));

        // The file baseline folds the classpath layers: base properties →
        // base json → profile properties → profile json, later winning.
        assertEquals("Dev Boot", sources.files().get("app.name"),
            "the dev profile properties override the base application.properties");
        assertEquals("9191", sources.files().get("server.port"),
            "the dev profile properties override the base application.properties");
        assertEquals("Profiled IoC container", sources.files().get("app.description"),
            "the dev profile json overrides the base application.json");
        assertEquals("dev.localhost", sources.files().get("server.host"));
        assertEquals("1.0.0", sources.files().get("app.version"),
            "a base json key with no profile counterpart survives the fold");

        // CLI outranks every file layer in the merged picture.
        assertEquals("Overridden", merged(sources).get("app.name"));
        assertEquals("7070", merged(sources).get("server.port"));
    }

    @Test
    void autoPrefixesSimpleCliKeysWithFreewayNamespace() {
        Map<String, String> args = ConfigLoaderImpl.parseArgs(
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
        Map<String, String> args = ConfigLoaderImpl.parseArgs(
            "--freeway.profile=dev"
        );

        assertEquals("dev", args.get("freeway.profile"));
        assertEquals(1, args.size());
    }

    @Test
    void parsesNegativeNumberValues() {
        Map<String, String> args = ConfigLoaderImpl.parseArgs(
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
            () -> ConfigLoaderImpl.parseArgs(new String[]{"--ok=1", null}));
        assertTrue(ex.getMessage().contains("must not be null"),
            "got: " + ex.getMessage());
    }

    @Test
    void rejectsBareDoubleDash() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> ConfigLoaderImpl.parseArgs("--"));
        assertTrue(ex.getMessage().contains("--"),
            "the error must name the offending argument, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("must not be empty"),
            "got: " + ex.getMessage());
    }

    @Test
    void rejectsDoubleDashWithEmptyKey() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> ConfigLoaderImpl.parseArgs("--=x"));
        assertTrue(ex.getMessage().contains("--=x"),
            "the error must name the offending argument, got: " + ex.getMessage());
    }

    @Test
    void rejectsBarePropertyStyleDashD() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> ConfigLoaderImpl.parseArgs("-D"));
        assertTrue(ex.getMessage().contains("-D"),
            "the error must name the offending argument, got: " + ex.getMessage());
    }

    @Test
    void rejectsKeyContainingEqualsSign() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> ConfigLoaderImpl.parseArgs("-D=x"));
        assertTrue(ex.getMessage().contains("-D=x"),
            "the error must name the offending argument, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("must not contain '='"),
            "got: " + ex.getMessage());
    }

    @Test
    void equalsInValueIsAllowed() {
        // '=' inside the VALUE (after the first '=') is legitimate.
        Map<String, String> args = ConfigLoaderImpl.parseArgs(
            "--app.url=http://h/p?a=b&c=d");

        assertEquals("http://h/p?a=b&c=d", args.get("app.url"));
        assertEquals(1, args.size());
    }

    @Test
    void misspelledFlagBecomesHarmlessUnknownKey() {
        // A typo like --profle=dev must not silently activate the wrong
        // profile; it stays an unknown (harmless) key — frozen behavior.
        Map<String, String> args = ConfigLoaderImpl.parseArgs("--profle=dev");

        assertEquals("dev", args.get("freeway.profle"));
        assertFalse(args.containsKey("freeway.profile"));
        assertEquals(1, args.size());
    }

    @Test
    void positionalArgumentsAreWarnedAndIgnored() {
        // Positional args are not config — they must not crash the parse,
        // but they must no longer be silently swallowed without a trace.
        Map<String, String> args = ConfigLoaderImpl.parseArgs(
            "positional", "--ok=1", "another");

        assertEquals("1", args.get("freeway.ok"));
        assertEquals(1, args.size());
    }

    @Test
    void followingFlagTurnsKeyIntoBoolean() {
        Map<String, String> args = ConfigLoaderImpl.parseArgs(
            "--port", "--verbose"
        );

        assertEquals("true", args.get("freeway.port"),
            "a following flag must turn the key into a boolean");
        assertEquals("true", args.get("freeway.verbose"));
    }

    @Test
    void rejectsOversizedPropertiesResource() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            ConfigLoaderImpl.loadLayers(new OversizedPropertiesLoader()));

        assertTrue(ex.getMessage().contains("Unable to load application.properties"));
        assertTrue(ex.getCause().getMessage().contains("exceeds"));
    }

    @Test
    void rejectsOversizedJsonResource() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            ConfigLoaderImpl.loadLayers(new OversizedJsonLoader()));

        assertTrue(ex.getMessage().contains("Unable to load application.json"));
        // The read cap is the direct cause — no intermediate wrapper.
        assertTrue(ex.getCause() != null
                && ex.getCause().getMessage().contains("exceeds"));
    }

    @Test
    void emptyJsonResourceIsTreatedAsNoConfig() {
        ConfigSources sources =
            ConfigLoaderImpl.loadLayers(new MultiContentLoader("application.json", ""));

        assertEquals("Freeway Boot", sources.files().get("app.name"),
            "the properties layer must still load normally");
        assertEquals("Freeway Boot", merged(sources).get("app.name"),
            "an empty application.json must be skipped, not crash the load");
    }

    @Test
    void blankJsonResourceIsTreatedAsNoConfig() {
        ConfigSources sources =
            ConfigLoaderImpl.loadLayers(new MultiContentLoader(
                "application.json", "  \n\t \r\n  "));

        assertEquals("Freeway Boot", merged(sources).get("app.name"),
            "a whitespace-only application.json must be skipped, not crash the load");
    }

    @Test
    void malformedJsonResourceStillFails() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            ConfigLoaderImpl.loadLayers(new MultiContentLoader(
                "application.json", "{\"bad\": ")));

        assertTrue(ex.getMessage().contains("Unable to load application.json"),
            "malformed JSON must still fail startup, got: " + ex.getMessage());
    }

    @Test
    void propertyStyleAndShortFlagsFollowPrefixingRules() {
        Map<String, String> args = ConfigLoaderImpl.parseArgs(
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
        Map<String, String> args = ConfigLoaderImpl.parseArgs(
            "--port", "-1x"
        );

        assertEquals("true", args.get("freeway.port"),
            "a non-numeric dash argument must turn the key into a boolean");
        assertEquals(1, args.size());
    }

    @Test
    void rejectsProfileNamesThatCanAddressOtherResources() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            ConfigLoaderImpl.loadLayers(ConfigLoaderImplTest.class.getClassLoader(), "--freeway.profile=../secret"));

        assertTrue(ex.getMessage().contains("Invalid freeway.profile value"));
    }

    @Test
    void envVarNamesConvertToFreewayConfigKeys() {
        // Default FREEWAY_ prefix maps into the freeway.* namespace.
        assertEquals("freeway.server.port",
            ConfigLoaderImpl.convertEnvKey("FREEWAY_SERVER_PORT", "FREEWAY_", true));
        assertEquals("freeway.log.level",
            ConfigLoaderImpl.convertEnvKey("FREEWAY_LOG_LEVEL", "FREEWAY_", true));
        assertEquals("freeway.db.url",
            ConfigLoaderImpl.convertEnvKey("FREEWAY_DB_URL", "FREEWAY_", true));
        // Underscores inside a key segment become dots.
        assertEquals("freeway.log.file.max.size",
            ConfigLoaderImpl.convertEnvKey("FREEWAY_LOG_FILE_MAX_SIZE", "FREEWAY_", true));
    }

    @Test
    void customEnvPrefixPassesThroughToAppNamespace() {
        // Replacing the prefix hands the whole env-to-config mapping to the
        // app: prefix stripped, `_` → `.`, no namespace inference. The app
        // can reach freeway.* keys too (APP_FREEWAY_HTTP_PORT →
        // freeway.http.port) and its own keys directly (APP_SERVER_PORT →
        // server.port).
        assertEquals("server.port",
            ConfigLoaderImpl.convertEnvKey("APP_SERVER_PORT", "APP_", false));
        assertEquals("name",
            ConfigLoaderImpl.convertEnvKey("APP_NAME", "APP_", false),
            "passthrough strips only the prefix — no namespace inference");
        assertEquals("freeway.http.port",
            ConfigLoaderImpl.convertEnvKey("APP_FREEWAY_HTTP_PORT", "APP_", false),
            "freeway.* keys remain reachable under a custom prefix");
        // The freeway.* namespace is bound to the FREEWAY_ prefix itself.
        assertEquals("freeway.server.port",
            ConfigLoaderImpl.convertEnvKey("APP_SERVER_PORT", "APP_", true),
            "namespace choice is explicit, not inferred from the prefix");
    }

    @Test
    void hyphenatedKeysKeepTheirHyphenInTheEnvMapping() {
        // A key's dots become underscores in the env spelling; every other
        // character — a hyphen above all — is part of the key name and is
        // carried through verbatim. Folding '-' to '.' would merge two
        // distinct keys ('key-store-password' vs 'key.store.password').
        assertEquals("freeway.http.ssl.key-store-password",
            ConfigLoaderImpl.convertEnvKey(
                "FREEWAY_HTTP_SSL_KEY-STORE-PASSWORD", "FREEWAY_", true));
        assertEquals("freeway.log.file.max-size",
            ConfigLoaderImpl.convertEnvKey("FREEWAY_LOG_FILE_MAX-SIZE", "FREEWAY_", true),
            "the dashed key is reachable only through its exact env spelling");
        // The underscore spelling can therefore only produce dot segments: it
        // addresses a DIFFERENT key, it does not alias the hyphenated one.
        assertEquals("freeway.http.ssl.key.store.password",
            ConfigLoaderImpl.convertEnvKey(
                "FREEWAY_HTTP_SSL_KEY_STORE_PASSWORD", "FREEWAY_", true));
    }

    @Test
    void jvmSystemPropertyDrivesEnvPrefix() {
        // freeway.env.prefix is read from the JVM system property only
        // (-Dfreeway.env.prefix=APP_): a custom prefix then maps APP_* vars
        // and ignores FREEWAY_*.
        System.setProperty("freeway.env.prefix", "APP_");
        try {
            Map<String, String> mapped = ConfigLoaderImpl.loadEnvironment(
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
        Map<String, String> mapped = ConfigLoaderImpl.loadEnvironment(
            Map.of("APP_X", "1", "FREEWAY_Y", "2"));
        assertEquals(Map.of("freeway.y", "2"), mapped);
    }

    @Test
    void envPrefixInConfigFileDoesNotChangeEnvMapping() {
        // Regression (frozen behavior): freeway.env.prefix configured in a
        // config file is an ordinary config key — it must NOT change how the
        // environment layer maps vars (still FREEWAY_, JVM-property-driven).
        ClassLoader loader = new MultiContentLoader(
            "application.properties", "freeway.env.prefix=APP_\n");
        ConfigSources sources =
            ConfigLoaderImpl.loadLayers(loader);

        assertEquals("APP_", merged(sources).get("freeway.env.prefix"),
            "the file value is a normal config key");
        for (String key : sources.environment().keySet()) {
            assertTrue(key.startsWith("freeway."),
                "env mapping must still use the FREEWAY_ prefix, got: " + key);
        }
    }

    @Test
    void multipleProfilesParseInOrder() {
        ConfigSources sources = ConfigLoaderImpl.loadLayers(
            Thread.currentThread().getContextClassLoader(),
            "--freeway.profile=dev,prod"
        );
        assertEquals(List.of("dev", "prod"), sources.profiles(),
            "comma-separated profiles must be parsed in order");
        // dev profile resources exist in the test classpath; prod does not —
        // the missing profile must be skipped, not fail the load.
        assertTrue(merged(sources).containsKey("app.name"));
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
        ClassLoader loader = new MultiContentLoader(
            "application.properties",
            "app.name=Freeway Boot\nfreeway.profile=dev\n",
            "application-dev.properties",
            "app.name=Dev Boot\nfreeway.profile=prod\n");

        // Activation via the base properties layer (no CLI override): without
        // the fix the profile layer's "prod" outranks base "dev" in merged().
        ConfigSources sources =
            ConfigLoaderImpl.loadLayers(loader);
        assertEquals(List.of("dev"), sources.profiles());
        assertEquals("dev", merged(sources).get("freeway.profile"),
            "merged() must report the base-layer activation value, not the profile layer's");
        assertEquals("Dev Boot", merged(sources).get("app.name"),
            "the profile file's other keys must still apply");
        assertEquals("dev", sources.files().get("freeway.profile"),
            "the activation key is stripped from the profile files at load time — "
                + "only the base layer's value surfaces");

        // Activation via CLI --profile=dev: profiles() and the resolved
        // freeway.profile value must agree.
        AppConfig config = ConfigLoaderImpl.load(loader, "--profile=dev");
        assertEquals(List.of("dev"), config.profiles());
        assertEquals("dev", value(config, "freeway.profile"),
            "the resolved freeway.profile must agree with config().profiles()");
    }

    @Test
    void jsonOutranksPropertiesInTheClasspathBaseline() {
        ConfigSources sources = ConfigLoaderImpl.loadLayers(new MultiContentLoader(
            "application.properties", "app.name=from-properties\n",
            "application.json", "{\"app.name\": \"from-json\"}"));

        assertEquals("from-json", sources.files().get("app.name"),
            "application.json outranks application.properties");
    }

    @Test
    void profileJsonOutranksAnotherProfilesProperties() {
        // The profile band merges as two format passes — every profile's
        // properties, then every profile's json — so the file format outranks
        // the profile order: application-dev.json beats
        // application-prod.properties. Pinned because the rule is otherwise
        // invisible (and was undocumented).
        ConfigSources sources = ConfigLoaderImpl.loadLayers(new MultiContentLoader(
            "application.properties", "freeway.profile=dev,prod\n",
            "application-dev.json", "{\"app.name\": \"from-dev-json\"}",
            "application-prod.properties", "app.name=from-prod-properties\n"));

        assertEquals(List.of("dev", "prod"), sources.profiles());
        assertEquals("from-dev-json", sources.files().get("app.name"),
            "json outranks properties across profiles, not the later profile");
    }

    @Test
    void filesystemOverrideFilesMirrorTheClasspathBandOrder() {
        // The filesystem half of the same rule: the base files, then every
        // profile's properties, then every profile's json — so the format
        // outranks the profile order on both sides of the baseline — and
        // finally the freeway.config.file extras, in the declared order.
        System.setProperty("freeway.config.file",
            " extra.properties , second.properties ");
        try {
            List<String> names = ConfigLoaderImpl
                .overrideFiles(List.of("dev", "prod"))
                .stream()
                .map(path -> path.getFileName().toString())
                .toList();

            assertEquals(List.of(
                "application.properties", "application.json",
                "application-dev.properties", "application-prod.properties",
                "application-dev.json", "application-prod.json",
                "extra.properties", "second.properties"), names);
        } finally {
            System.clearProperty("freeway.config.file");
        }
    }

    @Test
    void bootstrapOnlyKeyOnTheCommandLineLosesAndIsNamedInAWarning() throws IOException {
        // A bootstrap key declares where a source comes from, so it is read
        // before the CLI exists: the -D channel names the file that is loaded,
        // the CLI spelling names one that is not — and it says so.
        Path declared = Files.writeString(
            dir.resolve("declared.properties"), "bootstrap.probe=from-bootstrap\n");
        Path ignored = dir.resolve("ignored.properties");
        System.setProperty("freeway.config.file", declared.toString());
        AppConfig[] loaded = new AppConfig[1];
        List<String> warnings;
        try {
            warnings = captureWarnings(() -> loaded[0] = ConfigLoaderImpl.load(
                ConfigLoaderImplTest.class.getClassLoader(),
                "--freeway.config.file=" + ignored));
        } finally {
            System.clearProperty("freeway.config.file");
        }
        try {
            assertEquals("from-bootstrap", value(loaded[0], "bootstrap.probe"),
                "the bootstrap channel decides; the CLI spelling is not a channel");
        } finally {
            loaded[0].close();
        }

        assertTrue(
            warnings.stream().anyMatch(warning ->
                warning.contains("freeway.config.file")
                    && warning.contains("the command-line arguments")),
            "a bootstrap key on the command line must be named in a WARN, got: "
                + warnings);
    }

    @Test
    void bootstrapOnlyKeyInAnExtraFileIsNamedInAWarning() throws IOException {
        // The freeway.config.file extras are the other file channel that never
        // participates in bootstrap resolution — the file is named in the
        // warning, so the operator knows which file to fix.
        Path extra = Files.writeString(
            dir.resolve("extra.properties"), "freeway.env.prefix=APP_\n");
        System.setProperty("freeway.config.file", extra.toString());
        List<String> warnings;
        try {
            warnings = captureWarnings(() ->
                ConfigLoaderImpl.load(ConfigLoaderImplTest.class.getClassLoader())
                    .close());
        } finally {
            System.clearProperty("freeway.config.file");
        }

        assertTrue(
            warnings.stream().anyMatch(warning ->
                warning.contains("freeway.env.prefix")
                    && warning.contains("extra.properties")),
            "an extra config file declaring a bootstrap key must be named in a "
                + "WARN, got: " + warnings);
    }

    /**
     * The full layered view the tests assert on: file baseline + env + cli —
     * the same overlay the symbol chain applies tier by tier.
     */
    private static Map<String, String> merged(ConfigSources sources) {
        return ConfigMaps.overlay(
            List.of(sources.files(), sources.environment(), sources.cli()));
    }

    /** Resolves a key through the config's own providers, in declared order. */
    private static String value(AppConfig config, String key) {
        for (SymbolProvider provider : config.providers()) {
            String value = provider.lookup(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * The WARN messages logged under the loader's logger while {@code action}
     * runs — the only observable effect of a bootstrap key appearing in a
     * channel it cannot come from. The framework's SLF4J provider is
     * JUL-backed, so the messages arrive as JUL records.
     */
    private static List<String> captureWarnings(Runnable action) {
        Logger logger = Logger.getLogger(ConfigLoaderImpl.class.getName());
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()
                        && record.getMessage() != null) {
                    messages.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        boolean parentHandlers = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(parentHandlers);
        }
        return messages;
    }

    private static final class OversizedPropertiesLoader extends ClassLoader {
        @Override
        public InputStream getResourceAsStream(String name) {
            if ("application.properties".equals(name)) {
                return new RepeatingInputStream(ConfigFileReader.MAX_BYTES + 1);
            }
            return null;
        }
    }

    private static final class OversizedJsonLoader extends ClassLoader {
        @Override
        public InputStream getResourceAsStream(String name) {
            if ("application.json".equals(name)) {
                return new RepeatingInputStream(ConfigFileReader.MAX_BYTES + 1);
            }
            return null;
        }
    }

    /** Serves fixed content for one named resource, delegating everything else. */
    /**
     * Serves fixed content for named resources, delegating everything else to
     * the real classpath. Content is given as name, text pairs.
     */
    private static final class MultiContentLoader extends ClassLoader {
        private final Map<String, byte[]> overrides = new LinkedHashMap<>();

        private MultiContentLoader(String... nameAndContent) {
            for (int i = 0; i < nameAndContent.length; i += 2) {
                overrides.put(
                    nameAndContent[i],
                    nameAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
            }
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
