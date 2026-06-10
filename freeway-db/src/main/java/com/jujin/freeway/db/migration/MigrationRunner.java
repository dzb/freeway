package com.jujin.freeway.db.migration;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.ioc.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class MigrationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(MigrationRunner.class);
    static final int MAX_MIGRATION_BYTES = 16 * 1024 * 1024;

    private final Database database;
    private final boolean enabled;
    private final String path;
    private final String table;

    public MigrationRunner(
        Database database,
        @Value("${freeway.db.migration.enabled:true}") boolean enabled,
        @Value("${freeway.db.migration.path:db/migration/}") String path,
        @Value("${freeway.db.migration.table:_migrations}") String table
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
        List<String> migrations = scanMigrations();
        if (migrations.isEmpty()) {
            return 0;
        }

        // Reject duplicate versions before any execution
        Set<String> seen = new HashSet<>();
        for (String m : migrations) {
            String v = versionFromPath(m);
            if (!seen.add(v)) {
                throw new SqlException("Duplicate migration version: " + v
                    + " — detected in file " + m);
            }
        }

        Map<String, String> existingChecksums = loadChecksums();

        int installedRank = existingChecksums.size();
        int ran = 0;
        for (String migration : migrations) {
            String version = versionFromPath(migration);
            if (existingChecksums.containsKey(version)) {
                continue;
            }
            byte[] raw = readResourceBytes(migration);
            String checksum = sha256(raw);
            applyMigration(migration, checksum, installedRank + ran + 1);
            ran++;
            LOG.info("Applied migration: {}", migration);
        }
        if (ran > 0) LOG.info("Ran {} migration(s)", ran);
        return ran;
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
            """
            .formatted(table)
        );
    }

    private void applyMigration(String resourcePath, String checksum, int installedRank) {
        String sql = readResource(resourcePath);
        if (sql.isBlank()) {
            throw new SqlException("Migration file is empty: " + resourcePath);
        }
        String version = versionFromPath(resourcePath);
        String description = descriptionFromPath(resourcePath);
        database.transaction(() -> {
            database.execute(sql);
            database.execute(
                "insert into " + table + " (version, description, checksum, installed_rank) values (?, ?, ?, ?)",
                version, description, checksum, installedRank
            );
        });
    }

    private List<String> scanMigrations() {
        ClassLoader classLoader = classLoader();
        Set<String> result = new TreeSet<>();
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
            throw new SqlException("Failed to scan migrations under " + path, e);
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
            throw new SqlException("Failed to scan file migrations under " + path, e);
        }
    }

    private void scanJarRoot(Set<String> result, URL url) {
        try {
            JarURLConnection connection = (JarURLConnection) url.openConnection();
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
                    if (!entry.isDirectory() && name.startsWith(root) && isSqlFile(name)) {
                        result.add(name);
                    }
                }
            }
        } catch (IOException e) {
            throw new SqlException("Failed to scan jar migrations under " + path, e);
        }
    }

    private Map<String, String> loadChecksums() {
        Map<String, String> map = new LinkedHashMap<>();
        List<ChecksumRow> rows = database.query(
            "select version, checksum from " + table + " order by installed_rank"
        ).list(ChecksumRow.class);
        for (ChecksumRow row : rows) {
            map.put(row.version(), row.checksum());
        }
        return map;
    }

    private record ChecksumRow(String version, String checksum) {}

    private byte[] readResourceBytes(String resourcePath) {
        ClassLoader classLoader = classLoader();
        try (InputStream in = classLoader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new SqlException("Migration file not found on classpath: " + resourcePath);
            }
            return readBytes(in, resourcePath);
        } catch (IOException e) {
            throw new SqlException("Failed to read migration file: " + resourcePath, e);
        }
    }

    private String readResource(String resourcePath) {
        return new String(readResourceBytes(resourcePath), StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new SqlException("SHA-256 not available", e);
        }
    }

    private static byte[] readBytes(InputStream in, String resourcePath) throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (total > MAX_MIGRATION_BYTES - read) {
                throw new SqlException(
                    "Migration file too large: " + resourcePath + " (max " + MAX_MIGRATION_BYTES + " bytes)"
                );
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    private static String normalizePath(String path) {
        String value = path == null || path.isBlank() ? "db/migration/" : path.trim();
        value = value.replace('\\', '/');
        return value.endsWith("/") ? value : value + "/";
    }

    private static String normalizeTable(String table) {
        String value = table == null || table.isBlank() ? "_migrations" : table.trim();
        if (!value.matches("[a-zA-Z0-9_$#]+")) {
            throw new IllegalArgumentException(
                "migration table must only contain [a-zA-Z0-9_$#], got: " + value
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
