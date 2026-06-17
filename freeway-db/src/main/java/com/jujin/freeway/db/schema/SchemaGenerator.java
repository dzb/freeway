package com.jujin.freeway.db.schema;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Generates DDL statements from Java types.
 * <p>
 * Reads {@link BeanPlan} for property metadata, combining {@link SqlTypeMapping}
 * and {@link Dialect} to produce complete {@code CREATE TABLE} statements.
 */
public final class SchemaGenerator {

    private final Dialect dialect;

    public SchemaGenerator(Dialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    /** Generates a CREATE TABLE statement for a single entity type. */
    public String generate(Class<?> entityType) {
        return generateTable(define(entityType));
    }

    /** Generates CREATE TABLE statements for multiple entity types, in order. */
    public List<String> generateAll(Class<?>... entityTypes) {
        List<String> ddls = new ArrayList<>();
        for (Class<?> type : entityTypes) {
            ddls.add(generateTable(define(type)));
        }
        return ddls;
    }

    /** Extracts the table definition from an entity type. */
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

    /** Generates all CREATE INDEX statements for an entity type. */
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
