package com.jujin.freeway.db.schema;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 从 Java 类型生成 DDL 语句。
 * <p>
 * 读取 {@link BeanPlan} 提取属性元数据，结合 {@link SqlTypeMapping} 和 {@link Dialect}
 * 输出完整的 {@code CREATE TABLE} 语句。
 */
public final class SchemaGenerator {

    private final Dialect dialect;

    public SchemaGenerator() {
        this(new DialectDefault());
    }

    public SchemaGenerator(Dialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    /**
     * 为单个实体类生成 CREATE TABLE 语句。
     */
    public String generate(Class<?> entityType) {
        return generateTable(define(entityType));
    }

    /**
     * 为多个实体类生成 CREATE TABLE 语句，按顺序返回。
     */
    public List<String> generateAll(Class<?>... entityTypes) {
        List<String> ddls = new ArrayList<>();
        for (Class<?> type : entityTypes) {
            ddls.add(generateTable(define(type)));
        }
        return ddls;
    }

    /**
     * 从实体类提取表定义。
     */
    public TableDef define(Class<?> entityType) {
        Objects.requireNonNull(entityType, "entityType");
        String tableName = SqlTypeMapping.tableName(entityType);
        BeanPlan plan = BeanIntrospector.plan(entityType);
        List<ColumnDef> columns = SqlTypeMapping.columns(plan, dialect);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException(
                entityType.getName() + " has no mappable properties"
            );
        }
        List<IndexDef> indexes = SqlTypeMapping.indexes(plan, tableName);
        return new TableDef(tableName, columns, indexes);
    }

    /**
     * 为实体类生成所有 CREATE INDEX 语句。
     */
    public List<String> generateIndexes(Class<?> entityType) {
        return dialect.createIndexes(define(entityType));
    }

    Dialect dialect() {
        return dialect;
    }

    private String generateTable(TableDef table) {
        return dialect.createTable(table);
    }
}
