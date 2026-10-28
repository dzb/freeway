package com.jujin.freeway.boot.internal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The API references in the current documentation surfaces must name members
 * that exist.
 *
 * <p>The config table already has this check ({@code ConfigDocsConsistencyTest});
 * the prose surfaces did not, and drifted: a reference to
 * {@code AppRuntime.container()} survived the method's deletion by several
 * releases, in the skill that tells an agent how to write against this
 * framework. A stale name there is worse than a stale sentence elsewhere — it
 * is an instruction that does not compile.</p>
 *
 * <p>Scope is the surfaces that describe the <em>current</em> API: README,
 * AGENTS.md and {@code skills/}. Historical documents (release notes, audits)
 * are records of what was true then and are deliberately not scanned.</p>
 *
 * <p>A reference is a backticked {@code Type.member(}. The member may be
 * declared anywhere in the type's module (inheritance and sibling helpers are
 * normal), so the search is over the module's sources, not one file.</p>
 */
class ApiDocsConsistencyTest {

    private static final Path REPO = findRepoRoot();
    private static final Path SKILLS = REPO == null ? null : REPO.resolve("skills");

    private static Path findRepoRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            if (Files.isDirectory(candidate.resolve("freeway-boot"))
                    && Files.isDirectory(candidate.resolve("skills"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    private static final Pattern TYPE_MEMBER =
        Pattern.compile("`([A-Z][A-Za-z0-9_]*)\\.([a-z][A-Za-z0-9_]*)\\(");

    /** Simple type name → the concatenated {@code src/main} of its module. */
    private static Map<String, String> mainSources() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, StringBuilder> perModule = new LinkedHashMap<>();
        for (Path module : Files.newDirectoryStream(REPO, "freeway-*")) {
            Path main = module.resolve("src/main/java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    text.append(Files.readString(file)).append('\n');
                }
            }
            perModule.put(module.getFileName().toString(), text);
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    Matcher typed = Pattern.compile(
                        "(?:^|\\n)(?:public |final |abstract |sealed |non-sealed )*"
                            + "(?:class|interface|enum|record) ([A-Za-z0-9_]+)").matcher(source);
                    while (typed.find()) {
                        sources.put(typed.group(1), module.getFileName().toString());
                    }
                }
            }
        }
        // A name is looked up in its module's whole main tree.
        Map<String, String> resolved = new LinkedHashMap<>();
        sources.forEach((type, module) -> resolved.put(type, perModule.get(module).toString()));
        return resolved;
    }

    private static List<Path> docs() throws IOException {
        List<Path> docs = new ArrayList<>();
        docs.add(REPO.resolve("README.md"));
        docs.add(REPO.resolve("AGENTS.md"));
        if (SKILLS != null && Files.isDirectory(SKILLS)) {
            try (Stream<Path> files = Files.walk(SKILLS)) {
                docs.addAll(files.filter(p -> p.toString().endsWith(".md")).toList());
            }
        }
        return docs;
    }

    @Test
    void everyDocumentedTypeMemberExists() throws IOException {
        assumeTrue(REPO != null, "runs only inside the repository");
        Map<String, String> modules = mainSources();
        Map<String, String> failures = new LinkedHashMap<>();
        for (Path doc : docs()) {
            if (!Files.isRegularFile(doc)) {
                continue;
            }
            String text = Files.readString(doc);
            Matcher matcher = TYPE_MEMBER.matcher(text);
            while (matcher.find()) {
                String type = matcher.group(1);
                String member = matcher.group(2);
                String sources = modules.get(type);
                if (sources == null) {
                    continue; // not a freeway type (JDK, third-party, prose)
                }
                Pattern declared = Pattern.compile("\\b" + Pattern.quote(member) + "\\s*\\(");
                if (!declared.matcher(sources).find()) {
                    int line = 1 + text.substring(0, matcher.start()).split("\n", -1).length - 1;
                    failures.put(REPO.relativize(doc) + ":" + line,
                        type + "." + member + "(…)");
                }
            }
        }
        assertTrue(failures.isEmpty(),
            "documented members that no module declares: " + failures);
    }
}
