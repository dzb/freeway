package com.jujin.freeway.db.migration;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DatabaseBuilder;
import com.jujin.freeway.db.DatabaseConfig;
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
    private static final String URL_KEY = DatabaseConfig.PREFIX + ".url";
    private static final String USER_KEY = DatabaseConfig.PREFIX + ".username";
    private static final String PASS_KEY = DatabaseConfig.PREFIX + ".password";

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

            List<String> versions = db.query("select version from _migrations order by version")
                .list(String.class);
            assertEquals(List.of("V001", "V002", "V003"), versions);

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
    void rejectsOversizedMigrationFile(@TempDir Path tempDir) throws Exception {
        Path migrationDir = Files.createDirectories(tempDir.resolve("db/migration"));
        Path migration = migrationDir.resolve("V999__too_large.sql");
        try (RandomAccessFile file = new RandomAccessFile(migration.toFile(), "rw")) {
            file.setLength(MigrationRunner.MAX_MIGRATION_BYTES + 1L);
        }

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { tempDir.toUri().toURL() }, null);
             Database db = new DatabaseBuilder()
                 .config(DatabaseConfig.defaults(
                     "jdbc:h2:mem:freeway_migration_large_" + UUID.randomUUID().toString().replace('-', '_') + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                     "sa",
                     ""
                 ))
                 .build()) {
            Thread.currentThread().setContextClassLoader(loader);
            MigrationRunner runner = new MigrationRunner(db, true, "db/migration", "_migrations");

            SqlException ex = assertThrows(SqlException.class, runner::run);

            assertTrue(ex.getMessage().contains("Migration file too large"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
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
}
