package com.jujin.freeway.boot.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code internal} is a stability promise, not a visibility gate: a public
 * type there is still reachable by every consumer. This budget makes each one
 * an explicit decision — adding or removing a public type under
 * {@code internal} fails until {@code api/internal-surface.txt} is updated.
 *
 * <p>The list is the whole answer, not a generated cache: the test compares
 * both directions, so a stale entry fails as loudly as an undocumented one.
 */
class InternalSurfaceBudgetTest {

    private static final Path REPO = findRepoRoot();
    private static final Path BUDGET = REPO == null ? null : REPO.resolve("api/internal-surface.txt");
    private static final Pattern PUBLIC_TYPE = Pattern.compile(
        "(?m)^\\s*public\\s+(?:final\\s+|sealed\\s+|abstract\\s+)?"
            + "(?:class|interface|record|enum)\\s+([A-Za-z0-9_]+)");

    private static Path findRepoRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("api/internal-surface.txt"))
                    && Files.isDirectory(candidate.resolve("freeway-boot"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    @Test
    void publicTypesUnderInternalMatchTheBudget() throws IOException {
        assumeTrue(REPO != null && BUDGET != null, "repository layout not present");

        Set<String> expected = new LinkedHashSet<>();
        for (String line : Files.readAllLines(BUDGET)) {
            String entry = line.strip();
            if (!entry.isEmpty() && !entry.startsWith("#")) {
                expected.add(entry);
            }
        }

        Set<String> actual = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(REPO)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> p.toString().contains("/internal/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList()) {
                String relative = REPO.relativize(file).toString();
                Matcher matcher = PUBLIC_TYPE.matcher(Files.readString(file));
                while (matcher.find()) {
                    actual.add(relative + "#" + matcher.group(1));
                }
            }
        }

        assertEquals(expected, actual,
            "public types under internal are an API budget: update "
                + "api/internal-surface.txt deliberately when adding or removing one");
    }
}
