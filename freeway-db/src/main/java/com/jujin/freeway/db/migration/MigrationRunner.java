package com.jujin.freeway.db.migration;
import java.sql.SQLException;
import java.sql.Timestamp;

import com.jujin.freeway.commons.util.Digests;
import com.jujin.freeway.commons.util.ByteStreams;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.db.dialect.Dialect;
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
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MigrationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(
        MigrationRunner.class
    );
    static final int MAX_MIGRATION_BYTES = 16 * 1024 * 1024;
    private static final String LOCK_VERSION = "__LOCK__";
    /** Default lease for a leftover lock row: generous enough for slow
     *  migrations, short enough that a crashed process self-heals instead of
     *  failing every subsequent startup until manual cleanup. */
    public static final Duration DEFAULT_LOCK_TTL = Duration.ofHours(1);

    /** Flyway-compatible: V followed by digits and optional separators. */
    private static final String VERSION_PATTERN = "V\\d[\\d._]*";

    private final Database database;
    private final boolean enabled;
    private final String path;
    private final String table;
    private final Duration lockTtl;
    /**
     * Owner token written into the lock row's {@code description} column by
     * the successful {@link #acquireLock()} and cleared by
     * {@link #releaseLock()}. Lets a slow owner (migration outliving
     * {@link #lockTtl}, meanwhile taken over by a sibling) release without
     * deleting the sibling's fresh lock row. Only touched on the
     * {@link #run()} thread, which holds the monitor.
     */
    private String lockOwner;

    public MigrationRunner(
        Database database,
        boolean enabled,
        String path,
        String table
    ) {
        this(database, enabled, path, table, DEFAULT_LOCK_TTL);
    }

    /**
     * @param lockTtl how long a held lock row may persist before another
     *                instance treats it as stale and takes it over. Zero or
     *                negative disables takeover (fail-only, pre-1.4 behavior).
     */
    public MigrationRunner(
        Database database,
        boolean enabled,
        String path,
        String table,
        Duration lockTtl
    ) {
        this.database = database;
        this.enabled = enabled;
        this.path = normalizePath(path);
        this.table = normalizeTable(table);
        this.lockTtl = lockTtl == null ? DEFAULT_LOCK_TTL : lockTtl;
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

        if (!migrations.isEmpty()) {
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

            // Reject duplicate versions before any execution. Versions are
            // compared numerically (leading zeros stripped per part), so
            // V1__a.sql and V01__b.sql are the same migration and must not
            // both be applied.
            Set<String> seen = new HashSet<>();
            Map<String, String> seenNormalized = new LinkedHashMap<>();
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
                String normalized = normalizeVersion(v);
                String previousFile = seenNormalized.putIfAbsent(normalized, m);
                if (previousFile != null) {
                    throw new SqlException(
                        "Duplicate migration version: " +
                            v +
                            " — file " +
                            m +
                            " is the same version as " +
                            versionFromPath(previousFile) +
                            " in " +
                            previousFile +
                            " (leading zeros and separators are ignored when comparing versions)"
                    );
                }
            }
        }

        Map<String, String> existing = loadChecksums();
        validateAppliedMigrationsPresent(migrations, existing);

        if (migrations.isEmpty()) {
            return 0;
        }

        validateChecksums(migrations, existing);

        int installedRank = loadMaxInstalledRank();
        int ran = 0;
        for (String migration : migrations) {
            String version = versionFromPath(migration);
            // Normalized identity: a renamed file (V01 -> V1) is the same
            // migration and must not be applied twice.
            if (existing.containsKey(normalizeVersion(version))) {
                continue;
            }
            byte[] raw = readResourceBytes(migration);
            String checksum = checksum(raw);
            applyMigration(migration, checksum, ++installedRank);
            ran++;
            LOG.info("Applied migration: {}", migration);
        }
        if (ran > 0) LOG.info("Ran {} migration(s)", ran);
        return ran;
    }

    /**
     * Fail fast when a previously-applied migration's file is no longer on the
     * classpath. This catches packaging errors where an artifact ships without
     * migrations that were already applied; silently skipping them would hide
     * the mistake until the file is re-added later and surfaces as a confusing
     * checksum mismatch.
     */
    private void validateAppliedMigrationsPresent(
        List<String> migrations,
        Map<String, String> existing
    ) {
        if (existing.isEmpty()) {
            return;
        }
        Set<String> scanned = new HashSet<>();
        for (String m : migrations) {
            scanned.add(normalizeVersion(versionFromPath(m)));
        }
        for (String version : existing.keySet()) {
            if (!scanned.contains(version)) {
                throw new SqlException(
                    "Migration V" +
                        version +
                        " was applied but its file is missing from the classpath under " +
                        path +
                        " — possible packaging error (the migration file was removed " +
                        "from the deployed artifact)"
                );
            }
        }
    }

    /**
     * Fail fast if a previously-applied migration has been modified.
     * This protects against silent drift where a SQL file changes
     * after being applied, which could break assumptions made by
     * later migrations.
     *
     * <p>Validation is dual-track for backward compatibility: the stored
     * checksum was recorded from the migration file's raw bytes, so the raw
     * bytes are compared first. If that mismatches, the file is compared a
     * second time with CRLF line endings normalized to LF — a file whose line
     * endings changed between recording and deployment (e.g. checked out with
     * CRLF on Windows after being recorded from an LF checkout) has identical
     * SQL content and must not be reported as modified. Either track matching
     * passes validation; the stored checksum row is never rewritten, so old
     * rows stay valid and new migrations are still recorded from raw bytes.
     */
    private void validateChecksums(
        List<String> migrations,
        Map<String, String> existing
    ) {
        if (existing.isEmpty()) return;
        for (String m : migrations) {
            String version = versionFromPath(m);
            // Identity is normalized (V01 == V1): a renamed file must still
            // match its applied checksum instead of being re-applied.
            String stored = existing.get(normalizeVersion(version));
            if (stored == null) continue;
            byte[] raw = readResourceBytes(m);
            String current = checksum(raw);
            if (stored.equals(current)) {
                continue;
            }
            if (stored.equals(normalizedChecksum(raw))) {
                // Only the line endings differ — accept without rewriting the
                // stored row, keeping the raw-bytes checksum authoritative.
                continue;
            }
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

    /** SHA-256 of the migration file's raw bytes — the canonical stored checksum. */
    private static String checksum(byte[] raw) {
        return Digests.sha256Hex(raw);
    }

    /**
     * SHA-256 of the migration file with CRLF line endings normalized to LF
     * (a lone CR is treated as a line ending too). Used as the second
     * validation track so a pure line-ending change is not reported as a
     * checksum mismatch; any real content change survives the normalization
     * and still mismatches.
     */
    private static String normalizedChecksum(byte[] raw) {
        return Digests.sha256Hex(normalizeLineEndings(raw));
    }

    private static byte[] normalizeLineEndings(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        for (int i = 0; i < raw.length; i++) {
            byte b = raw[i];
            if (b == '\r') {
                if (i + 1 < raw.length && raw[i + 1] == '\n') {
                    i++;
                }
                out.write('\n');
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    /**
     * The migration file's basename with backslashes normalized to forward
     * slashes and the {@code .sql} suffix stripped (e.g.
     * {@code db/migration/V1__a.sql} → {@code V1__a}).
     */
    private static String baseName(String path) {
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (name.endsWith(".sql")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    public static String versionFromPath(String path) {
        String name = baseName(path);
        int sep = name.indexOf("__");
        return sep > 0 ? name.substring(0, sep) : name;
    }

    private void acquireLock() {
        String owner = UUID.randomUUID().toString();
        if (insertLockRow(owner)) {
            lockOwner = owner;
            return;
        }
        // The lock slot is taken. Take over only when the holding row is
        // provably stale — a live migration must never be preempted.
        if (!lockTtl.isZero() && !lockTtl.isNegative() && takeStaleLock()) {
            if (insertLockRow(owner)) {
                lockOwner = owner;
                LOG.warn(
                    "Took over stale migration lock (held longer than {})",
                    lockTtl
                );
                return;
            }
        }
        throw new SqlException(
            "Cannot acquire migration lock — " +
                "another instance may be running migrations. " +
                "If no other instance is running, the lock is either fresh " +
                "or staleness detection was unavailable: " +
                "delete from " +
                table +
                " where version = '" +
                LOCK_VERSION +
                "'"
        );
    }

    private boolean insertLockRow(String owner) {
        try {
            database.execute(
                "insert into " +
                    table +
                    " (version, description, checksum, installed_rank) values ('" +
                    LOCK_VERSION +
                    "', ?, '', -1)",
                owner
            );
            return true;
        } catch (SqlException e) {
            if (isDuplicateKey(e)) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Best-effort staleness check: compares the lock row's {@code executed_at}
     * against the database's own {@code current_timestamp} (fetched in the
     * same query) and deletes the row when it outlived {@link #lockTtl}.
     *
     * <p>Both timestamps go through the identical JDBC read path, so their
     * difference is correct even when the database session timezone differs
     * from the JVM timezone — comparing the stored timestamp directly against
     * {@code Instant.now()} would skew by the whole timezone gap and could
     * steal a live lock. Any failure to read or parse the timestamps (older
     * schema without the column, driver type surprises) keeps the lock —
     * conservative by construction, because wrongly stealing an active lock
     * would let two instances migrate concurrently.
     *
     * <p>The steal itself is conditional: the row is deleted only when its
     * {@code executed_at} still equals the value observed above. A sibling
     * instance that took over (or the owner re-acquiring) between our SELECT
     * and DELETE changes the row, so our DELETE affects 0 rows and we back
     * off instead of deleting a fresh lock and migrating concurrently.
     */
    private boolean takeStaleLock() {
        try {
            List<LockRow> rows = database
                .query(
                    "select executed_at, current_timestamp as db_now from " +
                        table +
                        " where version = '" + LOCK_VERSION + "'"
                )
                .list(LockRow.class);
            if (rows.size() != 1) {
                return false;
            }
            Instant acquiredAt = rows.getFirst().executedAt();
            Instant dbNow = rows.getFirst().dbNow();
            if (
                acquiredAt == null || dbNow == null ||
                Duration.between(acquiredAt, dbNow).compareTo(lockTtl) < 0
            ) {
                return false;
            }
            return deleteStaleLock(acquiredAt);
        } catch (RuntimeException e) {
            LOG.debug("Could not evaluate migration-lock staleness", e);
            return false;
        }
    }

    /**
     * Deletes the lock row only if it still carries the observed
     * {@code executed_at}. Returns true exactly when this instance removed
     * the stale row (1 row affected) and may proceed to re-insert.
     */
    private boolean deleteStaleLock(Instant observed) {
        try {
            var result = database.execute(
                "delete from " + table + " where version = '" + LOCK_VERSION
                    + "' and executed_at = ?",
                Timestamp.from(observed)
            );
            return result.rows() == 1;
        } catch (RuntimeException e) {
            LOG.debug("Could not steal stale migration lock", e);
            return false;
        }
    }

    /**
     * Releases the lock row acquired by this instance. The delete is scoped
     * to our owner token: a sibling that took over our stale row (migration
     * outliving {@link #lockTtl}) owns a different token, so our late
     * release removes 0 rows instead of deleting its fresh lock. Rows
     * predating owner tokens (empty description, e.g. inserted by an older
     * release or by hand) fall back to the unconditional delete.
     */
    private void releaseLock() {
        String owner = lockOwner;
        lockOwner = null;
        try {
            if (owner != null) {
                database.execute(
                    "delete from " + table + " where version = '" + LOCK_VERSION
                        + "' and description = ?",
                    owner
                );
            } else {
                database.execute(
                    "delete from " + table + " where version = '" + LOCK_VERSION + "'"
                );
            }
        } catch (RuntimeException e) {
            LOG.warn("Failed to release migration lock", e);
        }
    }

    // Package-visible: pinned by a table-driven test covering real driver
    // message/SQLState shapes (H2, MySQL, PostgreSQL, SQLite).
    //
    // Exact state codes, deliberately NOT a "23xxx family" prefix match:
    // the class covers all integrity violations — 23513 is a CHECK failure
    // and 23502 a NOT-NULL failure, neither of which means another instance
    // holds the migration lock. Only unique/duplicate (23505), the generic
    // integrity code some drivers map duplicate-key to (23000), and
    // serialization retries (40001) qualify.
    static boolean isDuplicateKey(SqlException e) {
        if (e.getCause() instanceof SQLException se) {
            String state = se.getSQLState();
            if ("23505".equals(state) || "23000".equals(state)
                    || "40001".equals(state)) {
                return true;
            }
        }
        if (e.getCause() != null) {
            String msg = e.getCause().getMessage();
            if (msg != null) {
                msg = msg.toLowerCase(Locale.ROOT);
                return msg.contains("duplicate")
                        || msg.contains("unique")
                        || msg.contains("primary key");
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
        List<String> statements = splitStatements(sql, database.dialect());
        if (statements.isEmpty()) {
            throw new SqlException(
                "Migration file is empty or contains no executable SQL: " +
                    resourcePath
            );
        }
        if (
            !database.dialect().supportsTransactionalDdl() &&
            containsDdl(statements)
        ) {
            throw new SqlException(
                "Dialect does not support transactional DDL — migration " +
                    resourcePath +
                    " contains DDL and cannot be applied atomically on this " +
                    "database: the DDL would commit but the checksum row would " +
                    "be lost, and the next startup would re-run the DDL and fail. " +
                    "Split DDL into separate migrations and make statements " +
                    "idempotent (IF NOT EXISTS), or use a transactional-DDL database"
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

    /** Statement-leading keywords that make a statement DDL (implicit-commit on MySQL). */
    private static final Set<String> DDL_KEYWORDS = Set.of(
        "create", "alter", "drop", "truncate", "rename", "comment",
        "grant", "revoke", "analyze", "attach", "detach"
    );

    /**
     * True when any statement begins with a DDL keyword. Split statements have
     * comments stripped, so the first token is the statement's keyword — only
     * leading whitespace needs skipping. Used to reject migrations that cannot
     * be applied atomically on dialects without transactional DDL.
     */
    static boolean containsDdl(List<String> statements) {
        for (String statement : statements) {
            String first = firstWord(statement);
            if (first != null && DDL_KEYWORDS.contains(first)) {
                return true;
            }
        }
        return false;
    }

    private static String firstWord(String statement) {
        int i = 0;
        int len = statement.length();
        while (i < len && Character.isWhitespace(statement.charAt(i))) {
            i++;
        }
        int start = i;
        while (
            i < len &&
            (Character.isLetterOrDigit(statement.charAt(i)) || statement.charAt(i) == '_')
        ) {
            i++;
        }
        return start == i ? null : statement.substring(start, i).toLowerCase(Locale.ROOT);
    }

    static List<String> splitStatements(String sql) {
        return SqlTextParser.splitStatements(sql);
    }

    /** Splits per the target database's dialect (e.g. MySQL # comments). */
    static List<String> splitStatements(String sql, Dialect dialect) {
        return SqlTextParser.splitStatements(sql, dialect);
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
                // Normalize the version key so legacy raw rows (V001) compare
                // equal to canonical forms (V1) — every downstream check uses
                // the same identity.
                map.put(normalizeVersion(row.version()), row.checksum());
            }
        }
        return map;
    }

    /** Highest installed_rank across applied migrations (excluding the lock row). */
    private int loadMaxInstalledRank() {
        List<RankRow> rows = database
            .query(
                "select installed_rank from " +
                    table +
                    " where version <> '" + LOCK_VERSION + "'"
            )
            .list(RankRow.class);
        return rows.stream().mapToInt(RankRow::installedRank).max().orElse(0);
    }

    private record ChecksumRow(String version, String checksum) {}

    private record RankRow(int installedRank) {}

    private record LockRow(Instant executedAt, Instant dbNow) {}

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

    /**
     * Canonical form of a version for duplicate detection: leading zeros are
     * stripped per dot/underscore part and parts are joined with '.', so
     * V1 and V01 share a key, and V1_0 and V1.0 share a key. Note V1 ("1")
     * and V1.0 ("1.0") remain distinct keys — renaming a migration between
     * them would be treated as a new version.
     */
    private static String normalizeVersion(String version) {
        String body = version.startsWith("V") ? version.substring(1) : version;
        String[] parts = body.split("[._]");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(stripLeadingZeros(parts[i]));
        }
        return sb.toString();
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
        String name = baseName(path);
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
