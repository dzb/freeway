package com.jujin.freeway.db;
import java.time.Duration;

import com.jujin.freeway.db.PooledConnection;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.db.dialect.H2Dialect;
import com.jujin.freeway.db.dialect.MySqlDialect;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void zeroQueryTimeoutMeansNoTimeout() {
        String dbName = "freeway_builder_qt_" + UUID.randomUUID().toString().replace('-', '_');
        PoolConfig base = PoolConfig.defaults(
            "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa", ""
        );
        // queryTimeout=0 → JDBC setQueryTimeout(0) = no timeout.
        PoolConfig modified = new PoolConfig(
            base.url(), base.username(), base.password(),
            base.maxSize(), base.minIdle(),
            base.connectionTimeout(), base.maxLifetime(), base.maxIdleTime(),
            base.cleanInterval(), base.healthCheckQuery(), base.healthCheckTimeout(),
            Duration.ZERO
        );

        Database db = new DatabaseBuilder().config(modified).build();
        try (db) {
            assertTrue(db.ping(), "zero query timeout must be accepted and usable");
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
    void customCoercerRetainsJdbcDefaultRules() {
        String dbName = "freeway_builder_jdbc_rules_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .config(PoolConfig.defaults(
                "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .coercer(new CoercerDefault())
            .build();

        try (db) {
            db.execute("create table t (ts timestamp)");
            db.execute("insert into t values ('2024-06-15 14:30:00')");

            LocalDateTime value = db.query("select ts from t")
                .one(LocalDateTime.class).orElseThrow();
            assertEquals(LocalDateTime.of(2024, 6, 15, 14, 30, 0), value,
                "a custom CoercerDefault must still get the JDBC Timestamp rule");
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
    void detectsDialectFromJdbcUrl() {
        assertDialectForUrl("jdbc:mysql://localhost:3306/app", "mysql");
        assertDialectForUrl("jdbc:sqlite:/tmp/freeway_builder.db", "sqlite");
        assertDialectForUrl("jdbc:h2:mem:freeway_builder_plain", "h2");
        assertDialectForUrl("jdbc:h2:mem:freeway_builder_mysqlmode;MODE=MySQL", "mysql");
        assertDialectForUrl("jdbc:postgresql://localhost/app", "postgresql");
    }

    @Test
    void explicitDialectOverridesUrlDetection() {
        Database db = new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:mysql://localhost:3306/app", "sa", ""))
            .dialect(new H2Dialect())
            .pool(new StubPool())
            .build();
        try (db) {
            assertEquals("h2", db.dialect().dialectId(),
                "explicit .dialect(...) must win over URL auto-detection");
        }
    }

    private static void assertDialectForUrl(String url, String expectedId) {
        // StubPool: the URL is only a dialect hint here, never a real connection.
        Database db = new DatabaseBuilder()
            .config(PoolConfig.defaults(url, "sa", ""))
            .pool(new StubPool())
            .build();
        try (db) {
            assertEquals(expectedId, db.dialect().dialectId(),
                "URL '" + url + "' must resolve to dialect '" + expectedId + "'");
        }
    }

    // ====================== 修复 1: 未知 JDBC URL 必须显式报错 ======================

    @Test
    void unknownUrlSchemeThrowsWithGuidance() {
        IllegalStateException oracle = assertThrows(IllegalStateException.class,
            () -> DatabaseBuilder.dialectForUrl("jdbc:oracle:thin:@localhost:1521:xe"));
        assertTrue(oracle.getMessage().contains("jdbc:oracle"),
            "message must name the offending URL: " + oracle.getMessage());
        assertTrue(oracle.getMessage().contains("freeway.db.dialect"),
            "message must point at the dialect config key: " + oracle.getMessage());

        IllegalStateException sqlserver = assertThrows(IllegalStateException.class,
            () -> DatabaseBuilder.dialectForUrl("jdbc:sqlserver://localhost:1433;databaseName=app"));
        assertTrue(sqlserver.getMessage().contains("jdbc:sqlserver"),
            "message must name the offending URL: " + sqlserver.getMessage());
    }

    @Test
    void buildWithUnknownUrlThrowsBeforeAnyConnection() {
        DatabaseBuilder builder = new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:oracle:thin:@localhost:1521:xe", "sa", ""))
            .pool(new StubPool());
        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(ex.getMessage().contains("freeway.db.dialect"),
            "build() must surface the guidance: " + ex.getMessage());
    }

    @Test
    void nullAndBlankUrlDefaultToPostgres() {
        assertEquals("postgresql", DatabaseBuilder.dialectForUrl(null).dialectId());
        assertEquals("postgresql", DatabaseBuilder.dialectForUrl("").dialectId());
        assertEquals("postgresql", DatabaseBuilder.dialectForUrl("   ").dialectId());
    }

    @Test
    void h2MemUrlResolvesWithoutThrowing() {
        assertEquals("h2", DatabaseBuilder.dialectForUrl("jdbc:h2:mem:plain").dialectId());
    }

    @Test
    void customSchemeWithExplicitDialectIsUsable() {
        Database db = new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:custom:whatever", "sa", ""))
            .dialect(new MySqlDialect())
            .pool(new StubPool())
            .build();
        try (db) {
            assertEquals("mysql", db.dialect().dialectId(),
                "an explicit dialect must make a custom URL scheme usable");
        }
    }

    // ====================== 修复 2: Sql 经 Database 便利方法时校验方言 ======================

    @Test
    void executeSqlRejectsReturningOnMySqlDialect() {
        Database db = mysqlDb();
        try (db) {
            SqlException ex = assertThrows(SqlException.class,
                () -> db.execute(Sql.insert("users").set("name", "john").returning("id")));
            assertTrue(ex.getMessage().contains("RETURNING"),
                "MySQL must reject RETURNING via execute(Sql): " + ex.getMessage());
        }
    }

    @Test
    void querySqlRejectsReturningOnMySqlDialect() {
        Database db = mysqlDb();
        try (db) {
            SqlException ex = assertThrows(SqlException.class,
                () -> db.query(Sql.insert("users").set("name", "john").returning("id")));
            assertTrue(ex.getMessage().contains("RETURNING"),
                "MySQL must reject RETURNING via query(Sql): " + ex.getMessage());
        }
    }

    @Test
    void executeSqlRejectsOnConflictOnMySqlDialect() {
        Database db = mysqlDb();
        try (db) {
            SqlException ex = assertThrows(SqlException.class,
                () -> db.execute(Sql.insert("users").set("id", 1).onConflict("id").doNothing()));
            assertTrue(ex.getMessage().contains("ON CONFLICT"),
                "MySQL must reject ON CONFLICT via execute(Sql): " + ex.getMessage());
        }
    }

    private static Database mysqlDb() {
        return new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:mysql://localhost:3306/app", "sa", ""))
            .dialect(new MySqlDialect())
            .pool(new StubPool())
            .build();
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
