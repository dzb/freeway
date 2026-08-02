package com.jujin.freeway.db;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseResourceLifecycleTest {
    @Test
    void transactionReleasesConnectionAfterCommit() {
        Database db = singleConnectionDb("tx_commit");
        try (db) {
            db.execute("create table t (id int)");

            db.transaction(() -> db.execute("insert into t values (1)"));

            DatabaseStats stats = db.stats();
            assertEquals(0, stats.active());
            assertEquals(1, stats.idle());
            assertEquals(1, stats.total());
            assertEquals(1, db.query("select 1").one(Integer.class).orElseThrow());
        }
    }

    @Test
    void streamInitializationFailureReleasesConnection() {
        Database db = singleConnectionDb("stream_failure");
        try (db) {
            assertThrows(
                SqlException.class,
                () -> db.query("select id from missing_table").stream(Integer.class)
            );

            assertEquals(0, db.stats().active());
            assertEquals(1, db.query("select 1").one(Integer.class).orElseThrow());
        }
    }

    @Test
    void queryCreatedInsideTransactionCannotBeConsumedAfter() {
        Database db = singleConnectionDb("tx_query_after");
        try (db) {
            Query[] holder = new Query[1];
            db.transaction(() -> holder[0] = db.query("select 1"));

            assertThrows(SqlException.class,
                () -> holder[0].one(Integer.class),
                "consuming a transaction-bound Query after commit must fail loudly");
        }
    }

    @Test
    void batchCreatedInsideTransactionCannotBeConsumedAfter() {
        Database db = singleConnectionDb("tx_batch_after");
        try (db) {
            db.execute("create table t (id int)");
            BatchQuery[] holder = new BatchQuery[1];
            db.transaction(() -> holder[0] = db.batch("insert into t values (?)"));

            assertThrows(SqlException.class,
                () -> holder[0].rows(new Object[]{1}).execute(),
                "consuming a transaction-bound BatchQuery after commit must fail loudly");
        }
    }

    @Test
    void queryCreatedAndConsumedInsideTransactionWorks() {
        Database db = singleConnectionDb("tx_query_inside");
        try (db) {
            db.transaction(() ->
                assertEquals(1,
                    db.query("select 1").one(Integer.class).orElseThrow()));
        }
    }

    private static Database singleConnectionDb(String prefix) {
        String dbName = "freeway_resource_" + prefix + "_" + UUID.randomUUID().toString().replace('-', '_');
        PoolConfig defaults = PoolConfig.defaults(
            "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        PoolConfig config = new PoolConfig(
            defaults.url(),
            defaults.username(),
            defaults.password(),
            1,
            0,
            Duration.ofMillis(200),
            defaults.maxLifetime(),
            defaults.maxIdleTime(),
            defaults.cleanInterval(),
            defaults.healthCheckQuery(),
            defaults.healthCheckTimeout(),
            defaults.queryTimeout()
        );
        return new DatabaseBuilder().config(config).build();
    }
}
