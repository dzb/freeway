package com.jujin.freeway.db.hikari;

import com.jujin.freeway.db.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HikariPoolIntegrationTest {

    @Test
    void pingAndStatsReflectHikariPool() {
        String dbName = "freeway_hikari_" + UUID.randomUUID().toString().replace('-', '_');
        PoolConfig config = PoolConfig.defaults(
            "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""
        );

        HikariPool pool = new HikariPool(config);
        Database db = new DatabaseBuilder().config(config).pool(pool).build();

        try (db) {
            assertTrue(db.ping(), "ping should succeed with HikariCP");

            DatabaseStats stats = db.stats();
            assertEquals(config.maxSize(), stats.maxSize(),
                "maxSize should match config from HikariCP");

            assertTrue(stats.idle() >= 0, "idle connections reported by HikariCP");
            assertEquals(0, stats.active(),
                "no active connections after ping returns to pool");
        }
    }

    @Test
    void executeAndQueryWorkThroughHikariCP() {
        String dbName = "freeway_hikari_crud_" + UUID.randomUUID().toString().replace('-', '_');
        PoolConfig config = PoolConfig.defaults(
            "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""
        );

        HikariPool pool = new HikariPool(config);
        Database db = new DatabaseBuilder().config(config).pool(pool).build();

        try (db) {
            db.execute("create table items (id int primary key, name varchar(50))");
            db.execute("insert into items values (?, ?)", 1, "alpha");
            db.execute("insert into items values (?, ?)", 2, "beta");

            String name = db.query("select name from items where id = ?", 1)
                .one(String.class).orElseThrow();
            assertEquals("alpha", name);

            long count = db.query("select count(*) from items").one(Long.class).orElseThrow();
            assertEquals(2L, count);

            DatabaseStats stats = db.stats();
            assertEquals(0, stats.active(), "no active connections after queries");
        }
    }

    @Test
    void customMaxSizeIsReflectedInStats() {
        String dbName = "freeway_hikari_max_" + UUID.randomUUID().toString().replace('-', '_');
        PoolConfig config = new PoolConfig(
            "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa", "",
            7,
            2,
            Duration.ofSeconds(30),
            Duration.ofMinutes(30),
            Duration.ofMinutes(10),
            Duration.ofSeconds(30),
            null,
            Duration.ofSeconds(5),
            Duration.ofSeconds(30)
        );

        HikariPool pool = new HikariPool(config);
        Database db = new DatabaseBuilder().config(config).pool(pool).build();

        try (db) {
            DatabaseStats stats = db.stats();
            assertEquals(7, stats.maxSize(), "custom maxSize from HikariCP config");
        }
    }
}
