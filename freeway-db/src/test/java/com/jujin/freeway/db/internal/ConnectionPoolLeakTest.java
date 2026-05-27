package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.DatabaseStats;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 连接池泄露检测集成测试 — 验证 active 追踪和 stats 上报。
 */
class ConnectionPoolLeakTest {

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
            Duration.ofSeconds(3)
        );
        var pool = new ConnectionPool(config);

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
            Duration.ofSeconds(3)
        );
        var pool = new ConnectionPool(config);

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
            Duration.ofSeconds(3)
        );
        var pool = new ConnectionPool(config);

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
}
