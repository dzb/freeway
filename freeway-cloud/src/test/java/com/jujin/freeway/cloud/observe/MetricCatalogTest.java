package com.jujin.freeway.cloud.observe;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Metric names are a public contract: the catalog in
 * {@code docs/reference/metrics.md} and the literals in framework code must
 * agree, obey the naming grammar, and not collide once rendered for
 * Prometheus.
 */
class MetricCatalogTest {

    private static final Path REPO = findRepoRoot();
    private static final Path CATALOG = REPO == null ? null
        : REPO.resolve("docs/reference/metrics.md");
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z0-9]+)+$");
    private static final Pattern ROW = Pattern.compile(
        "^\\|\\s*`([^`]+)`\\s*\\|\\s*(counter|timer|gauge|histogram)\\s*\\|.*\\|$");
    private static final Pattern LITERAL = Pattern.compile(
        "metrics\\.(counter|timer|gauge|histogram)\\(\"([^\"]+)\"");

    private static Path findRepoRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("docs/reference/metrics.md"))
                    && Files.isDirectory(candidate.resolve("freeway-cloud"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    @Test
    void documentedNamesMatchTheLiteralsAndObeyTheGrammar() throws IOException {
        assumeTrue(REPO != null && CATALOG != null, "repository layout not present");

        Map<String, String> documented = new LinkedHashMap<>();
        for (String line : Files.readAllLines(CATALOG)) {
            Matcher row = ROW.matcher(line.strip());
            if (row.matches()) {
                String name = row.group(1);
                String type = row.group(2);
                assertFalse(documented.containsKey(name),
                    "duplicate catalog row: " + name);
                documented.put(name, type);
            }
        }
        assertFalse(documented.isEmpty(), "the metrics catalog must list the built-ins");

        Map<String, String> literals = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(REPO)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList()) {
                Matcher matcher = LITERAL.matcher(Files.readString(file));
                while (matcher.find()) {
                    String type = matcher.group(1);
                    String name = matcher.group(2);
                    String previous = literals.putIfAbsent(name, type);
                    if (previous != null) {
                        assertEquals(previous, type,
                            "metric " + name + " is registered as both " + previous
                                + " and " + type);
                    }
                }
            }
        }

        assertEquals(documented, literals,
            "the metric catalog and the framework's literals must agree");

        for (String name : literals.keySet()) {
            assertFalse(!NAME.matcher(name).matches(),
                "metric name must be dot-separated lowercase: " + name);
        }

        // The default registry must accept the whole catalog: a rendered-name
        // collision (a.b vs a-b, or a timer suffix overlapping another name)
        // would make the scraper reject the exposition.
        MetricsDefault metrics = new MetricsDefault();
        assertDoesNotThrow(() -> {
            for (Map.Entry<String, String> entry : literals.entrySet()) {
                switch (entry.getValue()) {
                    case "counter" -> metrics.counter(entry.getKey());
                    case "timer" -> metrics.timer(entry.getKey());
                    case "gauge" -> metrics.gauge(entry.getKey(), () -> 0L);
                    case "histogram" -> metrics.histogram(entry.getKey());
                    default -> throw new AssertionError(entry.getValue());
                }
            }
        });
    }
}
