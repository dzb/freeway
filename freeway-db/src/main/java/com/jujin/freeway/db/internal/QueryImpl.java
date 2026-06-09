package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.ExecuteResult;
import com.jujin.freeway.db.Query;
import com.jujin.freeway.db.SqlException;

import java.lang.ref.Cleaner;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
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
        ExecuteContext ctx = null;
        ResultSet rs = null;
        boolean returned = false;
        try {
            ctx = borrow(false);
            bindAll(ctx.stmt);
            ctx.stmt.setFetchSize(100);
            rs = ctx.stmt.executeQuery();
            var mapper = db.rowMapperResolver().resolve(targetType);

            StreamResources resources = new StreamResources(rs, ctx);
            Spliterator<T> spliterator = new Spliterators.AbstractSpliterator<>(
                Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL
            ) {
                private int rowNum;

                @Override
                public boolean tryAdvance(Consumer<? super T> action) {
                    try {
                        if (resources.closed()) {
                            return false;
                        }
                        if (resources.rs().next()) {
                            action.accept(mapper.map(resources.rs(), rowNum++));
                            return true;
                        }
                        resources.close();
                        return false;
                    } catch (SQLException e) {
                        resources.close();
                        throw new SqlException(
                            "Stream query failed: " + e.getMessage(), e
                        );
                    }
                }
            };

            returned = true;
            return StreamSupport.stream(spliterator, false)
                .onClose(resources::close);
        } catch (SQLException e) {
            throw new SqlException("Stream query failed: " + e.getMessage(), e);
        } finally {
            if (!returned) {
                if (rs != null) {
                    try {
                        rs.close();
                    } catch (SQLException ignored) {
                    }
                }
                if (ctx != null) {
                    ctx.close();
                }
            }
        }
    }

    ExecuteResult execute() {
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
        int placeholderCount = NamedParamParser.positionalPlaceholderIndexes(sqlToCheck).size();

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
        boolean anyExpanded = false;
        List<Integer> placeholders = NamedParamParser.positionalPlaceholderIndexes(originalSql);

        for (int paramIdx = 0; paramIdx < positionalParams.length; paramIdx++) {
            Object param = positionalParams[paramIdx];
            if (paramIdx >= placeholders.size()) {
                throw new SqlException(
                    "Too many positional parameters for SQL: " +
                        originalSql +
                        ". Params: " + Arrays.toString(positionalParams)
                    );
            }
            int q = placeholders.get(paramIdx);
            int sqlIdx = paramIdx == 0 ? 0 : placeholders.get(paramIdx - 1) + 1;
            sb.append(originalSql, sqlIdx, q);

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
        if (placeholders.size() > positionalParams.length) {
            throw new SqlException(
                "Too few positional parameters for SQL: " +
                    originalSql +
                    ". Params: " + Arrays.toString(positionalParams)
            );
        }
        int sqlIdx = placeholders.isEmpty() ? 0 : placeholders.getLast() + 1;
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

        for (int i = 0; i < parsed.names().size(); i++) {
            String name = parsed.names().get(i);
            int q = parsed.parameterIndexes().get(i);
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
        PreparedStatement stmt = null;
        boolean success = false;
        try {
            stmt = prepareStatement(conn.jdbcConnection(), sql, mayHaveKeys);
            stmt.setQueryTimeout(db.queryTimeoutSeconds());
            success = true;
            return new ExecuteContext(stmt, conn, db.pool());
        } finally {
            if (!success) {
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (SQLException ignored) {
                    }
                }
                db.pool().release(conn);
            }
        }
    }

    private PreparedStatement prepareStatement(
        java.sql.Connection conn, String sql, boolean mayHaveKeys
    ) throws SQLException {
        if (mayHaveKeys) {
            return conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        }
        return conn.prepareStatement(sql, Statement.NO_GENERATED_KEYS);
    }

    private static final Cleaner STREAM_CLEANER = Cleaner.create();

    private static final class StreamResources implements AutoCloseable {
        private final ResultSet rs;
        private final ExecuteContext ctx;
        private boolean closed;
        private final Cleaner.Cleanable cleanable;

        private StreamResources(ResultSet rs, ExecuteContext ctx) {
            this.rs = rs;
            this.ctx = ctx;
            this.cleanable = STREAM_CLEANER.register(this, () -> {
                try {
                    rs.close();
                } catch (SQLException ignored) {
                }
                ctx.close();
            });
        }

        private ResultSet rs() {
            return rs;
        }

        private boolean closed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            cleanable.clean();
            try {
                rs.close();
            } catch (SQLException ignored) {
            }
            ctx.close();
        }
    }

    private static final class ExecuteContext implements AutoCloseable {
        private final PreparedStatement stmt;
        private final PooledConnection connectionSource;
        private final ConnectionPool pool;
        private boolean closed;

        private ExecuteContext(
            PreparedStatement stmt,
            PooledConnection connectionSource,
            ConnectionPool pool
        ) {
            this.stmt = stmt;
            this.connectionSource = connectionSource;
            this.pool = pool;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
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
