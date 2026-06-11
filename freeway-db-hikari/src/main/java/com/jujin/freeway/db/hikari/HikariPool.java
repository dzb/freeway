package com.jujin.freeway.db.hikari;

import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.Pool;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.internal.PooledConnection;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.SQLException;
import java.time.Instant;

public final class HikariPool implements Pool {

    private final HikariDataSource ds;
    private final HikariConfig config;

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
            return new PooledConnection(ds.getConnection(), Instant.now());
        } catch (SQLException e) {
            throw new com.jujin.freeway.db.SqlException("Failed to borrow connection", e);
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
            0, 0, 0
        );
    }

    @Override
    public void close() {
        ds.close();
    }
}
