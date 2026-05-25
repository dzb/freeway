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
    private final PooledConnection transactionConnection;
    private final String sql;
    private final NamedParamParser.Result parsed;
    private List<Object[]> positionalRows = List.of();
    private List<Map<String, Object>> namedRows = List.of();

    BatchQueryImpl(
        DatabaseImpl db,
        PooledConnection transactionConnection,
        String sql
    ) {
        this.db = db;
        this.transactionConnection = transactionConnection;
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
        boolean ownConnection = transactionConnection == null;
        PooledConnection conn = ownConnection ? db.pool().borrow() : transactionConnection;
        try {
            var stmt = conn
                .jdbcConnection()
                .prepareStatement(parsed.jdbcSql(), Statement.NO_GENERATED_KEYS);
            stmt.setQueryTimeout(db.queryTimeoutSeconds());
            try {
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
                return stmt.executeBatch();
            } finally {
                stmt.close();
            }
        } catch (SQLException e) {
            throw new SqlException("Batch execution failed: " + e.getMessage(), e);
        } finally {
            if (ownConnection) {
                db.pool().release(conn);
            }
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
