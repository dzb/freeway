package com.jujin.freeway.db.util;

import com.jujin.freeway.db.dialect.MySqlDialect;
import com.jujin.freeway.db.dialect.PostgresDialect;
import com.jujin.freeway.db.dialect.SqliteDialect;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqlTextParser 纯单元测试 — 不依赖数据库，仅测试 SQL 文本解析逻辑。
 */
class SqlTextParserTest {

    // ===================== 命名参数解析 =====================

    @Test
    void noNamedParams() {
        var r = SqlTextParser.parseNamed("select id, name from t where id = ?");
        assertEquals(List.of(), r.names());
        assertEquals("select id, name from t where id = ?", r.sql());
    }

    @Test
    void colonNamedParam() {
        var r = SqlTextParser.parseNamed("select id from t where name = :name");
        assertEquals(List.of("name"), r.names());
        assertEquals("select id from t where name = ?", r.sql());
    }

    @Test
    void dollarNamedParam() {
        var r = SqlTextParser.parseNamed("select id from t where name = $name");
        assertEquals(List.of("name"), r.names());
        assertEquals("select id from t where name = ?", r.sql());
    }

    @Test
    void multipleNamedParams() {
        var r = SqlTextParser.parseNamed(
            "select id from t where x = :x and y = $y and z = :z"
        );
        assertEquals(List.of("x", "y", "z"), r.names());
        assertEquals(
            "select id from t where x = ? and y = ? and z = ?",
            r.sql()
        );
    }

    @Test
    void sameParamUsedMultipleTimes() {
        var r = SqlTextParser.parseNamed(
            "select id from t where x >= :min and y >= :min"
        );
        // 同名参数在 names 列表中重复出现，以便按位置绑定
        assertEquals(List.of("min", "min"), r.names());
        assertEquals(
            "select id from t where x >= ? and y >= ?",
            r.sql()
        );
    }

    // ===================== 参数名边界 =====================

    @Test
    void paramNameWithUnderscore() {
        var r = SqlTextParser.parseNamed("select id from t where x = :my_param");
        assertEquals(List.of("my_param"), r.names());
    }

    @Test
    void paramNameWithDigits() {
        var r = SqlTextParser.parseNamed("select id from t where x = :p1 and y = :p2");
        assertEquals(List.of("p1", "p2"), r.names());
    }

    @Test
    void paramNameSingleLetter() {
        var r = SqlTextParser.parseNamed("select id from t where x = :a");
        assertEquals(List.of("a"), r.names());
    }

    @Test
    void colonWithoutParamNameIsNotParsed() {
        // 冒号后跟非字母/下划线，不应被解析为参数
        var r = SqlTextParser.parseNamed("select id from t where x = :1");
        assertEquals(List.of(), r.names());
        assertEquals("select id from t where x = :1", r.sql());
    }

    @Test
    void dollarWithoutParamNameIsNotParsed() {
        // $ 后跟非字母/下划线，不应被解析为参数
        var r = SqlTextParser.parseNamed("select id from t where x = $1");
        assertEquals(List.of(), r.names());
        assertEquals("select id from t where x = $1", r.sql());
    }

    // ===================== 字符串字面量隔离 =====================

    @Test
    void namedParamInsideSingleQuotesIsIgnored() {
        var r = SqlTextParser.parseNamed(
            "select id from t where label = '$literal'"
        );
        assertEquals(List.of(), r.names());
        assertEquals(
            "select id from t where label = '$literal'",
            r.sql()
        );
    }

    @Test
    void namedParamInsideDoubleQuotesIsIgnored() {
        var r = SqlTextParser.parseNamed(
            "select id from t where label = \"$literal\""
        );
        assertEquals(List.of(), r.names());
        assertEquals(
            "select id from t where label = \"$literal\"",
            r.sql()
        );
    }

    @Test
    void escapedDoubleQuoteInsideIdentifierIsIgnored() {
        var r = SqlTextParser.parseNamed(
            "select \"label \"\":param\"\"\" from t where id = :id"
        );
        assertEquals(List.of("id"), r.names());
        assertEquals(
            "select \"label \"\":param\"\"\" from t where id = ?",
            r.sql()
        );
    }

    @Test
    void escapedSingleQuoteInsideString() {
        // SQL 中 '' 是转义的单引号
        var r = SqlTextParser.parseNamed(
            "select id from t where label = 'it''s :param'"
        );
        assertEquals(List.of(), r.names());
        assertEquals(
            "select id from t where label = 'it''s :param'",
            r.sql()
        );
    }

    @Test
    void paramOutsideQuotesIsParsedEvenWithQuotedContentNearby() {
        var r = SqlTextParser.parseNamed(
            "select id from t where label = :name and desc = 'some text'"
        );
        assertEquals(List.of("name"), r.names());
        assertEquals(
            "select id from t where label = ? and desc = 'some text'",
            r.sql()
        );
    }

    // ===================== 注释隔离 =====================

    @Test
    void namedParamInSingleLineCommentIsIgnored() {
        var r = SqlTextParser.parseNamed(
            "select id from t\n-- where x = :param\nwhere y = 1"
        );
        assertEquals(List.of(), r.names());
        assertEquals(
            "select id from t\n-- where x = :param\nwhere y = 1",
            r.sql()
        );
    }

    @Test
    void namedParamInBlockCommentIsIgnored() {
        var r = SqlTextParser.parseNamed(
            "select id from t /* where x = :param */ where y = 1"
        );
        assertEquals(List.of(), r.names());
        assertEquals(
            "select id from t /* where x = :param */ where y = 1",
            r.sql()
        );
    }

    @Test
    void paramAfterCommentIsParsed() {
        var r = SqlTextParser.parseNamed(
            "select id from t /* comment */ where x = :param"
        );
        assertEquals(List.of("param"), r.names());
        assertEquals(
            "select id from t /* comment */ where x = ?",
            r.sql()
        );
    }

    // ===================== 特殊场景 =====================

    @Test
    void emptySql() {
        var r = SqlTextParser.parseNamed("");
        assertEquals(List.of(), r.names());
        assertEquals("", r.sql());
    }

    @Test
    void sqlWithoutAnySpecialChars() {
        var r = SqlTextParser.parseNamed("select 1");
        assertEquals(List.of(), r.names());
        assertEquals("select 1", r.sql());
    }

    @Test
    void paramAfterFunctionCall() {
        var r = SqlTextParser.parseNamed(
            "select coalesce(x, :default) from t"
        );
        assertEquals(List.of("default"), r.names());
        assertEquals(
            "select coalesce(x, ?) from t",
            r.sql()
        );
    }

    @Test
    void paramInInClause() {
        var r = SqlTextParser.parseNamed(
            "select id from t where id in (:ids)"
        );
        assertEquals(List.of("ids"), r.names());
        assertEquals(
            "select id from t where id in (?)",
            r.sql()
        );
    }

    @Test
    void dollarSymbolInTableNameNotConfusedWithParam() {
        // $ 在标识符中间或末尾不是参数
        var r = SqlTextParser.parseNamed("select id from t$1");
        assertEquals(List.of(), r.names());
        assertEquals("select id from t$1", r.sql());
    }

    @Test
    void colonInStringLiteralNotConfusedWithParam() {
        var r = SqlTextParser.parseNamed(
            "select id from t where label = ':not_a_param'"
        );
        assertEquals(List.of(), r.names());
        assertEquals(
            "select id from t where label = ':not_a_param'",
            r.sql()
        );
    }

    @Test
    void postgresCastIsNotConfusedWithNamedParam() {
        var r = SqlTextParser.parseNamed(
            "select created_at::timestamp from events where id = :id"
        );
        assertEquals(List.of("id"), r.names());
        assertEquals(
            "select created_at::timestamp from events where id = ?",
            r.sql()
        );
    }

    @Test
    void positionalPlaceholdersIgnoreStringsCommentsAndCasts() {
        var indexes = SqlTextParser.paramIndexes(
            "select '?'::varchar -- ? ignored\nwhere id = ? /* ? ignored */ and name = ?"
        );
        assertEquals(2, indexes.size());
    }

    @Test
    void namedParameterIndexesIgnoreLiteralAndCommentQuestionMarks() {
        var r = SqlTextParser.parseNamed(
            "select '?' -- ? ignored\nwhere id in ($ids) and name = :name"
        );
        assertEquals(List.of("ids", "name"), r.names());
        assertEquals(2, r.parameterIndexes().size());
        assertTrue(r.parameterIndexes().get(0) > r.sql().indexOf("'?'"));
        assertEquals('?', r.sql().charAt(r.parameterIndexes().get(0)));
        assertEquals('?', r.sql().charAt(r.parameterIndexes().get(1)));
    }

    @Test
    void positionalPlaceholdersIgnoreEscapedDoubleQuotedIdentifiers() {
        var indexes = SqlTextParser.paramIndexes(
            "select \"label \"\"?\"\"\" from t where id = ?"
        );
        assertEquals(1, indexes.size());
    }

    @Test
    void mixedColonAndDollarParams() {
        var r = SqlTextParser.parseNamed(
            "select id from t where x = :x and y = $y"
        );
        assertEquals(List.of("x", "y"), r.names());
        assertEquals(
            "select id from t where x = ? and y = ?",
            r.sql()
        );
    }

    @Test
    void paramInUpdateSet() {
        var r = SqlTextParser.parseNamed(
            "update t set name = :name where id = :id"
        );
        assertEquals(List.of("name", "id"), r.names());
        assertEquals(
            "update t set name = ? where id = ?",
            r.sql()
        );
    }

    @Test
    void paramInInsert() {
        var r = SqlTextParser.parseNamed(
            "insert into t (id, name) values (:id, :name)"
        );
        assertEquals(List.of("id", "name"), r.names());
        assertEquals(
            "insert into t (id, name) values (?, ?)",
            r.sql()
        );
    }

    // ===================== 标识符 / 注释 / 转义 =====================

    @Test
    void backtickIdentifierIsNotParsedForParams() {
        var r = SqlTextParser.parseNamed(
            "select `a:b` from t where id = :id"
        );
        assertEquals(List.of("id"), r.names());
        assertEquals("select `a:b` from t where id = ?", r.sql());
    }

    @Test
    void paramIndexesSkipQuestionMarkInBacktickIdentifier() {
        // The ? inside `a?b` is identifier text, not a placeholder; only the
        // trailing ? counts.
        assertEquals(
            List.of(30),
            SqlTextParser.paramIndexes("select `a?b` from t where x = ?")
        );
    }

    @Test
    void bracketIdentifierIsNotParsedForParams() {
        var r = SqlTextParser.parseNamed(
            "select [a:b] from t where id = :id"
        );
        assertEquals(List.of("id"), r.names());
        assertEquals("select [a:b] from t where id = ?", r.sql());
    }

    @Test
    void hashCommentIsSkipped() {
        var r = SqlTextParser.parseNamed("select 1 # :x\nfrom t");
        assertEquals(List.of(), r.names());
        assertEquals("select 1 # :x\nfrom t", r.sql());
    }

    @Test
    void jsonbHashOperatorIsNotAComment() {
        var r = SqlTextParser.parseNamed("select data #> :path from t");
        assertEquals(List.of("path"), r.names());
        assertEquals("select data #> ? from t", r.sql());
    }

    @Test
    void escapeStringBackslashQuotesAreSkipped() {
        var r = SqlTextParser.parseNamed(
            "select E'it\\'s' from t where x = :x"
        );
        assertEquals(List.of("x"), r.names());
        assertEquals("select E'it\\'s' from t where x = ?", r.sql());
    }

    @Test
    void mysqlBackslashEscapedQuoteKeepsFollowingParamVisible() {
        // MySQL treats \' as an escaped quote inside ordinary string literals;
        // the string must not close early, or :p after it would be swallowed.
        var r = SqlTextParser.parseNamed(
            "select id from t where label = 'it\\'s' and x = :p",
            new MySqlDialect()
        );
        assertEquals(List.of("p"), r.names());
        assertEquals("select id from t where label = 'it\\'s' and x = ?", r.sql());
    }

    @Test
    void supersetTreatsBackslashAsEscapeInStrings() {
        // SUPERSET (no dialect) is the union of all built-in dialects' syntax,
        // and MySQL's backslash escaping is part of that union — the union
        // errs on the side of skipping more text.
        var r = SqlTextParser.parseNamed(
            "select id from t where label = 'it\\'s' and x = :p"
        );
        assertEquals(List.of("p"), r.names());
    }

    @Test
    void postgresBackslashInOrdinaryStringIsNotAnEscape() {
        // PostgreSQL (standard_conforming_strings=on): a backslash is literal,
        // so 'it\' closes the string and the trailing quote opens a new
        // (unterminated) string that swallows :p — a user SQL error, and the
        // PG profile keeps that behavior unchanged.
        var r = SqlTextParser.parseNamed(
            "select id from t where label = 'it\\'s' and x = :p",
            new PostgresDialect()
        );
        assertEquals(List.of(), r.names());
    }

    @Test
    void unterminatedDollarQuoteIsLeftAsLiteral() {
        var r = SqlTextParser.parseNamed("select $$abc");
        assertEquals(List.of(), r.names());
        // Regression: the body used to be appended twice ("$$abcabc").
        assertEquals("select $$abc", r.sql());
    }

    @Test
    void splitStatementsIgnoresSemicolonsInHashComments() {
        var stmts = SqlTextParser.splitStatements(
            "select 1 # comment; here\n; select 2"
        );
        assertEquals(List.of("select 1", "select 2"), stmts);
    }

    // ===================== 方言画像 =====================

    @Test
    void postgresProfileTreatsHashAsOperatorNotComment() {
        // PostgreSQL has no # comments — # is the XOR operator, so a named
        // parameter inside a "# ..." region is real SQL there.
        var r = SqlTextParser.parseNamed(
            "select a # b where x = :x",
            new PostgresDialect()
        );
        assertEquals(List.of("x"), r.names());
        assertEquals("select a # b where x = ?", r.sql());
    }

    @Test
    void postgresProfileKeepsJsonbPathOperators() {
        var r = SqlTextParser.parseNamed(
            "select data #> :path from t",
            new PostgresDialect()
        );
        assertEquals(List.of("path"), r.names());
        assertEquals("select data #> ? from t", r.sql());
    }

    @Test
    void postgresProfileDoesNotRecognizeBackticks() {
        // Backticks are not quoting in PostgreSQL, so :b inside them is a
        // parameter there (the lenient superset treats them as an identifier).
        var r = SqlTextParser.parseNamed(
            "select `a:b` from t where x = :x",
            new PostgresDialect()
        );
        assertEquals(List.of("b", "x"), r.names());
    }

    @Test
    void mysqlProfileRecognizesHashCommentsAndBackticks() {
        var comment = SqlTextParser.parseNamed(
            "select 1 # :x\nfrom t",
            new MySqlDialect()
        );
        assertEquals(List.of(), comment.names());

        var backtick = SqlTextParser.parseNamed(
            "select `a:b` from t where id = :id",
            new MySqlDialect()
        );
        assertEquals(List.of("id"), backtick.names());
        assertEquals("select `a:b` from t where id = ?", backtick.sql());
    }

    @Test
    void sqliteProfileRecognizesBackticks() {
        // SQLite accepts ANSI double quotes and MySQL-style backticks.
        var r = SqlTextParser.parseNamed(
            "select `a:b` from t where x = :x",
            new SqliteDialect()
        );
        assertEquals(List.of("x"), r.names());
        assertEquals("select `a:b` from t where x = ?", r.sql());
    }

    @Test
    void supersetDefaultStaysLenient() {
        // The no-dialect entry points keep the union-of-dialects behavior.
        var r = SqlTextParser.parseNamed("select `a:b` from t where x = :x");
        assertEquals(List.of("x"), r.names());
    }

    // ===================== INSERT 检测 =====================

    @Test
    void insertDetectionRequiresStatementHead() {
        assertTrue(SqlTextParser.hasTopLevelInsert("insert into t values (1)"));
        assertTrue(SqlTextParser.hasTopLevelInsert("INSERT INTO t values (1)"));
        assertTrue(
            SqlTextParser.hasTopLevelInsert(
                "with x as (select 1) insert into t select * from x"
            )
        );
        // Regression: a depth-0 'insert' token inside a SELECT must not match.
        assertFalse(SqlTextParser.hasTopLevelInsert("select * from insert into"));
        assertFalse(SqlTextParser.hasTopLevelInsert("delete from t"));
        assertFalse(SqlTextParser.hasTopLevelInsert("-- comment\nselect 1"));
    }
}
