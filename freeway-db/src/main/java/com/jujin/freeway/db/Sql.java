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
 * Sql.update("users").setExpression("name = ?", name).setExpression("status = ?", status)
 *     .where("id = ?", id);
 *
 * // INSERT
 * Sql.insert("users").setColumn("name", name).setColumn("status", status);
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
    /** The compound keyword in force ({@code UNION} / {@code UNION ALL}), or null. */
    private final String compoundQuery;
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
        String compoundQuery,
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
            null, List.of(), null, List.of(), List.of());
    }

    /** UPDATE:{@code Sql.update("users").setExpression("name = ?", v).where("id = ?", id)} */
    public static Sql update(String tableName) {
        return new Sql("UPDATE " + tableName, List.of(), "", new Object[0],
            null, List.of(), tableName, List.of(), List.of());
    }

    /** INSERT:{@code Sql.insert("users").setColumn("name", v).setColumn("status", v)} */
    public static Sql insert(String tableName) {
        return new Sql(null, List.of(), "", new Object[0],
            null, List.of(), tableName, List.of(), List.of());
    }

    /** DELETE:{@code Sql.delete("users").where("id = ?", id)} */
    public static Sql delete(String tableName) {
        return new Sql("DELETE FROM " + tableName, List.of(), "", new Object[0],
            null, List.of(), null, List.of(), List.of());
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

    /**
     * {@code WHERE expr} (or {@code AND expr} when a condition already exists).
     *
     * <p>A {@link Sql} among the values must arrive through parentheses:
     * {@code where("id in (?)", sub)}. Spliced bare it merges the subquery's own
     * {@code WHERE} into this one, and the statement is rejected by the driver
     * rather than here — so a bare splice is refused at build time, with the fix
     * in the message. For a column slot use {@link #setColumn(String, Object)},
     * which parenthesizes for you.
     */
    public Sql where(String expr, Object... values) {
        requireWhereAllowed("WHERE");
        return addCondition(andConnector(conditions), expr, values);
    }

    /** {@code OR expr} — or plain {@code expr} as the first condition,
     *  where {@code OR} would have nothing to connect. */
    public Sql orWhere(String expr, Object... values) {
        requireWhereAllowed("OR WHERE");
        return addCondition(orConnector(conditions), expr, values);
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
        return addGroupedCondition(orConnector(conditions), builder);
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
        Group group = buildGroup(builder);
        return addCondition(connector, group.sql(), group.args());
    }

    /**
     * Runs a group builder and rejects an empty result. Shared with
     * {@link Group}, which nests the same way: the two differ in how a
     * condition is stored — this class is immutable and copies its lists,
     * {@code Group} appends in place — so only the builder step is common.
     */
    private static Group buildGroup(Consumer<Group> builder) {
        Objects.requireNonNull(builder, "builder");
        Group group = new Group();
        builder.accept(group);
        if (group.conditions.isEmpty()) {
            throw new IllegalStateException("Group must contain at least one condition");
        }
        return group;
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
     * One INSERT assignment: a plain column name plus its value
     * ({@code Sql.insert("users").setColumn("name", name)}).
     *
     * <p>INSERT-only, and the name says so — the mode used to be guessed from
     * the fragment's characters, which rejected legitimate quoted column names
     * such as {@code "`my col`"} and deferred real misuse to placeholder
     * counting.</p>
     *
     * <p>The column name is <b>validated as a name</b>, not quoted: this builder
     * is dialect-free until {@link #sql(Dialect)}, so it has no quoting rules to
     * apply, and a name that needs quoting must arrive quoted. Each part of a
     * qualified name is checked on its own, and the bare and quoted forms
     * compose — {@code tenant_id}, {@code users.name}, {@code `my col`},
     * {@code [my col]}, {@code public}."My Col" and {@code `db`.`table`.col} are
     * all accepted, as is a doubled delimiter inside a quoted part
     * ({@code "a""b"}). Anything else — {@code "a = 1, evil"} — is an expression,
     * and expressions belong in {@link #setExpression(String, Object)}. The
     * validation is what keeps a request-derived name from becoming SQL.</p>
     *
     * <p>A {@link Sql} value is SQL rather than data: it renders as a
     * <b>parenthesized scalar subquery</b> in this column's slot, and its
     * parameters ride along at that position. So
     * {@code setColumn("tenant_id", Sql.select("id").from("tenants"))} renders
     * {@code VALUES ((SELECT id FROM tenants))} — the form every dialect
     * accepts, and the only shape of a nested query that fits one column. The
     * parentheses are the renderer's because this API takes a bare name and a
     * value, not a fragment; in {@link #setExpression(String, Object)} the
     * caller writes the fragment and therefore writes them.</p>
     */
    public Sql setColumn(String column, Object value) {
        requireUpdateOrInsert("setColumn");
        if (!isInsert()) {
            throw new IllegalStateException(
                "setColumn() is INSERT-only — use setExpression(\"column = ?\", value)"
                    + " for UPDATE"
            );
        }
        requireNoPendingJoin("setColumn");
        requireColumnName(column);
        List<String> newTargets = new ArrayList<>(dmlTargets);
        newTargets.add(column);
        List<Object> newValues = new ArrayList<>(dmlValues);
        // A Sql value is SQL, not data: splice it in as a parenthesized scalar
        // subquery and carry its placeholders over. Without this the object
        // would be bound as a parameter and the statement would silently mean
        // something else. The parentheses are added at render time
        // (renderInsertValues) because this is the one path with no fragment in
        // which a caller could write them — a fragment method leaves the
        // grouping to its caller (setExpression("tenant_id = (?)", sub)).
        if (value instanceof Sql nested) {
            requireScalarSubquery(nested);
            newValues.add(new InlineValue(nested.sql(), nested.args()));
        } else {
            newValues.add(value);
        }
        return new Sql(head, conditions, tail, args,
            compoundQuery, ctes, dmlTable, newTargets, newValues);
    }

    /**
     * Rejects a nested {@link Sql} that cannot stand in a column slot.
     *
     * <p>The slot renders as a parenthesized scalar subquery, so the value must
     * be a plain {@code SELECT}. Anything else renders syntactically valid
     * nonsense — a {@code UNION} parenthesized as an expression, an
     * {@code INSERT} spliced into a {@code VALUES} row — and renders without
     * complaint, which is the part that matters: the failure would otherwise be
     * the database's, far from the call that made it. A {@code WITH} query is
     * fine; it is still a {@code SELECT}.
     * </p>
     */
    private void requireScalarSubquery(Sql nested) {
        if (nested.isSelect()) {
            return;
        }
        throw new IllegalArgumentException(
            "setColumn() renders a Sql value as a scalar subquery, but "
                + describe(nested) + " is not a plain SELECT — a column slot "
                + "holds one row-producing query, and a compound (UNION) or a "
                + "DML statement parenthesized as an expression is not one. "
                + "Use a SELECT here, or build the statement yourself."
        );
    }

    /**
     * What kind of statement this is, for an error message. Deliberately not the
     * statement's own text: it can be arbitrarily long and carry bind values,
     * and an exception message is the wrong place to spill either.
     */
    private static String describe(Sql sql) {
        if (sql.isInsert()) {
            return "an INSERT";
        }
        if (sql.isUpdate()) {
            return "an UPDATE";
        }
        if (sql.compoundQuery != null) {
            return "a compound query (" + sql.compoundQuery + ")";
        }
        // head == null identifies the INSERT factory and is covered above, so
        // what reaches here is a statement with a head: DELETE, or a SELECT
        // that failed the isSelect() shape check.
        return "a " + (sql.head == null ? "statement" : sql.head.split("\\s+", 2)[0]);
    }

    /**
     * A column name, or a name the caller has already quoted for their target
     * dialect. Each part of a qualified name is checked on its own, and the bare
     * and quoted forms compose. What is rejected is anything carrying SQL
     * structure — an operator, a comma, an unbalanced quote, a comment marker —
     * so that a name assembled from request data cannot become an expression or
     * a second column. It is not a dialect's quoting implementation: this builder
     * is dialect-free until {@link #sql(Dialect)}, so a name needing quoting must
     * arrive quoted, and the quotes it accepts come from the same dialect-free
     * superset the fragment scanner uses
     * ({@code SqlTextParser.LexerConfig.closingIdentifierQuote}) — one list, not
     * two.
     */
    private static void requireColumnName(String column) {
        Objects.requireNonNull(column, "column");
        // A qualified name is a dot-separated list of parts, and each part is
        // independently bare or quoted — "public".\"My Col\" and
        // `db`.`table`.col are how PostgreSQL and MySQL spell a qualified name
        // whose parts need quoting, so the two forms compose rather than being
        // alternatives. A dot inside a QUOTED part is that part's own character
        // (`arr[0]`-style names, or a column literally named "a.b"), which is
        // why the split only happens between parts, never inside quotes.
        int start = 0;
        while (true) {
            int dot = indexOfUnquotedDot(column, start);
            if (!isNamePart(column, start, dot < 0 ? column.length() : dot)) {
                throw notAColumnName(column);
            }
            if (dot < 0) {
                return;
            }
            start = dot + 1;
        }
    }

    /**
     * The next '.' that is not inside a quoted part, or -1.
     *
     * <p>Opens and closes are tracked separately because {@code [} and {@code ]}
     * are different characters — matching the opening one would never close the
     * quote, and every following dot would then read as part of the name.
     */
    private static int indexOfUnquotedDot(String column, int from) {
        char close = 0;
        for (int i = from; i < column.length(); i++) {
            char c = column.charAt(i);
            if (close == 0) {
                close = SqlTextParser.LexerConfig.closingIdentifierQuote(c);
                if (close == 0 && c == '.') {
                    return i;
                }
            } else if (c == close) {
                close = 0;
            }
        }
        return -1;
    }

    /** One part of a qualified name: a bare identifier, or a quoted one. */
    private static boolean isNamePart(String column, int from, int to) {
        int length = to - from;
        if (length <= 0) {
            return false;
        }
        char first = column.charAt(from);
        char last = column.charAt(to - 1);
        if (paired(first, last)) {
            return isQuotedPart(column, from, to, first);
        }
        // Unquoted: every character must be legal in a bare identifier, so a
        // comma, an operator, a space or a stray quote cannot get in.
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int i = from + 1; i < to; i++) {
            char c = column.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '$') {
                return false;
            }
        }
        return true;
    }

    private static boolean paired(char first, char last) {
        return last != 0 && SqlTextParser.LexerConfig.closingIdentifierQuote(first) == last;
    }

    /**
     * A quoted part. The caller has already decided the delimiters, so this only
     * checks that the closing one does not appear bare inside — a name that closes
     * its own quoting is the one thing this check exists to catch. A doubled
     * delimiter is the standard way to write one inside a quoted identifier
     * ({@code "a""b"} is the name {@code a"b}), so it is allowed.
     */
    private static boolean isQuotedPart(String column, int from, int to, char open) {
        char close = SqlTextParser.LexerConfig.closingIdentifierQuote(open);
        String inner = column.substring(from + 1, to - 1);
        if (inner.isBlank()) {
            return false;
        }
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c != close) {
                continue;
            }
            // A doubled delimiter is the escape; a single one ends the name and
            // whatever followed would be SQL again.
            boolean doubled = i + 1 < inner.length() && inner.charAt(i + 1) == close;
            if (!doubled) {
                return false;
            }
            i++;
        }
        return true;
    }

    private static IllegalArgumentException notAColumnName(String column) {
        // The name is echoed so the reader can see which part failed, but
        // flattened: a name carrying newlines would otherwise turn one message
        // into several lines of log.
        return new IllegalArgumentException(
            "'" + column.replaceAll("\\s+", " ") + "' is not a column name — "
                + "setColumn() takes one column per value, and expressions belong "
                + "in setExpression(\"column = ?\", value). A name may be qualified "
                + "(users.name) and any part may be quoted for your dialect "
                + "(`my col`, \"my col\", [my col])."
        );
    }

    /**
     * One UPDATE assignment as a full expression
     * ({@code Sql.update("users").setExpression("name = ?", name)}).
     *
     * <p>UPDATE-only; placeholders are normalized like everywhere else
     * ({@code ?}, {@code :name} and {@code $1} all become {@code ?}).</p>
     *
     * <p>A fragment is raw SQL you have written, so you own the grouping: a
     * {@link Sql} value must arrive through parentheses —
     * {@code setExpression("tenant_id = (?)", sub)} — the same rule every
     * fragment method follows and enforces (see {@link #where}). {@link
     * #setColumn} is the one that differs, because a column name is not a
     * fragment and there is nowhere for you to write them.</p>
     */
    public Sql setExpression(String expr, Object value) {
        requireUpdateOrInsert("setExpression");
        if (!isUpdate()) {
            throw new IllegalStateException(
                "setExpression() is UPDATE-only — use setColumn(\"column\", value)"
                    + " for INSERT"
            );
        }
        requireNoPendingJoin("setExpression");
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
            var placeholders = renderInsertValues(dmlValues);
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
            return concat(cteArgs, flattened(dmlValues));
        }
        // UPDATE binds SET values before WHERE values; SELECT/DELETE carry no
        // SET values — concat's empty short-circuit covers that case.
        return concat(cteArgs, concat(flattened(dmlValues), args));
    }

    /**
     * The bind values of a DML assignment list, in SQL text order.
     *
     * <p>An {@link InlineValue} stands for one column whose text is already
     * rendered, so it contributes its own parameters at that position rather
     * than being bound as a value. The expansion is positional, which is what
     * keeps the flattened list aligned with the placeholders in
     * {@link #buildSql()} — the subquery's {@code ?}s sit inside its text, in
     * the same position this expansion puts their values.
     * </p>
     */
    private static Object[] flattened(List<Object> dmlValues) {
        if (dmlValues.stream().noneMatch(v -> v instanceof InlineValue)) {
            return dmlValues.toArray();
        }
        var out = new ArrayList<Object>(dmlValues.size());
        for (Object value : dmlValues) {
            if (value instanceof InlineValue inline) {
                appendArgs(out, inline.params());
            } else {
                out.add(value);
            }
        }
        return out.toArray();
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
            return addCondition(orConnector(conditions), expr, values);
        }

        public Group whereNot(String expr, Object... values) {
            return addCondition(notConnector(conditions), expr, values);
        }

        public Group whereGroup(Consumer<Group> builder) {
            return addGroupedCondition(andConnector(conditions), builder);
        }

        public Group orWhereGroup(Consumer<Group> builder) {
            return addGroupedCondition(orConnector(conditions), builder);
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
            Group group = buildGroup(builder);
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

        // Asymmetric on purpose. The left side needs no parentheses: UNION is
        // left-associative, so `(A ∪ B) ∪ C` and `(A) UNION (B) UNION (C)` are the
        // same query, and re-wrapping only nests the text a level per chained
        // call. The right side does need them — `A ∪ (B ∪ C)` is not
        // `A ∪ B ∪ C`, and dropping them would silently re-associate what the
        // caller grouped.
        String combined = (compoundQuery == null ? "(" + sql() + ")" : sql())
            + " " + operator + " (" + other.sql() + ")";
        Object[] combinedArgs = concat(args(), other.args());
        return new Sql(combined, List.of(), "", combinedArgs,
            operator, List.of(), null, List.of(), List.of());
    }

    private void requireSimpleSelect(String operation) {
        if (!isSelect() || compoundQuery != null) {
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
        if (compoundQuery != null) {
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
        return isSelect() || compoundQuery != null;
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
        if (!isSelect() || head == null || compoundQuery != null) {
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

    /**
     * The first condition carries no connector: an {@code OR} with nothing to
     * OR against would render {@code WHERE OR expr} — invalid SQL that only the
     * driver rejects. Same reasoning as {@link #andConnector}, and the two must
     * stay in step.
     */
    private static String orConnector(List<Condition> conditions) {
        return conditions.isEmpty() ? "" : "OR";
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

    /**
     * Splices a value into a fragment: {@code ?} for data, the nested query's own
     * text for a {@link Sql}.
     *
     * <p>A {@link Sql} must arrive through parentheses — {@code (?)} in the
     * fragment, {@code where("id in (?)", sub)} — and this is where that is
     * enforced. It belongs here rather than in prose because the failure without
     * it is a statement that reads as valid SQL, means something else, and is
     * rejected by the driver rather than by this call. {@link
     * #setColumn(String, Object)} does not come through here: it has no fragment
     * for the caller to write parentheses in, so it renders them itself.
     */
    private static void appendValue(StringBuilder sb, List<Object> matched, Object value) {
        if (value instanceof Sql sql) {
            requireParens(sb, sql);
            sb.append(sql.sql());
            appendArgs(matched, sql.args());
            return;
        }
        sb.append('?');
        matched.add(value);
    }

    /**
     * A {@code Sql} spliced into a fragment must arrive through parentheses.
     *
     * <p>Spliced bare it produces a statement that reads as valid SQL and means
     * something else: {@code where("id in ?", sub)} renders {@code id in SELECT
     * user_id FROM orders WHERE total > ?}, and the subquery's own {@code WHERE}
     * merges into the enclosing one. Nothing here can catch that — the builder
     * does not parse SQL, and the error surfaces from the driver, far from the
     * call that made it.
     *
     * <p>So the caller owns the grouping and this checks they did: the
     * placeholder they wrote must be the one inside {@code (?)}. {@link
     * #setColumn} does not consult this — it has no fragment for the caller to
     * write parentheses in, so the renderer adds them itself.
     */
    private static void requireParens(StringBuilder sb, Sql spliced) {
        if (!atOpenParen(sb)) {
            throw new SqlException(
                "A Sql value spliced into a fragment needs its own parentheses — "
                    + "write \"(?)\" where the query goes, not \"?\", or the "
                    + "subquery merges into the enclosing statement. Splicing "
                    + describe(spliced) + " as-is would render "
                    + "… in SELECT … WHERE …, which the database rejects far "
                    + "from this call. For a column slot use setColumn(\"col\", "
                    + "sub), which parenthesizes for you."
            );
        }
    }

    /** Whether the text so far ends at an opening parenthesis. */
    private static boolean atOpenParen(StringBuilder sb) {
        for (int i = sb.length() - 1; i >= 0; i--) {
            char c = sb.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c == '(';
            }
        }
        return false;
    }

    /**
     * A value that is already SQL text, not a bind parameter — the INSERT-side
     * counterpart of what {@link #appendValue} does for a {@link Sql} inside a
     * fragment.
     *
     * <p>INSERT is positional: {@code dmlTargets} and {@code dmlValues} are
     * parallel, one column per entry, and {@code buildSql} joins them with
     * commas. A {@code Sql} value cannot ride in as an object — it would be
     * bound as a parameter and the statement would mean something else. So the
     * rendered text becomes the entry, and the nested query's own placeholders
     * ride along inside that same text. {@link #renderInsertValues} wraps that
     * text in parentheses, which is what makes it a scalar subquery rather than
     * a bare {@code SELECT} sitting in a VALUES row.
     * </p>
     *
     * <p>The nested parameters must not be added to {@code dmlValues} as
     * separate entries: that list is the column list, and an extra entry would
     * render an extra {@code ?} and shift every later column. They belong to
     * the text, so {@link #args()} reads them back off the wrapper.
     * </p>
     */
    private record InlineValue(String text, Object[] params) {
    }

    /**
     * Renders the VALUES list. Each entry is one column, so an inlined query
     * contributes its own text (placeholders included) and every other value
     * contributes exactly one {@code ?}.
     */
    private static String renderInsertValues(List<Object> dmlValues) {
        var out = new StringBuilder();
        for (int i = 0; i < dmlValues.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            Object value = dmlValues.get(i);
            // The parentheses are what make this a scalar subquery: a bare
            // `VALUES (SELECT ...)` is not an expression in PostgreSQL, MySQL
            // or SQLite, while `VALUES ((SELECT ...))` is.
            out.append(value instanceof InlineValue inline ? "(" + inline.text() + ")" : "?");
        }
        return out.toString();
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
