package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DatabaseBuilder;
import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.Pool;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.PooledConnection;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.db.dialect.H2Dialect;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Pool.invalidate(PooledConnection)} — 销毁语义：连接必须被物理关闭、
 * 槽位归还，且释放后（或关闭后）重复调用是幂等的。
 */
class PoolInvalidateTest {

    @Test
    void invalidateDestroysPhysicalConnectionAndFreesSlot() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        Driver driver = countingDriver("jdbc:freeway-invalidate:", opens, closes);
        DriverManager.registerDriver(driver);
        try {
            PoolDefault pool = new PoolDefault(
                config("jdbc:freeway-invalidate:destroy", 1)
            );
            try {
                PooledConnection first = pool.borrow();
                Connection destroyed = first.connection();
                assertEquals(1, pool.stats().active());

                pool.invalidate(first);

                assertEquals(
                    1, closes.get(),
                    "invalidate must physically close the connection"
                );
                assertEquals(0, pool.stats().active());
                assertEquals(
                    0, pool.stats().total(),
                    "an invalidated connection must free its slot"
                );

                PooledConnection second = pool.borrow();
                assertNotSame(
                    destroyed, second.connection(),
                    "a destroyed connection must never be handed out again"
                );
                assertEquals(2, opens.get());
                pool.release(second);
            } finally {
                pool.close();
            }
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    @Test
    void repeatedInvalidateAndReleaseAfterInvalidateAreNoOps() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        Driver driver = countingDriver(
            "jdbc:freeway-invalidate-twice:",
            new AtomicInteger(),
            closes
        );
        DriverManager.registerDriver(driver);
        try {
            PoolDefault pool = new PoolDefault(
                config("jdbc:freeway-invalidate-twice:test", 2)
            );
            try {
                PooledConnection conn = pool.borrow();
                pool.invalidate(conn);
                // Both are cleanup paths: neither may destroy twice (which
                // would double-decrement total) nor throw.
                pool.invalidate(conn);
                pool.release(conn);

                assertEquals(1, closes.get());
                assertEquals(0, pool.stats().total());
                assertEquals(0, pool.stats().active());

                // The pool is still usable: the freed slot is real.
                PooledConnection next = pool.borrow();
                assertEquals(1, pool.stats().active());
                pool.release(next);
            } finally {
                pool.close();
            }
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    @Test
    void invalidateHandsItsSlotToAWaitingBorrower() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        Driver driver = countingDriver(
            "jdbc:freeway-invalidate-wait:",
            new AtomicInteger(),
            closes
        );
        DriverManager.registerDriver(driver);
        try {
            PoolDefault pool = new PoolDefault(
                config("jdbc:freeway-invalidate-wait:test", 1)
            );
            try {
                PooledConnection held = pool.borrow();
                AtomicReference<PooledConnection> borrowed =
                    new AtomicReference<>();
                CountDownLatch acquired = new CountDownLatch(1);
                Thread waiter = Thread.ofVirtual().start(() -> {
                    borrowed.set(pool.borrow());
                    acquired.countDown();
                });

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (
                    pool.stats().waiting() == 0 && System.nanoTime() < deadline
                ) {
                    Thread.sleep(10);
                }
                assertEquals(1, pool.stats().waiting());

                pool.invalidate(held);

                assertTrue(
                    acquired.await(5, TimeUnit.SECONDS),
                    "invalidate must release the pool permit"
                );
                assertTrue(
                    borrowed.get().connection().isValid(1),
                    "the waiter must receive a fresh, usable connection"
                );
                assertEquals(1, closes.get());
                pool.release(borrowed.get());
            } finally {
                pool.close();
            }
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    @Test
    void invalidateRejectsNullAndForeignHandles() throws Exception {
        Driver driver = countingDriver(
            "jdbc:freeway-invalidate-foreign:",
            new AtomicInteger(),
            new AtomicInteger()
        );
        DriverManager.registerDriver(driver);
        try {
            PoolDefault pool = new PoolDefault(
                config("jdbc:freeway-invalidate-foreign:test", 1)
            );
            try {
                PooledConnection held = pool.borrow();

                assertThrows(
                    NullPointerException.class,
                    () -> pool.invalidate(null)
                );

                PooledConnection foreign = () -> null;
                SqlException ex = assertThrows(
                    SqlException.class,
                    () -> pool.invalidate(foreign)
                );
                assertTrue(
                    ex.getMessage().contains("Foreign PooledConnection rejected"),
                    "expected a foreign-handle rejection, got: " + ex.getMessage()
                );
                assertEquals(
                    1, pool.stats().active(),
                    "a rejected handle must not disturb the borrowed connection"
                );
                pool.release(held);
            } finally {
                pool.close();
            }
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    @Test
    void invalidateAfterCloseIsNoOp() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        Driver driver = countingDriver(
            "jdbc:freeway-invalidate-closed:",
            new AtomicInteger(),
            closes
        );
        DriverManager.registerDriver(driver);
        try {
            PoolDefault pool = new PoolDefault(
                config("jdbc:freeway-invalidate-closed:test", 1)
            );
            PooledConnection held = pool.borrow();
            pool.close();
            // close() already force-closed the active connection; a cleanup
            // path that invalidates afterwards must not destroy it twice.
            pool.invalidate(held);

            assertEquals(1, closes.get());
            assertEquals(0, pool.stats().total());
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    @Test
    void transactionInvalidatesConnectionThatCannotBeRestored() throws Exception {
        AtomicBoolean failRestore = new AtomicBoolean();
        AtomicInteger closes = new AtomicInteger();
        Driver driver = restoreFailingDriver(
            "jdbc:freeway-restore-tx:",
            failRestore,
            closes
        );
        DriverManager.registerDriver(driver);
        try {
            RecordingPool pool = new RecordingPool(
                new PoolDefault(config("jdbc:freeway-restore-tx:tx", 1))
            );
            Database db = new DatabaseBuilder()
                .config(config("jdbc:freeway-restore-tx:tx", 1))
                .pool(pool)
                .dialect(new H2Dialect())
                .build();
            try (db) {
                db.execute("create table t (id int)");

                // Healthy path: the connection is recycled, never invalidated.
                db.transaction(() -> db.execute("insert into t values (1)"));
                assertEquals(
                    0, pool.invalidated.get(),
                    "a connection whose state was restored must be recycled"
                );
                assertEquals(1, pool.stats().total());
                assertEquals(1, pool.stats().idle());

                // setAutoCommit(true) fails from here on: the connection must
                // be destroyed, not handed to the next borrower with
                // autoCommit still off.
                failRestore.set(true);
                db.transaction(() -> db.execute("insert into t values (2)"));

                assertEquals(
                    1, pool.invalidated.get(),
                    "an unrestorable connection must be invalidated"
                );
                assertEquals(
                    0, pool.stats().total(),
                    "the invalidated connection must free its slot"
                );
                assertEquals(1, closes.get());
            } finally {
                pool.close();
            }
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    @Test
    void batchInvalidatesConnectionThatCannotBeRestored() throws Exception {
        AtomicBoolean failRestore = new AtomicBoolean();
        AtomicInteger closes = new AtomicInteger();
        Driver driver = restoreFailingDriver(
            "jdbc:freeway-restore-batch:",
            failRestore,
            closes
        );
        DriverManager.registerDriver(driver);
        try {
            RecordingPool pool = new RecordingPool(
                new PoolDefault(config("jdbc:freeway-restore-batch:batch", 1))
            );
            Database db = new DatabaseBuilder()
                .config(config("jdbc:freeway-restore-batch:batch", 1))
                .pool(pool)
                .dialect(new H2Dialect())
                .build();
            try (db) {
                db.execute("create table t (id int)");

                failRestore.set(true);
                db.batch("insert into t values (?)")
                    .rows(new Object[] { 1 }, new Object[] { 2 })
                    .execute();

                assertEquals(
                    1, pool.invalidated.get(),
                    "a batch whose autoCommit cannot be restored must invalidate"
                );
                assertEquals(0, pool.stats().total());
                assertEquals(1, closes.get());
            } finally {
                pool.close();
            }
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    private static PoolConfig config(String url, int maxSize) {
        return new PoolConfig(
            url,
            "sa",
            "",
            maxSize,
            0,
            Duration.ofSeconds(5),
            Duration.ofMinutes(30),
            Duration.ofMinutes(5),
            Duration.ofSeconds(30),
            null,
            Duration.ofSeconds(3),
            PoolConfig.DEFAULT_QUERY_TIMEOUT
        );
    }

    private static Driver countingDriver(
        String urlPrefix,
        AtomicInteger opens,
        AtomicInteger closes
    ) {
        return new TestDriver(urlPrefix) {
            @Override
            Connection connect(String url) throws SQLException {
                opens.incrementAndGet();
                return connectionProxy(closes);
            }
        };
    }

    /**
     * Serves real H2 connections wrapped in a proxy that can fail
     * {@code setAutoCommit(true)} on demand — the failure that makes both
     * restore paths destroy instead of recycle.
     */
    private static Driver restoreFailingDriver(
        String urlPrefix,
        AtomicBoolean failRestore,
        AtomicInteger closes
    ) {
        return new TestDriver(urlPrefix) {
            @Override
            Connection connect(String url) throws SQLException {
                Connection delegate = new org.h2.Driver().connect(
                    url.replace(urlPrefix, "jdbc:h2:mem:"),
                    new Properties()
                );
                return restoreFailingProxy(delegate, failRestore, closes);
            }
        };
    }

    private static Connection restoreFailingProxy(
        Connection delegate,
        AtomicBoolean failRestore,
        AtomicInteger closes
    ) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (
                "setAutoCommit".equals(method.getName()) &&
                Boolean.TRUE.equals(args[0]) &&
                failRestore.get()
            ) {
                throw new SQLException("autoCommit restore exploded");
            }
            if ("close".equals(method.getName())) {
                closes.incrementAndGet();
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] { Connection.class },
            handler
        );
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

    /** Minimal {@link Driver} so tests control connect/close accounting. */
    private abstract static class TestDriver implements Driver {
        private final String urlPrefix;

        TestDriver(String urlPrefix) {
            this.urlPrefix = urlPrefix;
        }

        abstract Connection connect(String url) throws SQLException;

        @Override
        public Connection connect(String url, Properties info)
            throws SQLException {
            return acceptsURL(url) ? connect(url) : null;
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith(urlPrefix);
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
    }

    /** Delegating pool that records how often the database invalidated. */
    private static final class RecordingPool implements Pool {
        private final Pool delegate;
        private final AtomicInteger invalidated = new AtomicInteger();

        RecordingPool(Pool delegate) {
            this.delegate = delegate;
        }

        @Override
        public PooledConnection borrow() {
            return delegate.borrow();
        }

        @Override
        public void release(PooledConnection conn) {
            delegate.release(conn);
        }

        @Override
        public void invalidate(PooledConnection conn) {
            invalidated.incrementAndGet();
            delegate.invalidate(conn);
        }

        @Override
        public DatabaseStats stats() {
            return delegate.stats();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
