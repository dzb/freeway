package com.jujin.freeway.db.migration;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DatabaseBuilder;
import com.jujin.freeway.db.DbConfigKeys;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.DbModule;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import java.io.RandomAccessFile;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationRunnerTest {
    private static final String URL_KEY = DbConfigKeys.URL;
    private static final String USER_KEY = DbConfigKeys.USERNAME;
    private static final String PASS_KEY = DbConfigKeys.PASSWORD;

    private String previousUrl;
    private String previousUser;
    private String previousPass;

    @BeforeEach
    void captureProperties() {
        previousUrl = System.getProperty(URL_KEY);
        previousUser = System.getProperty(USER_KEY);
        previousPass = System.getProperty(PASS_KEY);
    }

    @AfterEach
    void restoreProperties() {
        restore(URL_KEY, previousUrl);
        restore(USER_KEY, previousUser);
        restore(PASS_KEY, previousPass);
    }

    @Test
    void runsClasspathMigrationsInOrder() {
        String dbName = "freeway_migration_" + UUID.randomUUID().toString().replace('-', '_');
        System.setProperty(URL_KEY, "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        System.setProperty(USER_KEY, "sa");
        System.setProperty(PASS_KEY, "");

        Container container = Freeway.create(new DbModule());
        Database db = container.get(Database.class);
        MigrationRunner runner = container.get(MigrationRunner.class);

        try {
            assertEquals(3, runner.run());

            List<Item> items = db.query("select id, label from migration_items order by id")
                .list(Item.class);
            assertEquals(List.of(
                new Item(1L, "alpha"),
                new Item(2L, "beta")
            ), items);

            List<MigrationRow> migrations = db
                .query(
                    "select version, installed_rank from _migrations where version <> '__LOCK__' order by installed_rank"
                )
                .list(MigrationRow.class);
            assertEquals(
                List.of(
                    new MigrationRow("V001", 1),
                    new MigrationRow("V002", 2),
                    new MigrationRow("V003", 3)
                ),
                migrations
            );

            assertEquals(0, runner.run());
        } finally {
            container.close();
        }
    }

    @Test
    void versionFromPathStripsSuffixAndDescription() {
        assertEquals("V001", MigrationRunner.versionFromPath("db/migration/V001__create_table.sql"));
        assertEquals("abc", MigrationRunner.versionFromPath("abc.sql"));
    }

    @Test
    void runsMigrationsInNaturalVersionOrder(@TempDir Path tempDir) throws Exception {
        Path migrationDir = Files.createDirectories(tempDir.resolve("db/migration"));
        Files.writeString(
            migrationDir.resolve("V1_2__create_table.sql"),
            "create table natural_order (id bigint primary key)"
        );
        Files.writeString(
            migrationDir.resolve("V1_10__seed_table.sql"),
            "insert into natural_order (id) values (1)"
        );

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { tempDir.toUri().toURL() }, null);
             Database db = tempDb("freeway_natural_order")) {
            Thread.currentThread().setContextClassLoader(loader);
            MigrationRunner runner = new MigrationRunner(db, true, "db/migration", "_migrations");

            assertEquals(2, runner.run());
            assertEquals(List.of(1L), db.query("select id from natural_order order by id").list(Long.class));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void splitsStatementsOutsideQuotesCommentsAndDollarQuotes() {
        String sql = """
            -- header comment
            create table t (id bigint primary key);
            insert into t (id, note) values (1, 'semi;colon');
            do $$ begin perform 1; end $$;
            """;

        assertEquals(
            List.of(
                "create table t (id bigint primary key)",
                "insert into t (id, note) values (1, 'semi;colon')",
                "do $$ begin perform 1; end $$"
            ),
            MigrationRunner.splitStatements(sql)
        );
    }

    @Test
    void rejectsOversizedMigrationFile(@TempDir Path tempDir) throws Exception {
        Path migrationDir = Files.createDirectories(tempDir.resolve("db/migration"));
        Path migration = migrationDir.resolve("V999__too_large.sql");
        try (RandomAccessFile file = new RandomAccessFile(migration.toFile(), "rw")) {
            file.setLength(MigrationRunner.MAX_MIGRATION_BYTES + 1L);
        }

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { tempDir.toUri().toURL() }, null);
             Database db = new DatabaseBuilder()
                 .config(PoolConfig.defaults(
                     "jdbc:h2:mem:freeway_migration_large_" + UUID.randomUUID().toString().replace('-', '_') + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                     "sa",
                     ""
                 ))
                 .build()) {
            Thread.currentThread().setContextClassLoader(loader);
            MigrationRunner runner = new MigrationRunner(db, true, "db/migration", "_migrations");

            SqlException ex = assertThrows(SqlException.class, runner::run);

            assertTrue(ex.getCause() != null && ex.getCause().getMessage().contains("too large"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void detectsChecksumMismatch(@TempDir Path tempDir) throws Exception {
        Path migrationDir = Files.createDirectories(tempDir.resolve("db/migration"));
        Files.writeString(
            migrationDir.resolve("V001__create.sql"),
            "create table checksum_test (id bigint primary key)"
        );

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { tempDir.toUri().toURL() }, null);
             Database db = tempDb("freeway_cs")) {
            Thread.currentThread().setContextClassLoader(loader);
            MigrationRunner runner = new MigrationRunner(db, true, "db/migration", "_migrations");

            assertEquals(1, runner.run());

            // Modify the SQL file after it was applied
            Files.writeString(
                migrationDir.resolve("V001__create.sql"),
                "create table checksum_test (id bigint primary key, modified int)"
            );

            SqlException ex = assertThrows(SqlException.class, runner::run);
            assertTrue(ex.getMessage().contains("Checksum mismatch"));
            assertTrue(ex.getMessage().contains("V001"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void rejectsBadVersionFormat(@TempDir Path tempDir) throws Exception {
        Path migrationDir = Files.createDirectories(tempDir.resolve("db/migration"));
        // File that doesn't start with V + digits
        Files.writeString(
            migrationDir.resolve("bad_name.sql"),
            "create table t (id int)"
        );

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { tempDir.toUri().toURL() }, null);
             Database db = tempDb("freeway_badver")) {
            Thread.currentThread().setContextClassLoader(loader);
            MigrationRunner runner = new MigrationRunner(db, true, "db/migration", "_migrations");

            SqlException ex = assertThrows(SqlException.class, runner::run);
            assertTrue(ex.getMessage().contains("Bad migration version"));
            assertTrue(ex.getMessage().contains("bad_name"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void detectsDuplicateVersions(@TempDir Path tempDir) throws Exception {
        Path migrationDir = Files.createDirectories(tempDir.resolve("db/migration"));
        // Two files that resolve to the same version "V001"
        Files.writeString(
            migrationDir.resolve("V001__first.sql"),
            "create table t1 (id bigint)"
        );
        Files.writeString(
            migrationDir.resolve("V001__second.sql"),
            "create table t2 (id bigint)"
        );

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { tempDir.toUri().toURL() }, null);
             Database db = tempDb("freeway_dupver")) {
            Thread.currentThread().setContextClassLoader(loader);
            MigrationRunner runner = new MigrationRunner(db, true, "db/migration", "_migrations");

            SqlException ex = assertThrows(SqlException.class, runner::run);
            assertTrue(ex.getMessage().contains("Duplicate migration version"));
            assertTrue(ex.getMessage().contains("V001"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void rejectsNumericallyDuplicateVersions(@TempDir Path tempDir) throws Exception {
        Path migrationDir = Files.createDirectories(tempDir.resolve("db/migration"));
        // V1 and V01 are the same version once leading zeros are stripped —
        // both would otherwise be applied, double-running the same migration.
        Files.writeString(
            migrationDir.resolve("V1__first.sql"),
            "create table t1 (id bigint)"
        );
        Files.writeString(
            migrationDir.resolve("V01__second.sql"),
            "create table t2 (id bigint)"
        );

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { tempDir.toUri().toURL() }, null);
             Database db = tempDb("freeway_numdup")) {
            Thread.currentThread().setContextClassLoader(loader);
            MigrationRunner runner = new MigrationRunner(db, true, "db/migration", "_migrations");

            SqlException ex = assertThrows(SqlException.class, runner::run);
            assertTrue(ex.getMessage().contains("Duplicate migration version"));
            assertTrue(ex.getMessage().contains("V1__first.sql"));
            assertTrue(ex.getMessage().contains("V01__second.sql"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void detectsAppliedMigrationMissingFromClasspath(@TempDir Path tempDir) throws Exception {
        Path migrationDir = Files.createDirectories(tempDir.resolve("db/migration"));
        Files.writeString(
            migrationDir.resolve("V001__create.sql"),
            "create table missing_file (id bigint primary key)"
        );
        Files.writeString(
            migrationDir.resolve("V002__seed.sql"),
            "insert into missing_file (id) values (1)"
        );

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { tempDir.toUri().toURL() }, null);
             Database db = tempDb("freeway_missing_file")) {
            Thread.currentThread().setContextClassLoader(loader);
            MigrationRunner runner = new MigrationRunner(db, true, "db/migration", "_migrations");

            assertEquals(2, runner.run());

            // Simulate a packaging mistake: the applied file vanishes from the classpath
            Files.delete(migrationDir.resolve("V001__create.sql"));

            SqlException ex = assertThrows(SqlException.class, runner::run);
            assertTrue(ex.getMessage().contains("V1"));
            assertTrue(ex.getMessage().contains("missing"));
            assertTrue(ex.getMessage().contains("packaging"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void rejectsEmptyMigrationFile(@TempDir Path tempDir) throws Exception {
        Path migrationDir = Files.createDirectories(tempDir.resolve("db/migration"));
        Files.writeString(migrationDir.resolve("V001__empty.sql"), "");

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { tempDir.toUri().toURL() }, null);
             Database db = tempDb("freeway_empty")) {
            Thread.currentThread().setContextClassLoader(loader);
            MigrationRunner runner = new MigrationRunner(db, true, "db/migration", "_migrations");

            SqlException ex = assertThrows(SqlException.class, runner::run);
            assertTrue(ex.getMessage().contains("empty"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void rejectsCommentOnlyMigrationFile(@TempDir Path tempDir) throws Exception {
        Path migrationDir = Files.createDirectories(tempDir.resolve("db/migration"));
        Files.writeString(
            migrationDir.resolve("V001__comment_only.sql"),
            """
            -- only comments
            /* and blocks */
            """
        );

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { tempDir.toUri().toURL() }, null);
             Database db = tempDb("freeway_comment_only")) {
            Thread.currentThread().setContextClassLoader(loader);
            MigrationRunner runner = new MigrationRunner(db, true, "db/migration", "_migrations");

            SqlException ex = assertThrows(SqlException.class, runner::run);
            assertTrue(ex.getMessage().contains("empty"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void runsMultiStatementMigrationFile(@TempDir Path tempDir) throws Exception {
        Path migrationDir = Files.createDirectories(tempDir.resolve("db/migration"));
        Files.writeString(
            migrationDir.resolve("V001__multi_step.sql"),
            """
            -- create and seed in one file
            create table migration_multi (
                id bigint primary key,
                note varchar(64) not null
            );
            insert into migration_multi (id, note) values (1, 'semi;colon');
            insert into migration_multi (id, note) values (2, 'beta');
            """
        );

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { tempDir.toUri().toURL() }, null);
             Database db = tempDb("freeway_multi")) {
            Thread.currentThread().setContextClassLoader(loader);
            MigrationRunner runner = new MigrationRunner(db, true, "db/migration", "_migrations");

            assertEquals(1, runner.run());

            List<Item> items = db
                .query("select id, note as label from migration_multi order by id")
                .list(Item.class);
            assertEquals(
                List.of(
                    new Item(1L, "semi;colon"),
                    new Item(2L, "beta")
                ),
                items
            );
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void lockPreventsConcurrentRuns(@TempDir Path tempDir) throws Exception {
        Path migrationDir = Files.createDirectories(tempDir.resolve("db/migration"));
        Files.writeString(
            migrationDir.resolve("V001__step.sql"),
            "create table lock_test (id bigint primary key)"
        );

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { tempDir.toUri().toURL() }, null);
             Database db = tempDb("freeway_lock")) {
            Thread.currentThread().setContextClassLoader(loader);
            // Ensure the tracking table exists before inserting a fake lock
            db.execute(
                "create table if not exists _migrations (" +
                "version varchar(255) primary key," +
                "description varchar(512)," +
                "checksum char(64) not null," +
                "installed_rank int not null," +
                "executed_at timestamp default current_timestamp)"
            );
            // Simulate another instance holding the lock
            db.execute(
                "insert into _migrations (version, description, checksum, installed_rank) values ('__LOCK__', '', '', -1)"
            );

            MigrationRunner runner = new MigrationRunner(db, true, "db/migration", "_migrations");

            SqlException ex = assertThrows(SqlException.class, runner::run);
            assertTrue(ex.getMessage().contains("Cannot acquire migration lock"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void disabledRunnerReturnsZero(@TempDir Path tempDir) throws Exception {
        Path migrationDir = Files.createDirectories(tempDir.resolve("db/migration"));
        Files.writeString(
            migrationDir.resolve("V001__step.sql"),
            "create table t (id int)"
        );

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { tempDir.toUri().toURL() }, null);
             Database db = tempDb("freeway_disabled")) {
            Thread.currentThread().setContextClassLoader(loader);
            MigrationRunner runner = new MigrationRunner(db, false, "db/migration", "_migrations");

            assertEquals(0, runner.run());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void renamedMigrationFileIsNotReappliedOrReportedMissing(@TempDir Path tempDir)
            throws Exception {
        // V01 and V1 are the same migration. After applying V1__step.sql,
        // renaming the file to V01__step.sql must neither re-apply it (the
        // apply loop must use normalized identity) nor report it as missing.
        Path migrationDir = Files.createDirectories(tempDir.resolve("db/migration"));
        Path v1 = migrationDir.resolve("V1__step.sql");
        Files.writeString(v1, "create table t (id int)");

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                 new java.net.URL[] { tempDir.toUri().toURL() }, null);
             Database db = tempDb("freeway_rename")) {
            Thread.currentThread().setContextClassLoader(loader);
            MigrationRunner runner = new MigrationRunner(
                db, true, "db/migration", "_migrations");

            assertEquals(1, runner.run(), "first run applies V1__step.sql");

            Files.move(
                v1,
                migrationDir.resolve("V01__step.sql"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );

            assertEquals(0, runner.run(),
                "renamed file is the same version — must not re-apply");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static Database tempDb(String name) {
        String dbName = name + "_" + UUID.randomUUID().toString().replace('-', '_');
        return new DatabaseBuilder()
            .config(PoolConfig.defaults(
                "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
            ))
            .build();
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    public record Item(long id, String label) {
    }

    public record MigrationRow(String version, int installedRank) {
    }
}
