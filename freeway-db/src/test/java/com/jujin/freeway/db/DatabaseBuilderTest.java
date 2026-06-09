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
        DatabaseConfig base = DatabaseConfig.defaults(
            "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        DatabaseConfig modified = new DatabaseConfig(
            base.url(), base.username(), base.password(),
            6,
            base.minIdle(),
            base.connectionTimeout(), base.maxLifetime(), base.maxIdleTime(),
            base.cleanInterval(), base.healthCheckQuery(), base.healthCheckTimeout(),
            base.queryTimeout()
        );

        Database db = new DatabaseBuilder().config(modified).build();

        try (db) {
            assertEquals(6, db.stats().maxSize());
        }
    }

    @Test
    void standaloneBuilderUsesDefaultCoercion() {
        String dbName = "freeway_builder_coercion_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .config(DatabaseConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .build();

        try (db) {
            db.execute("create table t (v decimal(10,0))");
            db.execute("insert into t values (123)");

            Short result = db.query("select v from t").one(Short.class).orElseThrow();
            assertEquals(Short.valueOf((short) 123), result);
        }
    }

    @Test
    void standaloneBuilderAcceptsManualRowMapper() {
        String dbName = "freeway_builder_mapper_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .config(DatabaseConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .rowMapper(Marker.class, (rs, rowNum) -> new Marker(rs.getString(1)))
            .build();

        try (db) {
            List<Marker> markers = db.query("select 'manual'").list(Marker.class);
            assertEquals(List.of(new Marker("manual")), markers);
        }
    }

    public record Marker(String value) {
    }
}
