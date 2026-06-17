package com.jujin.freeway.db;

import com.jujin.freeway.db.schema.PostgresDialect;

import com.jujin.freeway.db.schema.*;
import com.jujin.freeway.db.migration.MigrationRunner;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DbModuleTest {
    private static final String URL_KEY = PoolConfig.PREFIX + ".url";
    private static final String USER_KEY = PoolConfig.PREFIX + ".username";
    private static final String PASS_KEY = PoolConfig.PREFIX + ".password";
    private static final String MIG_PATH_KEY = "freeway.db.migration.path";
    private static final String MIG_TABLE_KEY = "freeway.db.migration.table";
    private static final String SCHEMA_GROUPS_KEY = "freeway.db.schema.groups";

    private String previousUrl;
    private String previousUser;
    private String previousPass;
    private String previousMigPath;
    private String previousMigTable;
    private String previousSchemaGroups;

    @BeforeEach
    void captureProperties() {
        previousUrl = System.getProperty(URL_KEY);
        previousUser = System.getProperty(USER_KEY);
        previousPass = System.getProperty(PASS_KEY);
        previousMigPath = System.getProperty(MIG_PATH_KEY);
        previousMigTable = System.getProperty(MIG_TABLE_KEY);
        previousSchemaGroups = System.getProperty(SCHEMA_GROUPS_KEY);
    }

    @AfterEach
    void restoreProperties() {
        restore(URL_KEY, previousUrl);
        restore(USER_KEY, previousUser);
        restore(PASS_KEY, previousPass);
        restore(MIG_PATH_KEY, previousMigPath);
        restore(MIG_TABLE_KEY, previousMigTable);
        restore(SCHEMA_GROUPS_KEY, previousSchemaGroups);
    }

    @Test
    void moduleProvidesDatabaseQueriesTransactionsAndCustomMappers() {
        String dbName = "freeway_" + UUID.randomUUID().toString().replace('-', '_');
        System.setProperty(URL_KEY, "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        System.setProperty(USER_KEY, "sa");
        System.setProperty(PASS_KEY, "");

        try (Container container = Freeway.create(
            new DbModule(),
                binder -> binder.contribute(RowMapping.class).add(new RowMapping(
                Money.class,
                (rs, rowNum) -> new Money(rs.getLong("amount_cents"))
            ))
        )) {
            Database db = container.get(Database.class);
            db.execute(
                """
                create table ledger (
                    id bigint primary key,
                    name varchar(64) not null,
                    amount_cents bigint not null,
                    created_at timestamp not null
                )
                """
            );

            db.batch("insert into ledger (id, name, amount_cents, created_at) values (?, ?, ?, ?)")
                .rows(
                    new Object[] { 1L, "alpha", 1250L, java.sql.Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")) },
                    new Object[] { 2L, "beta", 2250L, java.sql.Timestamp.from(Instant.parse("2025-01-02T00:00:00Z")) }
                )
                .execute();

            List<LedgerRow> rows = db.query("select id, name, amount_cents, created_at from ledger order by id")
                .list(LedgerRow.class);
            assertEquals(2, rows.size());
            assertEquals(new LedgerRow(1L, "alpha", 1250L, Instant.parse("2025-01-01T00:00:00Z")), rows.get(0));
            assertEquals(new LedgerRow(2L, "beta", 2250L, Instant.parse("2025-01-02T00:00:00Z")), rows.get(1));

            List<Money> moneyByCollection = db.query("select amount_cents from ledger where id in (?) order by id", List.of(1L, 2L))
                .list(Money.class);
            assertEquals(List.of(new Money(1250L), new Money(2250L)), moneyByCollection);

            Money first = db.query("select amount_cents from ledger where id = $id")
                .param("id", 1L)
                .one(Money.class)
                .orElseThrow();
            assertEquals(new Money(1250L), first);

            db.transaction(() -> db.execute("update ledger set amount_cents = amount_cents + ? where id = ?", 100L, 1L));
            long updated = db.query("select amount_cents from ledger where id = ?", 1L)
                .one(Long.class)
                .orElseThrow();
            assertEquals(1350L, updated);
            assertNotNull(db.stats());
        }
    }

    @Test
    void dbHubWrapsNamedDatabaseContributions() {
        Database primary = new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:h2:mem:primary_" + UUID.randomUUID().toString().replace('-', '_') + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .build();
        Database audit = new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:h2:mem:audit_" + UUID.randomUUID().toString().replace('-', '_') + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .build();

        try {
            Container container = Freeway.create(
                new DbModule(),
                    binder -> binder.contribute(DatabaseNamed.class).add(new DatabaseNamed("primary", primary)),
                    binder -> binder.contribute(DatabaseNamed.class).add(new DatabaseNamed("audit", audit))
            );

            DatabaseHub hub = container.get(DatabaseHub.class);
            assertEquals(primary, hub.get("primary"));
            assertEquals(audit, hub.get("audit"));
            assertSame(primary, hub.primary());
            assertEquals(Map.of("primary", primary, "audit", audit), hub.all());
        } finally {
            primary.close();
            audit.close();
        }
    }

    @Test
    void schemaEntitiesCreateTablesBeforeMigrations(@TempDir Path tempDir) throws Exception {
        // Use a unique migration path to avoid conflict with classpath test resources
        String migPath = "schema_integration_test/";
        Path migrationDir = Files.createDirectories(tempDir.resolve(migPath));
        Files.writeString(
            migrationDir.resolve("V001__seed_categories.sql"),
            "insert into category (id, name) values (1, 'tech'), (2, 'food')"
        );

        String dbName = "freeway_schema_mig_" + UUID.randomUUID().toString().replace('-', '_');
        System.setProperty(URL_KEY, "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        System.setProperty(USER_KEY, "sa");
        System.setProperty(PASS_KEY, "");
        System.setProperty("freeway.db.migration.path", migPath);
        System.setProperty("freeway.db.migration.table", "_schema_migrations");

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[] { tempDir.toUri().toURL() },
                previous)) {
            Thread.currentThread().setContextClassLoader(loader);

            try (Container container = Freeway.create(
                new DbModule(),
                binder -> binder.contribute(SchemaEntity.class)
                    .add(SchemaEntity.of("test", Category.class))
            )) {
                Database db = container.get(Database.class);

                // 1. Schema: auto-create the Category table from @Table annotation
                int schemaOps = Schema.ensure(db, new PostgresDialect(), Category.class);
                assertTrue(schemaOps >= 1, "Schema should create the table");

                // Verify table exists and is empty
                List<Category> before = db.query("select id, name from category").list(Category.class);
                assertTrue(before.isEmpty(), "Table should be empty before migration");

                // 2. Migration: seed data via SQL files
                MigrationRunner runner = container.get(MigrationRunner.class);
                runner.run();

                // Verify data was inserted
                List<Category> after = db.query("select id, name from category order by id").list(Category.class);
                assertEquals(2, after.size());
                assertEquals(1L, after.get(0).id());
                assertEquals("tech", after.get(0).name());
                assertEquals(2L, after.get(1).id());
                assertEquals("food", after.get(1).name());

                // Idempotent: re-running does nothing
                assertEquals(0, runner.run());
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void schemaFillsGapWhereMigrationLeavesOff(@TempDir Path tempDir) throws Exception {
        // Use a unique migration path to avoid conflict with classpath test resources
        String migPath = "schema_gap_test/";
        Path migrationDir = Files.createDirectories(tempDir.resolve(migPath));
        Files.writeString(
            migrationDir.resolve("V001__create_items.sql"),
            "create table items (id bigint primary key, label varchar(64))"
        );
        Files.writeString(
            migrationDir.resolve("V002__seed_items.sql"),
            "insert into items (id, label) values (1, 'hello')"
        );

        String dbName = "freeway_schema_gap_" + UUID.randomUUID().toString().replace('-', '_');
        System.setProperty(URL_KEY, "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        System.setProperty(USER_KEY, "sa");
        System.setProperty(PASS_KEY, "");
        System.setProperty("freeway.db.migration.path", migPath);
        System.setProperty("freeway.db.migration.table", "_gap_migrations");

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[] { tempDir.toUri().toURL() },
                previous)) {
            Thread.currentThread().setContextClassLoader(loader);

            try (Container container = Freeway.create(
                new DbModule(),
                binder -> binder.contribute(SchemaEntity.class)
                    .add(SchemaEntity.of("test", Tag.class))
            )) {
                Database db = container.get(Database.class);

                // 1. Migration first: creates items table + seeds data
                MigrationRunner runner = container.get(MigrationRunner.class);
                assertEquals(2, runner.run());

                List<String> labels = db.query("select label from items order by id").list(String.class);
                assertEquals(List.of("hello"), labels);

                // 2. Schema: adds Tag table without touching items
                int ops = Schema.ensure(db, new PostgresDialect(), Tag.class);
                assertTrue(ops == 1, "Should create the Tag table exactly once");

                // items table still intact
                labels = db.query("select label from items order by id").list(String.class);
                assertEquals(List.of("hello"), labels);

                // Tag table exists
                db.execute("insert into tag (id, name) values (1, 'important')");
                List<Tag> tags = db.query("select id, name from tag").list(Tag.class);
                assertEquals(1, tags.size());
                assertEquals("important", tags.get(0).name());
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void schemaGroupFilterRunsOnlyEnabledGroups() {
        String dbName = "freeway_group_filter_" + UUID.randomUUID().toString().replace('-', '_');
        System.setProperty(URL_KEY, "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        System.setProperty(USER_KEY, "sa");
        System.setProperty(PASS_KEY, "");
        System.setProperty("freeway.db.schema.groups", "core");

        try (Container container = Freeway.create(
            new DbModule(),
            binder -> {
                binder.contribute(SchemaEntity.class)
                    .add(SchemaEntity.of("core", Category.class));
                binder.contribute(SchemaEntity.class)
                    .add(SchemaEntity.of("audit", AuditEntry.class));
            }
        )) {
            Database db = container.get(Database.class);

            // Simulate runSchema's group filtering:
            // only "core" group should be applied, "audit" skipped
            Schema.ensure(db, new PostgresDialect(), Category.class);
            // audit group NOT called: Schema.ensure(db, new PostgresDialect(), AuditEntry.class)

            // Verify: core table exists
            db.execute("insert into category (id, name) values (1, 'tech')");
            List<Category> cats = db.query("select id, name from category").list(Category.class);
            assertEquals(1, cats.size());

            // Verify: audit table does NOT exist (group was filtered out)
            assertThrows(RuntimeException.class, () ->
                db.execute("insert into audit_log (id, message) values (1, 'test')"));
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    public record Money(long cents) {
    }

    public record LedgerRow(long id, String name, long amountCents, Instant createdAt) {
    }

    // ===== Schema entity types for integration tests =====

    @Table("category")
    public static class Category {
        @Id private Long id;
        private String name;

        public Category() {}
        public Long id() { return id; }
        public String name() { return name; }
    }

    @Table("tag")
    public static class Tag {
        @Id private Long id;
        private String name;

        public Tag() {}
        public Long id() { return id; }
        public String name() { return name; }
    }

    @Table("audit_log")
    public static class AuditEntry {
        @Id private Long id;
        private String message;

        public AuditEntry() {}
        public Long id() { return id; }
        public String message() { return message; }
    }
}
