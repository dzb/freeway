package com.jujin.freeway.boot.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Every public framework exception appears in {@code docs/reference/failures.md}
 * and every catalog row names a real exception: the taxonomy is the contract,
 * so an uncovered exception is a documentation hole rather than a detail.
 */
class FailureCatalogTest {

    private static final Path REPO = findRepoRoot();
    private static final Path CATALOG = REPO == null ? null
        : REPO.resolve("docs/reference/failures.md");
    private static final Pattern DECLARATION = Pattern.compile(
        "public\\s+(?:final\\s+|sealed\\s+|abstract\\s+)?class\\s+([A-Za-z0-9_]+Exception)\\b");
    private static final Pattern ROW = Pattern.compile(
        "^\\|\\s*`([A-Za-z0-9_]+Exception)`\\s*\\|");

    private static Path findRepoRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("docs/reference/failures.md"))
                    && Files.isDirectory(candidate.resolve("freeway-boot"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    @Test
    void everyPublicExceptionIsInTheCatalog() throws IOException {
        assumeTrue(REPO != null && CATALOG != null, "repository layout not present");

        Set<String> documented = new LinkedHashSet<>();
        for (String line : Files.readAllLines(CATALOG)) {
            Matcher row = ROW.matcher(line.strip());
            if (row.find()) {
                documented.add(row.group(1));
            }
        }
        assertFalse(documented.isEmpty(), "the failure catalog must list the exceptions");

        Set<String> actual = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(REPO)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList()) {
                Matcher matcher = DECLARATION.matcher(Files.readString(file));
                while (matcher.find()) {
                    actual.add(matcher.group(1));
                }
            }
        }

        assertEquals(documented, actual,
            "every public exception and every catalog row must agree; update "
                + "docs/reference/failures.md when the taxonomy changes");
    }
}
