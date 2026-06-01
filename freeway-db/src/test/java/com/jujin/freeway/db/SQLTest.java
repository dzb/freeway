package com.jujin.freeway.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SQL} 构建器的单元测试。
 * 直接验证 sql() 和 args() 的输出，无需数据库连接。
 */
class SQLTest {

    // ====================== SELECT ======================

    @Test
    void simpleSelect() {
        SQL q = SQL.select("id, name").from("users");
        assertEquals("SELECT id, name FROM users", q.sql());
        assertArrayEquals(new Object[0], q.args());
    }

    @Test
    void selectWithPositionalParam() {
        SQL q = SQL.select("*").from("users").where("id = ?", 1);
        assertEquals("SELECT * FROM users WHERE id = ?", q.sql());
        assertArrayEquals(new Object[]{1}, q.args());
    }

    @Test
    void selectWithNamedParam() {
        SQL q = SQL.select("*").from("users").where("name = :name", "john");
        assertEquals("SELECT * FROM users WHERE name = ?", q.sql());
        assertArrayEquals(new Object[]{"john"}, q.args());
    }

    @Test
    void selectWithDollarParam() {
        SQL q = SQL.select("*").from("users").where("status = $status", "active");
        assertEquals("SELECT * FROM users WHERE status = ?", q.sql());
        assertArrayEquals(new Object[]{"active"}, q.args());
    }

    @Test
    void selectWithMixedParams() {
        SQL q = SQL.select("*").from("users")
            .where("id = :id and name = ?", 1, "john");
        assertEquals("SELECT * FROM users WHERE id = ? and name = ?", q.sql());
        assertArrayEquals(new Object[]{1, "john"}, q.args());
    }

    @Test
    void selectWithMultipleWhere() {
        SQL q = SQL.select("*").from("users")
            .where("id = ?", 1)
            .where("name = ?", "john");
        assertEquals("SELECT * FROM users WHERE id = ? AND name = ?", q.sql());
        assertArrayEquals(new Object[]{1, "john"}, q.args());
    }

    @Test
    void selectWithOrWhere() {
        SQL q = SQL.select("*").from("users")
            .where("status = ?", 1)
            .orWhere("role = ?", "admin");
        assertEquals("SELECT * FROM users WHERE status = ? OR role = ?", q.sql());
        assertArrayEquals(new Object[]{1, "admin"}, q.args());
    }

    @Test
    void selectWithWhereNot() {
        SQL q = SQL.select("*").from("users")
            .where("status = ?", 1)
            .whereNot("deleted = ?", 1);
        assertEquals("SELECT * FROM users WHERE status = ? AND NOT deleted = ?", q.sql());
        assertArrayEquals(new Object[]{1, 1}, q.args());
    }

    @Test
    void selectWithWhereGroup() {
        SQL q = SQL.select("*").from("users")
            .whereGroup(g -> g.where("status = ?", "ACTIVE")
                .orWhere("role = ?", "admin"));
        assertEquals("SELECT * FROM users WHERE (status = ? OR role = ?)", q.sql());
        assertArrayEquals(new Object[]{"ACTIVE", "admin"}, q.args());
    }

    @Test
    void selectWithNestedWhereGroup() {
        SQL q = SQL.select("*").from("users")
            .whereGroup(g -> g.where("tenant_id = ?", 7)
                .whereGroup(h -> h.where("status = ?", "ACTIVE")
                    .orWhere("role = ?", "admin")));
        assertEquals("SELECT * FROM users WHERE (tenant_id = ? AND (status = ? OR role = ?))", q.sql());
        assertArrayEquals(new Object[]{7, "ACTIVE", "admin"}, q.args());
    }

    @Test
    void selectWithWhereNotGroup() {
        SQL q = SQL.select("*").from("users")
            .whereNotGroup(g -> g.where("deleted = ?", true)
                .where("archived = ?", true));
        assertEquals("SELECT * FROM users WHERE NOT (deleted = ? AND archived = ?)", q.sql());
        assertArrayEquals(new Object[]{true, true}, q.args());
    }

    @Test
    void joinMustBeClosedByOnBeforeNextClause() {
        assertThrows(IllegalStateException.class, () ->
            SQL.select("*").from("users")
                .join("orders")
                .where("orders.user_id = users.id"));
    }

    @Test
    void selectWithWhereOrWhereWhereNot() {
        SQL q = SQL.select("*").from("users")
            .where("a = ?", 1)
            .orWhere("b = ?", 2)
            .whereNot("c = ?", 3)
            .where("d = ?", 4);
        assertEquals("SELECT * FROM users WHERE a = ? OR b = ? AND NOT c = ? AND d = ?", q.sql());
        assertArrayEquals(new Object[]{1, 2, 3, 4}, q.args());
    }

    @Test
    void selectWithOrderBy() {
        SQL q = SQL.select("*").from("users").where("id = ?", 1).orderBy("name DESC");
        assertEquals("SELECT * FROM users WHERE id = ? ORDER BY name DESC", q.sql());
        assertArrayEquals(new Object[]{1}, q.args());
    }

    @Test
    void selectWithLimit() {
        SQL q = SQL.select("*").from("users").limit(10);
        assertEquals("SELECT * FROM users LIMIT 10", q.sql());
    }

    @Test
    void selectWithLimitOffset() {
        SQL q = SQL.select("*").from("users").limit(10).offset(20);
        assertEquals("SELECT * FROM users LIMIT 10 OFFSET 20", q.sql());
    }

    @Test
    void selectWithJoin() {
        SQL q = SQL.select("*").from("users")
            .join("orders").on("users.id = orders.user_id")
            .where("orders.total > ?", 100);
        assertEquals(
            "SELECT * FROM users JOIN orders ON users.id = orders.user_id WHERE orders.total > ?",
            q.sql());
        assertArrayEquals(new Object[]{100}, q.args());
    }

    @Test
    void selectWithLeftJoin() {
        SQL q = SQL.select("*").from("users")
            .leftJoin("orders").on("users.id = orders.user_id");
        assertEquals(
            "SELECT * FROM users LEFT JOIN orders ON users.id = orders.user_id",
            q.sql());
    }

    @Test
    void selectWithInnerJoin() {
        SQL q = SQL.select("*").from("users")
            .innerJoin("orders").on("users.id = orders.user_id");
        assertEquals(
            "SELECT * FROM users INNER JOIN orders ON users.id = orders.user_id",
            q.sql());
    }

    @Test
    void selectWithGroupByAndHaving() {
        SQL q = SQL.select("dept, count(*) as cnt").from("users")
            .groupBy("dept")
            .having("cnt > ?", 5);
        assertEquals(
            "SELECT dept, count(*) as cnt FROM users GROUP BY dept HAVING cnt > ?",
            q.sql());
        assertArrayEquals(new Object[]{5}, q.args());
    }

    @Test
    void selectWithHavingGroup() {
        SQL q = SQL.select("dept, count(*) as cnt").from("users")
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
        SQL left = SQL.select("id").from("active_users").where("status = ?", "A");
        SQL right = SQL.select("id").from("archived_users").where("status = ?", "B");

        SQL q = left.unionAll(right).orderBy("id DESC");

        assertEquals(
            "(SELECT id FROM active_users WHERE status = ?) UNION ALL (SELECT id FROM archived_users WHERE status = ?) ORDER BY id DESC",
            q.sql());
        assertArrayEquals(new Object[]{"A", "B"}, q.args());
    }

    @Test
    void unionRejectsFurtherWhereClauses() {
        assertThrows(IllegalStateException.class, () ->
            SQL.select("*").from("users")
                .union(SQL.select("*").from("archived_users"))
                .where("id = ?", 1));
    }

    @Test
    void selectWithCommonTableExpression() {
        SQL activeUsers = SQL.select("id")
            .from("users")
            .where("status = ?", "ACTIVE");

        SQL q = SQL.select("id")
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
        SQL sub = SQL.select("user_id").from("orders").where("total > ?", 100);
        SQL q = SQL.select("*").from("users").where("id in (?)", sub);
        assertEquals("SELECT * FROM users WHERE id in (SELECT user_id FROM orders WHERE total > ?)", q.sql());
        assertArrayEquals(new Object[]{100}, q.args());
    }

    @Test
    void insertRejectsWhere() {
        assertThrows(IllegalStateException.class, () ->
            SQL.insert("users").where("id = ?", 1));
    }

    @Test
    void selectRejectsSet() {
        assertThrows(IllegalStateException.class, () ->
            SQL.select("*").from("users").set("name = ?", "john"));
    }

    @Test
    void updateRejectsGroupBy() {
        assertThrows(IllegalStateException.class, () ->
            SQL.update("users").groupBy("dept"));
    }

    @Test
    void selectRejectsOnConflict() {
        assertThrows(IllegalStateException.class, () ->
            SQL.select("*").from("users").onConflict("id"));
    }

    // ====================== UPDATE ======================

    @Test
    void simpleUpdate() {
        SQL q = SQL.update("users").set("name = ?", "john").where("id = ?", 1);
        assertEquals("UPDATE users SET name = ? WHERE id = ?", q.sql());
        assertArrayEquals(new Object[]{"john", 1}, q.args());
    }

    @Test
    void updateWithMultipleSets() {
        SQL q = SQL.update("users")
            .set("name = ?", "john")
            .set("status = ?", 1)
            .where("id = ?", 42);
        assertEquals("UPDATE users SET name = ?, status = ? WHERE id = ?", q.sql());
        assertArrayEquals(new Object[]{"john", 1, 42}, q.args());
    }

    @Test
    void updateWithNamedParams() {
        SQL q = SQL.update("users")
            .set("name = :name", "john")
            .where("id = :id", 1);
        assertEquals("UPDATE users SET name = ? WHERE id = ?", q.sql());
        assertArrayEquals(new Object[]{"john", 1}, q.args());
    }

    @Test
    void updateWithReturning() {
        SQL q = SQL.update("users")
            .set("name = ?", "john")
            .where("id = ?", 1)
            .returning("id");
        assertEquals("UPDATE users SET name = ? WHERE id = ? RETURNING id", q.sql());
        assertArrayEquals(new Object[]{"john", 1}, q.args());
    }

    // ====================== INSERT ======================

    @Test
    void simpleInsert() {
        SQL q = SQL.insert("users").set("name", "john").set("status", 1);
        assertEquals("INSERT INTO users (name, status) VALUES (?, ?)", q.sql());
        assertArrayEquals(new Object[]{"john", 1}, q.args());
    }

    @Test
    void insertWithReturning() {
        SQL q = SQL.insert("users").set("name", "john").returning("id");
        assertEquals("INSERT INTO users (name) VALUES (?) RETURNING id", q.sql());
        assertArrayEquals(new Object[]{"john"}, q.args());
    }

    @Test
    void insertWithOnConflictDoNothing() {
        SQL q = SQL.insert("users").set("id", 1).set("name", "john")
            .onConflict("id")
            .doNothing();
        assertEquals("INSERT INTO users (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", q.sql());
        assertArrayEquals(new Object[]{1, "john"}, q.args());
    }

    @Test
    void insertWithOnConflictDoUpdateSet() {
        SQL q = SQL.insert("users").set("id", 1).set("name", "john")
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
        SQL q = SQL.insert("logs").set("message", "hello");
        assertEquals("INSERT INTO logs (message) VALUES (?)", q.sql());
        assertArrayEquals(new Object[]{"hello"}, q.args());
    }

    // ====================== DELETE ======================

    @Test
    void simpleDelete() {
        SQL q = SQL.delete("users").where("id = ?", 1);
        assertEquals("DELETE FROM users WHERE id = ?", q.sql());
        assertArrayEquals(new Object[]{1}, q.args());
    }

    @Test
    void deleteWithMultipleConditions() {
        SQL q = SQL.delete("users")
            .where("status = ?", 0)
            .where("expired = ?", true);
        assertEquals("DELETE FROM users WHERE status = ? AND expired = ?", q.sql());
        assertArrayEquals(new Object[]{0, true}, q.args());
    }

    // ====================== 不可变性 ======================

    @Test
    void immutability() {
        SQL q1 = SQL.select("*").from("users").where("id = ?", 1);
        SQL q2 = q1.where("name = ?", "john");

        assertEquals("SELECT * FROM users WHERE id = ?", q1.sql());
        assertArrayEquals(new Object[]{1}, q1.args());

        assertEquals("SELECT * FROM users WHERE id = ? AND name = ?", q2.sql());
        assertArrayEquals(new Object[]{1, "john"}, q2.args());
    }

    @Test
    void immutabilityInsert() {
        SQL q1 = SQL.insert("users").set("name", "john");
        SQL q2 = q1.set("status", 1);

        assertEquals("INSERT INTO users (name) VALUES (?)", q1.sql());
        assertArrayEquals(new Object[]{"john"}, q1.args());

        assertEquals("INSERT INTO users (name, status) VALUES (?, ?)", q2.sql());
        assertArrayEquals(new Object[]{"john", 1}, q2.args());
    }

    // ====================== 边缘情况 ======================

    @Test
    void whereNoConditions() {
        SQL q = SQL.select("*").from("users");
        assertEquals("SELECT * FROM users", q.sql());
        assertEquals(0, q.args().length);
    }

    @Test
    void stringLiteralContainingDollar() {
        SQL q = SQL.select("*").from("users").where("label = ?", "a$b");
        assertEquals("SELECT * FROM users WHERE label = ?", q.sql());
        assertArrayEquals(new Object[]{"a$b"}, q.args());
    }

    @Test
    void paramInStringLiteralNotParsed() {
        SQL q = SQL.select("*").from("users").where("name = '$literal'");
        assertEquals("SELECT * FROM users WHERE name = '$literal'", q.sql());
        assertEquals(0, q.args().length);
    }

    @Test
    void multipleParamsInOneFragment() {
        SQL q = SQL.select("*").from("users")
            .where("a = ? AND b = :b AND c = ?", 1, 2, 3);
        assertEquals("SELECT * FROM users WHERE a = ? AND b = ? AND c = ?", q.sql());
        assertArrayEquals(new Object[]{1, 2, 3}, q.args());
    }

    @Test
    void emptyWhereAfterFrom() {
        SQL q = SQL.select("*").from("users").orderBy("id");
        assertEquals("SELECT * FROM users ORDER BY id", q.sql());
    }

    @Test
    void toStringReturnsSql() {
        SQL q = SQL.select("*").from("users").where("id = ?", 1);
        assertEquals(q.sql(), q.toString());
    }

    @Test
    void equalsAndHashCode() {
        SQL q1 = SQL.select("*").from("users").where("id = ?", 1);
        SQL q2 = SQL.select("*").from("users").where("id = ?", 1);
        assertEquals(q1, q2);
        assertEquals(q1.hashCode(), q2.hashCode());
    }

    // ====================== 实际数据库集成测试 ======================

    @Test
    void integrationSelect() {
        var db = builder("sql_integ_select").build();
        try (db) {
            db.sql("create table t_user (id bigint primary key, name varchar(16), status int)").execute();
            db.sql("insert into t_user values (1, 'alpha', 1), (2, 'beta', 0)").execute();

            SQL q = SQL.select("*").from("t_user").where("status = ?", 1);
            var users = db.sql(q.sql(), q.args()).list(IdName.class);
            assertEquals(1, users.size());
            assertEquals("alpha", users.get(0).name());
        }
    }

    @Test
    void integrationSelectNamed() {
        var db = builder("sql_integ_named").build();
        try (db) {
            db.sql("create table t_user (id bigint primary key, name varchar(16))").execute();
            db.sql("insert into t_user values (1, 'alpha')").execute();

            SQL q = SQL.select("*").from("t_user").where("id = :id", 1L);
            var user = db.sql(q.sql(), q.args()).one(IdName.class);
            assertTrue(user.isPresent());
            assertEquals("alpha", user.get().name());
        }
    }

    @Test
    void integrationDynamicWhere() {
        var db = builder("sql_integ_dynamic").build();
        try (db) {
            db.sql("create table t_user (id bigint primary key, name varchar(16), age int)").execute();
            db.sql("insert into t_user values (1, 'alpha', 25), (2, 'beta', 30)").execute();

            // 动态条件模拟
            String nameFilter = "alpha";
            int ageFilter = 0;

            SQL q = SQL.select("*").from("t_user");
            if (!nameFilter.isEmpty()) q = q.where("name = ?", nameFilter);
            if (ageFilter > 0) q = q.where("age >= ?", ageFilter);

            var users = db.sql(q.sql(), q.args()).list(IdName.class);
            assertEquals(1, users.size());
            assertEquals("alpha", users.get(0).name());
        }
    }

    @Test
    void integrationInsert() {
        var db = builder("sql_integ_insert").build();
        try (db) {
            db.sql("create table t_user (id bigint primary key, name varchar(16))").execute();

            SQL q = SQL.insert("t_user").set("id", 1L).set("name", "newguy");
            db.sql(q.sql(), q.args()).execute();

            var user = db.sql("select id, name from t_user where id = ?", 1L).one(IdName.class);
            assertTrue(user.isPresent());
            assertEquals("newguy", user.get().name());
        }
    }

    @Test
    void integrationUpdate() {
        var db = builder("sql_integ_update").build();
        try (db) {
            db.sql("create table t_user (id bigint primary key, name varchar(16))").execute();
            db.sql("insert into t_user values (1, 'oldname')").execute();

            SQL q = SQL.update("t_user").set("name = ?", "newname").where("id = ?", 1L);
            db.sql(q.sql(), q.args()).execute();

            var user = db.sql("select id, name from t_user where id = ?", 1L).one(IdName.class);
            assertTrue(user.isPresent());
            assertEquals("newname", user.get().name());
        }
    }

    @Test
    void integrationDelete() {
        var db = builder("sql_integ_delete").build();
        try (db) {
            db.sql("create table t_user (id bigint primary key, name varchar(16))").execute();
            db.sql("insert into t_user values (1, 'goner'), (2, 'keeper')").execute();

            SQL q = SQL.delete("t_user").where("id = ?", 1L);
            db.sql(q.sql(), q.args()).execute();

            var users = db.sql("select id, name from t_user order by id").list(IdName.class);
            assertEquals(1, users.size());
            assertEquals("keeper", users.get(0).name());
        }
    }

    @Test
    void integrationJava25TextBlock() {
        // Java 25 文本块支持 —— 纯字符串构造即可，无特殊 API 变更
        var db = builder("sql_integ_textblock").build();
        try (db) {
            db.sql("create table t_user (id bigint primary key, name varchar(16))").execute();
            db.sql("insert into t_user values (1, 'hello')").execute();

            // 用文本块写 SQL，SQL 类只负责构建
            SQL q = SQL.select("id, name").from("t_user").where("id = ?", 1L);
            var user = db.sql(q.sql(), q.args()).one(IdName.class);
            assertTrue(user.isPresent());
        }
    }

    // ====================== 辅助 ======================

    private static DatabaseBuilder builder(String name) {
        return new DatabaseBuilder()
            .url("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            .username("sa")
            .password("");
    }

    public record IdName(long id, String name) {
    }
}
