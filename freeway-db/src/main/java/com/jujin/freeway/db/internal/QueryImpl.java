package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.ExecuteResult;
import com.jujin.freeway.db.Query;
import com.jujin.freeway.db.SqlException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

final class QueryImpl implements Query {
    private final DatabaseImpl db;
    private final PooledConnection boundConnection;
    private final String originalSql;
    private final Object[] positionalParams;
    private final Map<String, Object> namedParams;
    private final boolean mayHaveGeneratedKeys;
    private NamedParamParser.Result parsed;
    private boolean expandedChecked;
    private String expandedSql;
    private Object[] expandedFlatParams;

    QueryImpl(
        DatabaseImpl db,
        PooledConnection boundConnection,
        String sql,
        Object[] positionalParams,
        boolean mayHaveGeneratedKeys
    ) {
        this.db = db;
        this.boundConnection = boundConnection;
        this.originalSql = sql;
        this.positionalParams = positionalParams;
        this.mayHaveGeneratedKeys = mayHaveGeneratedKeys;
        this.namedParams = new HashMap<>();
    }

    @Override
    public Query param(String name, Object value) {
        if (positionalParams.length > 0) {
            throw new SqlException(
                "Cannot mix positional and named parameters. SQL: " + originalSql
            );
        }
        namedParams.put(name, value);
        return this;
    }

    @Override
    public <T> List<T> list(Class<T> targetType) {
        try (var ctx = borrow(false)) {
            bindAll(ctx.stmt);
            try (var rs = ctx.stmt.executeQuery()) {
                var mapper = db.rowMapperResolver().resolve(targetType);
                var list = new ArrayList<T>();
                int rowNum = 0;
                while (rs.next()) {
                    list.add(mapper.map(rs, rowNum++));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new SqlException("Query failed: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> Optional<T> one(Class<T> targetType) {
        try (var ctx = borrow(false)) {
            bindAll(ctx.stmt);
            try (var rs = ctx.stmt.executeQuery()) {
                if (rs.next()) {
                    var mapper = db.rowMapperResolver().resolve(targetType);
                    return Optional.ofNullable(mapper.map(rs, 0));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new SqlException("Query failed: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> Stream<T> stream(Class<T> targetType) {
        try {
            var ctx = borrow(false);
            bindAll(ctx.stmt);
            ctx.stmt.setFetchSize(100);
            var rs = ctx.stmt.executeQuery();
            var mapper = db.rowMapperResolver().resolve(targetType);

            Spliterator<T> spliterator = new Spliterators.AbstractSpliterator<>(
                Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL
            ) {
                private int rowNum;

                @Override
                public boolean tryAdvance(Consumer<? super T> action) {
                    try {
                        if (rs.next()) {
                            action.accept(mapper.map(rs, rowNum++));
                            return true;
                        }
                        return false;
                    } catch (SQLException e) {
                        throw new SqlException(
                            "Stream query failed: " + e.getMessage(), e
                        );
                    }
                }
            };

            return StreamSupport.stream(spliterator, false)
                .onClose(() -> {
                    try { rs.close(); } catch (SQLException ignored) { }
                    ctx.close();
                });
        } catch (SQLException e) {
            throw new SqlException("Stream query failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ExecuteResult execute() {
        try (var ctx = borrow(mayHaveGeneratedKeys)) {
            bindAll(ctx.stmt);
            int rows = ctx.stmt.executeUpdate();
            long id = 0L;
            if (mayHaveGeneratedKeys) {
                try (var rs = ctx.stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        Object key = rs.getObject(1);
                        if (key instanceof Number n) {
                            id = n.longValue();
                        }
                    }
                }
            }
            return new ExecuteResult(rows, id);
        } catch (SQLException e) {
            throw new SqlException("Update failed: " + e.getMessage(), e);
        }
    }

    private void bindAll(PreparedStatement stmt) throws SQLException {
        ensureExpanded();
        if (expandedFlatParams != null) {
            for (int i = 0; i < expandedFlatParams.length; i++) {
                stmt.setObject(i + 1, expandedFlatParams[i]);
            }
            return;
        }
        if (!namedParams.isEmpty()) {
            bindNamed(stmt);
        } else {
            bindPositional(stmt);
        }
    }

    private void bindPositional(PreparedStatement stmt) throws SQLException {
        String sqlToCheck = expandedSql != null ? expandedSql : originalSql;
        int placeholderCount = 0;
        for (int i = 0; i < sqlToCheck.length(); i++) {
            if (sqlToCheck.charAt(i) == '?') {
                placeholderCount++;
            }
        }

        if (positionalParams.length != placeholderCount) {
            throw new SqlException(
                "Parameter count mismatch: SQL has " + placeholderCount
                    + " placeholder(s) but " + positionalParams.length + " value(s) provided. SQL: "
                    + originalSql + ". Params: " + Arrays.toString(positionalParams)
            );
        }

        for (int i = 0; i < positionalParams.length; i++) {
            stmt.setObject(i + 1, positionalParams[i]);
        }
    }

    private void bindNamed(PreparedStatement stmt) throws SQLException {
        if (parsed == null) {
            parsed = NamedParamParser.parse(originalSql);
        }
        for (String name : parsed.names()) {
            if (!namedParams.containsKey(name)) {
                throw new SqlException("Missing value for named parameter '" + name + "'");
            }
        }
        for (String name : namedParams.keySet()) {
            if (!parsed.names().contains(name)) {
                throw new SqlException("Unknown named parameter '" + name + "'");
            }
        }
        for (int i = 0; i < parsed.names().size(); i++) {
            stmt.setObject(i + 1, namedParams.get(parsed.names().get(i)));
        }
    }

    private String jdbcSql() {
        ensureExpanded();
        if (expandedSql != null) {
            return expandedSql;
        }
        if (!namedParams.isEmpty()) {
            if (parsed == null) {
                parsed = NamedParamParser.parse(originalSql);
            }
            return parsed.jdbcSql();
        }
        return originalSql;
    }

    private void ensureExpanded() {
        if (expandedChecked) {
            return;
        }
        expandedChecked = true;
        if (!namedParams.isEmpty()) {
            expandNamed();
        } else {
            expandPositional();
        }
    }

    private void expandPositional() {
        var sb = new StringBuilder();
        var flat = new ArrayList<>();
        int sqlIdx = 0;
        boolean anyExpanded = false;

        for (int paramIdx = 0; paramIdx < positionalParams.length; paramIdx++) {
            Object param = positionalParams[paramIdx];
            int q = originalSql.indexOf('?', sqlIdx);
            if (q < 0) {
                throw new SqlException(
                    "Too many positional parameters for SQL: " +
                        originalSql +
                        ". Params: " + Arrays.toString(positionalParams)
                );
            }
            sb.append(originalSql, sqlIdx, q);
            sqlIdx = q + 1;

            if (param instanceof Collection<?> col) {
                if (col.isEmpty()) {
                    throw new SqlException("Cannot expand empty Collection for '?' placeholder");
                }
                appendExpanded(sb, flat, col);
                anyExpanded = true;
            } else if (param instanceof Object[] arr) {
                appendExpanded(sb, flat, Arrays.asList(arr));
                anyExpanded = true;
            } else {
                sb.append('?');
                flat.add(param);
            }
        }
        if (originalSql.indexOf('?', sqlIdx) >= 0) {
            throw new SqlException(
                "Too few positional parameters for SQL: " +
                    originalSql +
                    ". Params: " + Arrays.toString(positionalParams)
            );
        }
        sb.append(originalSql, sqlIdx, originalSql.length());

        if (anyExpanded) {
            expandedSql = sb.toString();
            expandedFlatParams = flat.toArray();
        }
    }

    private void expandNamed() {
        if (parsed == null) {
            parsed = NamedParamParser.parse(originalSql);
        }
        validateNamedParameters();

        String jdbcSql = parsed.jdbcSql();
        boolean anyExpanded = false;
        int qIdx = 0;
        var sqlOut = new StringBuilder();
        var flat = new ArrayList<>();

        for (String name : parsed.names()) {
            int q = jdbcSql.indexOf('?', qIdx);
            if (q < 0) {
                break;
            }
            sqlOut.append(jdbcSql, qIdx, q);
            qIdx = q + 1;

            Object value = namedParams.get(name);
            if (value instanceof Collection<?> col) {
                if (col.isEmpty()) {
                    throw new SqlException("Cannot expand empty Collection for named param '" + name + "'");
                }
                appendExpanded(sqlOut, flat, col);
                anyExpanded = true;
            } else if (value instanceof Object[] arr) {
                if (arr.length == 0) {
                    throw new SqlException("Cannot expand empty array for named param '" + name + "'");
                }
                appendExpanded(sqlOut, flat, Arrays.asList(arr));
                anyExpanded = true;
            } else {
                sqlOut.append('?');
                flat.add(value);
            }
        }
        sqlOut.append(jdbcSql, qIdx, jdbcSql.length());

        if (anyExpanded) {
            expandedSql = sqlOut.toString();
            expandedFlatParams = flat.toArray();
        }
    }

    private void validateNamedParameters() {
        for (String name : parsed.names()) {
            if (!namedParams.containsKey(name)) {
                throw new SqlException("Missing value for named parameter '" + name + "'");
            }
        }
        for (String name : namedParams.keySet()) {
            if (!parsed.names().contains(name)) {
                throw new SqlException("Unknown named parameter '" + name + "'");
            }
        }
    }

    private static void appendExpanded(
        StringBuilder sb,
        ArrayList<Object> flat,
        Collection<?> col
    ) {
        boolean first = true;
        for (Object value : col) {
            if (value instanceof Collection<?> || value != null && value.getClass().isArray()) {
                throw new SqlException(
                    "Nested collections are not supported in query parameters: collection=" +
                        col.getClass().getName() + ", value=" + value.getClass().getName()
                );
            }
            if (!first) {
                sb.append(',');
            }
            sb.append('?');
            flat.add(value);
            first = false;
        }
    }


    private ExecuteContext borrow(boolean mayHaveKeys) throws SQLException {
        String sql = jdbcSql();
        if (boundConnection != null) {
            var stmt = prepareStatement(
                boundConnection.jdbcConnection(), sql, mayHaveKeys);
            stmt.setQueryTimeout(db.queryTimeoutSeconds());
            return new ExecuteContext(stmt, null, null);
        }

        PooledConnection conn = db.pool().borrow();
        var stmt = prepareStatement(conn.jdbcConnection(), sql, mayHaveKeys);
        stmt.setQueryTimeout(db.queryTimeoutSeconds());
        return new ExecuteContext(stmt, conn, db.pool());
    }

    private PreparedStatement prepareStatement(
        java.sql.Connection conn, String sql, boolean mayHaveKeys
    ) throws SQLException {
        if (mayHaveKeys) {
            return conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        }
        return conn.prepareStatement(sql, Statement.NO_GENERATED_KEYS);
    }

    private record ExecuteContext(
        PreparedStatement stmt,
        PooledConnection connectionSource,
        ConnectionPool pool
    ) implements AutoCloseable {
        @Override
        public void close() {
            try {
                stmt.close();
            } catch (SQLException ignored) {
            }
            if (connectionSource != null && pool != null) {
                pool.release(connectionSource);
            }
        }
    }
}
