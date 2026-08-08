package com.jujin.freeway.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Sql} 构建器的单元测试。
 * 直接验证 sql() 和 args() 的输出，无需数据库连接。
 */
class SqlTest {

    // ====================== SELECT ======================

    @Test
    void simpleSelect() {
        Sql q = Sql.select("id, name").from("users");
        assertEquals("SELECT id, name FROM users", q.sql());
        assertArrayEquals(new Object[0], q.args());
    }

    @Test
    void selectWithPositionalParam() {
        Sql q = Sql.select("*").from("users").where("id = ?", 1);
        assertEquals("SELECT * FROM users WHERE id = ?", q.sql());
        assertArrayEquals(new Object[]{1}, q.args());
    }

    @Test
    void selectWithNamedParam() {
        Sql q = Sql.select("*").from("users").where("name = :name", "john");
        assertEquals("SELECT * FROM users WHERE name = ?", q.sql());
        assertArrayEquals(new Object[]{"john"}, q.args());
    }

    @Test
    void selectWithDollarParam() {
        Sql q = Sql.select("*").from("users").where("status = $status", "active");
        assertEquals("SELECT * FROM users WHERE status = ?", q.sql());
        assertArrayEquals(new Object[]{"active"}, q.args());
    }

    @Test
    void ignoresPlaceholdersInsideCommentsAndQuotedIdentifiers() {
        Sql q = Sql.select("*").from("users")
            .where("id = ? /* comment ? */ and name = ?", 1, "john");
        assertEquals(
            "SELECT * FROM users WHERE id = ? /* comment ? */ and name = ?",
            q.sql());
        assertArrayEquals(new Object[]{1, "john"}, q.args());

        Sql lineComment = Sql.select("*").from("users")
            .where("id = ? -- trailing ?\nand active = ?", 1, true);
        assertEquals(
            "SELECT * FROM users WHERE id = ? -- trailing ?\nand active = ?",
            lineComment.sql());
        assertArrayEquals(new Object[]{1, true}, lineComment.args());

        Sql quoted = Sql.select("*").from("users")
            .where("name = ? and \"col?umn\" = ?", 1, 2);
        assertEquals(
            "SELECT * FROM users WHERE name = ? and \"col?umn\" = ?",
            quoted.sql());
        assertArrayEquals(new Object[]{1, 2}, quoted.args());
    }

    @Test
    void repeatedNamedParamInFragmentReusesItsValue() {
        Sql q = Sql.select("*").from("users")
            .where("a = :x and b = :x", 1);
        assertEquals("SELECT * FROM users WHERE a = ? and b = ?", q.sql());
        assertArrayEquals(new Object[]{1, 1}, q.args());
    }

    @Test
    void selectWithMixedParams() {
        Sql q = Sql.select("*").from("users")
            .where("id = :id and name = ?", 1, "john");
        assertEquals("SELECT * FROM users WHERE id = ? and name = ?", q.sql());
        assertArrayEquals(new Object[]{1, "john"}, q.args());
    }

    @Test
    void selectWithMultipleWhere() {
        Sql q = Sql.select("*").from("users")
            .where("id = ?", 1)
            .where("name = ?", "john");
        assertEquals("SELECT * FROM users WHERE id = ? AND name = ?", q.sql());
        assertArrayEquals(new Object[]{1, "john"}, q.args());
    }

    @Test
    void selectWithOrWhere() {
        Sql q = Sql.select("*").from("users")
            .where("status = ?", 1)
            .orWhere("role = ?", "admin");
        assertEquals("SELECT * FROM users WHERE status = ? OR role = ?", q.sql());
        assertArrayEquals(new Object[]{1, "admin"}, q.args());
    }

    @Test
    void selectWithWhereNot() {
        Sql q = Sql.select("*").from("users")
            .where("status = ?", 1)
            .whereNot("deleted = ?", 1);
        assertEquals("SELECT * FROM users WHERE status = ? AND NOT deleted = ?", q.sql());
        assertArrayEquals(new Object[]{1, 1}, q.args());
    }

    @Test
    void selectWithWhereGroup() {
        Sql q = Sql.select("*").from("users")
            .whereGroup(g -> g.where("status = ?", "ACTIVE")
                .orWhere("role = ?", "admin"));
        assertEquals("SELECT * FROM users WHERE (status = ? OR role = ?)", q.sql());
        assertArrayEquals(new Object[]{"ACTIVE", "admin"}, q.args());
    }

    @Test
    void selectWithNestedWhereGroup() {
        Sql q = Sql.select("*").from("users")
            .whereGroup(g -> g.where("tenant_id = ?", 7)
                .whereGroup(h -> h.where("status = ?", "ACTIVE")
                    .orWhere("role = ?", "admin")));
        assertEquals("SELECT * FROM users WHERE (tenant_id = ? AND (status = ? OR role = ?))", q.sql());
        assertArrayEquals(new Object[]{7, "ACTIVE", "admin"}, q.args());
    }

    @Test
    void selectWithWhereNotGroup() {
        Sql q = Sql.select("*").from("users")
            .whereNotGroup(g -> g.where("deleted = ?", true)
                .where("archived = ?", true));
        assertEquals("SELECT * FROM users WHERE NOT (deleted = ? AND archived = ?)", q.sql());
        assertArrayEquals(new Object[]{true, true}, q.args());
    }

    @Test
    void joinMustBeClosedByOnBeforeNextClause() {
        assertThrows(IllegalStateException.class, () ->
            Sql.select("*").from("users")
                .join("orders")
                .where("orders.user_id = users.id"));
    }

    @Test
    void selectWithWhereOrWhereWhereNot() {
        Sql q = Sql.select("*").from("users")
            .where("a = ?", 1)
            .orWhere("b = ?", 2)
            .whereNot("c = ?", 3)
            .where("d = ?", 4);
        assertEquals("SELECT * FROM users WHERE a = ? OR b = ? AND NOT c = ? AND d = ?", q.sql());
        assertArrayEquals(new Object[]{1, 2, 3, 4}, q.args());
    }

    @Test
    void selectWithOrderBy() {
        Sql q = Sql.select("*").from("users").where("id = ?", 1).orderBy("name DESC");
        assertEquals("SELECT * FROM users WHERE id = ? ORDER BY name DESC", q.sql());
        assertArrayEquals(new Object[]{1}, q.args());
    }

    @Test
    void selectWithLimit() {
        Sql q = Sql.select("*").from("users").limit(10);
        assertEquals("SELECT * FROM users LIMIT 10", q.sql());
    }

    @Test
    void selectWithLimitOffset() {
        Sql q = Sql.select("*").from("users").limit(10).offset(20);
        assertEquals("SELECT * FROM users LIMIT 10 OFFSET 20", q.sql());
    }

    @Test
    void selectWithJoin() {
        Sql q = Sql.select("*").from("users")
            .join("orders").on("users.id = orders.user_id")
            .where("orders.total > ?", 100);
        assertEquals(
            "SELECT * FROM users JOIN orders ON users.id = orders.user_id WHERE orders.total > ?",
            q.sql());
        assertArrayEquals(new Object[]{100}, q.args());
    }

    @Test
    void selectWithLeftJoin() {
        Sql q = Sql.select("*").from("users")
            .leftJoin("orders").on("users.id = orders.user_id");
        assertEquals(
            "SELECT * FROM users LEFT JOIN orders ON users.id = orders.user_id",
            q.sql());
    }

    @Test
    void selectWithInnerJoin() {
        Sql q = Sql.select("*").from("users")
            .innerJoin("orders").on("users.id = orders.user_id");
        assertEquals(
            "SELECT * FROM users INNER JOIN orders ON users.id = orders.user_id",
            q.sql());
    }

    @Test
    void selectWithGroupByAndHaving() {
        Sql q = Sql.select("dept, count(*) as cnt").from("users")
            .groupBy("dept")
            .having("cnt > ?", 5);
        assertEquals(
            "SELECT dept, count(*) as cnt FROM users GROUP BY dept HAVING cnt > ?",
            q.sql());
        assertArrayEquals(new Object[]{5}, q.args());
    }

    @Test
    void selectWithHavingGroup() {
        Sql q = Sql.select("dept, count(*) as cnt").from("users")
            .groupBy("dept")
            .havingGroup(g -> g.where("cnt > ?", 5)
                .whereNot("dept = ?", "tmp"));
        assertEquals(
            "SELECT dept, count(*) as cnt FROM users GROUP BY dept HAVING (cnt > ? AND NOT dept = ?)",
            q.sql());
        assertArrayEquals(new Object[]{5, "tmp"}, q.args());
    }

    @Test
    void selectWithUnionAllAndOuterOrderBy() {
        Sql left = Sql.select("id").from("active_users").where("status = ?", "A");
        Sql right = Sql.select("id").from("archived_users").where("status = ?", "B");

        Sql q = left.unionAll(right).orderBy("id DESC");

        assertEquals(
            "(SELECT id FROM active_users WHERE status = ?) UNION ALL (SELECT id FROM archived_users WHERE status = ?) ORDER BY id DESC",
            q.sql());
        assertArrayEquals(new Object[]{"A", "B"}, q.args());
    }

    @Test
    void unionRejectsFurtherWhereClauses() {
        assertThrows(IllegalStateException.class, () ->
            Sql.select("*").from("users")
                .union(Sql.select("*").from("archived_users"))
                .where("id = ?", 1));
    }

    @Test
    void selectWithCommonTableExpression() {
        Sql activeUsers = Sql.select("id")
            .from("users")
            .where("status = ?", "ACTIVE");

        Sql q = Sql.select("id")
            .with("active_users", activeUsers)
            .from("active_users")
            .where("id > ?", 10);

        assertEquals(
            "WITH active_users AS (SELECT id FROM users WHERE status = ?) SELECT id FROM active_users WHERE id > ?",
            q.sql());
        assertArrayEquals(new Object[]{"ACTIVE", 10}, q.args());
    }

    @Test
    void selectWithSubqueryArgument() {
        Sql sub = Sql.select("user_id").from("orders").where("total > ?", 100);
        Sql q = Sql.select("*").from("users").where("id in (?)", sub);
        assertEquals("SELECT * FROM users WHERE id in (SELECT user_id FROM orders WHERE total > ?)", q.sql());
        assertArrayEquals(new Object[]{100}, q.args());
    }

    @Test
    void insertRejectsWhere() {
        assertThrows(IllegalStateException.class, () ->
            Sql.insert("users").where("id = ?", 1));
    }

    @Test
    void selectRejectsSet() {
        assertThrows(IllegalStateException.class, () ->
            Sql.select("*").from("users").set("name = ?", "john"));
    }

    @Test
    void updateRejectsGroupBy() {
        assertThrows(IllegalStateException.class, () ->
            Sql.update("users").groupBy("dept"));
    }

    @Test
    void selectRejectsOnConflict() {
        assertThrows(IllegalStateException.class, () ->
            Sql.select("*").from("users").onConflict("id"));
    }

    // ====================== UPDATE ======================

    @Test
    void simpleUpdate() {
        Sql q = Sql.update("users").set("name = ?", "john").where("id = ?", 1);
        assertEquals("UPDATE users SET name = ? WHERE id = ?", q.sql());
        assertArrayEquals(new Object[]{"john", 1}, q.args());
    }

    @Test
    void updateWithMultipleSets() {
        Sql q = Sql.update("users")
            .set("name = ?", "john")
            .set("status = ?", 1)
            .where("id = ?", 42);
        assertEquals("UPDATE users SET name = ?, status = ? WHERE id = ?", q.sql());
        assertArrayEquals(new Object[]{"john", 1, 42}, q.args());
    }

    @Test
    void updateWithNamedParams() {
        Sql q = Sql.update("users")
            .set("name = :name", "john")
            .where("id = :id", 1);
        assertEquals("UPDATE users SET name = ? WHERE id = ?", q.sql());
        assertArrayEquals(new Object[]{"john", 1}, q.args());
    }

    @Test
    void updateWithReturning() {
        Sql q = Sql.update("users")
            .set("name = ?", "john")
            .where("id = ?", 1)
            .returning("id");
        assertEquals("UPDATE users SET name = ? WHERE id = ? RETURNING id", q.sql());
        assertArrayEquals(new Object[]{"john", 1}, q.args());
    }

    // ====================== INSERT ======================

    @Test
    void simpleInsert() {
        Sql q = Sql.insert("users").set("name", "john").set("status", 1);
        assertEquals("INSERT INTO users (name, status) VALUES (?, ?)", q.sql());
        assertArrayEquals(new Object[]{"john", 1}, q.args());
    }

    @Test
    void insertWithReturning() {
        Sql q = Sql.insert("users").set("name", "john").returning("id");
        assertEquals("INSERT INTO users (name) VALUES (?) RETURNING id", q.sql());
        assertArrayEquals(new Object[]{"john"}, q.args());
    }

    @Test
    void returningValidatedAgainstDialect() {
        Sql q = Sql.insert("users").set("name", "john").returning("id");
        assertEquals("INSERT INTO users (name) VALUES (?) RETURNING id",
            q.sql(new com.jujin.freeway.db.dialect.PostgresDialect()));
        assertThrows(SqlException.class, () ->
            q.sql(new com.jujin.freeway.db.dialect.MySqlDialect()));
    }

    @Test
    void onConflictValidatedAgainstDialect() {
        Sql q = Sql.insert("users").set("id", 1).onConflict("id").doNothing();
        q.sql(new com.jujin.freeway.db.dialect.PostgresDialect());
        assertThrows(SqlException.class, () ->
            q.sql(new com.jujin.freeway.db.dialect.MySqlDialect()));
    }

    @Test
    void insertWithOnConflictDoNothing() {
        Sql q = Sql.insert("users").set("id", 1).set("name", "john")
            .onConflict("id")
            .doNothing();
        assertEquals("INSERT INTO users (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", q.sql());
        assertArrayEquals(new Object[]{1, "john"}, q.args());
    }

    @Test
    void insertWithOnConflictDoUpdateSet() {
        Sql q = Sql.insert("users").set("id", 1).set("name", "john")
            .onConflict("id")
            .doUpdateSet("name = excluded.name")
            .returning("id");
        assertEquals(
            "INSERT INTO users (id, name) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET name = excluded.name RETURNING id",
            q.sql());
        assertArrayEquals(new Object[]{1, "john"}, q.args());
    }

    @Test
    void insertSingleColumn() {
        Sql q = Sql.insert("logs").set("message", "hello");
        assertEquals("INSERT INTO logs (message) VALUES (?)", q.sql());
        assertArrayEquals(new Object[]{"hello"}, q.args());
    }

    // ====================== DELETE ======================

    @Test
    void simpleDelete() {
        Sql q = Sql.delete("users").where("id = ?", 1);
        assertEquals("DELETE FROM users WHERE id = ?", q.sql());
        assertArrayEquals(new Object[]{1}, q.args());
    }

    @Test
    void deleteWithMultipleConditions() {
        Sql q = Sql.delete("users")
            .where("status = ?", 0)
            .where("expired = ?", true);
        assertEquals("DELETE FROM users WHERE status = ? AND expired = ?", q.sql());
        assertArrayEquals(new Object[]{0, true}, q.args());
    }

    // ====================== 不可变性 ======================

    @Test
    void immutability() {
        Sql q1 = Sql.select("*").from("users").where("id = ?", 1);
        Sql q2 = q1.where("name = ?", "john");

        assertEquals("SELECT * FROM users WHERE id = ?", q1.sql());
        assertArrayEquals(new Object[]{1}, q1.args());

        assertEquals("SELECT * FROM users WHERE id = ? AND name = ?", q2.sql());
        assertArrayEquals(new Object[]{1, "john"}, q2.args());
    }

    @Test
    void immutabilityInsert() {
        Sql q1 = Sql.insert("users").set("name", "john");
        Sql q2 = q1.set("status", 1);

        assertEquals("INSERT INTO users (name) VALUES (?)", q1.sql());
        assertArrayEquals(new Object[]{"john"}, q1.args());

        assertEquals("INSERT INTO users (name, status) VALUES (?, ?)", q2.sql());
        assertArrayEquals(new Object[]{"john", 1}, q2.args());
    }

    // ====================== 边缘情况 ======================

    @Test
    void whereNoConditions() {
        Sql q = Sql.select("*").from("users");
        assertEquals("SELECT * FROM users", q.sql());
        assertEquals(0, q.args().length);
    }

    @Test
    void stringLiteralContainingDollar() {
        Sql q = Sql.select("*").from("users").where("label = ?", "a$b");
        assertEquals("SELECT * FROM users WHERE label = ?", q.sql());
        assertArrayEquals(new Object[]{"a$b"}, q.args());
    }

    @Test
    void selectWithTypeCastAndNamedParam() {
        // PostgreSQL :: type cast must not be confused with :name
        Sql q = Sql.select("*").from("events")
            .where("created_at::date = :d", java.time.LocalDate.of(2024, 1, 15));
        assertEquals("SELECT * FROM events WHERE created_at::date = ?", q.sql());
        assertEquals(1, q.args().length);
    }

    @Test
    void selectWithTypeCastAndMultipleNamedParams() {
        Sql q = Sql.select("*").from("events")
            .where("created_at::timestamp > :t AND id = :id",
                java.time.LocalDateTime.of(2024, 6, 1, 0, 0), 1L);
        assertEquals(
            "SELECT * FROM events WHERE created_at::timestamp > ? AND id = ?",
            q.sql());
        assertEquals(2, q.args().length);
    }

    @Test
    void selectWithTypeCastAndMixedParam() {
        // ? positional + :: type cast — :: handling should not break ?
        Sql q = Sql.select("*").from("events")
            .where("created_at::date > ? AND status = :s",
                java.time.LocalDate.of(2024, 1, 1), "active");
        assertEquals(
            "SELECT * FROM events WHERE created_at::date > ? AND status = ?",
            q.sql());
        assertEquals(2, q.args().length);
    }

    @Test
    void paramInStringLiteralNotParsed() {
        Sql q = Sql.select("*").from("users").where("name = '$literal'");
        assertEquals("SELECT * FROM users WHERE name = '$literal'", q.sql());
        assertEquals(0, q.args().length);
    }

    @Test
    void multipleParamsInOneFragment() {
        Sql q = Sql.select("*").from("users")
            .where("a = ? AND b = :b AND c = ?", 1, 2, 3);
        assertEquals("SELECT * FROM users WHERE a = ? AND b = ? AND c = ?", q.sql());
        assertArrayEquals(new Object[]{1, 2, 3}, q.args());
    }

    @Test
    void emptyWhereAfterFrom() {
        Sql q = Sql.select("*").from("users").orderBy("id");
        assertEquals("SELECT * FROM users ORDER BY id", q.sql());
    }

    @Test
    void toStringReturnsSql() {
        Sql q = Sql.select("*").from("users").where("id = ?", 1);
        assertEquals(q.sql(), q.toString());
    }

    @Test
    void equalsAndHashCode() {
        Sql q1 = Sql.select("*").from("users").where("id = ?", 1);
        Sql q2 = Sql.select("*").from("users").where("id = ?", 1);
        assertEquals(q1, q2);
        assertEquals(q1.hashCode(), q2.hashCode());
    }

    // ====================== 实际数据库集成测试 ======================

    @Test
    void integrationSelect() {
        var db = builder("sql_integ_select").build();
        try (db) {
            db.execute("create table t_user (id bigint primary key, name varchar(16), status int)");
            db.execute("insert into t_user values (1, 'alpha', 1), (2, 'beta', 0)");

            Sql q = Sql.select("*").from("t_user").where("status = ?", 1);
            var users = db.query(q.sql(), q.args()).list(IdName.class);
            assertEquals(1, users.size());
            assertEquals("alpha", users.get(0).name());
        }
    }

    @Test
    void integrationSelectNamed() {
        var db = builder("sql_integ_named").build();
        try (db) {
            db.execute("create table t_user (id bigint primary key, name varchar(16))");
            db.execute("insert into t_user values (1, 'alpha')");

            Sql q = Sql.select("*").from("t_user").where("id = :id", 1L);
            var user = db.query(q.sql(), q.args()).one(IdName.class);
            assertTrue(user.isPresent());
            assertEquals("alpha", user.get().name());
        }
    }

    @Test
    void integrationDynamicWhere() {
        var db = builder("sql_integ_dynamic").build();
        try (db) {
            db.execute("create table t_user (id bigint primary key, name varchar(16), age int)");
            db.execute("insert into t_user values (1, 'alpha', 25), (2, 'beta', 30)");

            // 动态条件模拟
            String nameFilter = "alpha";
            int ageFilter = 0;

            Sql q = Sql.select("*").from("t_user");
            if (!nameFilter.isEmpty()) q = q.where("name = ?", nameFilter);
            if (ageFilter > 0) q = q.where("age >= ?", ageFilter);

            var users = db.query(q.sql(), q.args()).list(IdName.class);
            assertEquals(1, users.size());
            assertEquals("alpha", users.get(0).name());
        }
    }

    @Test
    void integrationInsert() {
        var db = builder("sql_integ_insert").build();
        try (db) {
            db.execute("create table t_user (id bigint primary key, name varchar(16))");

            Sql q = Sql.insert("t_user").set("id", 1L).set("name", "newguy");
            db.execute(q.sql(), q.args());

            var user = db.query("select id, name from t_user where id = ?", 1L).one(IdName.class);
            assertTrue(user.isPresent());
            assertEquals("newguy", user.get().name());
        }
    }

    @Test
    void integrationUpdate() {
        var db = builder("sql_integ_update").build();
        try (db) {
            db.execute("create table t_user (id bigint primary key, name varchar(16))");
            db.execute("insert into t_user values (1, 'oldname')");

            Sql q = Sql.update("t_user").set("name = ?", "newname").where("id = ?", 1L);
            db.execute(q.sql(), q.args());

            var user = db.query("select id, name from t_user where id = ?", 1L).one(IdName.class);
            assertTrue(user.isPresent());
            assertEquals("newname", user.get().name());
        }
    }

    @Test
    void integrationDelete() {
        var db = builder("sql_integ_delete").build();
        try (db) {
            db.execute("create table t_user (id bigint primary key, name varchar(16))");
            db.execute("insert into t_user values (1, 'goner'), (2, 'keeper')");

            Sql q = Sql.delete("t_user").where("id = ?", 1L);
            db.execute(q.sql(), q.args());

            var users = db.query("select id, name from t_user order by id").list(IdName.class);
            assertEquals(1, users.size());
            assertEquals("keeper", users.get(0).name());
        }
    }

    @Test
    void integrationJava25TextBlock() {
        // Java 25 文本块支持 —— 纯字符串构造即可，无特殊 API 变更
        var db = builder("sql_integ_textblock").build();
        try (db) {
            db.execute("create table t_user (id bigint primary key, name varchar(16))");
            db.execute("insert into t_user values (1, 'hello')");

            // 用文本块写 Sql，Sql 类只负责构建
            Sql q = Sql.select("id, name").from("t_user").where("id = ?", 1L);
            var user = db.query(q.sql(), q.args()).one(IdName.class);
            assertTrue(user.isPresent());
        }
    }

    // ====================== 辅助 ======================

    private static DatabaseBuilder builder(String name) {
        return new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    public record IdName(long id, String name) {
    }

    // ====================== 审计修复回归 ======================

    @Test
    void dollarQuotedLiteralInFragmentIsPreserved() {
        Sql q = Sql.select("*").from("t")
            .where("note = $tag$body :x $tag$");
        assertEquals(
            "SELECT * FROM t WHERE note = $tag$body :x $tag$",
            q.sql()
        );
        assertArrayEquals(new Object[0], q.args());
    }

    @Test
    void insertWithoutSetThrows() {
        SqlException ex = assertThrows(SqlException.class, () -> Sql.insert("t").sql());
        assertTrue(ex.getMessage().contains("at least one column"));
    }

    @Test
    void updateWithoutSetThrows() {
        SqlException ex = assertThrows(SqlException.class,
            () -> Sql.update("t").where("id = ?", 1).sql());
        assertTrue(ex.getMessage().contains("at least one SET"));
    }

    @Test
    void orderByAfterLimitIsRejected() {
        assertThrows(IllegalStateException.class,
            () -> Sql.select("*").from("t").limit(5).orderBy("id"));
    }

    @Test
    void offsetWithoutLimitIsRejected() {
        assertThrows(IllegalStateException.class,
            () -> Sql.select("*").from("t").offset(5));
    }

    @Test
    void insertSetRejectsExpression() {
        assertThrows(IllegalArgumentException.class,
            () -> Sql.insert("t").set("name = ?", "x"));
    }

    @Test
    void postgresXorFragmentIsDocumentedLimitation() {
        // SUPERSET treats bare '#' as a MySQL comment (jsonb #> exempted), so
        // a PostgreSQL XOR fragment with a placeholder after '#' cannot be
        // normalized at build time — the '?' is swallowed by the comment.
        SqlException ex = assertThrows(SqlException.class,
            () -> Sql.select("*").from("t").where("flags # 8 = ?", 1));
        assertTrue(ex.getMessage().contains("Too many parameter values"),
            "XOR fragments must fail at build time with a clear count error: "
                + ex.getMessage());
    }
}
