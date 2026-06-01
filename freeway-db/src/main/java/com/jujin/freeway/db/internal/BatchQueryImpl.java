package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.BatchQuery;
import com.jujin.freeway.db.SqlException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class BatchQueryImpl implements BatchQuery {
    private final DatabaseImpl db;
    private final PooledConnection boundConnection;
    private final String sql;
    private final NamedParamParser.Result parsed;
    private List<Object[]> positionalRows = List.of();
    private List<Map<String, Object>> namedRows = List.of();

    BatchQueryImpl(
        DatabaseImpl db,
        PooledConnection boundConnection,
        String sql
    ) {
        this.db = db;
        this.boundConnection = boundConnection;
        this.sql = sql;
        this.parsed = NamedParamParser.parse(sql);
    }

    @Override
    public BatchQuery rows(Object[]... rows) {
        this.positionalRows = List.of(rows);
        this.namedRows = List.of();
        return this;
    }

    @Override
    public BatchQuery rows(List<Object[]> rows) {
        this.positionalRows = rows == null ? List.of() : rows;
        this.namedRows = List.of();
        return this;
    }

    @Override
    public BatchQuery named(List<Map<String, Object>> rows) {
        this.namedRows = rows == null ? List.of() : rows;
        this.positionalRows = List.of();
        return this;
    }

    @Override
    public int[] execute() {
        boolean ownConnection = boundConnection == null;
        PooledConnection conn = ownConnection ? db.pool().borrow() : boundConnection;
        boolean autoCommitChanged = false;
        try {
            if (ownConnection || conn.jdbcConnection().getAutoCommit()) {
                conn.jdbcConnection().setAutoCommit(false);
                autoCommitChanged = true;
            }
            try (PreparedStatement stmt = conn
                .jdbcConnection()
                .prepareStatement(parsed.jdbcSql(), Statement.NO_GENERATED_KEYS)) {
                stmt.setQueryTimeout(db.queryTimeoutSeconds());
                if (!namedRows.isEmpty()) {
                    for (var row : namedRows) {
                        bindRow(stmt, row);
                        stmt.addBatch();
                    }
                } else {
                    for (var row : positionalRows) {
                        for (int i = 0; i < row.length; i++) {
                            stmt.setObject(i + 1, row[i]);
                        }
                        stmt.addBatch();
                    }
                }
                int[] counts = stmt.executeBatch();
                if (autoCommitChanged) {
                    conn.jdbcConnection().commit();
                }
                return counts;
            } catch (SQLException e) {
                if (autoCommitChanged) {
                    rollbackQuietly(conn);
                }
                throw new SqlException("Batch execution failed: " + e.getMessage(), e);
            }
        } catch (SQLException e) {
            if (autoCommitChanged) {
                rollbackQuietly(conn);
            }
            throw new SqlException("Batch execution failed: " + e.getMessage(), e);
        } finally {
            if (autoCommitChanged) {
                restoreAutoCommitQuietly(conn);
            }
            if (ownConnection) {
                db.pool().release(conn);
            }
        }
    }

    private void rollbackQuietly(PooledConnection conn) {
        try {
            conn.jdbcConnection().rollback();
        } catch (SQLException ignored) {
        }
    }

    private void restoreAutoCommitQuietly(PooledConnection conn) {
        try {
            conn.jdbcConnection().setAutoCommit(true);
        } catch (SQLException ignored) {
        }
    }

    private void bindRow(PreparedStatement stmt, Map<String, Object> row) throws SQLException {
        for (String name : parsed.names()) {
            if (!row.containsKey(name)) {
                throw new SqlException("Missing value for named parameter '" + name + "' in batch SQL: " + sql);
            }
        }
        for (String paramName : row.keySet()) {
            if (!parsed.names().contains(paramName)) {
                throw new SqlException("Unknown named parameter '" + paramName + "' in batch SQL: " + sql);
            }
        }
        for (int i = 0; i < parsed.names().size(); i++) {
            stmt.setObject(i + 1, row.get(parsed.names().get(i)));
        }
    }
}
