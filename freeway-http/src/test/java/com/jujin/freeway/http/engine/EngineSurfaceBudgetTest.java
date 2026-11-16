package com.jujin.freeway.http.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * The engine packages have no stability promise, but their public types are
 * still reachable. This budget lists the cross-package assembly seams and
 * fails on any new public type, so a package-private implementation detail
 * cannot leak into the public surface by accident.
 */
class EngineSurfaceBudgetTest {

    private static final Path REPO = findRepoRoot();
    private static final Path BUDGET = REPO == null ? null : REPO.resolve("api/http-engine-surface.txt");
    private static final Path ENGINE = REPO == null ? null
        : REPO.resolve("freeway-http/src/main/java/com/jujin/freeway/http/engine");
    private static final Pattern PUBLIC_TYPE = Pattern.compile(
        "(?m)^public\\s+(?:final\\s+|sealed\\s+|abstract\\s+)?"
            + "(?:class|interface|record|enum)\\s+([A-Za-z0-9_]+)");

    private static Path findRepoRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("api/http-engine-surface.txt"))
                    && Files.isDirectory(candidate.resolve("freeway-http"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    @Test
    void publicTypesInEngineMatchTheBudget() throws IOException {
        assumeTrue(REPO != null && BUDGET != null && ENGINE != null,
            "repository layout not present");

        Set<String> expected = new LinkedHashSet<>();
        for (String line : Files.readAllLines(BUDGET)) {
            String entry = line.strip();
            if (!entry.isEmpty() && !entry.startsWith("#")) {
                expected.add(entry);
            }
        }

        Set<String> actual = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(ENGINE)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList()) {
                String relative = REPO.relativize(file).toString();
                Matcher matcher = PUBLIC_TYPE.matcher(Files.readString(file));
                while (matcher.find()) {
                    actual.add(relative + "#" + matcher.group(1));
                }
            }
        }

        assertEquals(expected, actual,
            "public types in the HTTP engine are a surface budget: update "
                + "api/http-engine-surface.txt deliberately when widening or narrowing one");
        assertTrue(actual.contains(
            "freeway-http/src/main/java/com/jujin/freeway/http/engine/HttpResponseWriter.java#HttpResponseWriter"),
            "the budget must include the h1/h2 writer seam");
    }
}
