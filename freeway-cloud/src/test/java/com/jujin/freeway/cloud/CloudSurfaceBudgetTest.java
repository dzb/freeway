package com.jujin.freeway.cloud;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The public feature surface is deliberate: moving an implementation into
 * {@code internal} or adding a new public type must update the budget in the
 * same change. Internal-package types are covered by
 * {@code api/internal-surface.txt} instead.
 */
class CloudSurfaceBudgetTest {

    private static final Path REPO = findRepoRoot();
    private static final Path BUDGET = REPO == null ? null : REPO.resolve("api/cloud-surface.txt");
    private static final Pattern PUBLIC_TOP_LEVEL = Pattern.compile(
        "(?m)^public\\s+(?:final\\s+|sealed\\s+|abstract\\s+)?"
            + "(?:class|interface|record|enum|@interface)\\s+([A-Za-z0-9_]+)");

    @Test
    void publicTopLevelTypesMatchTheBudget() throws IOException {
        assumeTrue(REPO != null && BUDGET != null, "repository layout not present");

        Set<String> expected = new LinkedHashSet<>();
        for (String line : Files.readAllLines(BUDGET)) {
            String entry = line.strip();
            if (!entry.isEmpty() && !entry.startsWith("#")) {
                expected.add(entry);
            }
        }

        Set<String> actual = new LinkedHashSet<>();
        Path cloudRoot = REPO.resolve("freeway-cloud/src/main/java/com/jujin/freeway/cloud");
        try (Stream<Path> walk = Files.walk(cloudRoot)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("package-info.java"))
                    .filter(p -> !p.toString().contains("/internal/"))
                    .toList()) {
                String relative = REPO.relativize(file).toString().replace('\\', '/');
                Matcher matcher = PUBLIC_TOP_LEVEL.matcher(Files.readString(file));
                while (matcher.find()) {
                    actual.add(relative + "#" + matcher.group(1));
                }
            }
        }

        assertEquals(expected, actual,
            "public top-level types in freeway-cloud are an API budget: update "
                + "api/cloud-surface.txt deliberately when adding, removing or "
                + "moving one");
    }

    private static Path findRepoRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("api/cloud-surface.txt"))
                    && Files.isDirectory(candidate.resolve("freeway-cloud"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }
}
