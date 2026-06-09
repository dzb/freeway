package com.jujin.freeway.db;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 命名参数解析和混合参数使用的边缘情况测试。
 */
class NamedParamEdgeCaseTest {

    @Test
    void namedParameters() {
        String dbName = uniqueDb("named");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key, name varchar(16))");
            db.execute("insert into t values (1, 'alpha'), (2, 'beta')");

            List<NameEntry> results = db.query("select id, name from t where id = $id")
                .param("id", 1L)
                .list(NameEntry.class);
            assertEquals(1, results.size());
            assertEquals(1L, results.get(0).id());
            assertEquals("alpha", results.get(0).name());
        }
    }

    @Test
    void namedParametersWithCollectionExpansion() {
        String dbName = uniqueDb("named_coll");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key, name varchar(16))");
            db.execute("insert into t values (1, 'a'), (2, 'b'), (3, 'c')");

            List<NameEntry> results = db.query("select id, name from t where id in ($ids) order by id")
                .param("ids", List.of(1L, 3L))
                .list(NameEntry.class);
            assertEquals(2, results.size());
            assertEquals(1L, results.get(0).id());
            assertEquals(3L, results.get(1).id());
        }
    }

    @Test
    void namedCollectionExpansionIgnoresQuestionMarksInStringsAndComments() {
        String dbName = uniqueDb("named_coll_question");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key, name varchar(16))");
            db.execute("insert into t values (1, 'a'), (2, 'b'), (3, 'c')");

            List<NameEntry> results = db.query("""
                select id, name from t
                where '?' = '?'
                  and id in ($ids) -- ? ignored
                  and name <> :excluded
                order by id
                """)
                .param("ids", List.of(1L, 2L, 3L))
                .param("excluded", "b")
                .list(NameEntry.class);

            assertEquals(List.of(
                new NameEntry(1L, "a"),
                new NameEntry(3L, "c")
            ), results);
        }
    }

    @Test
    void namedParametersUsedMultipleTimes() {
        String dbName = uniqueDb("named_multi");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (x bigint, y bigint)");
            db.execute("insert into t values (10, 20), (10, 30)");

            // $min 被多次使用
            List<Pair> results = db.query("select x, y from t where x >= $min and y >= $min order by y")
                .param("min", 10L)
                .list(Pair.class);
            assertEquals(2, results.size());
            assertEquals(10L, results.get(0).x());
            assertEquals(20L, results.get(0).y());
        }
    }

    @Test
    void namedParameterRejectsMissingKeys() {
        String dbName = uniqueDb("named_missing");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint)");

            assertThrows(SqlException.class,
                () -> db.query("select id from t where id = $missing").list(Long.class));
        }
    }

    @Test
    void namedParameterRejectsExtraKeys() {
        String dbName = uniqueDb("named_extra");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint)");
            db.execute("insert into t values (1)");

            assertThrows(SqlException.class,
                () -> db.query("select id from t where id = $id")
                    .param("id", 1L)
                    .param("extra", "x")
                    .one(Long.class));
        }
    }

    @Test
    void sqlWithStringLiteralContainingDollar() {
        String dbName = uniqueDb("named_literal");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint, label varchar(32))");
            db.execute("insert into t values (1, 'a$b')");

            // $ 号在字符串字面量中，不应被解析为命名参数
            List<NameEntry> results = db.query("select id, label as name from t where label = '$literal'")
                .list(NameEntry.class);
            assertEquals(0, results.size());

            // 用实际值查
            List<NameEntry> actual = db.query("select id, label as name from t where label = ?", "a$b")
                .list(NameEntry.class);
            assertEquals(1, actual.size());
            assertEquals("a$b", actual.get(0).name());
        }
    }

    @Test
    void namedParametersInBatch() {
        String dbName = uniqueDb("named_batch");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key, label varchar(16))");

            int[] counts = db.batch("insert into t (id, label) values ($id, $label)")
                .named(List.of(
                    Map.of("id", 1L, "label", "a"),
                    Map.of("id", 2L, "label", "b")
                ))
                .execute();
            assertArrayEquals(new int[]{1, 1}, counts);

            List<NameEntry> rows = db.query("select id, label as name from t order by id")
                .list(NameEntry.class);
            assertEquals(2, rows.size());
            assertEquals(new NameEntry(1L, "a"), rows.get(0));
            assertEquals(new NameEntry(2L, "b"), rows.get(1));
        }
    }

    @Test
    void namedBatchRejectsMissingKeys() {
        String dbName = uniqueDb("named_batch_missing");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint, label varchar(16))");

            assertThrows(SqlException.class,
                () -> db.batch("insert into t values ($id, $label)")
                    .named(List.of(Map.of("id", 1L)))
                    .execute());
        }
    }

    @Test
    void mixedPositionalAndNamedRejected() {
        String dbName = uniqueDb("mixed_reject");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint)");

            assertThrows(SqlException.class,
                () -> db.query("select id from t where id = $id", 1L)
                    .param("id", 1L));
        }
    }

    // ====================== 辅助 ======================

    private static DatabaseBuilder builder(String name) {
        return new DatabaseBuilder()
            .config(DatabaseConfig.defaults("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    private static String uniqueDb(String prefix) {
        return "freeway_named_" + prefix + "_" + UUID.randomUUID().toString().replace('-', '_');
    }

    public record NameEntry(long id, String name) {
    }

    public record Pair(long x, long y) {
    }
}
