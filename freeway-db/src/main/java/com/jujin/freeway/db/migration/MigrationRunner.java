package com.jujin.freeway.db.migration;

import com.jujin.freeway.commons.util.Digests;
import com.jujin.freeway.commons.util.ByteStreams;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.db.util.SqlTextParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MigrationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(
        MigrationRunner.class
    );
    static final int MAX_MIGRATION_BYTES = 16 * 1024 * 1024;
    private static final String LOCK_VERSION = "__LOCK__";

    /** Flyway-compatible: V followed by digits and optional separators. */
    private static final String VERSION_PATTERN = "V\\d[\\d._]*";

    private final Database database;
    private final boolean enabled;
    private final String path;
    private final String table;

    public MigrationRunner(
        Database database,
        boolean enabled,
        String path,
        String table
    ) {
        this.database = database;
        this.enabled = enabled;
        this.path = normalizePath(path);
        this.table = normalizeTable(table);
    }

    public synchronized int run() {
        if (!enabled) {
            return 0;
        }

        ensureTable();
        acquireLock();
        try {
            return doRun();
        } finally {
            releaseLock();
        }
    }

    private int doRun() {

        List<String> migrations = scanMigrations();
        if (migrations.isEmpty()) {
            return 0;
        }

        // Validate version format before any execution
        for (String m : migrations) {
            String v = versionFromPath(m);
            if (!v.matches(VERSION_PATTERN)) {
                throw new SqlException(
                    "Bad migration version '" +
                        v +
                        "' in file " +
                        m +
                        " — must match pattern " +
                        VERSION_PATTERN
                );
            }
        }

        // Reject duplicate versions before any execution
        Set<String> seen = new HashSet<>();
        for (String m : migrations) {
            String v = versionFromPath(m);
            if (!seen.add(v)) {
                throw new SqlException(
                    "Duplicate migration version: " +
                        v +
                        " — detected in file " +
                        m
                );
            }
        }

        Map<String, String> existing = loadChecksums();
        validateChecksums(migrations, existing);

        int installedRank = existing.size();
        int ran = 0;
        for (String migration : migrations) {
            String version = versionFromPath(migration);
            if (existing.containsKey(version)) {
                continue;
            }
            byte[] raw = readResourceBytes(migration);
            String checksum = Digests.sha256Hex(raw);
            applyMigration(migration, checksum, installedRank + ran + 1);
            ran++;
            LOG.info("Applied migration: {}", migration);
        }
        if (ran > 0) LOG.info("Ran {} migration(s)", ran);
        return ran;
    }

    /**
     * Fail fast if a previously-applied migration has been modified.
     * This protects against silent drift where a SQL file changes
     * after being applied, which could break assumptions made by
     * later migrations.
     */
    private void validateChecksums(
        List<String> migrations,
        Map<String, String> existing
    ) {
        if (existing.isEmpty()) return;
        for (String m : migrations) {
            String version = versionFromPath(m);
            String stored = existing.get(version);
            if (stored == null) continue;
            byte[] raw = readResourceBytes(m);
            String current = Digests.sha256Hex(raw);
            if (!stored.equals(current)) {
                throw new SqlException(
                    "Checksum mismatch for version " +
                        version +
                        " (" +
                        m +
                        ") — the SQL file has been modified since it was applied. " +
                        "Stored: " +
                        stored +
                        ", Current: " +
                        current
                );
            }
        }
    }

    public static String versionFromPath(String path) {
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (name.endsWith(".sql")) {
            name = name.substring(0, name.length() - 4);
        }
        int sep = name.indexOf("__");
        return sep > 0 ? name.substring(0, sep) : name;
    }

    private void acquireLock() {
        try {
            database.execute(
                "insert into " +
                    table +
                    " (version, description, checksum, installed_rank) values ('" +
                    LOCK_VERSION +
                    "', '', '', -1)"
            );
        } catch (SqlException e) {
            if (isDuplicateKey(e)) {
                throw new SqlException(
                    "Cannot acquire migration lock — " +
                        "another instance may be running migrations. " +
                        "If no other instance is running, the lock may be stale: " +
                        "delete from " +
                        table +
                        " where version = '" +
                        LOCK_VERSION +
                        "'"
                );
            }
            throw e;
        }
    }

    private void releaseLock() {
        try {
            database.execute(
                "delete from " + table + " where version = '" + LOCK_VERSION + "'"
            );
        } catch (RuntimeException e) {
            LOG.warn("Failed to release migration lock", e);
        }
    }

    private static boolean isDuplicateKey(SqlException e) {
        // primary: keyword matching on the cause's message (broad coverage)
        if (e.getCause() != null) {
            String msg = e.getCause().getMessage();
            if (msg != null) {
                msg = msg.toLowerCase(Locale.ROOT);
                if (msg.contains("unique")
                        || msg.contains("duplicate")
                        || msg.contains("primary key")
                        || msg.contains("violation")) {
                    return true;
                }
            }
        }
        // fallback: SQL standard state codes (driver-agnostic)
        if (e.getCause() instanceof java.sql.SQLException se) {
            String state = se.getSQLState();
            if (state != null && (state.startsWith("23") || "40001".equals(state))) {
                return true;
            }
        }
        return false;
    }

    private void ensureTable() {
        database.execute(
            """
            create table if not exists %s (
                version varchar(255) primary key,
                description varchar(512),
                checksum char(64) not null,
                installed_rank int not null,
                executed_at timestamp default current_timestamp
            )
            """.formatted(table)
        );
    }

    private void applyMigration(
        String resourcePath,
        String checksum,
        int installedRank
    ) {
        String sql = readResource(resourcePath);
        List<String> statements = splitStatements(sql);
        if (statements.isEmpty()) {
            throw new SqlException(
                "Migration file is empty or contains no executable SQL: " +
                    resourcePath
            );
        }
        String version = versionFromPath(resourcePath);
        String description = descriptionFromPath(resourcePath);
        database.transaction(() -> {
            for (String statement : statements) {
                database.execute(statement);
            }
            database.execute(
                "insert into " +
                    table +
                    " (version, description, checksum, installed_rank) values (?, ?, ?, ?)",
                version,
                description,
                checksum,
                installedRank
            );
        });
    }

    static List<String> splitStatements(String sql) {
        return SqlTextParser.splitStatements(sql);
    }

    private List<String> scanMigrations() {
        ClassLoader classLoader = classLoader();
        Set<String> result = new TreeSet<>(MigrationRunner::compareMigrationPaths);
        try {
            Enumeration<URL> roots = classLoader.getResources(path);
            while (roots.hasMoreElements()) {
                URL url = roots.nextElement();
                if ("file".equals(url.getProtocol())) {
                    scanFileRoot(result, url);
                } else if ("jar".equals(url.getProtocol())) {
                    scanJarRoot(result, url);
                }
            }
        } catch (IOException e) {
            throw new SqlException(
                "Failed to scan migrations under " + path,
                e
            );
        }
        return List.copyOf(result);
    }

    private void scanFileRoot(Set<String> result, URL url) {
        try {
            Path root = Path.of(url.toURI());
            if (!Files.exists(root)) {
                return;
            }
            try (var walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .map(this::toResourcePath)
                    .filter(this::isSqlFile)
                    .forEach(result::add);
            }
        } catch (IOException | URISyntaxException e) {
            throw new SqlException(
                "Failed to scan file migrations under " + path,
                e
            );
        }
    }

    private void scanJarRoot(Set<String> result, URL url) {
        try {
            JarURLConnection connection =
                (JarURLConnection) url.openConnection();
            String root = connection.getEntryName();
            if (root == null) {
                root = path;
            }
            if (!root.endsWith("/")) {
                root = root + "/";
            }
            try (JarFile jar = connection.getJarFile()) {
                for (JarEntry entry : Collections.list(jar.entries())) {
                    String name = entry.getName();
                    if (
                        !entry.isDirectory() &&
                        name.startsWith(root) &&
                        isSqlFile(name)
                    ) {
                        result.add(name);
                    }
                }
            }
        } catch (IOException e) {
            throw new SqlException(
                "Failed to scan jar migrations under " + path,
                e
            );
        }
    }

    private Map<String, String> loadChecksums() {
        Map<String, String> map = new LinkedHashMap<>();
        List<ChecksumRow> rows = database
            .query(
                "select version, checksum from " +
                    table +
                    " where version <> '" + LOCK_VERSION + "'" +
                    " order by installed_rank"
            )
            .list(ChecksumRow.class);
        for (ChecksumRow row : rows) {
            if (!LOCK_VERSION.equals(row.version())) {
                map.put(row.version(), row.checksum());
            }
        }
        return map;
    }

    private record ChecksumRow(String version, String checksum) {}

    private byte[] readResourceBytes(String resourcePath) {
        ClassLoader classLoader = classLoader();
        try (InputStream in = classLoader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new SqlException(
                    "Migration file not found on classpath: " + resourcePath
                );
            }
            return ByteStreams.readBytes(in, MAX_MIGRATION_BYTES, resourcePath);
        } catch (IOException e) {
            throw new SqlException(
                "Failed to read migration file: " + resourcePath,
                e
            );
        }
    }

    private String readResource(String resourcePath) {
        return new String(
            readResourceBytes(resourcePath),
            StandardCharsets.UTF_8
        );
    }

    private static int compareMigrationPaths(String left, String right) {
        int versionCompare = compareVersionStrings(
            versionFromPath(left),
            versionFromPath(right)
        );
        if (versionCompare != 0) {
            return versionCompare;
        }
        return left.compareTo(right);
    }

    private static int compareVersionStrings(String left, String right) {
        String[] leftParts = splitVersion(left);
        String[] rightParts = splitVersion(right);
        int count = Math.min(leftParts.length, rightParts.length);
        for (int i = 0; i < count; i++) {
            int cmp = compareVersionPart(leftParts[i], rightParts[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(leftParts.length, rightParts.length);
    }

    private static String[] splitVersion(String version) {
        String body = version.startsWith("V") ? version.substring(1) : version;
        return body.split("[._]");
    }

    private static int compareVersionPart(String left, String right) {
        String normalizedLeft = stripLeadingZeros(left);
        String normalizedRight = stripLeadingZeros(right);
        int lengthCompare = Integer.compare(
            normalizedLeft.length(),
            normalizedRight.length()
        );
        if (lengthCompare != 0) {
            return lengthCompare;
        }
        int compare = normalizedLeft.compareTo(normalizedRight);
        if (compare != 0) {
            return compare;
        }
        return Integer.compare(left.length(), right.length());
    }

    private static String stripLeadingZeros(String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }

    private static String normalizePath(String path) {
        String value =
            path == null || path.isBlank() ? "db/migration/" : path.trim();
        value = value.replace('\\', '/');
        return value.endsWith("/") ? value : value + "/";
    }

    private static String normalizeTable(String table) {
        String value =
            table == null || table.isBlank() ? "_migrations" : table.trim();
        if (!value.matches("[a-zA-Z0-9_$#]+")) {
            throw new IllegalArgumentException(
                "migration table must only contain [a-zA-Z0-9_$#], got: " +
                    value
            );
        }
        return value;
    }

    private static String descriptionFromPath(String path) {
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (name.endsWith(".sql")) {
            name = name.substring(0, name.length() - 4);
        }
        int sep = name.indexOf("__");
        return sep > 0 ? name.substring(sep + 2) : "";
    }

    private boolean isSqlFile(String path) {
        return path.endsWith(".sql");
    }

    private String toResourcePath(String relativePath) {
        return path + relativePath.replace('\\', '/');
    }

    private ClassLoader classLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : MigrationRunner.class.getClassLoader();
    }
}
