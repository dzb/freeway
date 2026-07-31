package com.jujin.freeway.db;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import com.jujin.freeway.commons.util.Types;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.db.schema.Column;
import com.jujin.freeway.db.schema.Dialect;
import com.jujin.freeway.db.schema.Generated;
import com.jujin.freeway.db.schema.Id;
import com.jujin.freeway.db.schema.SqlTypeMapping;
import com.jujin.freeway.db.schema.Transient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Lightweight ORM for entities annotated with {@code @Table}, {@code @Id},
 * {@code @Generated}, and {@code @Column}.
 *
 * <p>Provides basic CRUD operations:
 * <pre>{@code
 * Orm orm = Orm.of(db);
 * orm.insert(new Post("Hello", "World"));
 * Post p = orm.findById(Post.class, 1L).orElseThrow();
 * orm.save(p);  // upsert
 * orm.delete(p);
 * }</pre>
 */
public final class Orm {
    private final Database db;
    private final Dialect dialect;
    private final Coercer coercer;

    /** The dialect always comes from the database — it is never passed in. */
    public Orm(Database db, Coercer coercer) {
        this.db = Objects.requireNonNull(db, "db");
        this.dialect = Objects.requireNonNull(db.dialect(), "db.dialect()");
        this.coercer = Objects.requireNonNull(coercer, "coercer");
    }

    /** Creates an Orm with a default Coercer, using the dialect from the database. */
    public static Orm of(Database db) {
        return new Orm(db, new CoercerDefault());
    }

    /** Creates an Orm with the given Coercer, using the dialect from the database. */
    public static Orm of(Database db, Coercer coercer) {
        return new Orm(db, coercer);
    }

    // ==================== find ====================

    public <T> Optional<T> findById(Class<T> type, Object... idValues) {
        BeanPlan plan = BeanIntrospector.plan(type);
        String table = dialect.quoteName(SqlTypeMapping.tableName(type));
        String columns = columnsClause(plan);
        String where = idWhereClause(plan);
        return db.query("SELECT " + columns + " FROM " + table + " WHERE " + where, idValues).one(type);
    }

    public <T> List<T> findAll(Class<T> type) {
        return findAll(type, "", 0, 0);
    }

    /**
     * @param orderBy raw SQL ORDER BY clause (e.g. {@code "name ASC"}).
     *                <b>Warning:</b> this value is interpolated directly into the SQL;
     *                do not pass unsanitized user input.
     */
    public <T> List<T> findAll(Class<T> type, String orderBy, int limit, int offset) {
        BeanPlan plan = BeanIntrospector.plan(type);
        String table = dialect.quoteName(SqlTypeMapping.tableName(type));
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

    public <T> ExecuteResult insert(T entity, Class<T> type) {
        Class<T> t = resolveClass(entity, type);
        BeanPlan plan = BeanIntrospector.plan(t);
        String table = dialect.quoteName(SqlTypeMapping.tableName(t));
        ColumnInfo columns = insertColumns(plan);
        Object[] values = extractValues(plan, entity, columns.properties);

        ExecuteResult result = db.execute(
            "INSERT INTO " + table + " (" + String.join(", ", columns.names) + ") VALUES (" + placeholders(columns.names.size()) + ")",
            values);

        if (result.hasKey() && columns.generated != null && !plan.record()) {
            columns.generated.write(entity, coercer.coerce(result.key(), Types.rawClass(columns.generated.type())));
        }
        return result;
    }

    // ==================== save (upsert) ====================

    public <T> ExecuteResult save(T entity) {
        return save(entity, null);
    }

    public <T> ExecuteResult save(T entity, Class<T> type) {
        Class<T> t = resolveClass(entity, type);
        BeanPlan plan = BeanIntrospector.plan(t);
        String table = dialect.quoteName(SqlTypeMapping.tableName(t));
        List<BeanProperty> idProps = idProperties(plan);

        // read id values and collect raw column names — if any id is null, plain insert
        List<String> idCols = new ArrayList<>(idProps.size());
        boolean hasFullId = true;
        for (BeanProperty idProp : idProps) {
            if (idProp.read(entity) == null) hasFullId = false;
            idCols.add(rawColumnName(idProp));
        }

        if (!hasFullId) {
            return insert(entity, t);
        }

        ColumnInfo columns = insertColumns(plan);
        Object[] insertValues = extractValues(plan, entity, columns.properties);

        String sql = "INSERT INTO " + table + " (" + String.join(", ", columns.names) + ") VALUES ("
            + placeholders(columns.names.size()) + ")" + dialect.upsertClause(idCols, columns.rawNames);

        ExecuteResult result = db.execute(sql, insertValues);

        if (result.hasKey() && columns.generated != null && !plan.record()) {
            Object coercedKey = coercer.coerce(result.key(), Types.rawClass(columns.generated.type()));
            columns.generated.write(entity, coercedKey);
        }
        return result;
    }

    // ==================== update ====================

    public <T> ExecuteResult update(T entity) {
        return update(entity, null);
    }

    public <T> ExecuteResult update(T entity, Class<T> type) {
        Class<T> t = resolveClass(entity, type);
        BeanPlan plan = BeanIntrospector.plan(t);
        String table = dialect.quoteName(SqlTypeMapping.tableName(t));
        List<BeanProperty> idProps = idProperties(plan);

        Map<String, Object> setClauses = new LinkedHashMap<>();
        for (BeanProperty prop : plan.properties()) {
            if (isOrmTransient(prop) || isGenerated(prop) || isId(prop)) continue;
            String col = dialect.quoteName(rawColumnName(prop));
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

    public <T> ExecuteResult delete(T entity, Class<T> type) {
        Class<T> t = resolveClass(entity, type);
        BeanPlan plan = BeanIntrospector.plan(t);
        String table = dialect.quoteName(SqlTypeMapping.tableName(t));
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
        String table = dialect.quoteName(SqlTypeMapping.tableName(type));
        return db.execute("DELETE FROM " + table + " WHERE " + idWhereClause(plan), idValues);
    }

    // ==================== helpers ====================

    private String columnsClause(BeanPlan plan) {
        List<String> cols = new ArrayList<>();
        for (BeanProperty prop : plan.properties()) {
            if (isOrmTransient(prop)) continue;
            cols.add(dialect.quoteName(rawColumnName(prop)));
        }
        return String.join(", ", cols);
    }

    private String idWhereClause(BeanPlan plan) {
        List<String> clauses = new ArrayList<>();
        for (BeanProperty prop : plan.properties()) {
            if (isId(prop)) {
                clauses.add(dialect.quoteName(rawColumnName(prop)) + " = ?");
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

    @SuppressWarnings("unchecked")
    private static <T> Class<T> resolveClass(T entity, Class<T> type) {
        return type != null ? type : (Class<T>) entity.getClass();
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

    private static String rawColumnName(BeanProperty prop) {
        return SqlTypeMapping.columnName(prop, prop.annotation(Column.class).orElse(null));
    }

    private ColumnInfo insertColumns(BeanPlan plan) {
        List<String> names = new ArrayList<>();
        List<String> rawNames = new ArrayList<>();
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
            String raw = rawColumnName(prop);
            names.add(dialect.quoteName(raw));
            rawNames.add(raw);
            properties.add(prop);
        }
        return new ColumnInfo(names, rawNames, properties, generated);
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

    private record ColumnInfo(List<String> names, List<String> rawNames, List<BeanProperty> properties, BeanProperty generated) {}
}
