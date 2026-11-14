package com.jujin.freeway.boot.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The documentation set stays one coherent, English-only tree: every file is in
 * a category directory with a consistent name, every relative link resolves,
 * every document is reachable from its index, and every {@code docs/...}
 * reference in framework javadoc names a file that exists.
 *
 * <p>These are the drift modes that a translator or a module rename leaves
 * behind: a Chinese paragraph added back, a moved document whose links were not
 * updated, a new orphan page nobody indexes, an English-only rule quietly
 * broken. The checks read the working tree (the repository root is found by
 * walking up), so a standalone module build skips them.
 */
class DocsLayoutConsistencyTest {

    private static final Path REPO = findRepoRoot();
    private static final Path DOCS = REPO == null ? null : REPO.resolve("docs");

    private static final Pattern CJK =
        Pattern.compile("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]");
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern SOURCE_DOC_REF =
        Pattern.compile("docs/[A-Za-z0-9_./-]+\\.(?:md|reference|sample)");
    private static final Pattern KEBAB_MD =
        Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*\\.md");
    private static final Pattern SAMPLE =
        Pattern.compile("application(?:-[a-z0-9]+)?\\.(?:json|properties)\\.sample");

    /** The one Chinese document kept on purpose (linked from the English skill). */
    private static final String CHINESE_SKILL = "skills/freeway-dev/SKILL.zh.md";

    private static Path findRepoRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("docs/README.md"))
                    && Files.isDirectory(candidate.resolve("freeway-boot"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    // ── language ──────────────────────────────────────────────

    @Test
    void documentationIsEnglishOnly() throws IOException {
        assumeTrue(REPO != null, "repository layout not present");
        List<String> offenders = new ArrayList<>();
        for (Path file : documentationFiles()) {
            if (relative(file).equals(CHINESE_SKILL)) {
                continue; // the documented exception
            }
            if (CJK.matcher(Files.readString(file)).find()) {
                offenders.add(relative(file));
            }
        }
        assertEquals(List.of(), offenders,
            "documentation is English-only from Freeway 2.0 on");
    }

    /** Every Markdown, sample and reference file that is documentation. */
    private static List<Path> documentationFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String dir : List.of("docs", "demo/reference", "skills", "freeway-flow/docs")) {
            Path root = REPO.resolve(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> isDocumentationFile(p.getFileName().toString()))
                    .forEach(files::add);
            }
        }
        for (String name : List.of(
                "README.md", "TASTE.md", "AGENTS.md", "CHANGELOG.md", "freeway-flow/README.md")) {
            Path file = REPO.resolve(name);
            if (Files.isRegularFile(file)) {
                files.add(file);
            }
        }
        return files;
    }

    private static boolean isDocumentationFile(String name) {
        return name.endsWith(".md") || name.endsWith(".sample") || name.endsWith(".reference");
    }

    // ── naming and placement ──────────────────────────────────

    @Test
    void documentationNamesAndDirectoriesAreConsistent() throws IOException {
        assumeTrue(REPO != null, "repository layout not present");
        Set<String> rootFiles = Set.of(
            "README.md", "architecture.md", "developer-guide.md",
            "configuration.md", "module-system.md", "reflection.md");
        Set<String> directories = Set.of("modules", "design", "reference", "samples", "releases");
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(DOCS)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path rel = DOCS.relativize(file);
                String name = file.getFileName().toString();
                if (name.endsWith(".sample")) {
                    if (!SAMPLE.matcher(name).matches()) {
                        offenders.add("docs/" + rel + " (sample name)");
                    }
                    continue;
                }
                if (name.endsWith(".reference")) {
                    if (!rel.toString().equals("reference/logging.properties.reference")) {
                        offenders.add("docs/" + rel + " (unexpected reference file)");
                    }
                    continue;
                }
                if (!name.endsWith(".md")) {
                    offenders.add("docs/" + rel + " (not Markdown/sample/reference)");
                    continue;
                }
                if (rel.getNameCount() == 1) {
                    if (!rootFiles.contains(name)) {
                        offenders.add("docs/" + rel + " (not an allowed root document)");
                    }
                } else if (!directories.contains(rel.getName(0).toString())
                        || !KEBAB_MD.matcher(name).matches()) {
                    offenders.add("docs/" + rel + " (category or kebab-case name)");
                }
            }
        }
        try (Stream<Path> walk = Files.walk(REPO.resolve("demo/reference"))) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.equals("README.md") && !KEBAB_MD.matcher(name).matches()) {
                    offenders.add("demo/reference/" + name + " (kebab-case name)");
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    // ── links ─────────────────────────────────────────────────

    @Test
    void everyRelativeMarkdownLinkResolves() throws IOException {
        assumeTrue(REPO != null, "repository layout not present");
        List<String> broken = new ArrayList<>();
        for (Path md : markdownFiles(REPO)) {
            Matcher matcher = LINK.matcher(Files.readString(md));
            while (matcher.find()) {
                String target = matcher.group(1);
                if (isExternal(target)) {
                    continue;
                }
                String path = target.split("#", 2)[0];
                if (path.isBlank() || path.contains("{") || path.contains("*")) {
                    continue;
                }
                Path resolved = md.getParent().resolve(path).normalize();
                if (!Files.exists(resolved)) {
                    broken.add(relative(md) + " -> " + target);
                }
            }
        }
        assertEquals(List.of(), broken, "documentation links must name an existing file");
    }

    @Test
    void sourceJavadocReferencesResolve() throws IOException {
        assumeTrue(REPO != null, "repository layout not present");
        List<String> missing = new ArrayList<>();
        for (Path module : sourceModules()) {
            Path source = module.resolve("src/main/java");
            if (!Files.isDirectory(source)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path java : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Matcher matcher = SOURCE_DOC_REF.matcher(Files.readString(java));
                    String moduleDir = REPO.relativize(module).getName(0).toString();
                    while (matcher.find()) {
                        String reference = matcher.group();
                        boolean atRoot = Files.exists(REPO.resolve(reference));
                        boolean inModule = Files.exists(REPO.resolve(moduleDir).resolve(reference));
                        if (!atRoot && !inModule) {
                            missing.add(relative(java) + " -> " + reference);
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), missing,
            "a javadoc reference to a missing document is a stale pointer");
    }

    // ── index coverage ───────────────────────────────────────

    @Test
    void everyDocumentIsReachableFromItsIndex() throws IOException {
        assumeTrue(REPO != null, "repository layout not present");
        List<String> orphans = new ArrayList<>();

        Set<Path> docsCovered = linkedFiles(DOCS.resolve("README.md"));
        try (Stream<Path> walk = Files.walk(DOCS)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path rel = DOCS.relativize(file);
                if (rel.toString().equals("README.md")) {
                    continue;
                }
                if (!docsCovered.contains(file.normalize())) {
                    orphans.add("docs/" + rel);
                }
            }
        }

        Path demoIndex = REPO.resolve("demo/reference/README.md");
        Set<Path> demoCovered = linkedFiles(demoIndex);
        try (Stream<Path> walk = Files.walk(demoIndex.getParent())) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (file.getFileName().toString().equals("README.md")) {
                    continue;
                }
                if (!demoCovered.contains(file.normalize())) {
                    orphans.add("demo/reference/" + file.getFileName());
                }
            }
        }
        assertEquals(List.of(), orphans,
            "a document missing from its index is a page nobody finds");
    }

    /** All files a Markdown index links to; directory links cover their subtree. */
    private static Set<Path> linkedFiles(Path index) throws IOException {
        Set<Path> covered = new HashSet<>();
        Matcher matcher = LINK.matcher(Files.readString(index));
        while (matcher.find()) {
            String target = matcher.group(1).split("#", 2)[0];
            if (target.isBlank() || isExternal(target)) {
                continue;
            }
            Path resolved = index.getParent().resolve(target).normalize();
            if (Files.isDirectory(resolved)) {
                try (Stream<Path> walk = Files.walk(resolved)) {
                    walk.filter(Files::isRegularFile).forEach(p -> covered.add(p.normalize()));
                }
            } else {
                covered.add(resolved);
            }
        }
        return covered;
    }

    // ── the one deleted file ─────────────────────────────────

    @Test
    void everySkillReferenceIsLinkedFromTheEntry() throws IOException {
        assumeTrue(REPO != null, "repository layout not present");
        Path skills = REPO.resolve("skills/freeway-dev");
        String entry = Files.readString(skills.resolve("SKILL.md"));
        List<String> orphans = new ArrayList<>();
        try (Stream<Path> walk = Files.list(skills.resolve("references"))) {
            for (Path reference : walk.filter(Files::isRegularFile).toList()) {
                if (!entry.contains(reference.getFileName().toString())) {
                    orphans.add(reference.getFileName().toString());
                }
            }
        }
        assertEquals(List.of(), orphans,
            "a reference the entry does not link is a file the skill never reads");
    }

    @Test
    void theChineseSkillCopyIsTheDocumentedException() throws IOException {
        assumeTrue(REPO != null, "repository layout not present");
        Path chinese = REPO.resolve(CHINESE_SKILL);
        assertTrue(Files.isRegularFile(chinese),
            "the Chinese skill copy is kept as the one translation exception");
        assertTrue(CJK.matcher(Files.readString(chinese)).find(),
            "the exception must actually be the Chinese translation");
        assertTrue(Files.readString(REPO.resolve("skills/freeway-dev/SKILL.md"))
                .contains("SKILL.zh.md"),
            "the English skill must link the exception it carries");
    }

    // ── helpers ──────────────────────────────────────────────

    private static List<Path> markdownFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".md"))
                .filter(p -> !p.toString().contains("/target/"))
                .filter(p -> !p.toString().contains("/.git/"))
                .toList();
        }
    }

    private static List<Path> sourceModules() throws IOException {
        List<Path> modules = new ArrayList<>();
        try (Stream<Path> walk = Files.list(REPO)) {
            walk.filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith("freeway-"))
                .forEach(modules::add);
        }
        return modules;
    }

    private static boolean isExternal(String target) {
        return target.startsWith("http://")
            || target.startsWith("https://")
            || target.startsWith("mailto:")
            || target.startsWith("#");
    }

    private static String relative(Path file) {
        return REPO.relativize(file).toString();
    }
}
