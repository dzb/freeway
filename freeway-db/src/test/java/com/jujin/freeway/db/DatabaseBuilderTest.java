package com.jujin.freeway.db;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseBuilderTest {
    @Test
    void overlaysAnExistingConfig() {
        String dbName = "freeway_builder_overlay_" + UUID.randomUUID().toString().replace('-', '_');
        DatabaseConfig base = new DatabaseConfig(
            "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
            4,
            1,
            Duration.ofSeconds(2),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofSeconds(1),
            null,
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
        );

        Database db = DatabaseBuilder.from(base)
            .maxSize(6)
            .build();

        try (db) {
            assertEquals(6, db.stats().maxSize());
        }
    }

    @Test
    void standaloneBuilderUsesDefaultCoercion() {
        String dbName = "freeway_builder_coercion_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .url("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            .username("sa")
            .password("")
            .build();

        try (db) {
            db.sql("create table t (v decimal(10,0))").execute();
            db.sql("insert into t values (123)").execute();

            Short result = db.sql("select v from t").one(Short.class).orElseThrow();
            assertEquals(Short.valueOf((short) 123), result);
        }
    }

    @Test
    void standaloneBuilderAcceptsManualRowMapper() {
        String dbName = "freeway_builder_mapper_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .url("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            .username("sa")
            .password("")
            .rowMapper(Marker.class, (rs, rowNum) -> new Marker(rs.getString(1)))
            .build();

        try (db) {
            List<Marker> markers = db.sql("select 'manual'").list(Marker.class);
            assertEquals(List.of(new Marker("manual")), markers);
        }
    }

    public record Marker(String value) {
    }
}
