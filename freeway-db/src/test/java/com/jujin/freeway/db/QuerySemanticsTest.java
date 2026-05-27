package com.jujin.freeway.db;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuerySemanticsTest {
    @Test
    void collectionExpansionWorksForPositionalParameters() {
        String dbName = "freeway_query_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .url("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            .username("sa")
            .password("")
            .build();

        try (db) {
            Integer value = db.sql("select 1 where 1 in (?)", List.of(1, 2))
                .one(Integer.class)
                .orElseThrow();
            assertEquals(1, value);
        }
    }

    @Test
    void collectionExpansionRejectsTrailingPositionalParameters() {
        String dbName = "freeway_query_tail_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .url("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            .username("sa")
            .password("")
            .build();

        try (db) {
            assertThrows(
                SqlException.class,
                () -> db.sql("select 1 where 1 in (?)", List.of(1, 2), 3)
                    .one(Integer.class)
            );
        }
    }

    @Test
    void collectionExpansionRejectsUnknownNamedParameters() {
        String dbName = "freeway_query_named_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .url("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            .username("sa")
            .password("")
            .build();

        try (db) {
            assertThrows(
                SqlException.class,
                () -> db.sql("select 1 where 1 in ($ids)")
                    .param("ids", List.of(1, 2))
                    .param("extra", 3)
                    .one(Integer.class)
            );
        }
    }
}
