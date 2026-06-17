package com.jujin.freeway.db.hikari;

import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.Pool;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.PooledConnection;
import com.jujin.freeway.db.SqlException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

public final class HikariPool implements Pool {

    private final HikariDataSource ds;
    private final HikariConfig config;
    private final AtomicLong borrowCount = new AtomicLong(0);

    public HikariPool(PoolConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.url());
        hc.setUsername(config.username());
        hc.setPassword(config.password());
        hc.setMaximumPoolSize(config.maxSize());
        hc.setMinimumIdle(config.minIdle());
        hc.setConnectionTimeout(config.connectionTimeout().toMillis());
        hc.setMaxLifetime(config.maxLifetime().toMillis());
        hc.setIdleTimeout(config.maxIdleTime().toMillis());
        if (config.healthCheckQuery() != null) hc.setConnectionTestQuery(config.healthCheckQuery());
        this.config = hc;
        this.ds = new HikariDataSource(hc);
    }

    @Override
    public PooledConnection borrow() {
        try {
            borrowCount.incrementAndGet();
            return new HkConn(ds.getConnection());
        } catch (SQLException e) {
            throw new SqlException("Failed to borrow connection", e);
        }
    }

    @Override
    public void release(PooledConnection conn) {
        try {
            conn.connection().close();
        } catch (SQLException ignored) {
        }
    }

    @Override
    public DatabaseStats stats() {
        var pool = ds.getHikariPoolMXBean();
        return new DatabaseStats(
            pool.getActiveConnections(),
            pool.getIdleConnections(),
            pool.getTotalConnections(),
            pool.getThreadsAwaitingConnection(),
            config.getMaximumPoolSize(),
            0, // longLeased — HikariCP does not expose per-connection borrow duration
            borrowCount.get(),
            0  // borrowWaitNanos — HikariCP MXBean does not expose cumulative wait time
        );
    }

    @Override
    public void close() {
        ds.close();
    }

    private record HkConn(Connection connection) implements PooledConnection {}
}
