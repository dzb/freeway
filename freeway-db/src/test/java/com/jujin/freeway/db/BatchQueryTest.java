package com.jujin.freeway.db;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BatchQueryTest {

    @Test
    void batchInsertWithPositionalParams() {
        String dbName = uniqueDb("batch_pos");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key, label varchar(16))");

            var results = db.batch("insert into t (id, label) values (?, ?)")
                .rows(
                    new Object[]{1L, "a"},
                    new Object[]{2L, "b"},
                    new Object[]{3L, "c"}
                )
                .execute();
            assertEquals(3, results.size());
            for (var r : results) {
                assertEquals(1, r.rows());
            }

            long total = db.query("select count(*) from t").one(Long.class).orElseThrow();
            assertEquals(3L, total);
        }
    }

    @Test
    void batchInsertWithPositionalParamsRejectsShortRows() {
        String dbName = uniqueDb("batch_pos_short");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key, label varchar(16))");

            assertThrows(SqlException.class, () ->
                db.batch("insert into t (id, label) values (?, ?)")
                    .rows(
                        new Object[]{1L, "a"},
                        new Object[]{2L}
                    )
                    .execute()
            );

            long total = db.query("select count(*) from t").one(Long.class).orElseThrow();
            assertEquals(0L, total);
        }
    }

    @Test
    void batchInsertWithNamedParams() {
        String dbName = uniqueDb("batch_named");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key, label varchar(16))");

            var results = db.batch("insert into t (id, label) values ($id, $label)")
                .named(List.of(
                    java.util.Map.of("id", 10L, "label", "x"),
                    java.util.Map.of("id", 20L, "label", "y")
                ))
                .execute();
            assertEquals(2, results.size());
            for (var r : results) {
                assertEquals(1, r.rows());
            }

            List<Long> ids = db.query("select id from t order by id").list(Long.class);
            assertEquals(List.of(10L, 20L), ids);
        }
    }

    @Test
    void batchUpdate() {
        String dbName = uniqueDb("batch_update");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key, val int)");
            db.execute("insert into t values (1, 10), (2, 20)");

            var results = db.batch("update t set val = ? where id = ?")
                .rows(
                    new Object[]{100, 1L},
                    new Object[]{200, 2L}
                )
                .execute();
            assertEquals(2, results.size());
            for (var r : results) {
                assertEquals(1, r.rows());
                assertFalse(r.hasKey());
            }

            assertEquals(100, (int) db.query("select val from t where id = 1").one(Integer.class).orElseThrow());
            assertEquals(200, (int) db.query("select val from t where id = 2").one(Integer.class).orElseThrow());
        }
    }

    @Test
    void batchRollsBackWhenOwnTransactionFails() {
        String dbName = uniqueDb("batch_rollback");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key, label varchar(16))");

            assertThrows(SqlException.class, () ->
                db.batch("insert into t (id, label) values (?, ?)")
                    .rows(
                        new Object[]{1L, "a"},
                        new Object[]{1L, "dup"}
                    )
                    .execute()
            );

            long count = db.query("select count(*) from t").one(Long.class).orElseThrow();
            assertEquals(0L, count);
        }
    }

    @Test
    void batchWithCollectionExpansion() {
        String dbName = uniqueDb("batch_coll");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key)");

            var results = db.batch("insert into t (id) values (?)")
                .rows(
                    new Object[]{1L},
                    new Object[]{2L},
                    new Object[]{3L}
                )
                .execute();
            assertEquals(3, results.size());
            for (var r : results) {
                assertEquals(1, r.rows());
            }

            List<Long> ids = db.query("select id from t order by id").list(Long.class);
            assertEquals(List.of(1L, 2L, 3L), ids);
        }
    }

    @Test
    void batchWithZeroRows() {
        String dbName = uniqueDb("batch_zero");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key)");

            var results = db.batch("insert into t (id) values (?)")
                .rows()
                .execute();
            assertEquals(0, results.size());
        }
    }

    @Test
    void batchInsertReturnsAutoIncrementIds() {
        String dbName = uniqueDb("batch_ai");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint generated by default as identity primary key, label varchar(16))");

            var results = db.batch("insert into t (label) values (?)")
                    .rows(
                            new Object[]{"alpha"},
                            new Object[]{"beta"},
                            new Object[]{"gamma"}
                    )
                    .execute();

            assertEquals(3, results.size());
            for (var r : results) {
                assertEquals(1, r.rows());
                assertTrue(r.hasKey());
                assertTrue(r.longKey() > 0);
            }
            // IDs should be sequential
            assertEquals(results.get(0).longKey() + 1, results.get(1).longKey());
            assertEquals(results.get(1).longKey() + 1, results.get(2).longKey());

            List<Long> ids = db.query("select id from t order by id").list(Long.class);
            assertEquals(List.of(results.get(0).longKey(), results.get(1).longKey(), results.get(2).longKey()), ids);
        }
    }

    // ====================== 辅助 ======================

    private static DatabaseBuilder builder(String name) {
        return new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    private static String uniqueDb(String prefix) {
        return "freeway_batch_" + prefix + "_" + UUID.randomUUID().toString().replace('-', '_');
    }
}
