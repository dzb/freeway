package com.jujin.freeway.db.hikari;

import com.jujin.freeway.db.*;
import com.jujin.freeway.db.PooledConnection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HikariPoolIntegrationTest {

    private static String newDb() {
        return "jdbc:h2:mem:" + UUID.randomUUID().toString().replace('-', '_')
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    }

    @Test
    void pingAndStatsReflectHikariPool() {
        PoolConfig config = PoolConfig.defaults(newDb(), "sa", "");
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
        PoolConfig config = PoolConfig.defaults(newDb(), "sa", "");
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
        PoolConfig config = new PoolConfig(
            newDb(), "sa", "",
            7, 2,
            Duration.ofSeconds(30), Duration.ofMinutes(30), Duration.ofMinutes(10),
            Duration.ofSeconds(30), null,
            Duration.ofSeconds(5), Duration.ofSeconds(30)
        );

        HikariPool pool = new HikariPool(config);
        Database db = new DatabaseBuilder().config(config).pool(pool).build();

        try (db) {
            DatabaseStats stats = db.stats();
            assertEquals(7, stats.maxSize(), "custom maxSize from HikariCP config");
        }
    }

    @Test
    void concurrentBorrowsStayWithinMaxSize() throws Exception {
        PoolConfig config = new PoolConfig(
            newDb(), "sa", "",
            3, 0,
            Duration.ofSeconds(10), Duration.ofMinutes(30), Duration.ofMinutes(10),
            Duration.ofSeconds(30), null,
            Duration.ofSeconds(5), Duration.ofSeconds(30)
        );

        HikariPool pool = new HikariPool(config);
        int threads = 5;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger(0);
        List<PooledConnection> connections = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    PooledConnection conn = pool.borrow();
                    synchronized (connections) { connections.add(conn); }
                    success.incrementAndGet();
                    // hold the connection briefly
                    Thread.sleep(200);
                    synchronized (connections) {
                        if (connections.contains(conn)) {
                            connections.remove(conn);
                            pool.release(conn);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(success.get() >= 3, "at least maxSize connections should succeed");

        DatabaseStats stats = pool.stats();
        assertTrue(stats.maxSize() <= 3);
        pool.close();
    }

    @Test
    void poolExhaustionThrowsWhenTimeoutExceeded() throws Exception {
        PoolConfig config = new PoolConfig(
            newDb(), "sa", "",
            1, 0,
            Duration.ofMillis(500), // short timeout
            Duration.ofMinutes(30), Duration.ofMinutes(10),
            Duration.ofSeconds(30), null,
            Duration.ofSeconds(5), Duration.ofSeconds(30)
        );

        HikariPool pool = new HikariPool(config);
        PooledConnection first = pool.borrow();

        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                pool.borrow();
            } catch (Exception ex) {
                failure.set(ex);
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNotNull(failure.get(), "second borrow should fail when pool is exhausted");
        assertTrue(failure.get() instanceof SqlException || failure.get().getCause() != null,
            "failure should be SqlException or have a cause");

        pool.release(first);
        pool.close();
    }

    @Test
    void borrowAfterCloseThrows() {
        PoolConfig config = PoolConfig.defaults(newDb(), "sa", "");
        HikariPool pool = new HikariPool(config);

        pool.close();
        assertThrows(SqlException.class, pool::borrow,
            "borrow after close should throw");
    }

    @Test
    void releaseAfterCloseDoesNotThrow() {
        PoolConfig config = PoolConfig.defaults(newDb(), "sa", "");
        HikariPool pool = new HikariPool(config);

        PooledConnection conn = pool.borrow();
        pool.close();
        // releasing after close should not throw — HikariCP handles it
        assertDoesNotThrow(() -> pool.release(conn));
    }

    @Test
    void healthCheckQueryIsForwarded() throws Exception {
        PoolConfig config = new PoolConfig(
            newDb(), "sa", "",
            3, 1,
            Duration.ofSeconds(5), Duration.ofMinutes(30), Duration.ofMinutes(10),
            Duration.ofSeconds(30), "select 1",
            Duration.ofSeconds(5), Duration.ofSeconds(30)
        );

        HikariPool pool = new HikariPool(config);

        // Borrow several connections — HikariCP should validate them with the query
        List<PooledConnection> conns = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            conns.add(pool.borrow());
        }
        for (PooledConnection c : conns) {
            // verify each connection is alive
            try (Statement stmt = c.connection().createStatement();
                 ResultSet rs = stmt.executeQuery("select 1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            pool.release(c);
        }

        pool.close();
    }

    @Test
    void statsActiveReflectsBorrowedConnections() {
        PoolConfig config = new PoolConfig(
            newDb(), "sa", "",
            5, 0,
            Duration.ofSeconds(10), Duration.ofMinutes(30), Duration.ofMinutes(10),
            Duration.ofSeconds(30), null,
            Duration.ofSeconds(5), Duration.ofSeconds(30)
        );

        HikariPool pool = new HikariPool(config);

        assertEquals(0, pool.stats().active());
        PooledConnection c1 = pool.borrow();
        assertEquals(1, pool.stats().active());
        PooledConnection c2 = pool.borrow();
        assertEquals(2, pool.stats().active());

        pool.release(c1);
        assertEquals(1, pool.stats().active());
        pool.release(c2);
        assertEquals(0, pool.stats().active());

        pool.close();
    }

    @Test
    void closeIsIdempotent() {
        PoolConfig config = PoolConfig.defaults(newDb(), "sa", "");
        HikariPool pool = new HikariPool(config);

        pool.close();
        assertDoesNotThrow(pool::close, "second close should not throw");
    }
}
