package com.jujin.freeway.db;
import com.jujin.freeway.db.dialect.MySqlDialect;
import com.jujin.freeway.db.dialect.PostgresDialect;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void orWhereAsFirstConditionDropsTheConnector() {
        // Regression: the OR was hard-coded, so a condition list whose first
        // entry is an orWhere rendered "WHERE OR role = ?" — invalid SQL that
        // only the driver rejected. The connector now follows andConnector's
        // rule: nothing to connect means no connector.
        Sql q = Sql.select("*").from("users").orWhere("role = ?", "admin");
        assertEquals("SELECT * FROM users WHERE role = ?", q.sql());
        assertArrayEquals(new Object[]{"admin"}, q.args());
    }

    @Test
    void orWhereGroupAsFirstConditionDropsTheConnector() {
        Sql q = Sql.select("*").from("users")
            .orWhereGroup(g -> g.where("role = ?", "admin"));
        assertEquals("SELECT * FROM users WHERE (role = ?)", q.sql());
        assertArrayEquals(new Object[]{"admin"}, q.args());
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
    void chainedUnionDoesNotNestTheLeftSide() {
        Sql a = Sql.select("id").from("a").where("k = ?", 1);
        Sql b = Sql.select("id").from("b").where("k = ?", 2);
        Sql c = Sql.select("id").from("c").where("k = ?", 3);

        // UNION is left-associative, so a chained left side needs no
        // parentheses of its own — re-wrapping nested the text one level per
        // call for a query that reads the same either way.
        assertEquals(
            "(SELECT id FROM a WHERE k = ?) UNION (SELECT id FROM b WHERE k = ?) UNION (SELECT id FROM c WHERE k = ?)",
            a.union(b).union(c).sql());
        // The right side is the other case: `A ∪ (B ∪ C)` is not `A ∪ B ∪ C`, so
        // the grouping the caller wrote has to survive.
        assertEquals(
            "(SELECT id FROM a WHERE k = ?) UNION ((SELECT id FROM b WHERE k = ?) UNION (SELECT id FROM c WHERE k = ?))",
            a.union(b.union(c)).sql());
        // Bind order follows the branch order in both shapes.
        assertArrayEquals(new Object[]{1, 2, 3}, a.union(b).union(c).args());
        assertArrayEquals(new Object[]{1, 2, 3}, a.union(b.union(c)).args());
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

    /**
     * A {@code Sql} spliced bare into a fragment renders a statement that reads
     * as valid SQL, means something else, and is rejected by the driver — so the
     * refusal has to happen at build time, where the caller's own line is on the
     * stack. Each case below renders {@code … in SELECT …} or {@code … = SELECT
     * …} if the guard is removed; none of them throws today.
     */
    @Test
    void spliceWithoutParenthesesIsRefusedAtBuildTime() {
        Sql sub = Sql.select("user_id").from("orders").where("total > ?", 100);
        assertSpliceRefused(() -> Sql.select("*").from("users").where("id in ?", sub));
        assertSpliceRefused(() -> Sql.select("*").from("users").where("a = 1 and id in ?", sub));
        // A bare `?` carrying data, and a `?` inside someone else's parentheses,
        // are both untouched — the guard reads the fragment, not the argument.
        assertSpliceRefused(() -> Sql.update("t").setExpression("owner = ?", sub));
        assertSpliceRefused(() -> Sql.select("a").from("t").groupBy("a").having("cnt > ?", sub));
        assertEquals(
            "SELECT * FROM users WHERE a = ? and id in (SELECT user_id FROM orders WHERE total > ?)",
            Sql.select("*").from("users").where("a = ? and id in (?)", 1, sub).sql());
        assertEquals("SELECT * FROM users WHERE exists (select 1 from x where y = ?)",
            Sql.select("*").from("users")
                .where("exists (select 1 from x where y = ?)", 1).sql());
    }

    /**
     * Every fragment method splices through one place, so one message shape
     * covers all of them — and the message names the fix rather than the
     * symptom, because the symptom is a JDBC error the caller never sees.
     */
    private static void assertSpliceRefused(org.junit.jupiter.api.function.Executable call) {
        SqlException ex = assertThrows(SqlException.class, call);
        assertTrue(ex.getMessage().contains("\"(?)\""), ex.getMessage());
        assertTrue(ex.getMessage().contains("setColumn"), ex.getMessage());
    }

    @Test
    void insertRejectsWhere() {
        assertThrows(IllegalStateException.class, () ->
            Sql.insert("users").where("id = ?", 1));
    }

    @Test
    void selectRejectsSet() {
        assertThrows(IllegalStateException.class, () ->
            Sql.select("*").from("users").setExpression("name = ?", "john"));
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
        Sql q = Sql.update("users").setExpression("name = ?", "john").where("id = ?", 1);
        assertEquals("UPDATE users SET name = ? WHERE id = ?", q.sql());
        assertArrayEquals(new Object[]{"john", 1}, q.args());
    }

    @Test
    void updateWithMultipleSets() {
        Sql q = Sql.update("users")
            .setExpression("name = ?", "john")
            .setExpression("status = ?", 1)
            .where("id = ?", 42);
        assertEquals("UPDATE users SET name = ?, status = ? WHERE id = ?", q.sql());
        assertArrayEquals(new Object[]{"john", 1, 42}, q.args());
    }

    @Test
    void updateBindsSetArgsBeforeWhereArgsRegardlessOfCallOrder() {
        // The split into dmlValues (SET) + args (WHERE) exists precisely so
        // that binding order follows SQL text order — SET before WHERE — no
        // matter which fluent call came first. Merging the two lists would
        // silently reorder bindings when where() precedes set().
        Sql setFirst = Sql.update("users").setExpression("name = ?", "john").where("id = ?", 7L);
        Sql whereFirst = Sql.update("users").where("id = ?", 7L).setExpression("name = ?", "john");

        assertEquals(setFirst.sql(), whereFirst.sql());
        assertArrayEquals(new Object[]{"john", 7L}, setFirst.args());
        assertArrayEquals(setFirst.args(), whereFirst.args());
    }

    @Test
    void updateWithNamedParams() {
        Sql q = Sql.update("users")
            .setExpression("name = :name", "john")
            .where("id = :id", 1);
        assertEquals("UPDATE users SET name = ? WHERE id = ?", q.sql());
        assertArrayEquals(new Object[]{"john", 1}, q.args());
    }

    @Test
    void updateWithReturning() {
        Sql q = Sql.update("users")
            .setExpression("name = ?", "john")
            .where("id = ?", 1)
            .returning("id");
        assertEquals("UPDATE users SET name = ? WHERE id = ? RETURNING id", q.sql());
        assertArrayEquals(new Object[]{"john", 1}, q.args());
    }

    // ====================== INSERT ======================

    /**
     * A {@code Sql} value is SQL, not data. Splicing it in — parenthesized, so
     * it is a scalar subquery — is what makes a nested query expressible
     * through the INSERT-only {@code setColumn}: before this, the {@code Sql}
     * object was bound as a JDBC parameter and the statement silently meant
     * something else, and an unparenthesized splice would only parse on H2.
     */
    @Test
    void setColumnInlinesANestedQuery() {
        Sql sub = Sql.select("id").from("tenants").where("active = ?", true);
        Sql q = Sql.insert("users")
            .setColumn("tenant_id", sub)
            .setColumn("name", "john");

        assertEquals(
            "INSERT INTO users (tenant_id, name) VALUES ((SELECT id FROM tenants WHERE active = ?), ?)",
            q.sql());
        assertArrayEquals(new Object[]{true, "john"}, q.args(),
            "the subquery's own placeholder comes first — binding order follows "
                + "SQL text order (VALUES before WHERE)");
    }

    @Test
    void setColumnInlinesTwoNestedQueries() {
        Sql tenant = Sql.select("id").from("tenants").where("active = ?", true);
        Sql owner = Sql.select("id").from("users").where("name = ?", "root");
        Sql q = Sql.insert("memberships")
            .setColumn("tenant_id", tenant)
            .setColumn("owner_id", owner)
            .setColumn("role", "admin");

        assertEquals(
            "INSERT INTO memberships (tenant_id, owner_id, role) VALUES ("
                + "(SELECT id FROM tenants WHERE active = ?), "
                + "(SELECT id FROM users WHERE name = ?), ?)",
            q.sql());
        assertArrayEquals(new Object[]{true, "root", "admin"}, q.args(),
            "each inlined query contributes its own parameters at its own "
                + "position, and plain values keep theirs");
    }

    @Test
    void inlinedSubQueryWithNoParametersBindsNothing() {
        Sql sub = Sql.select("id").from("tenants");
        Sql q = Sql.insert("users").setColumn("tenant_id", sub);

        assertEquals(
            "INSERT INTO users (tenant_id) VALUES ((SELECT id FROM tenants))", q.sql());
        assertArrayEquals(new Object[0], q.args(),
            "the inline wrapper is not itself a bind value");
    }

    @Test
    void updatePathInlinesTheSameWay() {
        Sql sub = Sql.select("name").from("admins").where("id = ?", 7);
        Sql q = Sql.update("users")
            // A fragment is raw SQL the caller wrote, so the caller parenthesizes
            // a nested value — the same rule as where("id in (?)", sub).
            .setExpression("name = (?)", sub)
            .where("id = ?", 3);

        assertEquals(
            "UPDATE users SET name = (SELECT name FROM admins WHERE id = ?) WHERE id = ?",
            q.sql());
        assertArrayEquals(new Object[]{7, 3}, q.args());
    }

    /**
     * The parenthesis rule belongs to the renderer, not to the call site: an
     * unparenthesized splice merged the subquery's own WHERE into the enclosing
     * statement's, so {@code setExpression("a = ?", sub)} silently produced a
     * different query. Requiring {@code "a = (?)"} in the fragment pushed that
     * knowledge onto every caller, and the INSERT path had no fragment to carry
     * it — the same defect with two answers.
     */
    @Test
    void setColumnRejectsAFragmentAsTheColumnName() {
        // The javadoc promised a name is validated as a name; only a null check
        // existed, so a request-derived name became SQL: "a = 1, evil" rendered
        // as a second column.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> Sql.insert("t").setColumn("a = 1, evil", "v"));
        assertTrue(ex.getMessage().contains("is not a column name"), ex.getMessage());
        assertTrue(ex.getMessage().contains("setExpression"), ex.getMessage());
    }

    @Test
    void setColumnAcceptsBareAndQuotedNames() {
        for (String name : new String[]{"tenant_id", "_x", "col$1", "`my col`", "\"my col\"", "[my col]"}) {
            assertEquals("INSERT INTO users (" + name + ") VALUES (?)",
                Sql.insert("users").setColumn(name, "v").sql(),
                "must be accepted: " + name);
        }
    }

    /**
     * Bare and quoted are not alternatives — they compose, per part of a
     * qualified name. PostgreSQL and MySQL both spell a qualified name whose
     * parts need quoting this way, and a validator that rejects it breaks code
     * that worked the day before.
     */
    @Test
    void qualifiedNamesComposeBareAndQuotedParts() {
        for (String name : new String[]{
            "users.name", "public.\"My Col\"", "`db`.`table`.col",
            "\"sch\".tbl.\"c\"", "[db].[my table].[c]"}) {
            assertEquals("INSERT INTO users (" + name + ") VALUES (?)",
                Sql.insert("users").setColumn(name, "v").sql(),
                "must be accepted: " + name);
        }
    }

    /**
     * A dot inside a quoted part is that part's own character, so it must not
     * split the name — {@code "a.b"} is one column literally called {@code a.b}.
     */
    @Test
    void aDotInsideQuotesIsPartOfTheName() {
        assertEquals("INSERT INTO users (\"a.b\") VALUES (?)",
            Sql.insert("users").setColumn("\"a.b\"", "v").sql());
    }

    /**
     * A doubled delimiter is the standard escape ({@code "a""b"} is the name
     * {@code a"b}), and a foreign quote character inside a quoted part is an
     * ordinary character on the dialect that uses these delimiters. Rejecting
     * either would be the validator inventing rules the dialect does not have.
     */
    @Test
    void quotedPartsAllowEscapesAndForeignQuotes() {
        for (String name : new String[]{"\"a\"\"b\"", "`a\"b`", "`arr[0]`", "[a[b]"}) {
            assertEquals("INSERT INTO users (" + name + ") VALUES (?)",
                Sql.insert("users").setColumn(name, "v").sql(),
                "must be accepted: " + name);
        }
    }

    /** A name that closes its own quoting is the one thing the check exists for. */
    @Test
    void aNameThatClosesItsOwnQuotingIsRejected() {
        for (String name : new String[]{"\"a\"b\"", "`a`b`", "[a]b]", "a`b", "\"a\" = 1"}) {
            assertThrows(IllegalArgumentException.class,
                () -> Sql.insert("t").setColumn(name, "v"),
                "must be rejected: " + name);
        }
    }

    @Test
    void setColumnRejectsAValueThatIsNotAQuery() {
        // The slot renders as a scalar subquery; a UNION or an INSERT parenthesized
        // as an expression is nonsense that renders without complaint and fails at
        // the database instead.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> Sql.insert("t").setColumn("x",
                Sql.select("id").from("a").union(Sql.select("id").from("b"))));
        assertTrue(ex.getMessage().contains("scalar subquery"), ex.getMessage());
        assertTrue(ex.getMessage().contains("compound query"), ex.getMessage());
    }

    /** Each rejected kind is named, so the reader knows which call site to fix. */
    @Test
    void setColumnNamesTheKindOfStatementItRefuses() {
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> Sql.insert("t").setColumn("x", Sql.insert("u").setColumn("a", 1)))
            .getMessage().contains("an INSERT"));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> Sql.insert("t").setColumn("x", Sql.update("u").setExpression("a = ?", 1)))
            .getMessage().contains("an UPDATE"));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> Sql.insert("t").setColumn("x", Sql.delete("u")))
            .getMessage().contains("a DELETE"));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> Sql.insert("t").setColumn("x",
                Sql.select("1").unionAll(Sql.select("2"))))
            .getMessage().contains("UNION ALL"),
            "unionAll must not be reported as a plain UNION");
    }

    /** The message must not spill the statement's text or its bind values. */
    @Test
    void theRefusalDoesNotEchoTheStatementText() {
        String secret = "s3cret-tenant";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> Sql.insert("t").setColumn("x",
                Sql.select("id").from("tenants").where("name = ?", secret)
                    .union(Sql.select("id").from("other"))));
        assertFalse(ex.getMessage().contains(secret),
            "a bind value must not reach the exception message: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("FROM tenants"),
            "nor the statement text: " + ex.getMessage());
    }

    /** A WITH query is still a SELECT, and the javadoc says so. */
    @Test
    void aCommonTableExpressionIsAcceptedAsAColumnValue() {
        Sql cte = Sql.select("id").from("t").with("recent", Sql.select("id").from("u"));
        assertEquals("INSERT INTO t (x) VALUES ((WITH recent AS (SELECT id FROM u) SELECT id FROM t))",
            Sql.insert("t").setColumn("x", cte).sql());
    }

    @Test
    void plainValuesAreUnaffected() {
        Sql q = Sql.insert("users").setColumn("name", "john").setColumn("status", 1);
        assertEquals("INSERT INTO users (name, status) VALUES (?, ?)", q.sql());
        assertArrayEquals(new Object[]{"john", 1}, q.args());
    }

    @Test
    void simpleInsert() {
        Sql q = Sql.insert("users").setColumn("name", "john").setColumn("status", 1);
        assertEquals("INSERT INTO users (name, status) VALUES (?, ?)", q.sql());
        assertArrayEquals(new Object[]{"john", 1}, q.args());
    }

    @Test
    void setColumnTakesAnyColumnName() {
        // The old set() guessed INSERT-vs-UPDATE by scanning for '?', '=', ' '
        // and '(' in the fragment, so a legitimately quoted name with a space
        // was rejected as "an expression". The mode is in the method name now.
        Sql q = Sql.insert("users").setColumn("`my col`", "john");
        assertEquals("INSERT INTO users (`my col`) VALUES (?)", q.sql());
        assertArrayEquals(new Object[]{"john"}, q.args());
    }

    @Test
    void setColumnOnUpdateIsRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> Sql.update("users").setColumn("name", "john"));
        assertTrue(ex.getMessage().contains("setExpression"), ex.getMessage());
    }

    @Test
    void insertWithReturning() {
        Sql q = Sql.insert("users").setColumn("name", "john").returning("id");
        assertEquals("INSERT INTO users (name) VALUES (?) RETURNING id", q.sql());
        assertArrayEquals(new Object[]{"john"}, q.args());
    }

    @Test
    void returningValidatedAgainstDialect() {
        Sql q = Sql.insert("users").setColumn("name", "john").returning("id");
        assertEquals("INSERT INTO users (name) VALUES (?) RETURNING id",
            q.sql(new PostgresDialect()));
        assertThrows(SqlException.class, () ->
            q.sql(new MySqlDialect()));
    }

    @Test
    void onConflictValidatedAgainstDialect() {
        Sql q = Sql.insert("users").setColumn("id", 1).onConflict("id").doNothing();
        q.sql(new PostgresDialect());
        assertThrows(SqlException.class, () ->
            q.sql(new MySqlDialect()));
    }

    @Test
    void insertWithOnConflictDoNothing() {
        Sql q = Sql.insert("users").setColumn("id", 1).setColumn("name", "john")
            .onConflict("id")
            .doNothing();
        assertEquals("INSERT INTO users (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", q.sql());
        assertArrayEquals(new Object[]{1, "john"}, q.args());
    }

    @Test
    void insertWithOnConflictDoUpdateSet() {
        Sql q = Sql.insert("users").setColumn("id", 1).setColumn("name", "john")
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
        Sql q = Sql.insert("logs").setColumn("message", "hello");
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
        Sql q1 = Sql.insert("users").setColumn("name", "john");
        Sql q2 = q1.setColumn("status", 1);

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
        Sql q = Sql.select("*").from("event")
            .where("created_at::date = :d", LocalDate.of(2024, 1, 15));
        assertEquals("SELECT * FROM event WHERE created_at::date = ?", q.sql());
        assertEquals(1, q.args().length);
    }

    @Test
    void selectWithTypeCastAndMultipleNamedParams() {
        Sql q = Sql.select("*").from("event")
            .where("created_at::timestamp > :t AND id = :id",
                LocalDateTime.of(2024, 6, 1, 0, 0), 1L);
        assertEquals(
            "SELECT * FROM event WHERE created_at::timestamp > ? AND id = ?",
            q.sql());
        assertEquals(2, q.args().length);
    }

    @Test
    void selectWithTypeCastAndMixedParam() {
        // ? positional + :: type cast — :: handling should not break ?
        Sql q = Sql.select("*").from("event")
            .where("created_at::date > ? AND status = :s",
                LocalDate.of(2024, 1, 1), "active");
        assertEquals(
            "SELECT * FROM event WHERE created_at::date > ? AND status = ?",
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
        var db = Database.create(builder("sql_integ_select"));
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
        var db = Database.create(builder("sql_integ_named"));
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
        var db = Database.create(builder("sql_integ_dynamic"));
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
        var db = Database.create(builder("sql_integ_insert"));
        try (db) {
            db.execute("create table t_user (id bigint primary key, name varchar(16))");

            Sql q = Sql.insert("t_user").setColumn("id", 1L).setColumn("name", "newguy");
            db.execute(q.sql(), q.args());

            var user = db.query("select id, name from t_user where id = ?", 1L).one(IdName.class);
            assertTrue(user.isPresent());
            assertEquals("newguy", user.get().name());
        }
    }

    @Test
    void integrationUpdate() {
        var db = Database.create(builder("sql_integ_update"));
        try (db) {
            db.execute("create table t_user (id bigint primary key, name varchar(16))");
            db.execute("insert into t_user values (1, 'oldname')");

            Sql q = Sql.update("t_user").setExpression("name = ?", "newname").where("id = ?", 1L);
            db.execute(q.sql(), q.args());

            var user = db.query("select id, name from t_user where id = ?", 1L).one(IdName.class);
            assertTrue(user.isPresent());
            assertEquals("newname", user.get().name());
        }
    }

    @Test
    void integrationDelete() {
        var db = Database.create(builder("sql_integ_delete"));
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
        var db = Database.create(builder("sql_integ_textblock"));
        try (db) {
            db.execute("create table t_user (id bigint primary key, name varchar(16))");
            db.execute("insert into t_user values (1, 'hello')");

            // 用文本块写 Sql，Sql 类只负责构建
            Sql q = Sql.select("id, name").from("t_user").where("id = ?", 1L);
            var user = db.query(q.sql(), q.args()).one(IdName.class);
            assertTrue(user.isPresent());
        }
    }

    @Test
    void integrationExecuteAndQuerySqlBuilder() {
        // The Sql convenience methods must validate against the dialect and
        // still execute normally on a returning-capable database.
        var db = Database.create(builder("sql_integ_sql_builder"));
        try (db) {
            db.execute("create table t_user (id bigint primary key, name varchar(16))");

            ExecuteResult r = db.execute(Sql.insert("t_user").setColumn("id", 1L).setColumn("name", "alpha"));
            assertEquals(1, r.rows());

            String name = db.query(Sql.select("name").from("t_user").where("id = ?", 1L))
                .one(String.class).orElseThrow();
            assertEquals("alpha", name);
        }
    }

    @Test
    void backslashEscapedQuoteInFragmentKeepsNamedParam() {
        // MySQL-style \' inside a string literal must not close the string,
        // or :p would be swallowed and the fragment would fail at build time.
        Sql q = Sql.select("*").from("t")
            .where("label = 'it\\'s' AND x = :p", 5);
        assertEquals("SELECT * FROM t WHERE label = 'it\\'s' AND x = ?", q.sql());
        assertArrayEquals(new Object[]{5}, q.args());
    }

    // ====================== 辅助 ======================

    private static Database.Wiring builder(String name) {
        return Database.Wiring.defaults(PoolConfig.defaults("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
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
    void setExpressionOnInsertIsRejected() {
        // The mode is in the method name now, so the misuse is a builder-state
        // error (like every other require* guard), not a character heuristic.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> Sql.insert("t").setExpression("name = ?", "x"));
        assertTrue(ex.getMessage().contains("setColumn"), ex.getMessage());
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
