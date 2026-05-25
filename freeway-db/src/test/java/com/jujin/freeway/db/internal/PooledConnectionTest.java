package com.jujin.freeway.db.internal;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PooledConnection 单元测试 — 使用真实 H2 连接。
 */
class PooledConnectionTest {

    private static Connection jdbcConn;

    @BeforeAll
    static void setUp() throws Exception {
        jdbcConn = DriverManager.getConnection("jdbc:h2:mem:pooled_conn_test;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (jdbcConn != null) {
            jdbcConn.close();
        }
    }

    @Test
    void jdbcConnectionReturnsOriginal() {
        var pooled = new PooledConnection(jdbcConn, Instant.now());
        assertSame(jdbcConn, pooled.jdbcConnection());
    }

    @Test
    void isFreshReturnsTrueForNewConnection() {
        var pooled = new PooledConnection(jdbcConn, Instant.now());
        assertTrue(pooled.isFresh(Duration.ofSeconds(5)));
    }



    @Test
    void isFreshReturnsFalseForOldConnection() throws Exception {
        // 创建一个"过去"的连接
        var pooled = new PooledConnection(jdbcConn, Instant.now().minus(Duration.ofSeconds(10)));
        // 阈值 5 秒，但连接最后一次使用是 10 秒前
        assertFalse(pooled.isFresh(Duration.ofSeconds(5)));
    }

    @Test
    void markReturnedResetsFreshness() throws Exception {
        var pooled = new PooledConnection(jdbcConn, Instant.now().minus(Duration.ofSeconds(10)));
        pooled.markReturned();
        // markReturned 后 lastReturned 被重置为 now，应该 fresh
        assertTrue(pooled.isFresh(Duration.ofSeconds(5)));
    }

    @Test
    void isFreshPreciseThreshold() throws Exception {
        var pooled = new PooledConnection(jdbcConn, Instant.now().minus(Duration.ofMillis(300)));
        // 300ms 前的连接，阈值 500ms → fresh
        assertTrue(pooled.isFresh(Duration.ofMillis(500)));
        // 300ms 前的连接，阈值 100ms → 不 fresh
        assertFalse(pooled.isFresh(Duration.ofMillis(100)));
    }

    @Test
    void isExpiredExceedsMaxLifetime() {
        var pooled = new PooledConnection(
            jdbcConn,
            Instant.now().minus(Duration.ofHours(2))  // 2 小时前创建
        );
        var now = Instant.now();
        assertTrue(pooled.isExpired(now, Duration.ofHours(1), Duration.ofMinutes(30)));
    }

    @Test
    void isExpiredDoesNotExceedMaxLifetime() {
        var pooled = new PooledConnection(
            jdbcConn,
            Instant.now().minus(Duration.ofMinutes(10))  // 10 分钟前创建
        );
        var now = Instant.now();
        assertFalse(pooled.isExpired(now, Duration.ofHours(1), Duration.ofMinutes(30)));
    }

    @Test
    void isExpiredExceedsMaxIdleTime() {
        var pooled = new PooledConnection(jdbcConn, Instant.now());
        pooled.markReturned();  // 设置 lastReturned ≈ now
        // 模拟已经空闲了 30 分钟... 但我们不能改变 lastReturned
        // 用过去的 Instant 创建来模拟
        var oldPooled = new PooledConnection(
            jdbcConn,
            Instant.now().minus(Duration.ofHours(2))
        );
        // 不调用 markReturned，所以 lastReturned == createdAt
        var now = Instant.now();
        assertTrue(oldPooled.isExpired(now, Duration.ofHours(4), Duration.ofMinutes(5)));
    }

    @Test
    void isExpiredNotExceeded() {
        var pooled = new PooledConnection(jdbcConn, Instant.now());
        var now = Instant.now();
        assertFalse(pooled.isExpired(now, Duration.ofHours(1), Duration.ofMinutes(30)));
    }

    @Test
    void isExpiredWithinLifetimeBoundary() throws Exception {
        // 创建时间比 maxLifetime 少 2 秒，保证时钟漂移不导致误判
        var pooled = new PooledConnection(
            jdbcConn,
            Instant.now().minus(Duration.ofSeconds(58))
        );
        var now = Instant.now();
        // maxLifetime=60s, createdAt ≈ 58 秒前 → 未过期
        // maxIdleTime=120s, idle 也是 ≈58 秒 → 未过期
        assertFalse(pooled.isExpired(now, Duration.ofSeconds(60), Duration.ofSeconds(120)));
    }

    @Test
    void isExpiredExceedsLifetimeBoundary() {
        // 创建时间远超 maxLifetime
        var pooled = new PooledConnection(
            jdbcConn,
            Instant.now().minus(Duration.ofSeconds(120))
        );
        var now = Instant.now();
        // maxLifetime=60s, createdAt 120 秒前 → 过期
        // maxIdleTime=300s 设置够大以免干扰
        assertTrue(pooled.isExpired(now, Duration.ofSeconds(60), Duration.ofSeconds(300)));
    }

    @Test
    void markReturnedCalledMultipleTimes() throws Exception {
        var pooled = new PooledConnection(jdbcConn, Instant.now().minus(Duration.ofSeconds(30)));

        // 最初不 fresh
        assertFalse(pooled.isFresh(Duration.ofSeconds(5)));

        pooled.markReturned();
        assertTrue(pooled.isFresh(Duration.ofSeconds(5)));
        assertTrue(pooled.isFresh(Duration.ofSeconds(10)));

        // 再 mark 一次仍然 fresh
        pooled.markReturned();
        assertTrue(pooled.isFresh(Duration.ofSeconds(5)));
    }
}
