package com.jujin.freeway.db;

import com.jujin.freeway.db.dialect.Dialect;
import com.jujin.freeway.db.util.SqlTextParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Immutable chainable SQL builder.
 * <p>
 * Fully compatible with {@link Database#query(String, Object...)} and {@link Database#execute(String, Object...)} —
 * pass {@link #sql()} and {@link #args()} directly. Supports {@code ?} positional parameters and
 * {@code :name} / {@code $name} named parameters.
 * <p>
 * Examples:
 * <pre>{@code
 * // raw text block (Java 25+)
 * db.query(Sql.select("*").from("users").where("status = ?", 1));
 *
 * // dynamic conditions
 * Sql q = Sql.select("*").from("users");
 * if (name != null)  q = q.where("name LIKE ?", name);
 * if (status != 0)   q = q.where("status = ?", status);
 * db.query(q.sql(), q.args()).list(User.class);
 *
 * // named parameter style
 * Sql q = Sql.select("*").from("users").where("name = :name", name);
 * db.query(q.sql(), q.args()).list(User.class);
 *
 * // UPDATE
 * Sql.update("users").set("name = ?", name).set("status = ?", status).where("id = ?", id);
 *
 * // INSERT
 * Sql.insert("users").set("name", name).set("status", status);
 *
 * // DELETE
 * Sql.delete("users").where("id = ?", id);
 * }</pre>
 */
public final class Sql {

    private final String head;
    private final List<Condition> conditions;
    private final String tail;
    private final Object[] args;
    private final boolean compoundQuery;
    private final List<Cte> ctes;

    /**
     * DML assignments — one triple serves both INSERT and UPDATE, since a
     * statement is exactly one of them (never both): {@code head == null}
     * means INSERT built via {@link #insert(String)}, otherwise UPDATE via
     * {@link #update(String)}.
     *
     * <p>{@code dmlTargets} holds raw column names for INSERT and
     * {@code "col = ?"} expressions for UPDATE; {@code dmlValues} holds the
     * corresponding bound values.
     *
     * <p>Kept separate from {@link #args} (the WHERE/HAVING values): binding
     * order must follow SQL text order — SET/VALUES before WHERE — regardless
     * of the fluent-call order. A single merged list would only work if the
     * API enforced set-before-where; it deliberately does not.
     */
    private final String dmlTable;
    private final List<String> dmlTargets;
    private final List<Object> dmlValues;

    private Sql(
        String head,
        List<Condition> conditions,
        String tail,
        Object[] args,
        boolean compoundQuery,
        List<Cte> ctes,
        String dmlTable,
        List<String> dmlTargets,
        List<Object> dmlValues
    ) {
        this.head = head;
        this.conditions = conditions;
        this.tail = tail;
        this.args = args;
        this.compoundQuery = compoundQuery;
        this.ctes = ctes;
        this.dmlTable = dmlTable;
        this.dmlTargets = dmlTargets;
        this.dmlValues = dmlValues;
    }

    // ====================== static factories ======================

    /** SELECT:{@code Sql.select("id, name").from("users").where(...)} */
    public static Sql select(String columns) {
        return new Sql("SELECT " + columns, List.of(), "", new Object[0],
            false, List.of(), null, List.of(), List.of());
    }

    /** UPDATE:{@code Sql.update("users").set("name = ?", v).where("id = ?", id)} */
    public static Sql update(String tableName) {
        return new Sql("UPDATE " + tableName, List.of(), "", new Object[0],
            false, List.of(), tableName, List.of(), List.of());
    }

    /** INSERT:{@code Sql.insert("users").set("name", v).set("status", v)} */
    public static Sql insert(String tableName) {
        return new Sql(null, List.of(), "", new Object[0],
            false, List.of(), tableName, List.of(), List.of());
    }

    /** DELETE:{@code Sql.delete("users").where("id = ?", id)} */
    public static Sql delete(String tableName) {
        return new Sql("DELETE FROM " + tableName, List.of(), "", new Object[0],
            false, List.of(), null, List.of(), List.of());
    }

    public Sql with(String name, Sql query) {
        return with(name, null, query);
    }

    public Sql with(String name, String columns, Sql query) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(query, "query");
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("CTE name must not be blank");
        }
        List<Cte> newCtes = new ArrayList<>(ctes);
        newCtes.add(new Cte(trimmed, columns, query));
        return new Sql(head, conditions, tail, args,
            compoundQuery, newCtes,
            dmlTable, dmlTargets, dmlValues);
    }

    // ====================== FROM / JOIN ======================

    public Sql from(String tables) {
        requireSimpleSelect("FROM");
        requireNoPendingJoin("FROM");
        return withHead(head + " FROM " + tables);
    }

    public Sql join(String table) {
        requireSimpleSelect("JOIN");
        requireNoPendingJoin("JOIN");
        return withHead(head + " JOIN " + table);
    }

    public Sql leftJoin(String table) {
        requireSimpleSelect("LEFT JOIN");
        requireNoPendingJoin("LEFT JOIN");
        return withHead(head + " LEFT JOIN " + table);
    }

    public Sql innerJoin(String table) {
        requireSimpleSelect("INNER JOIN");
        requireNoPendingJoin("INNER JOIN");
        return withHead(head + " INNER JOIN " + table);
    }

    public Sql on(String expr) {
        requireSimpleSelect("ON");
        requirePendingJoin("ON");
        return withHead(head + " ON " + expr);
    }

    // ====================== WHERE conditions ======================

    /** {@code WHERE expr} (or {@code AND expr} when a condition already exists). */
    public Sql where(String expr, Object... values) {
        requireWhereAllowed("WHERE");
        String connector = conditions.isEmpty() ? "" : "AND";
        return addCondition(connector, expr, values);
    }

    /** {@code OR expr} */
    public Sql orWhere(String expr, Object... values) {
        requireWhereAllowed("OR WHERE");
        return addCondition("OR", expr, values);
    }

    /** {@code AND NOT expr} */
    public Sql whereNot(String expr, Object... values) {
        requireWhereAllowed("WHERE NOT");
        return addCondition(notConnector(conditions), expr, values);
    }

    public Sql whereGroup(Consumer<Group> builder) {
        requireWhereAllowed("WHERE GROUP");
        return addGroupedCondition(andConnector(conditions), builder);
    }

    public Sql orWhereGroup(Consumer<Group> builder) {
        requireWhereAllowed("OR WHERE GROUP");
        return addGroupedCondition("OR", builder);
    }

    public Sql whereNotGroup(Consumer<Group> builder) {
        requireWhereAllowed("WHERE NOT GROUP");
        return addGroupedCondition(notConnector(conditions), builder);
    }

    private Sql addCondition(String connector, String expr, Object... values) {
        NormalizedFragment parsed = normalizeArgs(expr, values);

        List<Condition> newConds = new ArrayList<>(conditions);
        newConds.add(new Condition(connector, parsed.expr()));

        return new Sql(
            head, newConds, tail,
            concat(args, parsed.args()),
            compoundQuery,
            ctes,
            dmlTable, dmlTargets, dmlValues);
    }

    private Sql addGroupedCondition(String connector, Consumer<Group> builder) {
        Objects.requireNonNull(builder, "builder");
        Group group = new Group();
        builder.accept(group);
        if (group.conditions.isEmpty()) {
            throw new IllegalStateException("Group must contain at least one condition");
        }
        return addCondition(connector, group.sql(), group.args());
    }

    // ====================== ORDER BY / GROUP BY / HAVING ======================

    public Sql orderBy(String clause) {
        requireSelectable("ORDER BY");
        requireNoPendingJoin("ORDER BY");
        requireBeforeLimit("ORDER BY");
        return withTail(" ORDER BY " + clause);
    }

    public Sql groupBy(String columns) {
        requireSimpleSelect("GROUP BY");
        requireNoPendingJoin("GROUP BY");
        requireBeforeLimit("GROUP BY");
        return withTail(" GROUP BY " + columns);
    }

    public Sql having(String expr, Object... values) {
        requireSimpleSelect("HAVING");
        requireNoPendingJoin("HAVING");
        requireBeforeLimit("HAVING");
        NormalizedFragment parsed = normalizeArgs(expr, values);

        String t = tail.isEmpty() ? " HAVING " : tail + " HAVING ";
        return new Sql(head, conditions, t + parsed.expr(),
            concat(args, parsed.args()),
            compoundQuery,
            ctes,
            dmlTable, dmlTargets, dmlValues);
    }

    public Sql havingGroup(Consumer<Group> builder) {
        requireSimpleSelect("HAVING GROUP");
        requireNoPendingJoin("HAVING GROUP");
        Objects.requireNonNull(builder, "builder");
        Group group = new Group();
        builder.accept(group);
        if (group.conditions.isEmpty()) {
            throw new IllegalStateException("Group must contain at least one condition");
        }

        String t = tail.isEmpty() ? " HAVING " : tail + " HAVING ";
        return new Sql(head, conditions, t + group.sql(),
            concat(args, group.args()),
            compoundQuery,
            ctes,
            dmlTable, dmlTargets, dmlValues);
    }

    // ====================== LIMIT / OFFSET ======================

    public Sql limit(int n) {
        requireSelectable("LIMIT");
        requireNoPendingJoin("LIMIT");
        if (n < 0) throw new IllegalArgumentException("LIMIT must be >= 0, got " + n);
        if (tail.contains(" OFFSET ")) {
            throw new IllegalStateException("LIMIT must be called before OFFSET");
        }
        return withTail(" LIMIT " + n);
    }

    public Sql offset(int n) {
        requireSelectable("OFFSET");
        requireNoPendingJoin("OFFSET");
        if (n < 0) throw new IllegalArgumentException("OFFSET must be >= 0, got " + n);
        if (!tail.contains(" LIMIT ")) {
            throw new IllegalStateException(
                "OFFSET requires a preceding LIMIT — bare OFFSET is invalid SQL on MySQL and SQLite"
            );
        }
        return withTail(" OFFSET " + n);
    }

    public Sql union(Sql other) {
        return combineCompound("UNION", other);
    }

    public Sql unionAll(Sql other) {
        return combineCompound("UNION ALL", other);
    }

    /**
     * Return columns for INSERT / UPDATE / DELETE.
     *
     * <p>RETURNING produces rows, so consume the statement via
     * {@link Database#query(Sql)} (or {@link Database#query(String, Object...)}
     * with {@link #sql()} / {@link #args()}) to read the returned columns;
     * {@link Database#execute(Sql)} discards RETURNING output and only reports
     * affected rows. Dialects without RETURNING (MySQL/MariaDB) reject the
     * statement through the {@code Database} convenience methods, which
     * validate against the target dialect.
     */
    public Sql returning(String columns) {
        Objects.requireNonNull(columns, "columns");
        if (!isDml()) {
            throw new IllegalStateException("RETURNING is only supported for INSERT/UPDATE/DELETE");
        }
        requireNoPendingJoin("RETURNING");
        return withTail(" RETURNING " + columns);
    }

    /** INSERT ... ON CONFLICT (...)。 */
    public Sql onConflict(String targetColumns) {
        requireInsert("ON CONFLICT");
        Objects.requireNonNull(targetColumns, "targetColumns");
        return withTail(" ON CONFLICT (" + targetColumns + ")");
    }

    /** INSERT ... DO NOTHING。 */
    public Sql doNothing() {
        requireInsert("DO NOTHING");
        return withTail(" DO NOTHING");
    }

    /** INSERT ... DO UPDATE SET ...。 */
    public Sql doUpdateSet(String assignments) {
        requireInsert("DO UPDATE SET");
        Objects.requireNonNull(assignments, "assignments");
        return withTail(" DO UPDATE SET " + assignments);
    }

    // ====================== SET (UPDATE / INSERT) ======================

    /**
    * Used for UPDATE or INSERT.
     * <p>
    * <b>UPDATE</b>: {@code .set("name = ?", value)} — full expression
     * <br>
    * <b>INSERT</b>: {@code .set("name", value)} — column name + value
     */
    public Sql set(String expr, Object value) {
        requireUpdateOrInsert("SET");
        requireNoPendingJoin("SET");
        if (head == null) {
            // INSERT mode: set("col", value)
            if (
                expr.indexOf('?') >= 0 ||
                expr.indexOf(':') >= 0 ||
                expr.indexOf('$') >= 0 ||
                expr.indexOf('=') >= 0 ||
                expr.indexOf(' ') >= 0 ||
                expr.indexOf('(') >= 0
            ) {
                throw new IllegalArgumentException(
                    "INSERT set() takes a plain column name, not an expression: \""
                        + expr + "\" — use set(\"column\", value)"
                );
            }
            List<String> newTargets = new ArrayList<>(dmlTargets);
            newTargets.add(expr);
            List<Object> newValues = new ArrayList<>(dmlValues);
            newValues.add(value);
            return new Sql(head, conditions, tail, args,
                compoundQuery, ctes, dmlTable, newTargets, newValues);
        }
        // UPDATE mode: set("col = ?", value)
        NormalizedFragment parsed = normalizeArgs(expr, value);

        List<String> newTargets = new ArrayList<>(dmlTargets);
        newTargets.add(parsed.expr());

        List<Object> newValues = new ArrayList<>(dmlValues);
        appendArgs(newValues, parsed.args());

        return new Sql(head, conditions, tail, args,
            compoundQuery, ctes, dmlTable, newTargets, newValues);
    }

    // ====================== output ======================

    /** Produces the complete SQL string. */
    public String sql() {
        requireNoPendingJoin("render SQL");
        return buildSql();
    }

    /**
     * Produces the SQL string and validates it against the given dialect.
     * Throws {@link SqlException} if the SQL uses features the dialect does not
     * support (e.g. {@code RETURNING} on MySQL, {@code ON CONFLICT} on MySQL).
     */
    public String sql(Dialect dialect) {
        Objects.requireNonNull(dialect, "dialect");
        String result = sql();
        if (tail.contains("RETURNING") && !dialect.supportsReturning()) {
            throw new SqlException(
                "Dialect '" + dialect.dialectId() + "' does not support RETURNING");
        }
        if (tail.contains("ON CONFLICT") && !dialect.supportsOnConflict()) {
            throw new SqlException(
                "Dialect '" + dialect.dialectId() + "' does not support ON CONFLICT; use upsertClause() or raw SQL");
        }
        return result;
    }

    private String buildSql() {
        String withClause = renderWithClause();
        if (isInsert()) {
            if (dmlTargets.isEmpty()) {
                throw new SqlException(
                    "INSERT requires at least one column — call set(\"column\", value) before building"
                );
            }
            var cols = String.join(", ", dmlTargets);
            var placeholders = String.join(", ", dmlValues.stream().map(v -> "?").toList());
            return withClause + "INSERT INTO " + dmlTable + " (" + cols + ") VALUES (" + placeholders + ")" + tail;
        }
        if (isUpdate() && dmlTargets.isEmpty()) {
            // Emitting "UPDATE t WHERE ..." without SET would silently touch
            // every matching row on dialects that tolerate the omission.
            throw new SqlException(
                "UPDATE requires at least one SET clause — call set(\"col = ?\", value) before building"
            );
        }
        var sb = new StringBuilder(head);

        if (!dmlTargets.isEmpty()) {
            sb.append(" SET ");
            sb.append(String.join(", ", dmlTargets));
        }

        if (!conditions.isEmpty()) {
            sb.append(" WHERE ");
            sb.append(renderConditions(conditions));
        }

        if (!tail.isEmpty()) {
            sb.append(tail);
        }
        return withClause + sb;
    }

    /**
     * Returns the arguments ordered by {@code ?} position.
     * Pass directly to {@link Database#query(String, Object...)} or {@link Database#execute(String, Object...)}.
     */
    public Object[] args() {
        Object[] cteArgs = cteArgs();
        if (isInsert()) {
            return concat(cteArgs, dmlValues.toArray());
        }
        // UPDATE binds SET values before WHERE values; SELECT/DELETE carry no
        // SET values — concat's empty short-circuit covers that case.
        return concat(cteArgs, concat(dmlValues.toArray(), args));
    }

    // ====================== internals ======================

    private record Condition(String connector, String expr) {}

    private record Cte(String name, String columns, Sql query) {}

    /**
     * Result of normalizing a caller-supplied SQL fragment: the rewritten
     * text with every placeholder unified to {@code ?}, plus the bound
     * values in placeholder order.
     */
    private record NormalizedFragment(String expr, Object[] args) {}

    public static final class Group {
        private final List<Condition> conditions = new ArrayList<>();
        private final List<Object> args = new ArrayList<>();

        private Group() {
        }

        public Group where(String expr, Object... values) {
            return addCondition(andConnector(conditions), expr, values);
        }

        public Group orWhere(String expr, Object... values) {
            return addCondition("OR", expr, values);
        }

        public Group whereNot(String expr, Object... values) {
            return addCondition(notConnector(conditions), expr, values);
        }

        public Group whereGroup(Consumer<Group> builder) {
            return addGroupedCondition(andConnector(conditions), builder);
        }

        public Group orWhereGroup(Consumer<Group> builder) {
            return addGroupedCondition("OR", builder);
        }

        public Group whereNotGroup(Consumer<Group> builder) {
            return addGroupedCondition(notConnector(conditions), builder);
        }

        public String sql() {
            if (conditions.isEmpty()) {
                throw new IllegalStateException("Group must contain at least one condition");
            }
            return "(" + renderConditions(conditions) + ")";
        }

        public Object[] args() {
            return args.toArray();
        }

        private Group addCondition(String connector, String expr, Object... values) {
            NormalizedFragment parsed = normalizeArgs(expr, values);
            conditions.add(new Condition(connector, parsed.expr()));
            appendArgs(args, parsed.args());
            return this;
        }

        private Group addGroupedCondition(String connector, Consumer<Group> builder) {
            Objects.requireNonNull(builder, "builder");
            Group group = new Group();
            builder.accept(group);
            if (group.conditions.isEmpty()) {
                throw new IllegalStateException("Group must contain at least one condition");
            }
            return addCondition(connector, group.sql(), group.args());
        }
    }

    private Sql withHead(String newHead) {
        return new Sql(newHead, conditions, tail, args,
            compoundQuery, ctes,
            dmlTable, dmlTargets, dmlValues);
    }

    private Sql withTail(String newTail) {
        return new Sql(head, conditions, tail + newTail, args,
            compoundQuery, ctes,
            dmlTable, dmlTargets, dmlValues);
    }

    private String renderWithClause() {
        if (ctes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("WITH ");
        for (int i = 0; i < ctes.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Cte cte = ctes.get(i);
            sb.append(cte.name());
            if (cte.columns() != null && !cte.columns().isBlank()) {
                sb.append(" (").append(cte.columns().trim()).append(")");
            }
            sb.append(" AS (").append(cte.query().sql()).append(")");
        }
        sb.append(' ');
        return sb.toString();
    }

    private Object[] cteArgs() {
        if (ctes.isEmpty()) {
            return new Object[0];
        }
        List<Object> values = new ArrayList<>();
        for (Cte cte : ctes) {
            appendArgs(values, cte.query().args());
        }
        return values.toArray();
    }

    private Sql combineCompound(String operator, Sql other) {
        requireSelectable(operator);
        requireNoPendingJoin(operator);
        requireNoOuterTail(operator);
        Objects.requireNonNull(other, "other");
        if (!ctes.isEmpty() || !other.ctes.isEmpty()) {
            throw new IllegalStateException(operator + " does not support WITH clauses");
        }
        if (!other.isSelectable()) {
            throw new IllegalStateException(operator + " requires a SELECT query");
        }
        if (!other.tail.isEmpty()) {
            throw new IllegalStateException(operator + " requires the right query to finish before ORDER BY/LIMIT/OFFSET");
        }

        String combined = "(" + sql() + ") " + operator + " (" + other.sql() + ")";
        Object[] combinedArgs = concat(args(), other.args());
        return new Sql(combined, List.of(), "", combinedArgs,
            true, List.of(), null, List.of(), List.of());
    }

    private void requireSimpleSelect(String operation) {
        if (!isSelect() || compoundQuery) {
            throw new IllegalStateException(operation + " is only supported for a plain SELECT");
        }
    }

    private void requireSelectable(String operation) {
        if (!isSelectable()) {
            throw new IllegalStateException(operation + " is only supported for SELECT");
        }
    }

    private void requireNoOuterTail(String operation) {
        if (!tail.isEmpty()) {
            throw new IllegalStateException(operation + " must be called before ORDER BY/LIMIT/OFFSET");
        }
    }

    private void requireInsert(String operation) {
        if (!isInsert()) {
            throw new IllegalStateException(operation + " is only supported for INSERT");
        }
    }

    private void requireUpdateOrInsert(String operation) {
        if (!isUpdate() && !isInsert()) {
            throw new IllegalStateException(operation + " is only supported for UPDATE/INSERT");
        }
    }

    private void requireWhereAllowed(String operation) {
        if (isInsert()) {
            throw new IllegalStateException(operation + " is not supported for INSERT");
        }
        if (compoundQuery) {
            throw new IllegalStateException(operation + " is not supported for compound SELECT");
        }
        requireNoPendingJoin(operation);
    }

    private void requirePendingJoin(String operation) {
        if (!hasPendingJoin()) {
            throw new IllegalStateException(operation + " requires a pending JOIN without ON");
        }
    }

    private void requireNoPendingJoin(String operation) {
        if (hasPendingJoin()) {
            throw new IllegalStateException(operation + " cannot be called before JOIN is completed with ON");
        }
    }

    /** ORDER BY / GROUP BY / HAVING must be emitted before LIMIT/OFFSET. */
    private void requireBeforeLimit(String operation) {
        if (tail.contains(" LIMIT ") || tail.contains(" OFFSET ")) {
            throw new IllegalStateException(
                operation + " must be called before LIMIT/OFFSET"
            );
        }
    }

    private boolean isSelect() {
        return head != null
            && dmlTable == null
            && head.startsWith("SELECT ");
    }

    private boolean isSelectable() {
        return isSelect() || compoundQuery;
    }

    public boolean isInsert() {
        // head == null identifies the INSERT factory — UPDATE carries its
        // "UPDATE <table>" head; SELECT/DELETE never hold a dmlTable.
        return dmlTable != null && head == null;
    }

    private boolean isUpdate() {
        return dmlTable != null && head != null;
    }

    private boolean isDml() {
        return isInsert() || isUpdate() || isDelete();
    }

    private boolean isDelete() {
        return head != null && head.startsWith("DELETE ");
    }

    private boolean hasPendingJoin() {
        if (!isSelect() || head == null || compoundQuery) {
            return false;
        }
        int joinIndex = Math.max(
            Math.max(head.lastIndexOf(" LEFT JOIN "), head.lastIndexOf(" INNER JOIN ")),
            head.lastIndexOf(" JOIN ")
        );
        if (joinIndex < 0) {
            return false;
        }
        return head.indexOf(" ON ", joinIndex) < 0;
    }

    private static String renderConditions(List<Condition> conditions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conditions.size(); i++) {
            Condition c = conditions.get(i);
            if (i == 0) {
                if (!c.connector.isEmpty()) {
                    sb.append(c.connector).append(' ');
                }
            } else {
                sb.append(' ').append(c.connector).append(' ');
            }
            sb.append(c.expr);
        }
        return sb.toString();
    }

    private static String andConnector(List<Condition> conditions) {
        return conditions.isEmpty() ? "" : "AND";
    }

    private static String notConnector(List<Condition> conditions) {
        return conditions.isEmpty() ? "NOT" : "AND NOT";
    }

    private static void appendArgs(List<Object> target, Object[] values) {
        for (Object value : values) {
            target.add(value);
        }
    }

    /**
     * Replaces named ({@code :name / $name}) and positional ({@code ?})
     * placeholders with {@code ?} and extracts their values in order.
     * String literals, quoted identifiers, and comments are skipped so
     * placeholders inside them are not mistaken for parameters. A named
     * parameter repeated within the same fragment reuses its first value.
     *
     * <p>Fragments are normalized with the lenient {@link SqlTextParser.LexerConfig#SUPERSET}
     * profile (no database is bound at build time); execution re-parses the
     * result against the target database's dialect.
     *
     * <p>{@code #} is treated as a line comment (MySQL semantics, with the
     * {@code #>} / {@code #>>} jsonb operators exempted) — so PostgreSQL's
     * bare {@code #} XOR operator is not a comment here. A fragment such as
     * {@code where("flags # 8 = ?", v)} therefore fails the placeholder count
     * at build time; write the XOR as {@code (flags # 8) = ?} with no
     * placeholder inside the operator's span, or use the {@code ?} operator's
     * function form on the target database.
     */
    private static NormalizedFragment normalizeArgs(String fragment, Object... values) {
        var sb = new StringBuilder(fragment.length());
        var matched = new ArrayList<>();
        var seen = new HashMap<String, Object>();
        class Normalizer implements SqlTextParser.TokenSink {
            int vi;

            @Override
            public void text(String sql, int from, int to) {
                sb.append(sql, from, to);
            }

            @Override
            public void named(String name, int sourceIndex) {
                if (seen.containsKey(name)) {
                    // repeated named parameter — reuse the first value
                    appendValue(sb, matched, seen.get(name));
                } else if (vi < values.length) {
                    Object value = values[vi++];
                    seen.put(name, value);
                    appendValue(sb, matched, value);
                } else {
                    throw new SqlException(
                        "Missing value for named parameter at position " + sourceIndex
                            + " in fragment: " + fragment);
                }
            }

            @Override
            public void positional(int sourceIndex) {
                if (vi < values.length) {
                    appendValue(sb, matched, values[vi++]);
                } else {
                    throw new SqlException(
                        "Missing value for '?' at position " + sourceIndex + " in fragment: " + fragment);
                }
            }
        }
        Normalizer normalizer = new Normalizer();
        SqlTextParser.scan(fragment, SqlTextParser.LexerConfig.SUPERSET, normalizer);

        if (normalizer.vi < values.length) {
            throw new SqlException(
                "Too many parameter values for SQL fragment: " + fragment
                    + " — " + normalizer.vi + " placeholder(s) but " + values.length + " value(s) provided");
        }

        return new NormalizedFragment(sb.toString(), matched.toArray());
    }

    private static void appendValue(StringBuilder sb, List<Object> matched, Object value) {
        if (value instanceof Sql sql) {
            sb.append(sql.sql());
            appendArgs(matched, sql.args());
            return;
        }
        sb.append('?');
        matched.add(value);
    }

    private static Object[] concat(Object[] a, Object[] b) {
        if (a.length == 0) return b;
        if (b.length == 0) return a;
        var result = new Object[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Sql sql)) return false;
        String s = sql(); return Objects.equals(s, sql.sql());
    }

    @Override
    public int hashCode() {
        return Objects.hash(sql());
    }

    @Override
    public String toString() {
        return sql();
    }
}
