package com.jujin.freeway.db.internal;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NamedParamParser 纯单元测试 — 不依赖数据库，仅测试 SQL 文本解析逻辑。
 */
class NamedParamParserTest {

    // ===================== 命名参数解析 =====================

    @Test
    void noNamedParams() {
        var r = NamedParamParser.parse("select id, name from t where id = ?");
        assertEquals(List.of(), r.names());
        assertEquals("select id, name from t where id = ?", r.jdbcSql());
    }

    @Test
    void colonNamedParam() {
        var r = NamedParamParser.parse("select id from t where name = :name");
        assertEquals(List.of("name"), r.names());
        assertEquals("select id from t where name = ?", r.jdbcSql());
    }

    @Test
    void dollarNamedParam() {
        var r = NamedParamParser.parse("select id from t where name = $name");
        assertEquals(List.of("name"), r.names());
        assertEquals("select id from t where name = ?", r.jdbcSql());
    }

    @Test
    void multipleNamedParams() {
        var r = NamedParamParser.parse(
            "select id from t where x = :x and y = $y and z = :z"
        );
        assertEquals(List.of("x", "y", "z"), r.names());
        assertEquals(
            "select id from t where x = ? and y = ? and z = ?",
            r.jdbcSql()
        );
    }

    @Test
    void sameParamUsedMultipleTimes() {
        var r = NamedParamParser.parse(
            "select id from t where x >= :min and y >= :min"
        );
        // 同名参数在 names 列表中重复出现，以便按位置绑定
        assertEquals(List.of("min", "min"), r.names());
        assertEquals(
            "select id from t where x >= ? and y >= ?",
            r.jdbcSql()
        );
    }

    // ===================== 参数名边界 =====================

    @Test
    void paramNameWithUnderscore() {
        var r = NamedParamParser.parse("select id from t where x = :my_param");
        assertEquals(List.of("my_param"), r.names());
    }

    @Test
    void paramNameWithDigits() {
        var r = NamedParamParser.parse("select id from t where x = :p1 and y = :p2");
        assertEquals(List.of("p1", "p2"), r.names());
    }

    @Test
    void paramNameSingleLetter() {
        var r = NamedParamParser.parse("select id from t where x = :a");
        assertEquals(List.of("a"), r.names());
    }

    @Test
    void colonWithoutParamNameIsNotParsed() {
        // 冒号后跟非字母/下划线，不应被解析为参数
        var r = NamedParamParser.parse("select id from t where x = :1");
        assertEquals(List.of(), r.names());
        assertEquals("select id from t where x = :1", r.jdbcSql());
    }

    @Test
    void dollarWithoutParamNameIsNotParsed() {
        // $ 后跟非字母/下划线，不应被解析为参数
        var r = NamedParamParser.parse("select id from t where x = $1");
        assertEquals(List.of(), r.names());
        assertEquals("select id from t where x = $1", r.jdbcSql());
    }

    // ===================== 字符串字面量隔离 =====================

    @Test
    void namedParamInsideSingleQuotesIsIgnored() {
        var r = NamedParamParser.parse(
            "select id from t where label = '$literal'"
        );
        assertEquals(List.of(), r.names());
        assertEquals(
            "select id from t where label = '$literal'",
            r.jdbcSql()
        );
    }

    @Test
    void namedParamInsideDoubleQuotesIsIgnored() {
        var r = NamedParamParser.parse(
            "select id from t where label = \"$literal\""
        );
        assertEquals(List.of(), r.names());
        assertEquals(
            "select id from t where label = \"$literal\"",
            r.jdbcSql()
        );
    }

    @Test
    void escapedSingleQuoteInsideString() {
        // SQL 中 '' 是转义的单引号
        var r = NamedParamParser.parse(
            "select id from t where label = 'it''s :param'"
        );
        assertEquals(List.of(), r.names());
        assertEquals(
            "select id from t where label = 'it''s :param'",
            r.jdbcSql()
        );
    }

    @Test
    void paramOutsideQuotesIsParsedEvenWithQuotedContentNearby() {
        var r = NamedParamParser.parse(
            "select id from t where label = :name and desc = 'some text'"
        );
        assertEquals(List.of("name"), r.names());
        assertEquals(
            "select id from t where label = ? and desc = 'some text'",
            r.jdbcSql()
        );
    }

    // ===================== 注释隔离 =====================

    @Test
    void namedParamInSingleLineCommentIsIgnored() {
        var r = NamedParamParser.parse(
            "select id from t\n-- where x = :param\nwhere y = 1"
        );
        assertEquals(List.of(), r.names());
        assertEquals(
            "select id from t\n-- where x = :param\nwhere y = 1",
            r.jdbcSql()
        );
    }

    @Test
    void namedParamInBlockCommentIsIgnored() {
        var r = NamedParamParser.parse(
            "select id from t /* where x = :param */ where y = 1"
        );
        assertEquals(List.of(), r.names());
        assertEquals(
            "select id from t /* where x = :param */ where y = 1",
            r.jdbcSql()
        );
    }

    @Test
    void paramAfterCommentIsParsed() {
        var r = NamedParamParser.parse(
            "select id from t /* comment */ where x = :param"
        );
        assertEquals(List.of("param"), r.names());
        assertEquals(
            "select id from t /* comment */ where x = ?",
            r.jdbcSql()
        );
    }

    // ===================== 特殊场景 =====================

    @Test
    void emptySql() {
        var r = NamedParamParser.parse("");
        assertEquals(List.of(), r.names());
        assertEquals("", r.jdbcSql());
    }

    @Test
    void sqlWithoutAnySpecialChars() {
        var r = NamedParamParser.parse("select 1");
        assertEquals(List.of(), r.names());
        assertEquals("select 1", r.jdbcSql());
    }

    @Test
    void paramAfterFunctionCall() {
        var r = NamedParamParser.parse(
            "select coalesce(x, :default) from t"
        );
        assertEquals(List.of("default"), r.names());
        assertEquals(
            "select coalesce(x, ?) from t",
            r.jdbcSql()
        );
    }

    @Test
    void paramInInClause() {
        var r = NamedParamParser.parse(
            "select id from t where id in (:ids)"
        );
        assertEquals(List.of("ids"), r.names());
        assertEquals(
            "select id from t where id in (?)",
            r.jdbcSql()
        );
    }

    @Test
    void dollarSymbolInTableNameNotConfusedWithParam() {
        // $ 在标识符中间或末尾不是参数
        var r = NamedParamParser.parse("select id from t$1");
        assertEquals(List.of(), r.names());
        assertEquals("select id from t$1", r.jdbcSql());
    }

    @Test
    void colonInStringLiteralNotConfusedWithParam() {
        var r = NamedParamParser.parse(
            "select id from t where label = ':not_a_param'"
        );
        assertEquals(List.of(), r.names());
        assertEquals(
            "select id from t where label = ':not_a_param'",
            r.jdbcSql()
        );
    }

    @Test
    void mixedColonAndDollarParams() {
        var r = NamedParamParser.parse(
            "select id from t where x = :x and y = $y"
        );
        assertEquals(List.of("x", "y"), r.names());
        assertEquals(
            "select id from t where x = ? and y = ?",
            r.jdbcSql()
        );
    }

    @Test
    void paramInUpdateSet() {
        var r = NamedParamParser.parse(
            "update t set name = :name where id = :id"
        );
        assertEquals(List.of("name", "id"), r.names());
        assertEquals(
            "update t set name = ? where id = ?",
            r.jdbcSql()
        );
    }

    @Test
    void paramInInsert() {
        var r = NamedParamParser.parse(
            "insert into t (id, name) values (:id, :name)"
        );
        assertEquals(List.of("id", "name"), r.names());
        assertEquals(
            "insert into t (id, name) values (?, ?)",
            r.jdbcSql()
        );
    }
}
