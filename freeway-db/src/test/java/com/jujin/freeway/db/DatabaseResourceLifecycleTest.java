package com.jujin.freeway.db;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void errorInsideTransactionRollsBack() {
        Database db = singleConnectionDb("tx_error_rollback");
        try (db) {
            db.execute("create table t (id int)");

            assertThrows(AssertionError.class, () -> db.transaction(() -> {
                db.execute("insert into t values (1)");
                throw new AssertionError("boom");
            }));

            // An Error from the work must roll back, not silently commit
            // (setAutoCommit(true) during state restore would commit).
            assertEquals(0L,
                db.query("select count(*) from t").one(Long.class).orElseThrow());
        }
    }

    @Test
    void dbWorkFromChildThreadInsideTransactionIsRejected() throws Exception {
        Database db = singleConnectionDb("tx_child_thread");
        try (db) {
            db.execute("create table t (id int)");

            AtomicReference<Throwable> childError = new AtomicReference<>();
            SqlException ex = assertThrows(SqlException.class, () -> db.transaction(() -> {
                db.execute("insert into t values (1)");
                Thread child = Thread.ofVirtual().start(() -> {
                    try {
                        // ScopedValue does not propagate — without the guard
                        // this would borrow an independent connection and
                        // commit outside the transaction.
                        db.execute("insert into t values (2)");
                    } catch (Throwable t) {
                        childError.set(t);
                    }
                });
                child.join();
                if (childError.get() == null) {
                    throw new AssertionError("child-thread DB work was not rejected");
                }
                if (childError.get() instanceof RuntimeException re) throw re;
                throw new RuntimeException(childError.get());
            }));

            assertTrue(ex.getMessage().contains("transaction thread"),
                "message must explain the ScopedValue limitation: " + ex.getMessage());

            // The parent's insert rolled back and the child never wrote:
            assertEquals(0L, db.query("select count(*) from t").one(Long.class).orElseThrow());
        }
    }

    @Test
    void transactionWorkOnSameThreadIsNotBlocked() {
        Database db = singleConnectionDb("tx_same_thread");
        try (db) {
            db.execute("create table t (id int)");

            db.transaction(() -> {
                db.execute("insert into t values (1)");
                db.batch("insert into t values (?)").rows(new Object[]{2}).execute();
                assertEquals(2L, db.query("select count(*) from t").one(Long.class).orElseThrow());
            });

            assertEquals(2L, db.query("select count(*) from t").one(Long.class).orElseThrow());
        }
    }

    @Test
    void concurrentTransactionDoesNotInvalidateInFlightQuery() throws Exception {
        Database db = twoConnectionDb("tx_concurrent");
        try (db) {
            db.execute("create table t (id int)");
            db.execute("insert into t values (1)");

            CountDownLatch queryCreated = new CountDownLatch(1);
            CountDownLatch otherDone = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread a = Thread.ofVirtual().start(() -> {
                try {
                    db.transaction(() -> {
                        Query q = db.query("select id from t where id = 1");
                        queryCreated.countDown();
                        try {
                            otherDone.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        assertEquals(1, q.one(Integer.class).orElseThrow());
                    });
                } catch (Throwable t) {
                    error.set(t);
                }
            });

            assertTrue(queryCreated.await(10, TimeUnit.SECONDS));
            // A second transaction finishing must not invalidate A's
            // in-flight query (regression: global epoch counter did).
            db.transaction(() -> db.execute("insert into t values (2)"));
            otherDone.countDown();
            a.join(10_000);

            assertNull(error.get(),
                () -> "concurrent transaction invalidated an in-flight query: "
                    + error.get());
        }
    }

    @Test
    void transactionOnOneDatabaseDoesNotLeakIntoAnother() {
        Database db1 = singleConnectionDb("tx_cross_a");
        Database db2 = singleConnectionDb("tx_cross_b");
        try (db1; db2) {
            db1.execute("create table ta (id int)");
            db2.execute("create table tb (id int)");
            db2.execute("insert into tb values (7)");

            db1.transaction(() -> {
                // Must run on db2's OWN connection; the static TX_CONN bug
                // made this prepare on db1's transaction connection and fail.
                assertEquals(7,
                    db2.query("select id from tb").one(Integer.class).orElseThrow());
            });

            assertEquals(0L,
                db1.query("select count(*) from ta").one(Long.class).orElseThrow());
        }
    }

    @Test
    void streamMappingFailureReleasesConnection() {
        Database db = singleConnectionDb("stream_map_fail");
        try (db) {
            db.execute("create table t (v varchar(10))");
            db.execute("insert into t values ('abc')");

            var stream = db.query("select v from t").stream(Integer.class);
            // Mapping 'abc' to Integer throws inside tryAdvance; the
            // connection must be released even without try-with-resources.
            assertThrows(RuntimeException.class, stream::findFirst);

            assertEquals(0, db.stats().active());
            assertEquals(1, db.query("select 1").one(Integer.class).orElseThrow());
        }
    }

    @Test
    void primitiveArrayParameterExpands() {
        Database db = singleConnectionDb("int_array_param");
        try (db) {
            db.execute("create table t (id int)");
            db.execute("insert into t values (1)");
            db.execute("insert into t values (2)");
            db.execute("insert into t values (3)");

            assertEquals(List.of(1, 3),
                db.query("select id from t where id in (?)", new int[]{1, 3})
                    .list(Integer.class));
        }
    }

    private static Database singleConnectionDb(String prefix) {
        return dbWithMaxSize(prefix, 1);
    }

    private static Database twoConnectionDb(String prefix) {
        return dbWithMaxSize(prefix, 2);
    }

    private static Database dbWithMaxSize(String prefix, int maxSize) {
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
            maxSize,
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
