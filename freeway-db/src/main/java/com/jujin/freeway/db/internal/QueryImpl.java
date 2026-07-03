package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.*;
import com.jujin.freeway.db.util.SqlTextParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(QueryImpl.class);
    private final DatabaseImpl db;
    private final PooledConnection boundConnection;
    private final String originalSql;
    private final Object[] positionalParams;
    private final Map<String, Object> namedParams;
    private final boolean mayHaveGeneratedKeys;
    private SqlTextParser.Result parsed;
    private List<Integer> positionalIndexes;
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
                "Cannot mix positional and named parameters. SQL: " +
                    originalSql
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
            LOG.warn("Query list failed: {}", originalSql, e);
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
            LOG.warn("Query one failed: {}", originalSql, e);
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
                Long.MAX_VALUE,
                Spliterator.ORDERED
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
                            "Stream query failed: " + e.getMessage(),
                            e
                        );
                    }
                }
            };

            returned = true;
            return StreamSupport.stream(spliterator, false).onClose(
                resources::close
            );
        } catch (SQLException e) {
            LOG.warn("Stream query failed: {}", originalSql, e);
            throw new SqlException("Stream query failed: " + e.getMessage(), e);
        } finally {
            if (!returned) {
                if (rs != null) {
                    try {
                        rs.close();
                    } catch (SQLException ignored) {}
                }
                if (ctx != null) {
                    ctx.close();
                }
            }
        }
    }

    @Override
    public ExecuteResult execute() {
        try (var ctx = borrow(mayHaveGeneratedKeys)) {
            bindAll(ctx.stmt);
            int rows = ctx.stmt.executeUpdate();
            Object key = null;
            if (mayHaveGeneratedKeys) {
                try (var rs = ctx.stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        key = rs.getObject(1);
                    }
                }
            }
            return new ExecuteResult(rows, key);
        } catch (SQLException e) {
            LOG.warn("Execute failed: {}", originalSql, e);
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
        var indexes = positionalPlaceholders();
        if (positionalParams.length != indexes.size()) {
            throw new SqlException(
                "Parameter count mismatch: SQL has " +
                    indexes.size() +
                    " placeholder(s) but " +
                    positionalParams.length +
                    " value(s) provided. SQL: " +
                    originalSql +
                    ". Params: " +
                    Arrays.toString(positionalParams)
            );
        }

        for (int i = 0; i < positionalParams.length; i++) {
            stmt.setObject(i + 1, positionalParams[i]);
        }
    }

    private List<Integer> positionalPlaceholders() {
        if (positionalIndexes == null) {
            positionalIndexes = SqlTextParser.paramIndexes(
                originalSql
            );
        }
        return positionalIndexes;
    }

    private void bindNamed(PreparedStatement stmt) throws SQLException {
        var p = parsed();
        for (int i = 0; i < p.names().size(); i++) {
            stmt.setObject(i + 1, namedParams.get(p.names().get(i)));
        }
    }

    private String sql() {
        ensureExpanded();
        if (expandedSql != null) {
            return expandedSql;
        }
        if (!namedParams.isEmpty()) {
            return parsed().sql();
        }
        return originalSql;
    }

    private SqlTextParser.Result parsed() {
        if (parsed == null) {
            parsed = SqlTextParser.parseNamed(originalSql);
        }
        return parsed;
    }

    /**
     * Expands parameter placeholders into a flat positional array.
     *
     * <p>Handles three parameter styles:
     * <ol>
     *   <li>Named params ({@code :name / $name}) via {@code .param()} API</li>
     *   <li>Auto-detected named placeholders with positional values</li>
     *   <li>Pure positional ({@code ?}) with collection expansion
     *       ({@code WHERE id IN (?)} with a List → repeated {@code ?})</li>
     * </ol>
     * Expanded results are cached so expansion runs exactly once per query.
     */
    private void ensureExpanded() {
        if (expandedChecked) {
            return;
        }
        expandedChecked = true;
        if (!namedParams.isEmpty()) {
            rejectMixedPlaceholderStyles();
            expandNamed();
        } else if (SqlTextParser.hasNamedPlaceholders(originalSql)) {
            autoBindNamed();
        } else {
            expandPositional();
        }
    }

    private void autoBindNamed() {
        rejectMixedPlaceholderStyles();
        var p = SqlTextParser.parseNamed(originalSql);
        if (positionalParams.length != p.names().size()) {
            throw new SqlException(
                "Parameter count mismatch in '" + originalSql + "': " +
                    p.names().size() + " named parameter(s) " + p.names() +
                    " but " + positionalParams.length + " value(s) provided. " +
                    "Use .param(\"name\", value) for named parameters."
            );
        }
        for (int i = 0; i < p.names().size(); i++) {
            String name = p.names().get(i);
            Object value = positionalParams[i];
            if (namedParams.containsKey(name) && !Objects.equals(namedParams.get(name), value)) {
                throw new SqlException(
                    "Duplicate named parameter ':" + name +
                    "' with different positional values in '" +
                    originalSql + "'. Use .param(\"" + name +
                    "\", value) for named parameters with repeated placeholders."
                );
            }
            namedParams.put(name, value);
        }
        expandNamed();
    }

    private void expandPositional() {
        var sb = new StringBuilder();
        var flat = new ArrayList<>();
        boolean anyExpanded = false;
        var placeholders = positionalPlaceholders();

        for (int i = 0; i < positionalParams.length; i++) {
            Object param = positionalParams[i];
            if (i >= placeholders.size()) {
                throw new SqlException(
                    "Too many positional parameters for SQL: " +
                        originalSql +
                        ". Params: " +
                        Arrays.toString(positionalParams)
                );
            }
            int q = placeholders.get(i);
            int sqlIdx = i == 0 ? 0 : placeholders.get(i - 1) + 1;
            sb.append(originalSql, sqlIdx, q);
            if (appendParam(sb, flat, param)) anyExpanded = true;
        }
        if (placeholders.size() > positionalParams.length) {
            throw new SqlException(
                "Too few positional parameters for SQL: " +
                    originalSql +
                    ". Params: " +
                    Arrays.toString(positionalParams)
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
        rejectMixedPlaceholderStyles();
        var p = parsed();
        validateNamedParameters();

        String effectiveSql = p.sql();
        boolean anyExpanded = false;
        int qIdx = 0;
        var sqlOut = new StringBuilder();
        var flat = new ArrayList<>();

        for (int i = 0; i < p.names().size(); i++) {
            String name = p.names().get(i);
            int q = p.parameterIndexes().get(i);
            sqlOut.append(effectiveSql, qIdx, q);
            qIdx = q + 1;

            Object value = namedParams.get(name);
            if (appendParam(sqlOut, flat, value)) anyExpanded = true;
        }
        sqlOut.append(effectiveSql, qIdx, effectiveSql.length());

        if (anyExpanded) {
            expandedSql = sqlOut.toString();
            expandedFlatParams = flat.toArray();
        }
    }

    private void rejectMixedPlaceholderStyles() {
        if (
            SqlTextParser.hasNamedPlaceholders(originalSql) &&
            !SqlTextParser.paramIndexes(originalSql).isEmpty()
        ) {
            throw new SqlException(
                "Cannot mix named and positional placeholders in SQL: " +
                    originalSql
            );
        }
    }

    private void validateNamedParameters() {
        var p = parsed();
        for (String name : p.names()) {
            if (!namedParams.containsKey(name)) {
                throw new SqlException(
                    "Missing value for named parameter '" +
                        name +
                        "' in SQL: " +
                        originalSql
                );
            }
        }
        for (String name : namedParams.keySet()) {
            if (!p.names().contains(name)) {
                throw new SqlException(
                    "Unknown named parameter '" +
                        name +
                        "' in SQL: " +
                        originalSql
                );
            }
        }
    }

    private boolean appendParam(
        StringBuilder sb,
        List<Object> flat,
        Object value
    ) {
        if (value instanceof Collection<?> col) {
            if (col.isEmpty()) {
                throw new SqlException(
                    "Cannot expand empty collection for SQL: " + originalSql
                );
            }
            appendExpanded(sb, flat, col);
            return true;
        }
        if (value instanceof Object[] arr) {
            if (arr.length == 0) {
                throw new SqlException(
                    "Cannot expand empty array for SQL: " + originalSql
                );
            }
            appendExpanded(sb, flat, Arrays.asList(arr));
            return true;
        }
        sb.append('?');
        flat.add(value);
        return false;
    }

    private static void appendExpanded(
        StringBuilder sb,
        List<Object> flat,
        Collection<?> col
    ) {
        boolean first = true;
        for (Object value : col) {
            if (
                value instanceof Collection<?> ||
                (value != null && value.getClass().isArray())
            ) {
                throw new SqlException(
                    "Nested collections are not supported in query parameters: collection=" +
                        col.getClass().getName() +
                        ", value=" +
                        value.getClass().getName()
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
        String effectiveSql = sql();
        int autoKeys = mayHaveKeys
            ? Statement.RETURN_GENERATED_KEYS
            : Statement.NO_GENERATED_KEYS;
        if (boundConnection != null) {
            var stmt = boundConnection
                .connection()
                .prepareStatement(effectiveSql, autoKeys);
            stmt.setQueryTimeout(db.queryTimeoutSeconds());
            return new ExecuteContext(stmt, null, null);
        }

        PooledConnection conn = db.pool().borrow();
        PreparedStatement stmt = null;
        boolean success = false;
        try {
            stmt = conn.connection().prepareStatement(effectiveSql, autoKeys);
            stmt.setQueryTimeout(db.queryTimeoutSeconds());
            success = true;
            return new ExecuteContext(stmt, conn, db.pool());
        } finally {
            if (!success) {
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (SQLException ignored) {}
                }
                db.pool().release(conn);
            }
        }
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
            this.cleanable = STREAM_CLEANER.register(this, new StreamCleanup(rs, ctx));
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
        }
    }

    private static final class StreamCleanup implements Runnable {

        private final ResultSet rs;
        private final ExecuteContext ctx;

        private StreamCleanup(ResultSet rs, ExecuteContext ctx) {
            this.rs = rs;
            this.ctx = ctx;
        }

        @Override
        public void run() {
            try {
                rs.close();
            } catch (SQLException ignored) {
            } finally {
                ctx.close();
            }
        }
    }

    private static final class ExecuteContext implements AutoCloseable {

        private final PreparedStatement stmt;
        private final PooledConnection connection;
        private final Pool pool;
        private boolean closed;

        private ExecuteContext(
            PreparedStatement stmt,
            PooledConnection connection,
            Pool pool
        ) {
            this.stmt = stmt;
            this.connection = connection;
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
            } catch (SQLException ignored) {}
            if (connection != null && pool != null) {
                pool.release(connection);
            }
        }
    }
}
