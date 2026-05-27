package com.jujin.freeway.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 不可变的链式 SQL 构建器。
 * <p>
 * 与 {@link Database#sql(String, Object...)} 完全兼容——输出 {@link #sql()} 和 {@link #args()}
 * 可直接传入。支持 {@code ?} 位置参数和 {@code :name} / {@code $name} 命名参数，风格自选。
 * <p>
 * 示例：
 * <pre>{@code
 * // 纯文本块（Java 25+）
 * db.sql(SQL.select("*").from("users").where("status = ?", 1));
 *
 * // 动态条件
 * SQL q = SQL.select("*").from("users");
 * if (name != null)  q = q.where("name LIKE ?", name);
 * if (status != 0)   q = q.where("status = ?", status);
 * db.sql(q.sql(), q.args()).list(User.class);
 *
 * // 命名参数风格
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

    // INSERT 专用
    private final String insertTable;
    private final List<String> insertColumns;
    private final List<Object> insertValues;

    // UPDATE SET 专用
    private final String updateTable;
    private final List<String> setClauses;
    private final List<Object> setArgs;

    private SQL(
        String head,
        List<Condition> conditions,
        String tail,
        Object[] args,
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
        this.insertTable = insertTable;
        this.insertColumns = insertColumns;
        this.insertValues = insertValues;
        this.updateTable = updateTable;
        this.setClauses = setClauses;
        this.setArgs = setArgs;
    }

    // ====================== 静态工厂 ======================

    /** SELECT 查询：{@code SQL.select("id, name").from("users").where(...)} */
    public static SQL select(String columns) {
        return new SQL("SELECT " + columns, List.of(), "", new Object[0],
            null, List.of(), List.of(), null, List.of(), List.of());
    }

    /** UPDATE 更新：{@code SQL.update("users").set("name = ?", v).where("id = ?", id)} */
    public static SQL update(String table) {
        return new SQL("UPDATE " + table, List.of(), "", new Object[0],
            null, List.of(), List.of(), table, List.of(), List.of());
    }

    /** INSERT 插入：{@code SQL.insert("users").set("name", v).set("status", v)} */
    public static SQL insert(String table) {
        return new SQL(null, List.of(), "", new Object[0],
            table, List.of(), List.of(), null, List.of(), List.of());
    }

    /** DELETE 删除：{@code SQL.delete("users").where("id = ?", id)} */
    public static SQL delete(String table) {
        return new SQL("DELETE FROM " + table, List.of(), "", new Object[0],
            null, List.of(), List.of(), null, List.of(), List.of());
    }

    // ====================== FROM / JOIN ======================

    public SQL from(String tables) {
        return withHead(head + " FROM " + tables);
    }

    public SQL join(String table) {
        return withHead(head + " JOIN " + table);
    }

    public SQL leftJoin(String table) {
        return withHead(head + " LEFT JOIN " + table);
    }

    public SQL innerJoin(String table) {
        return withHead(head + " INNER JOIN " + table);
    }

    public SQL on(String expr) {
        return withHead(head + " ON " + expr);
    }

    // ====================== WHERE 条件 ======================

    /** {@code WHERE expr}（若已有条件则为 {@code AND expr}） */
    public SQL where(String expr, Object... values) {
        String connector = conditions.isEmpty() ? "" : "AND";
        return addCondition(connector, expr, values);
    }

    /** {@code OR expr} */
    public SQL orWhere(String expr, Object... values) {
        return addCondition("OR", expr, values);
    }

    /** {@code AND NOT expr} */
    public SQL whereNot(String expr, Object... values) {
        return addCondition("AND NOT", expr, values);
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
            insertTable, insertColumns, insertValues,
            updateTable, setClauses, setArgs
        );
    }

    // ====================== ORDER BY / GROUP BY / HAVING ======================

    public SQL orderBy(String clause) {
        return withTail(" ORDER BY " + clause);
    }

    public SQL groupBy(String columns) {
        return withTail(" GROUP BY " + columns);
    }

    public SQL having(String expr, Object... values) {
        String t = tail.isEmpty() ? " HAVING " : tail + " HAVING ";
        return copy(head, conditions, t + expr,
            concat(args, values),
            insertTable, insertColumns, insertValues,
            updateTable, setClauses, setArgs);
    }

    // ====================== LIMIT / OFFSET ======================

    public SQL limit(int n) {
        return withTail(" LIMIT " + n);
    }

    public SQL offset(int n) {
        return withTail(" OFFSET " + n);
    }

    // ====================== SET (UPDATE / INSERT) ======================

    /**
     * 用于 UPDATE 或 INSERT。
     * <p>
     * <b>UPDATE</b>：{@code .set("name = ?", value)} —— 完整表达式
     * <br>
     * <b>INSERT</b>：{@code .set("name", value)} —— 列名 + 值
     */
    public SQL set(String expr, Object value) {
        if (insertTable != null) {
            // INSERT 模式：set("col", value)
            List<String> newCols = new ArrayList<>(insertColumns);
            List<Object> newVals = new ArrayList<>(insertValues);
            newCols.add(expr);
            newVals.add(value);
            return copy(head, conditions, tail, args,
                insertTable, newCols, newVals,
                null, List.of(), List.of());
        }
        // UPDATE 模式：set("col = ?", value)
        Object[] parsed = normalizeArgs(expr, value);
        String normalized = (String) parsed[0];
        Object[] extraArgs = (Object[]) parsed[1];

        List<String> newSets = new ArrayList<>(setClauses);
        newSets.add(normalized);

        List<Object> newSetArgs = new ArrayList<>(setArgs);
        newSetArgs.addAll(List.of(extraArgs));

        return copy(head, conditions, tail, args,
            null, List.of(), List.of(),
            updateTable, newSets, newSetArgs);
    }

    // ====================== 输出 ======================

    /** 生成完整的 SQL 字符串。 */
    public String sql() {
        if (insertTable != null) {
            // INSERT INTO table (col1, col2) VALUES (?, ?)
            var cols = String.join(", ", insertColumns);
            var placeholders = String.join(", ", insertValues.stream().map(v -> "?").toList());
            return "INSERT INTO " + insertTable + " (" + cols + ") VALUES (" + placeholders + ")";
        }
        var sb = new StringBuilder(head);

        // SET 子句（UPDATE）
        if (!setClauses.isEmpty()) {
            sb.append(" SET ");
            sb.append(String.join(", ", setClauses));
        }

        // WHERE 子句
        if (!conditions.isEmpty()) {
            sb.append(" WHERE ");
            for (int i = 0; i < conditions.size(); i++) {
                Condition c = conditions.get(i);
                if (!c.connector.isEmpty()) {
                    sb.append(' ').append(c.connector).append(' ');
                }
                sb.append(c.expr);
            }
        }

        if (!tail.isEmpty()) {
            sb.append(tail);
        }
        return sb.toString();
    }

    /** 返回按 {@code ?} 位置顺序排列的参数数组。可直接传入 {@link Database#sql(String, Object...)}。 */
    public Object[] args() {
        if (insertTable != null) {
            return insertValues.toArray();
        }
        if (!setArgs.isEmpty()) {
            // UPDATE: setArgs 在 args 之前（SET 在 WHERE 之前）
            return concat(setArgs.toArray(), args);
        }
        return args;
    }

    // ====================== 内部 ======================

    private record Condition(String connector, String expr) {}

    private SQL withHead(String newHead) {
        return copy(newHead, conditions, tail, args,
            insertTable, insertColumns, insertValues,
            updateTable, setClauses, setArgs);
    }

    private SQL withTail(String newTail) {
        return copy(head, conditions, tail + newTail, args,
            insertTable, insertColumns, insertValues,
            updateTable, setClauses, setArgs);
    }

    private static SQL copy(
        String head,
        List<Condition> conditions,
        String tail,
        Object[] args,
        String insertTable,
        List<String> insertColumns,
        List<Object> insertValues,
        String updateTable,
        List<String> setClauses,
        List<Object> setArgs
    ) {
        return new SQL(head, conditions, tail, args,
            insertTable, insertColumns, insertValues,
            updateTable, setClauses, setArgs);
    }

    /**
     * 解析 SQL 片段中的 {@code ?}、{:name}、{$name} 占位符，
     * 全部替换为 {@code ?}，并从 values 中按顺序取出实参。
     *
     * @return [normalizedSql(String), extraArgs(Object[])]
     */
    private static Object[] normalizeArgs(String fragment, Object... values) {
        var sb = new StringBuilder(fragment.length());
        var matched = new ArrayList<>();
        int vi = 0;
        int len = fragment.length();
        int i = 0;

        while (i < len) {
            char c = fragment.charAt(i);

            // 字符串字面量 '...'
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

            // 标识符引用 "..."
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

            // 行注释 --
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

            // 块注释 /* */
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

            // :name 或 $name 命名参数
            if ((c == ':' || c == '$') && i + 1 < len && isValidParamStart(fragment.charAt(i + 1))) {
                int start = i + 1;
                i += 2;
                while (i < len && isValidParamChar(fragment.charAt(i))) {
                    i++;
                }
                sb.append('?');
                if (vi < values.length) {
                    matched.add(values[vi++]);
                } else {
                    throw new SqlException(
                        "Missing value for named parameter at position " + start
                            + " in fragment: " + fragment
                    );
                }
                continue;
            }

            // ? 位置参数
            if (c == '?') {
                sb.append('?');
                if (vi < values.length) {
                    matched.add(values[vi++]);
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

        return new Object[]{sb.toString(), matched.toArray()};
    }

    private static boolean isValidParamStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isValidParamChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
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
        return Objects.equals(sql(), sql.sql());
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
