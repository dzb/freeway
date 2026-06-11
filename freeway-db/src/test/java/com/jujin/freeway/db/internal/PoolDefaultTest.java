package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.PoolConfig;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 连接池泄露检测集成测试 — 验证 active 追踪和 stats 上报。
 */
class PoolDefaultTest {

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
        assertNotNull(conn.borrowedAt());

        pool.release(conn);
        // 归还后：active 归零，borrowedAt 已清除
        DatabaseStats released = pool.stats();
        assertEquals(0, released.active());
        assertNull(conn.borrowedAt());

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
        assertNotNull(c1.borrowedAt());
        assertNotNull(c2.borrowedAt());

        pool.release(c1);
        assertEquals(1, pool.stats().active());
        assertNull(c1.borrowedAt());
        assertNotNull(c2.borrowedAt());

        pool.release(c2);
        assertEquals(0, pool.stats().active());
        assertNull(c2.borrowedAt());

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
}
