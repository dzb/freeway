package com.jujin.freeway.boot.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The config surface stays consistent with its documentation and its samples:
 * a key a sample writes must have a row in {@code docs/freeway-config.md}, and
 * a key the document promises must be read by some module.
 *
 * <p>Both directions are the same failure — a name nobody reads. A sample that
 * writes {@code freeway.log.max-size} (the real key is
 * {@code freeway.log.file.max-size}) is silently ignored at startup, and a
 * documented key no module reads is worse: the deployment believes it changed
 * something. The keys are found the way the framework finds them — string
 * literals plus the {@code PREFIX + "suffix"} constants — so this test reads
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
    private static final Path DOC = DOCS == null ? null : DOCS.resolve("freeway-config.md");

    private static Path findRepoRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("docs/freeway-config.md"))
                    && Files.isDirectory(candidate.resolve("freeway-boot"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    /** {@code freeway.*} literals, including the ones built as {@code PREFIX + "…"}. */
    private static final Pattern KEY_LITERAL = Pattern.compile("\"(freeway\\.[a-z0-9]+(?:\\.[a-z0-9_-]+)*)\"");
    private static final Pattern PREFIX_CONSTANT = Pattern.compile("(\\w*PREFIX\\w*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern PREFIX_CONCAT = Pattern.compile("(\\w*PREFIX\\w*)\\s*\\+\\s*\"([^\"]+)\"");

    /** Documented rows whose key is a family, not a literal. */
    private static final List<String> FAMILIES = List.of(
        "freeway.log.file.<name>.", "<logger-name>.level");

    private static List<Path> samples() throws IOException {
        try (Stream<Path> files = Files.list(DOCS)) {
            return files.filter(p -> p.getFileName().toString().startsWith("application")
                    && p.getFileName().toString().endsWith(".sample"))
                .sorted()
                .toList();
        }
    }

    /** Every key a sample sets — parsed by the framework's own config reader. */
    private static Set<String> sampleKeys(Path sample) throws IOException {
        return new LinkedHashSet<>(ConfigFileReader.read(sample).keySet());
    }

    @Test
    void everySampleKeyHasADocumentedRow() throws IOException {
        assumeTrue(DOC != null && Files.isRegularFile(DOC), "docs/freeway-config.md not present");
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
                        collectConcatenatedKeys(source, keys);
                    }
                }
            }
        }
        return keys;
    }

    private static void collectConcatenatedKeys(String source, Set<String> keys) {
        Matcher constants = PREFIX_CONSTANT.matcher(source);
        Set<String> prefixes = new LinkedHashSet<>();
        while (constants.find()) {
            prefixes.add(constants.group(1) + '=' + constants.group(2));
        }
        Matcher concat = PREFIX_CONCAT.matcher(source);
        while (concat.find()) {
            for (String constant : prefixes) {
                int equals = constant.indexOf('=');
                if (constant.substring(0, equals).equals(concat.group(1))) {
                    keys.add(constant.substring(equals + 1) + concat.group(2));
                }
            }
        }
    }

}
