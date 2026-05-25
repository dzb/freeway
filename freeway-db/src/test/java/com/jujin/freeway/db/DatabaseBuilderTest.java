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
            .mapping(RowMapping.of(Marker.class, (rs, rowNum) -> new Marker(rs.getString(1))))
            .build();

        try (db) {
            assertEquals(6, db.stats().maxSize());
            assertEquals(List.of(new Marker("overlay")), db.sql("select 'overlay'").list(Marker.class));
        }
    }

    public record Marker(String value) {
    }
}
