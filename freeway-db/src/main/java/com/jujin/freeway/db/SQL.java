package com.jujin.freeway.db;

import com.jujin.freeway.db.util.Names;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Immutable chainable SQL builder.
 * <p>
 * Fully compatible with {@link Database#sql(String, Object...)} — pass {@link #sql()} and {@link #args()}
 * directly. Supports {@code ?} positional parameters and {@code :name} / {@code $name} named parameters.
 * <p>
 * Examples:
 * <pre>{@code
 * // raw text block (Java 25+)
 * db.sql(SQL.select("*").from("users").where("status = ?", 1));
 *
 * // dynamic conditions
 * SQL q = SQL.select("*").from("users");
 * if (name != null)  q = q.where("name LIKE ?", name);
 * if (status != 0)   q = q.where("status = ?", status);
 * db.sql(q.sql(), q.args()).list(User.class);
 *
 * // named parameter style
 * SQL q = SQL.select("*").from("users").where("name = :name", name);
 * db.sql(q.sql(), q.args()).list(User.class);
 *
 * // UPDATE
 * SQL.update("users").set("name = ?", name).set("status = ?", status).where("id = ?", id);
 *
 * // INSERT
 * SQL.insert("users").set("name", name).set("status", status);
 *
 * // DELETE
 * SQL.delete("users").where("id = ?", id);
 * }</pre>
 */
public final class SQL {

    private final String head;
    private final List<Condition> conditions;
    private final String tail;
    private final Object[] args;
    private final boolean compoundQuery;
    private final List<Cte> ctes;

    // INSERT-specific fields
    private final String insertTable;
    private final List<String> insertColumns;
    private final List<Object> insertValues;

    // UPDATE SET-specific fields
    private final String updateTable;
    private final List<String> setClauses;
    private final List<Object> setArgs;

    private SQL(
        String head,
        List<Condition> conditions,
        String tail,
        Object[] args,
        boolean compoundQuery,
        List<Cte> ctes,
        String insertTable,
        List<String> insertColumns,
        List<Object> insertValues,
        String updateTable,
        List<String> setClauses,
        List<Object> setArgs
    ) {
        this.head = head;
        this.conditions = conditions;
        this.tail = tail;
        this.args = args;
        this.compoundQuery = compoundQuery;
        this.ctes = ctes;
        this.insertTable = insertTable;
        this.insertColumns = insertColumns;
        this.insertValues = insertValues;
        this.updateTable = updateTable;
        this.setClauses = setClauses;
        this.setArgs = setArgs;
    }

    // ====================== static factories ======================

    /** SELECT:{@code SQL.select("id, name").from("users").where(...)} */
    public static SQL select(String columns) {
        return new SQL("SELECT " + columns, List.of(), "", new Object[0],
            false, List.of(),
            null, List.of(), List.of(), null, List.of(), List.of());
    }

    /** UPDATE:{@code SQL.update("users").set("name = ?", v).where("id = ?", id)} */
    public static SQL update(String table) {
        return new SQL("UPDATE " + table, List.of(), "", new Object[0],
            false, List.of(),
            null, List.of(), List.of(), table, List.of(), List.of());
    }

    /** INSERT:{@code SQL.insert("users").set("name", v).set("status", v)} */
    public static SQL insert(String table) {
        return new SQL(null, List.of(), "", new Object[0],
            false, List.of(),
            table, List.of(), List.of(), null, List.of(), List.of());
    }

    /** DELETE:{@code SQL.delete("users").where("id = ?", id)} */
    public static SQL delete(String table) {
        return new SQL("DELETE FROM " + table, List.of(), "", new Object[0],
            false, List.of(),
            null, List.of(), List.of(), null, List.of(), List.of());
    }

    public SQL with(String name, SQL query) {
        return with(name, null, query);
    }

    public SQL with(String name, String columns, SQL query) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(query, "query");
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("CTE name must not be blank");
        }
        List<Cte> newCtes = new ArrayList<>(ctes);
        newCtes.add(new Cte(trimmed, columns, query));
        return copy(head, conditions, tail, args,
            compoundQuery, newCtes,
            insertTable, insertColumns, insertValues,
            updateTable, setClauses, setArgs);
    }

    // ====================== FROM / JOIN ======================

    public SQL from(String tables) {
        requireSimpleSelect("FROM");
        requireNoPendingJoin("FROM");
        return withHead(head + " FROM " + tables);
    }

    public SQL join(String table) {
        requireSimpleSelect("JOIN");
        requireNoPendingJoin("JOIN");
        return withHead(head + " JOIN " + table);
    }

    public SQL leftJoin(String table) {
        requireSimpleSelect("LEFT JOIN");
        requireNoPendingJoin("LEFT JOIN");
        return withHead(head + " LEFT JOIN " + table);
    }

    public SQL innerJoin(String table) {
        requireSimpleSelect("INNER JOIN");
        requireNoPendingJoin("INNER JOIN");
        return withHead(head + " INNER JOIN " + table);
    }

    public SQL on(String expr) {
        requireSimpleSelect("ON");
        requirePendingJoin("ON");
        return withHead(head + " ON " + expr);
    }

    // ====================== WHERE conditions ======================

    /** {@code WHERE expr} (or {@code AND expr} when a condition already exists). */
    public SQL where(String expr, Object... values) {
        requireWhereAllowed("WHERE");
        String connector = conditions.isEmpty() ? "" : "AND";
        return addCondition(connector, expr, values);
    }

    /** {@code OR expr} */
    public SQL orWhere(String expr, Object... values) {
        requireWhereAllowed("OR WHERE");
        return addCondition("OR", expr, values);
    }

    /** {@code AND NOT expr} */
    public SQL whereNot(String expr, Object... values) {
        requireWhereAllowed("WHERE NOT");
        return addCondition(notConnector(conditions), expr, values);
    }

    public SQL whereGroup(Consumer<Group> builder) {
        requireWhereAllowed("WHERE GROUP");
        return addGroupedCondition(andConnector(conditions), builder);
    }

    public SQL orWhereGroup(Consumer<Group> builder) {
        requireWhereAllowed("OR WHERE GROUP");
        return addGroupedCondition("OR", builder);
    }

    public SQL whereNotGroup(Consumer<Group> builder) {
        requireWhereAllowed("WHERE NOT GROUP");
        return addGroupedCondition(notConnector(conditions), builder);
    }

    private SQL addCondition(String connector, String expr, Object... values) {
        Object[] parsed = normalizeArgs(expr, values);
        String normalized = (String) parsed[0];
        Object[] extraArgs = (Object[]) parsed[1];

        List<Condition> newConds = new ArrayList<>(conditions);
        newConds.add(new Condition(connector, normalized));

        return copy(
            head, newConds, tail,
            concat(args, extraArgs),
            compoundQuery,
            ctes,
            insertTable, insertColumns, insertValues,
            updateTable, setClauses, setArgs);
    }

    private SQL addGroupedCondition(String connector, Consumer<Group> builder) {
        Objects.requireNonNull(builder, "builder");
        Group group = new Group();
        builder.accept(group);
        if (group.conditions.isEmpty()) {
            throw new IllegalStateException("Group must contain at least one condition");
        }
        return addCondition(connector, group.sql(), group.args());
    }

    // ====================== ORDER BY / GROUP BY / HAVING ======================

    public SQL orderBy(String clause) {
        requireSelectable("ORDER BY");
        requireNoPendingJoin("ORDER BY");
        return withTail(" ORDER BY " + clause);
    }

    public SQL groupBy(String columns) {
        requireSimpleSelect("GROUP BY");
        requireNoPendingJoin("GROUP BY");
        return withTail(" GROUP BY " + columns);
    }

    public SQL having(String expr, Object... values) {
        requireSimpleSelect("HAVING");
        requireNoPendingJoin("HAVING");
        Object[] parsed = normalizeArgs(expr, values);
        String normalized = (String) parsed[0];
        Object[] extraArgs = (Object[]) parsed[1];

        String t = tail.isEmpty() ? " HAVING " : tail + " HAVING ";
        return copy(head, conditions, t + normalized,
            concat(args, extraArgs),
            compoundQuery,
            ctes,
            insertTable, insertColumns, insertValues,
            updateTable, setClauses, setArgs);
    }

    public SQL havingGroup(Consumer<Group> builder) {
        requireSimpleSelect("HAVING GROUP");
        requireNoPendingJoin("HAVING GROUP");
        Objects.requireNonNull(builder, "builder");
        Group group = new Group();
        builder.accept(group);
        if (group.conditions.isEmpty()) {
            throw new IllegalStateException("Group must contain at least one condition");
        }

        String t = tail.isEmpty() ? " HAVING " : tail + " HAVING ";
        return copy(head, conditions, t + group.sql(),
            concat(args, group.args()),
            compoundQuery,
            ctes,
            insertTable, insertColumns, insertValues,
            updateTable, setClauses, setArgs);
    }

    // ====================== LIMIT / OFFSET ======================

    public SQL limit(int n) {
        requireSelectable("LIMIT");
        requireNoPendingJoin("LIMIT");
        return withTail(" LIMIT " + n);
    }

    public SQL offset(int n) {
        requireSelectable("OFFSET");
        requireNoPendingJoin("OFFSET");
        return withTail(" OFFSET " + n);
    }

    public SQL union(SQL other) {
        return combineCompound("UNION", other);
    }

    public SQL unionAll(SQL other) {
        return combineCompound("UNION ALL", other);
    }

    /** Return columns for INSERT / UPDATE / DELETE. */
    public SQL returning(String columns) {
        Objects.requireNonNull(columns, "columns");
        if (!isDml()) {
            throw new IllegalStateException("RETURNING is only supported for INSERT/UPDATE/DELETE");
        }
        requireNoPendingJoin("RETURNING");
        return withTail(" RETURNING " + columns);
    }

    /** INSERT ... ON CONFLICT (...)。 */
    public SQL onConflict(String targetColumns) {
        requireInsert("ON CONFLICT");
        Objects.requireNonNull(targetColumns, "targetColumns");
        return withTail(" ON CONFLICT (" + targetColumns + ")");
    }

    /** INSERT ... DO NOTHING。 */
    public SQL doNothing() {
        requireInsert("DO NOTHING");
        return withTail(" DO NOTHING");
    }

    /** INSERT ... DO UPDATE SET ...。 */
    public SQL doUpdateSet(String assignments) {
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
    public SQL set(String expr, Object value) {
        requireUpdateOrInsert("SET");
        requireNoPendingJoin("SET");
        if (insertTable != null) {
            // INSERT mode: set("col", value)
            List<String> newCols = new ArrayList<>(insertColumns);
            List<Object> newVals = new ArrayList<>(insertValues);
            newCols.add(expr);
            newVals.add(value);
            return copy(head, conditions, tail, args,
                compoundQuery,
                ctes,
                insertTable, newCols, newVals,
                null, List.of(), List.of());
        }
        // UPDATE mode: set("col = ?", value)
        Object[] parsed = normalizeArgs(expr, value);
        String normalized = (String) parsed[0];
        Object[] extraArgs = (Object[]) parsed[1];

        List<String> newSets = new ArrayList<>(setClauses);
        newSets.add(normalized);

        List<Object> newSetArgs = new ArrayList<>(setArgs);
        appendArgs(newSetArgs, extraArgs);

        return copy(head, conditions, tail, args,
            compoundQuery,
            ctes,
            null, List.of(), List.of(),
            updateTable, newSets, newSetArgs);
    }

    // ====================== output ======================

    /** Produces the complete SQL string. */
    public String sql() {
        requireNoPendingJoin("render SQL");
        String withClause = renderWithClause();
        if (insertTable != null) {
            // INSERT INTO table (col1, col2) VALUES (?, ?)
            var cols = String.join(", ", insertColumns);
            var placeholders = String.join(", ", insertValues.stream().map(v -> "?").toList());
            return withClause + "INSERT INTO " + insertTable + " (" + cols + ") VALUES (" + placeholders + ")" + tail;
        }
        var sb = new StringBuilder(head);

        // SET clause (UPDATE)
        if (!setClauses.isEmpty()) {
            sb.append(" SET ");
            sb.append(String.join(", ", setClauses));
        }

        // WHERE clause
        if (!conditions.isEmpty()) {
            sb.append(" WHERE ");
            sb.append(renderConditions(conditions));
        }

        if (!tail.isEmpty()) {
            sb.append(tail);
        }
        return withClause + sb;
    }

    /** Returns the arguments ordered by {@code ?} position. Pass directly to {@link Database#sql(String, Object...)}. */
    public Object[] args() {
        Object[] cteArgs = cteArgs();
        if (insertTable != null) {
            return concat(cteArgs, insertValues.toArray());
        }
        if (!setArgs.isEmpty()) {
            // UPDATE: setArgs before args (SET before WHERE)
            return concat(cteArgs, concat(setArgs.toArray(), args));
        }
        return concat(cteArgs, args);
    }

    // ====================== internals ======================

    private record Condition(String connector, String expr) {}

    private record Cte(String name, String columns, SQL query) {}

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
            Object[] parsed = normalizeArgs(expr, values);
            conditions.add(new Condition(connector, (String) parsed[0]));
            appendArgs(args, (Object[]) parsed[1]);
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

    private SQL withHead(String newHead) {
        return copy(newHead, conditions, tail, args,
            compoundQuery,
            ctes,
            insertTable, insertColumns, insertValues,
            updateTable, setClauses, setArgs);
    }

    private SQL withTail(String newTail) {
        return copy(head, conditions, tail + newTail, args,
            compoundQuery,
            ctes,
            insertTable, insertColumns, insertValues,
            updateTable, setClauses, setArgs);
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

    private SQL combineCompound(String operator, SQL other) {
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
        return new SQL(combined, List.of(), "", combinedArgs,
            true, List.of(),
            null, List.of(), List.of(),
            null, List.of(), List.of());
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

    private boolean isSelect() {
        return head != null
            && insertTable == null
            && updateTable == null
            && head.startsWith("SELECT ");
    }

    private boolean isSelectable() {
        return isSelect() || compoundQuery;
    }

    public boolean isInsert() {
        return insertTable != null;
    }

    private boolean isUpdate() {
        return updateTable != null;
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

    private static SQL copy(
        String head,
        List<Condition> conditions,
        String tail,
        Object[] args,
        boolean compoundQuery,
        List<Cte> ctes,
        String insertTable,
        List<String> insertColumns,
        List<Object> insertValues,
        String updateTable,
        List<String> setClauses,
        List<Object> setArgs
    ) {
        return new SQL(head, conditions, tail, args,
            compoundQuery,
            ctes,
            insertTable, insertColumns, insertValues,
            updateTable, setClauses, setArgs);
    }

    /**
     * Parses SQL fragments containing {@code ?}, {@code :name}, and {@code $name} placeholders.
     * Replaces all with {@code ?} and extracts arguments from the values array in order.
     *
     * @return [normalizedSql(String), extraArgs(Object[])]
     */
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

    private static Object[] normalizeArgs(String fragment, Object... values) {
        var sb = new StringBuilder(fragment.length());
        var matched = new ArrayList<>();
        int vi = 0;
        int len = fragment.length();
        int i = 0;

        while (i < len) {
            char c = fragment.charAt(i);

            // string literal '...'
            if (c == '\'') {
                sb.append(c);
                i++;
                while (i < len) {
                    char sc = fragment.charAt(i);
                    sb.append(sc);
                    i++;
                    if (sc == '\'') {
                        if (i < len && fragment.charAt(i) == '\'') {
                            sb.append('\'');
                            i++;
                        } else {
                            break;
                        }
                    }
                }
                continue;
            }

            // identifier "..."
            if (c == '"') {
                sb.append(c);
                i++;
                while (i < len && fragment.charAt(i) != '"') {
                    sb.append(fragment.charAt(i));
                    i++;
                }
                if (i < len) {
                    sb.append('"');
                    i++;
                }
                continue;
            }

            // line comment --
            if (c == '-' && i + 1 < len && fragment.charAt(i + 1) == '-') {
                sb.append(c);
                i++;
                sb.append('-');
                i++;
                while (i < len && fragment.charAt(i) != '\n') {
                    sb.append(fragment.charAt(i));
                    i++;
                }
                continue;
            }

            // block comment /* */
            if (c == '/' && i + 1 < len && fragment.charAt(i + 1) == '*') {
                sb.append('/');
                i++;
                sb.append('*');
                i++;
                while (i < len) {
                    char bc = fragment.charAt(i);
                    sb.append(bc);
                    i++;
                    if (bc == '*' && i < len && fragment.charAt(i) == '/') {
                        sb.append('/');
                        i++;
                        break;
                    }
                }
                continue;
            }

            // :name or $name named parameter
            if ((c == ':' || c == '$') && i + 1 < len && Names.isValidParamStart(fragment.charAt(i + 1))) {
                int start = i + 1;
                i += 2;
                while (i < len && Names.isValidParamChar(fragment.charAt(i))) {
                    i++;
                }
                if (vi < values.length) {
                    appendValue(sb, matched, values[vi++]);
                } else {
                    throw new SqlException(
                        "Missing value for named parameter at position " + start
                            + " in fragment: " + fragment
                    );
                }
                continue;
            }

            // ? positional parameter
            if (c == '?') {
                if (vi < values.length) {
                    appendValue(sb, matched, values[vi++]);
                } else {
                    throw new SqlException(
                        "Missing value for '?' at position " + i + " in fragment: " + fragment
                    );
                }
                i++;
                continue;
            }

            sb.append(c);
            i++;
        }

        if (vi < values.length) {
            throw new SqlException(
                "Too many parameter values for SQL fragment: " + fragment
                + " — " + vi + " placeholder(s) but " + values.length + " value(s) provided");
        }

        return new Object[]{sb.toString(), matched.toArray()};
    }

    private static void appendValue(StringBuilder sb, List<Object> matched, Object value) {
        if (value instanceof SQL sql) {
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
        if (!(o instanceof SQL sql)) return false;
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
