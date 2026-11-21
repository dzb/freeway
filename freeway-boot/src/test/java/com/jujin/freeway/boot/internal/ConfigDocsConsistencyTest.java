package com.jujin.freeway.boot.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The config surface stays consistent with its documentation and its samples:
 * a key a sample writes must have a row in {@code docs/configuration.md}, and
 * a key the document promises must be read by some module.
 *
 * <p>Both directions are the same failure — a name nobody reads. A sample that
 * writes {@code freeway.log.max-size} (the real key is
 * {@code freeway.log.file.max-size}) is silently ignored at startup, and a
 * documented key no module reads is worse: the deployment believes it changed
 * something. The keys are found the way the framework finds them — string
 * literals — keys are spelled in full, never composed — so this test reads
 * sources rather than a second hand-maintained list.
 *
 * <p>Runs only inside the repository (the docs and sibling modules are
 * siblings of this module's directory); a standalone module build skips it.
 */
class ConfigDocsConsistencyTest {

    /** The repository root, found by walking up — surefire's working directory
     *  is the module for a standalone build and the root in some reactor runs. */
    private static final Path REPO = findRepoRoot();
    private static final Path DOCS = REPO == null ? null : REPO.resolve("docs");
    private static final Path DOC = DOCS == null ? null : DOCS.resolve("configuration.md");

    private static Path findRepoRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("docs/configuration.md"))
                    && Files.isDirectory(candidate.resolve("freeway-boot"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    /** Every {@code freeway.*} key is spelled as a literal, so the scan is exactly that. */
    private static final Pattern KEY_LITERAL = Pattern.compile("\"(freeway\\.[a-z0-9]+(?:\\.[a-z0-9_-]+)*)\"");

    /** Documented rows whose key is a family, not a literal. */
    private static final List<String> FAMILIES = List.of(
        "freeway.log.file.<name>.", "<logger-name>.level");

    private static List<Path> samples() throws IOException {
        try (Stream<Path> files = Files.list(DOCS.resolve("samples"))) {
            return files.filter(p -> p.getFileName().toString().startsWith("application")
                    && p.getFileName().toString().endsWith(".sample"))
                .sorted()
                .toList();
        }
    }

    /**
     * Every key a sample sets — parsed by the framework's own config reader.
     * The {@code .sample} suffix is documentation only, so dispatch on the
     * real extension: a TOML sample parses as TOML, a JSON sample as JSON.
     */
    private static Set<String> sampleKeys(Path sample) throws IOException {
        String name = sample.getFileName().toString();
        String parseName = name.endsWith(".sample")
            ? name.substring(0, name.length() - ".sample".length())
            : name;
        try (java.io.InputStream in = Files.newInputStream(sample)) {
            return new LinkedHashSet<>(ConfigFilePlan.Reader.read(parseName, in).keySet());
        }
    }

    @Test
    void everySampleKeyHasADocumentedRow() throws IOException {
        assumeTrue(DOC != null && Files.isRegularFile(DOC), "docs/configuration.md not present");
        String doc = Files.readString(DOC);
        List<String> unknown = new ArrayList<>();
        for (Path sample : samples()) {
            for (String key : sampleKeys(sample)) {
                if (!key.startsWith("freeway.")) {
                    continue; // process-level keys (app.name, slf4j.provider) have their own rows
                }
                if (!doc.contains('`' + key + '`') && !isFamily(key)) {
                    unknown.add(sample.getFileName() + ": " + key);
                }
            }
        }
        assertEquals(List.of(), unknown,
            "a sample key without a documented row is a name nothing reads");
    }

    /** A commented key line in the TOML sample: {@code # key = ...}. */
    private static final Pattern TOML_SAMPLE_KEY =
        Pattern.compile("^#\\s+(\"[^\"]+\"|[A-Za-z][A-Za-z0-9_.-]*)\\s*=.*");

    /**
     * The TOML sample is a reference whose keys are comments by design, so the
     * plain sample scan sees an empty document. Activate every key line and
     * parse it: the result must be a valid document (catches TOML-subset
     * mistakes, duplicates and broken quoting) whose keys are all documented.
     */
    @Test
    void theTomlSampleActivatesToAValidDocumentWithDocumentedKeys() throws IOException {
        assumeTrue(DOC != null, "repository layout not present");
        Path sample = DOCS.resolve("samples").resolve("application.toml.sample");
        assumeTrue(Files.isRegularFile(sample), "TOML sample not present");
        String source = Files.readString(sample);

        StringBuilder activated = new StringBuilder();
        int uncommented = 0;
        for (String line : source.split("\n", -1)) {
            if (TOML_SAMPLE_KEY.matcher(line.strip()).matches()) {
                activated.append(line.replaceFirst("^\\s*#\\s*", "")).append('\n');
                uncommented++;
            } else {
                activated.append(line).append('\n');
            }
        }
        assertTrue(uncommented > 100,
            "the sample must list its keys, found " + uncommented);

        Map<String, String> keys;
        try (var in = new ByteArrayInputStream(
                activated.toString().getBytes(StandardCharsets.UTF_8))) {
            keys = ConfigFilePlan.Reader.read("application.toml", in);
        }
        assertFalse(keys.isEmpty(), "the activated sample must produce keys");

        String doc = Files.readString(DOC);
        List<String> unknown = keys.keySet().stream()
            .filter(key -> key.startsWith("freeway."))
            .filter(key -> !doc.contains('`' + key + '`') && !isFamily(key))
            .toList();
        assertEquals(List.of(), unknown,
            "a sample key without a documented row is a name nothing reads");
    }

    @Test
    void everyDocumentedKeyIsReadBySomeModule() throws IOException {
        assumeTrue(DOC != null && Files.isRegularFile(DOC), "repository layout not present");
        Set<String> known = sourceKeyLiterals();
        List<String> orphans = new ArrayList<>();
        for (String key : documentedKeys(Files.readString(DOC))) {
            if (!known.contains(key)) {
                orphans.add(key);
            }
        }
        assertEquals(List.of(), orphans,
            "a documented key no module reads promises a knob that does nothing");
    }

    /** Keys with a literal row in the document, families and process-level rows excluded. */
    private static Set<String> documentedKeys(String doc) {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : doc.split("\n")) {
            if (!line.startsWith("|")) {
                continue;
            }
            List<String> cells = List.of(line.strip().split("\\|"));
            if (cells.size() < 3) {
                continue;
            }
            String first = cells.get(1).strip();
            if (first.length() > 2 && first.startsWith("`") && first.endsWith("`")) {
                String key = first.substring(1, first.length() - 1);
                if (key.startsWith("freeway.") && !isFamily(key)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    private static boolean isFamily(String key) {
        return FAMILIES.stream().anyMatch(key::startsWith) || key.contains("<");
    }

    /**
     * The framework's config key literals as they appear in source: a direct
     * string, or a {@code *PREFIX} constant concatenated with its suffix.
     */
    private static Set<String> sourceKeyLiterals() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        try (Stream<Path> modules = Files.list(REPO)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path sources = module.resolve("src/main/java");
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(sources)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        String source = Files.readString(file);
                        Matcher literal = KEY_LITERAL.matcher(source);
                        while (literal.find()) {
                            keys.add(literal.group(1));
                        }
                    }
                }
            }
        }
        return keys;
    }
}
