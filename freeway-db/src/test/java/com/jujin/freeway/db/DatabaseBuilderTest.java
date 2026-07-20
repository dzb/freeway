package com.jujin.freeway.db;

import com.jujin.freeway.db.PooledConnection;
import com.jujin.freeway.db.schema.MySqlDialect;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DatabaseBuilderTest {
    @Test
    void overlaysAnExistingConfig() {
        String dbName = "freeway_builder_overlay_" + UUID.randomUUID().toString().replace('-', '_');
        PoolConfig base = PoolConfig.defaults(
            "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        PoolConfig modified = new PoolConfig(
            base.url(), base.username(), base.password(),
            6, base.minIdle(),
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
            .config(PoolConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
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
            .config(PoolConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .rowMapper(Marker.class, (rs, rowNum) -> new Marker(rs.getString(1)))
            .build();

        try (db) {
            List<Marker> markers = db.query("select 'manual'").list(Marker.class);
            assertEquals(List.of(new Marker("manual")), markers);
        }
    }

    @Test
    void customDialectIsUsed() {
        String dbName = "freeway_builder_dialect_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .dialect(new MySqlDialect())
            .build();
        try (db) {
            assertEquals("mysql", db.dialect().dialectId());
            assertFalse(db.dialect().supportsReturning());
        }
    }

    @Test
    void customPoolIsUsed() {
        String dbName = "freeway_builder_pool_" + UUID.randomUUID().toString().replace('-', '_');
        PoolConfig config = PoolConfig.defaults(
            "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""
        );

        Database db = new DatabaseBuilder()
            .config(config)
            .pool(new StubPool())
            .build();

        try (db) {
            assertEquals(Integer.MAX_VALUE, db.stats().maxSize());
        }
    }

    public record Marker(String value) {
    }

    private static final class StubPool implements Pool {
        @Override
        public PooledConnection borrow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release(PooledConnection conn) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DatabaseStats stats() {
            return new DatabaseStats(0, 0, 0, 0, Integer.MAX_VALUE, 0, 0, 0);
        }

        @Override
        public void close() {}
    }
}
