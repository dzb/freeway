package com.jujin.freeway.db;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DbModuleTest {
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
    void moduleProvidesDatabaseQueriesTransactionsAndCustomMappers() {
        String dbName = "freeway_" + UUID.randomUUID().toString().replace('-', '_');
        System.setProperty(URL_KEY, "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        System.setProperty(USER_KEY, "sa");
        System.setProperty(PASS_KEY, "");

        try (Container container = Freeway.create(
            new DbModule(),
            binder -> binder.contribute(RowMapperRegistrations.class).add(new RowMapperEntry(
                Money.class,
                (rs, rowNum) -> new Money(rs.getLong("amount_cents"))
            ))
        )) {
            Database db = container.get(Database.class);
            db.sql(
                """
                create table ledger (
                    id bigint primary key,
                    name varchar(64) not null,
                    amount_cents bigint not null,
                    created_at timestamp not null
                )
                """
            ).execute();

            db.batch("insert into ledger (id, name, amount_cents, created_at) values (?, ?, ?, ?)")
                .rows(
                    new Object[] { 1L, "alpha", 1250L, java.sql.Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")) },
                    new Object[] { 2L, "beta", 2250L, java.sql.Timestamp.from(Instant.parse("2025-01-02T00:00:00Z")) }
                )
                .execute();

            List<LedgerRow> rows = db.sql("select id, name, amount_cents, created_at from ledger order by id")
                .list(LedgerRow.class);
            assertEquals(2, rows.size());
            assertEquals(new LedgerRow(1L, "alpha", 1250L, Instant.parse("2025-01-01T00:00:00Z")), rows.get(0));
            assertEquals(new LedgerRow(2L, "beta", 2250L, Instant.parse("2025-01-02T00:00:00Z")), rows.get(1));

            List<Money> moneyByCollection = db.sql("select amount_cents from ledger where id in (?) order by id", List.of(1L, 2L))
                .list(Money.class);
            assertEquals(List.of(new Money(1250L), new Money(2250L)), moneyByCollection);

            Money first = db.sql("select amount_cents from ledger where id = $id")
                .param("id", 1L)
                .one(Money.class)
                .orElseThrow();
            assertEquals(new Money(1250L), first);

            db.transaction(tx -> tx.sql("update ledger set amount_cents = amount_cents + ? where id = ?", 100L, 1L).execute());
            long updated = db.sql("select amount_cents from ledger where id = ?", 1L)
                .one(Long.class)
                .orElseThrow();
            assertEquals(1350L, updated);
            assertNotNull(db.stats());
        }
    }

    @Test
    void dbHubWrapsNamedDatabaseContributions() {
        Database primary = new DatabaseBuilder()
            .config(DatabaseConfig.defaults("jdbc:h2:mem:primary_" + UUID.randomUUID().toString().replace('-', '_') + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .build();
        Database audit = new DatabaseBuilder()
            .config(DatabaseConfig.defaults("jdbc:h2:mem:audit_" + UUID.randomUUID().toString().replace('-', '_') + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .build();

        try {
            Container container = Freeway.create(
                new DbModule(),
                binder -> binder.contribute(DatabaseRegistrations.class).add(new DatabaseEntry("primary", primary)),
                binder -> binder.contribute(DatabaseRegistrations.class).add(new DatabaseEntry("audit", audit))
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
}
