package com.jujin.freeway.db;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BatchQueryTest {

    @Test
    void batchInsertWithPositionalParams() {
        String dbName = uniqueDb("batch_pos");
        Database db = builder(dbName).build();
        try (db) {
            db.sql("create table t (id bigint primary key, label varchar(16))").execute();

            int[] counts = db.batch("insert into t (id, label) values (?, ?)")
                .rows(
                    new Object[]{1L, "a"},
                    new Object[]{2L, "b"},
                    new Object[]{3L, "c"}
                )
                .execute();
            assertArrayEquals(new int[]{1, 1, 1}, counts);

            long total = db.sql("select count(*) from t").one(Long.class).orElseThrow();
            assertEquals(3L, total);
        }
    }

    @Test
    void batchInsertWithNamedParams() {
        String dbName = uniqueDb("batch_named");
        Database db = builder(dbName).build();
        try (db) {
            db.sql("create table t (id bigint primary key, label varchar(16))").execute();

            int[] counts = db.batch("insert into t (id, label) values ($id, $label)")
                .named(List.of(
                    java.util.Map.of("id", 10L, "label", "x"),
                    java.util.Map.of("id", 20L, "label", "y")
                ))
                .execute();
            assertArrayEquals(new int[]{1, 1}, counts);

            List<Long> ids = db.sql("select id from t order by id").list(Long.class);
            assertEquals(List.of(10L, 20L), ids);
        }
    }

    @Test
    void batchUpdate() {
        String dbName = uniqueDb("batch_update");
        Database db = builder(dbName).build();
        try (db) {
            db.sql("create table t (id bigint primary key, val int)").execute();
            db.sql("insert into t values (1, 10), (2, 20)").execute();

            int[] counts = db.batch("update t set val = ? where id = ?")
                .rows(
                    new Object[]{100, 1L},
                    new Object[]{200, 2L}
                )
                .execute();
            assertArrayEquals(new int[]{1, 1}, counts);

            assertEquals(100, (int) db.sql("select val from t where id = 1").one(Integer.class).orElseThrow());
            assertEquals(200, (int) db.sql("select val from t where id = 2").one(Integer.class).orElseThrow());
        }
    }

    @Test
    void batchRollsBackWhenOwnTransactionFails() {
        String dbName = uniqueDb("batch_rollback");
        Database db = builder(dbName).build();
        try (db) {
            db.sql("create table t (id bigint primary key, label varchar(16))").execute();

            assertThrows(SqlException.class, () ->
                db.batch("insert into t (id, label) values (?, ?)")
                    .rows(
                        new Object[]{1L, "a"},
                        new Object[]{1L, "dup"}
                    )
                    .execute()
            );

            long count = db.sql("select count(*) from t").one(Long.class).orElseThrow();
            assertEquals(0L, count);
        }
    }

    @Test
    void batchWithCollectionExpansion() {
        String dbName = uniqueDb("batch_coll");
        Database db = builder(dbName).build();
        try (db) {
            db.sql("create table t (id bigint primary key)").execute();

            int[] counts = db.batch("insert into t (id) values (?)")
                .rows(
                    new Object[]{1L},
                    new Object[]{2L},
                    new Object[]{3L}
                )
                .execute();
            assertArrayEquals(new int[]{1, 1, 1}, counts);

            List<Long> ids = db.sql("select id from t order by id").list(Long.class);
            assertEquals(List.of(1L, 2L, 3L), ids);
        }
    }

    @Test
    void batchWithZeroRows() {
        String dbName = uniqueDb("batch_zero");
        Database db = builder(dbName).build();
        try (db) {
            db.sql("create table t (id bigint primary key)").execute();

            int[] counts = db.batch("insert into t (id) values (?)")
                .rows()
                .execute();
            assertEquals(0, counts.length);
        }
    }

    // ====================== 辅助 ======================

    private static DatabaseBuilder builder(String name) {
        return new DatabaseBuilder()
            .url("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            .username("sa")
            .password("");
    }

    private static String uniqueDb(String prefix) {
        return "freeway_batch_" + prefix + "_" + UUID.randomUUID().toString().replace('-', '_');
    }
}
