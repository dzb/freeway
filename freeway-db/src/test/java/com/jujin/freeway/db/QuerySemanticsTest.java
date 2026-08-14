package com.jujin.freeway.db;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuerySemanticsTest {
    @Test
    void oneReturnsFirstRowWhenMultipleRowsMatch() {
        // Documented contract (S3): one() returns the FIRST row; a multi-row
        // result is silently truncated. Callers that must detect ambiguity
        // use list() and check the size.
        String dbName = "freeway_query_one_multi_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .build();

        try (db) {
            db.execute("create table multi_row (id int)");
            db.execute("insert into multi_row values (1)");
            db.execute("insert into multi_row values (2)");
            db.execute("insert into multi_row values (3)");

            Integer first = db.query("select id from multi_row order by id")
                .one(Integer.class)
                .orElseThrow();
            assertEquals(1, first, "one() must return the first row, silently truncating the rest");

            List<Integer> all = db.query("select id from multi_row order by id")
                .list(Integer.class);
            assertEquals(3, all.size(), "list() must reveal the multi-row result that one() truncates");
        }
    }

    @Test
    void emptyCollectionExpansionFailsWithGuidance() {
        String dbName = "freeway_query_empty_coll_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .build();

        try (db) {
            SqlException ex = assertThrows(
                SqlException.class,
                () -> db.query("select 1 where 1 in (:ids)")
                    .param("ids", List.of())
                    .one(Integer.class)
            );
            assertTrue(ex.getMessage().contains("empty collection"),
                "message must name the empty collection, got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("conditional branch"),
                "message must point at the conditional-branch workaround, got: " + ex.getMessage());
        }
    }

    @Test
    void emptyArrayExpansionFailsWithGuidance() {
        String dbName = "freeway_query_empty_arr_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .build();

        try (db) {
            SqlException ex = assertThrows(
                SqlException.class,
                () -> db.query("select 1 where 1 in (:ids)")
                    .param("ids", new Object[0])
                    .one(Integer.class)
            );
            assertTrue(ex.getMessage().contains("empty collection"),
                "message must name the empty collection, got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("conditional branch"),
                "message must point at the conditional-branch workaround, got: " + ex.getMessage());
        }
    }

    @Test
    void collectionExpansionWorksForPositionalParameters() {
        String dbName = "freeway_query_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .build();

        try (db) {
            Integer value = db.query("select 1 where 1 in (?)", List.of(1, 2))
                .one(Integer.class)
                .orElseThrow();
            assertEquals(1, value);
        }
    }

    @Test
    void collectionExpansionRejectsTrailingPositionalParameters() {
        String dbName = "freeway_query_tail_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .build();

        try (db) {
            assertThrows(
                SqlException.class,
                () -> db.query("select 1 where 1 in (?)", List.of(1, 2), 3)
                    .one(Integer.class)
            );
        }
    }

    @Test
    void collectionExpansionRejectsUnknownNamedParameters() {
        String dbName = "freeway_query_named_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .build();

        try (db) {
            assertThrows(
                SqlException.class,
                () -> db.query("select 1 where 1 in ($ids)")
                    .param("ids", List.of(1, 2))
                    .param("extra", 3)
                    .one(Integer.class)
            );
        }
    }

    @Test
    void positionalQuestionMarksInsideStringsAndCommentsAreIgnored() {
        String dbName = "freeway_query_literal_q_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .build();

        try (db) {
            String value = db.query(
                    "select '?' where 1 = ? -- ? ignored\n/* ? ignored */",
                    1
                )
                .one(String.class)
                .orElseThrow();
            assertEquals("?", value);
        }
    }
}
