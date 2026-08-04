package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.PooledConnection;

import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.SqlException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接池泄露检测集成测试 — 验证 active 追踪和 stats 上报。
 */
class PoolDefaultTest {

    @Test
    void idleCleanupDoesNotDropH2InMemoryDatabase() throws Exception {
        String dbName = "freeway_pool_idle_cleanup_"
            + UUID.randomUUID().toString().replace('-', '_');
        // No DB_CLOSE_DELAY: H2 drops the in-memory database when the last
        // connection closes. The pool must never transiently drop to zero
        // connections during idle cleanup.
        var config = new PoolConfig(
            "jdbc:h2:mem:" + dbName, "sa", "",
            4, 1,
            Duration.ofSeconds(5),
            Duration.ofMinutes(30),
            Duration.ofMillis(100), // maxIdleTime — expire quickly
            Duration.ofMillis(50),  // cleanInterval
            null,
            Duration.ofSeconds(5),
            Duration.ofSeconds(15)
        );
        PoolDefault pool = new PoolDefault(config);
        try {
            PooledConnection setup = pool.borrow();
            try (var stmt = setup.connection().createStatement()) {
                stmt.execute("create table t (id int)");
                stmt.execute("insert into t values (1)");
            }
            pool.release(setup);

            // Let the cleaner evict the idle connection and refill several
            // times. Before the fix this dropped the H2 in-memory database.
            Thread.sleep(600);

            PooledConnection check = pool.borrow();
            try (var stmt = check.connection().createStatement();
                 var rs = stmt.executeQuery("select count(*) from t")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1),
                    "idle cleanup must not drop the H2 in-memory database");
            }
            pool.release(check);
        } finally {
            pool.close();
        }
    }

    @Test
    void physicallyClosedConnectionIsNotReused() throws Exception {
        var config = new PoolConfig(
            "jdbc:h2:mem:pool_closed_reuse;DB_CLOSE_DELAY=-1", "sa", "",
            4, 0,
            Duration.ofSeconds(5),
            Duration.ofMinutes(30),
            Duration.ofMinutes(5),
            Duration.ofSeconds(30),
            null,
            Duration.ofSeconds(5),
            Duration.ofSeconds(15)
        );
        PoolDefault pool = new PoolDefault(config);
        try {
            // Simulate an out-of-band physical close (DB restart, driver reset,
            // restoreConnectionState failure) followed by a release.
            PooledConnection first = pool.borrow();
            first.connection().close();
            pool.release(first);

            // The next borrow must not hand out the dead connection — the
            // pool must replace it with a healthy one.
            PooledConnection second = pool.borrow();
            try (var stmt = second.connection().createStatement()) {
                stmt.execute("SELECT 1");
            }
            pool.release(second);
        } finally {
            pool.close();
        }
    }

    @Test
    void statsShowsBorrowedConnectionInActive() {
        var config = new PoolConfig(
            "jdbc:h2:mem:leak_test_1;DB_CLOSE_DELAY=-1", "sa", "",
            4, 0,
            Duration.ofSeconds(5),
            Duration.ofMinutes(30),
            Duration.ofMinutes(5),
            Duration.ofSeconds(30),
            null,
            Duration.ofSeconds(3), PoolConfig.DEFAULT_QUERY_TIMEOUT
        );
        var pool = new PoolDefault(config);

        // 借出前：longLeased = 0
        DatabaseStats before = pool.stats();
        assertEquals(0, before.active());

        PooledConnection conn = pool.borrow();
        // 借出后：active 增加，longLeased 追踪中
        DatabaseStats after = pool.stats();

        assertEquals(1, after.active());
        assertNotNull(((PooledConnectionDefault) conn).borrowedAt());

        pool.release(conn);
        // 归还后：active 归零，borrowedAt 已清除
        DatabaseStats released = pool.stats();
        assertEquals(0, released.active());
        assertNull(((PooledConnectionDefault) conn).borrowedAt());

        pool.close();
    }

    @Test
    void statsLongLeasedReportsZeroForQuickOperations() {
        var config = new PoolConfig(
            "jdbc:h2:mem:leak_test_2;DB_CLOSE_DELAY=-1", "sa", "",
            4, 0,
            Duration.ofSeconds(5),
            Duration.ofMinutes(30),
            Duration.ofMinutes(5),
            Duration.ofSeconds(30),
            null,
            Duration.ofSeconds(3), PoolConfig.DEFAULT_QUERY_TIMEOUT
        );
        var pool = new PoolDefault(config);

        PooledConnection conn = pool.borrow();
        // 刚借出，30s 阈值不可能触发
        assertEquals(0, pool.stats().longLeased());

        pool.release(conn);
        // 归还后也为 0
        assertEquals(0, pool.stats().longLeased());

        pool.close();
    }

    @Test
    void multipleBorrowsTrackedIndividually() {
        var config = new PoolConfig(
            "jdbc:h2:mem:leak_test_3;DB_CLOSE_DELAY=-1", "sa", "",
            4, 0,
            Duration.ofSeconds(5),
            Duration.ofMinutes(30),
            Duration.ofMinutes(5),
            Duration.ofSeconds(30),
            null,
            Duration.ofSeconds(3), PoolConfig.DEFAULT_QUERY_TIMEOUT
        );
        var pool = new PoolDefault(config);

        PooledConnection c1 = pool.borrow();
        PooledConnection c2 = pool.borrow();

        assertEquals(2, pool.stats().active());
        assertNotNull(((PooledConnectionDefault) c1).borrowedAt());
        assertNotNull(((PooledConnectionDefault) c2).borrowedAt());

        pool.release(c1);
        assertEquals(1, pool.stats().active());
        assertNull(((PooledConnectionDefault) c1).borrowedAt());
        assertNotNull(((PooledConnectionDefault) c2).borrowedAt());

        pool.release(c2);
        assertEquals(0, pool.stats().active());
        assertNull(((PooledConnectionDefault) c2).borrowedAt());

        pool.close();
    }

    @Test
    void statsTrackBorrowWaitTime() throws Exception {
        var config = new PoolConfig(
            "jdbc:h2:mem:leak_test_4;DB_CLOSE_DELAY=-1", "sa", "",
            1, 0,
            Duration.ofSeconds(5),
            Duration.ofMinutes(30),
            Duration.ofMinutes(5),
            Duration.ofSeconds(30),
            null,
            Duration.ofSeconds(3), PoolConfig.DEFAULT_QUERY_TIMEOUT
        );
        var pool = new PoolDefault(config);

        PooledConnection first = pool.borrow();
        AtomicReference<PooledConnection> second = new AtomicReference<>();
        CountDownLatch acquired = new CountDownLatch(1);

        Thread.ofVirtual().start(() -> {
            second.set(pool.borrow());
            acquired.countDown();
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (pool.stats().waiting() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(1, pool.stats().waiting());

        pool.release(first);
        assertTrue(acquired.await(5, TimeUnit.SECONDS));
        pool.release(second.get());

        DatabaseStats stats = pool.stats();
        assertEquals(2L, stats.borrowCount());
        assertTrue(stats.borrowWaitNanos() > 0L);
        assertTrue(stats.averageBorrowWaitNanos() > 0L);

        pool.close();
    }

    @Test
    void warmUpFailureClosesAlreadyCreatedConnections() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        Driver driver = new Driver() {
            @Override
            public Connection connect(String url, Properties info)
                throws SQLException {
                if (!acceptsURL(url)) {
                    return null;
                }
                if (opens.incrementAndGet() == 1) {
                    return connectionProxy(closes);
                }
                throw new SQLException("boom");
            }

            @Override
            public boolean acceptsURL(String url) {
                return url != null && url.startsWith("jdbc:freeway-warmup:");
            }

            @Override
            public DriverPropertyInfo[] getPropertyInfo(
                String url,
                Properties info
            ) {
                return new DriverPropertyInfo[0];
            }

            @Override
            public int getMajorVersion() {
                return 1;
            }

            @Override
            public int getMinorVersion() {
                return 0;
            }

            @Override
            public boolean jdbcCompliant() {
                return false;
            }

            @Override
            public Logger getParentLogger() {
                return Logger.getLogger("test");
            }
        };

        DriverManager.registerDriver(driver);
        try {
            var config = new PoolConfig(
                "jdbc:freeway-warmup:test", "sa", "",
                2, 2,
                Duration.ofSeconds(5),
                Duration.ofMinutes(30),
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                null,
                Duration.ofSeconds(3), PoolConfig.DEFAULT_QUERY_TIMEOUT
            );

            SqlException ex = assertThrows(SqlException.class, () -> new PoolDefault(config));
            assertTrue(ex.getMessage().contains("Failed to warm up connection pool"));
            assertEquals(2, opens.get());
            assertEquals(1, closes.get());
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    private static Connection connectionProxy(AtomicInteger closes) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("setAutoCommit".equals(name)) {
                return null;
            }
            if ("isValid".equals(name)) {
                return Boolean.TRUE;
            }
            if ("close".equals(name)) {
                closes.incrementAndGet();
                return null;
            }
            if ("isClosed".equals(name)) {
                return Boolean.FALSE;
            }
            if ("unwrap".equals(name)) {
                throw new SQLException("Not a wrapper");
            }
            if ("isWrapperFor".equals(name)) {
                return Boolean.FALSE;
            }
            if ("toString".equals(name)) {
                return "test-connection";
            }
            throw new UnsupportedOperationException(name);
        };
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] { Connection.class },
            handler
        );
    }
}
