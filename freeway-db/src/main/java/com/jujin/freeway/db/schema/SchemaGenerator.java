package com.jujin.freeway.db.schema;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.db.dialect.Dialect;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Generates DDL statements from Java types.
 * <p>
 * Reads {@link BeanPlan} for property metadata, combining {@link SqlTypeMapping}
 * and {@link Dialect} to produce complete {@code CREATE TABLE} statements. DDL
 * <em>assembly</em> is this class's responsibility — the dialect only supplies
 * syntax primitives ({@link Dialect#quoteName}, {@link Dialect#generatedClause},
 * capability flags).
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
        TableDef table = define(entityType);
        List<String> ddls = new ArrayList<>();
        for (IndexDef idx : table.indexes()) {
            ddls.add(idx.toSql(dialect, table.name()));
        }
        return ddls;
    }

    /**
     * Assembles a {@code CREATE TABLE IF NOT EXISTS} statement. Dialects that
     * declare {@link Dialect#generatedPrimaryKeyInline()} (SQLite) render the
     * generated primary key inline on the column and omit the trailing
     * {@code PRIMARY KEY (...)} clause.
     */
    String generateTable(TableDef table) {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sb.append(dialect.quoteName(table.name())).append(" (\n");
        List<String> pks = table.primaryKeys();
        boolean inlineGeneratedPk = table.columns().stream()
            .anyMatch(c -> c.generated() && c.primaryKey() && dialect.generatedPrimaryKeyInline());
        if (inlineGeneratedPk && pks.size() != 1) {
            throw new IllegalArgumentException(
                "SQLite AUTOINCREMENT requires a single primary key column");
        }
        if (dialect.generatedPrimaryKeyInline()) {
            // SQLite AUTOINCREMENT applies only to an INTEGER PRIMARY KEY
            // column — anything else fails at the DB with a confusing error.
            long generated = table.columns().stream()
                .filter(ColumnDef::generated)
                .count();
            if (generated > 0 && pks.size() != 1) {
                throw new IllegalArgumentException(
                    "SQLite generated columns must also be the single primary "
                        + "key column");
            }
            if (generated > 1) {
                throw new IllegalArgumentException(
                    "SQLite supports at most one generated column (the "
                        + "primary key)");
            }
        }
        for (int i = 0; i < table.columns().size(); i++) {
            ColumnDef col = table.columns().get(i);
            sb.append("    ").append(col.toSql(dialect));
            if (i < table.columns().size() - 1) {
                sb.append(",\n");
            }
        }
        if (!pks.isEmpty() && !inlineGeneratedPk) {
            sb.append(",\n    PRIMARY KEY (");
            sb.append(pks.stream().map(dialect::quoteName).collect(Collectors.joining(", ")));
            sb.append(")");
        }
        sb.append("\n)");
        return sb.toString();
    }
}
