package com.jujin.freeway.db;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import com.jujin.freeway.db.schema.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Orm {
    private final Database db;

    private Orm(Database db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    public static Orm of(Database db) {
        return new Orm(db);
    }

    // ==================== find ====================

    public <T> Optional<T> findById(Class<T> type, Object... idValues) {
        BeanPlan plan = BeanIntrospector.plan(type);
        String table = SqlTypeMapping.tableName(type);
        String columns = columnsClause(plan);
        String where = idWhereClause(plan);
        return db.query("SELECT " + columns + " FROM " + table + " WHERE " + where, idValues).one(type);
    }

    public <T> List<T> findAll(Class<T> type) {
        return findAll(type, "", 0, 0);
    }

    public <T> List<T> findAll(Class<T> type, String orderBy, int limit, int offset) {
        BeanPlan plan = BeanIntrospector.plan(type);
        String table = SqlTypeMapping.tableName(type);
        String columns = columnsClause(plan);
        StringBuilder sql = new StringBuilder("SELECT ").append(columns).append(" FROM ").append(table);
        if (orderBy != null && !orderBy.isBlank()) {
            sql.append(" ORDER BY ").append(orderBy);
        }
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
        }
        if (offset > 0) {
            sql.append(" OFFSET ").append(offset);
        }
        return db.query(sql.toString()).list(type);
    }

    // ==================== insert ====================

    public <T> ExecuteResult insert(T entity) {
        return insert(entity, null);
    }

    @SuppressWarnings("unchecked")
    public <T> ExecuteResult insert(T entity, Class<T> type) {
        Class<T> t = type != null ? type : (Class<T>) entity.getClass();
        BeanPlan plan = BeanIntrospector.plan(t);
        String table = SqlTypeMapping.tableName(t);
        ColumnInfo columns = insertColumns(plan);
        Object[] values = extractValues(plan, entity, columns.properties);

        ExecuteResult result = db.execute(
            "INSERT INTO " + table + " (" + String.join(", ", columns.names) + ") VALUES (" + placeholders(columns.names.size()) + ")",
            values);

        if (result.hasId() && columns.generated != null && !plan.record()) {
            columns.generated.write(entity, coercerValue(result.id(), columns.generated));
        }
        return result;
    }

    // ==================== save (upsert) ====================

    public <T> ExecuteResult save(T entity) {
        return save(entity, null);
    }

    @SuppressWarnings("unchecked")
    public <T> ExecuteResult save(T entity, Class<T> type) {
        Class<T> t = type != null ? type : (Class<T>) entity.getClass();
        BeanPlan plan = BeanIntrospector.plan(t);
        String table = SqlTypeMapping.tableName(t);
        List<BeanProperty> idProps = idProperties(plan);

        // read id values — if any are null, this is a plain insert
        Object[] idValues = new Object[idProps.size()];
        boolean hasFullId = true;
        for (int i = 0; i < idProps.size(); i++) {
            idValues[i] = idProps.get(i).read(entity);
            if (idValues[i] == null) hasFullId = false;
        }

        if (!hasFullId) {
            return insert(entity, t);
        }

        ColumnInfo columns = insertColumns(plan);
        Object[] insertValues = extractValues(plan, entity, columns.properties);

        // ON CONFLICT DO UPDATE — unique constraint already exists on @Id columns
        List<String> updateClauses = new ArrayList<>();
        for (int i = 0; i < columns.names.size(); i++) {
            updateClauses.add(columns.names.get(i) + " = EXCLUDED." + columns.names.get(i));
        }

        String sql = "INSERT INTO " + table + " (" + String.join(", ", columns.names) + ") VALUES ("
            + placeholders(columns.names.size()) + ") ON CONFLICT DO UPDATE SET "
            + String.join(", ", updateClauses);

        ExecuteResult result = db.execute(sql, insertValues);

        if (!plan.record() && columns.generated != null) {
            columns.generated.write(entity, coercerValue(result.id(), columns.generated));
        }
        return result;
    }

    // ==================== update ====================

    public <T> ExecuteResult update(T entity) {
        return update(entity, null);
    }

    @SuppressWarnings("unchecked")
    public <T> ExecuteResult update(T entity, Class<T> type) {
        Class<T> t = type != null ? type : (Class<T>) entity.getClass();
        BeanPlan plan = BeanIntrospector.plan(t);
        String table = SqlTypeMapping.tableName(t);
        List<BeanProperty> idProps = idProperties(plan);

        Map<String, Object> setClauses = new LinkedHashMap<>();
        for (BeanProperty prop : plan.properties()) {
            if (isOrmTransient(prop) || isGenerated(prop) || isId(prop)) continue;
            String col = SqlTypeMapping.columnName(prop, prop.annotation(Column.class));
            setClauses.put(col, prop.read(entity));
        }

        List<Object> values = new ArrayList<>(setClauses.values());
        Object[] ids = new Object[idProps.size()];
        for (int i = 0; i < idProps.size(); i++) {
            ids[i] = idProps.get(i).read(entity);
            if (ids[i] == null) {
                throw new SqlException("No @Id value for '" + idProps.get(i).name() + "' on " + t.getName());
            }
            values.add(ids[i]);
        }

        List<String> assignments = new ArrayList<>();
        for (String col : setClauses.keySet()) {
            assignments.add(col + " = ?");
        }

        return db.execute(
            "UPDATE " + table + " SET " + String.join(", ", assignments)
                + " WHERE " + idWhereClause(plan),
            values.toArray());
    }

    // ==================== delete ====================

    public <T> ExecuteResult delete(T entity) {
        return delete(entity, null);
    }

    @SuppressWarnings("unchecked")
    public <T> ExecuteResult delete(T entity, Class<T> type) {
        Class<T> t = type != null ? type : (Class<T>) entity.getClass();
        BeanPlan plan = BeanIntrospector.plan(t);
        String table = SqlTypeMapping.tableName(t);
        List<BeanProperty> idProps = idProperties(plan);

        Object[] ids = new Object[idProps.size()];
        for (int i = 0; i < idProps.size(); i++) {
            ids[i] = idProps.get(i).read(entity);
            if (ids[i] == null) {
                throw new SqlException("No @Id value for '" + idProps.get(i).name() + "' on " + t.getName());
            }
        }
        return db.execute("DELETE FROM " + table + " WHERE " + idWhereClause(plan), ids);
    }

    public <T> ExecuteResult deleteById(Class<T> type, Object... idValues) {
        BeanPlan plan = BeanIntrospector.plan(type);
        String table = SqlTypeMapping.tableName(type);
        return db.execute("DELETE FROM " + table + " WHERE " + idWhereClause(plan), idValues);
    }

    // ==================== helpers ====================

    private static String columnsClause(BeanPlan plan) {
        List<String> cols = new ArrayList<>();
        for (BeanProperty prop : plan.properties()) {
            if (isOrmTransient(prop)) continue;
            cols.add(SqlTypeMapping.columnName(prop, prop.annotation(Column.class)));
        }
        return String.join(", ", cols);
    }

    private static String idWhereClause(BeanPlan plan) {
        List<String> clauses = new ArrayList<>();
        for (BeanProperty prop : plan.properties()) {
            if (isId(prop)) {
                clauses.add(SqlTypeMapping.columnName(prop, prop.annotation(Column.class)) + " = ?");
            }
        }
        if (clauses.isEmpty()) {
            throw new SqlException("No @Id annotated property found on " + plan.type().getName());
        }
        return String.join(" AND ", clauses);
    }

    private static List<BeanProperty> idProperties(BeanPlan plan) {
        List<BeanProperty> result = new ArrayList<>();
        for (BeanProperty prop : plan.properties()) {
            if (isId(prop)) result.add(prop);
        }
        if (result.isEmpty()) {
            throw new SqlException("No @Id annotated property found on " + plan.type().getName());
        }
        return result;
    }

    private static Object coercerValue(long id, BeanProperty property) {
        Class<?> type = property.type() instanceof Class<?> c ? c : Long.class;
        if (type == Long.class || type == long.class) return id;
        if (type == Integer.class || type == int.class) return (int) id;
        if (type == Short.class || type == short.class) return (short) id;
        return id;
    }

    private static boolean isId(BeanProperty prop) {
        return prop.hasAnnotation(Id.class);
    }

    private static boolean isGenerated(BeanProperty prop) {
        return prop.hasAnnotation(Generated.class);
    }

    private static boolean isOrmTransient(BeanProperty prop) {
        return prop.hasAnnotation(Transient.class);
    }

    private static ColumnInfo insertColumns(BeanPlan plan) {
        List<String> names = new ArrayList<>();
        List<BeanProperty> properties = new ArrayList<>();
        BeanProperty generated = null;
        for (BeanProperty prop : plan.properties()) {
            if (isOrmTransient(prop)) continue;
            if (isGenerated(prop)) {
                if (generated != null) {
                    throw new SqlException("Multiple @Generated properties on " + plan.type().getName());
                }
                generated = prop;
                continue;
            }
            String col = SqlTypeMapping.columnName(prop, prop.annotation(Column.class));
            names.add(col);
            properties.add(prop);
        }
        return new ColumnInfo(names, properties, generated);
    }

    private static Object[] extractValues(BeanPlan plan, Object entity, List<BeanProperty> properties) {
        Object[] values = new Object[properties.size()];
        for (int i = 0; i < properties.size(); i++) {
            values[i] = properties.get(i).read(entity);
        }
        return values;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private record ColumnInfo(List<String> names, List<BeanProperty> properties, BeanProperty generated) {}
}
