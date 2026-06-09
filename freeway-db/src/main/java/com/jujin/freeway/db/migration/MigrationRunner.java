package com.jujin.freeway.db.migration;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.ioc.annotation.Value;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class MigrationRunner {
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

    public int run() {
        if (!enabled) {
            return 0;
        }

        ensureTable();
        List<String> migrations = scanMigrations();
        if (migrations.isEmpty()) {
            return 0;
        }

        Set<String> completed = new HashSet<>(
            database.query("select version from " + table).list(String.class)
        );
        int ran = 0;
        for (String migration : migrations) {
            String version = versionFromPath(migration);
            if (completed.contains(version)) {
                continue;
            }
            applyMigration(migration);
            completed.add(version);
            ran++;
        }
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
                executed_at timestamp default current_timestamp
            )
            """
            .formatted(table)
        );
    }

    private void applyMigration(String resourcePath) {
        String sql = readResource(resourcePath);
        if (sql.isBlank()) {
            throw new SqlException("Migration file is empty: " + resourcePath);
        }
        String version = versionFromPath(resourcePath);
        String description = descriptionFromPath(resourcePath);
        database.transaction(tx -> {
            tx.execute(sql);
            tx.execute(
                "insert into " + table + " (version, description) values (?, ?)",
                version,
                description
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
                for (JarEntry entry : java.util.Collections.list(jar.entries())) {
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

    private String readResource(String resourcePath) {
        ClassLoader classLoader = classLoader();
        try (InputStream in = classLoader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new SqlException("Migration file not found on classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SqlException("Failed to read migration file: " + resourcePath, e);
        }
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
