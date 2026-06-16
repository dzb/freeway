package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;
import java.util.List;
import java.util.Set;

/**
 * SQL 方言 — 负责 DDL 语法差异和数据库元数据查询。
 * 默认实现 {@link PostgresDialect} 适用于 PostgreSQL / H2 (PostgreSQL mode)。
 */
public interface Dialect {

    /** 引用标识符（表名、列名）。 */
    String quoteName(String name);

    /** 生成 CREATE TABLE 语句。 */
    String createTable(TableDef table);

    /** 生成独立的 CREATE INDEX 语句列表。 */
    List<String> createIndexes(TableDef table);

    /** 生成 ALTER TABLE ADD COLUMN 语句。 */
    String addColumn(String tableName, ColumnDef column);

    /** 生成 DROP TABLE IF EXISTS 语句。 */
    String dropTable(String tableName);

    /** 自增列的类型后缀，如 {@code "GENERATED ALWAYS AS IDENTITY"}。 */
    String generatedClause();

    /** UUID 列的默认 SQL 类型。 */
    String defaultUUIDType();

    /** 查询数据库中已存在的表名集合。 */
    Set<String> existingTables(Database db);

    /** 查询某个表的已有列名集合。 */
    Set<String> existingColumns(Database db, String tableName);
}
