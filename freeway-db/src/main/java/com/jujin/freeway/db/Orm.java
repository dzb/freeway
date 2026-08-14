package com.jujin.freeway.db;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import com.jujin.freeway.commons.util.Types;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.db.schema.Column;
import com.jujin.freeway.db.dialect.Dialect;
import com.jujin.freeway.db.schema.Generated;
import com.jujin.freeway.db.schema.Id;
import com.jujin.freeway.db.schema.SqlTypeMapping;
import com.jujin.freeway.db.schema.Transient;

import java.lang.reflect.Array;
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
            if (limit > 0) {
                sql.append(" OFFSET ").append(offset);
            } else {
                // No LIMIT — MySQL/SQLite reject a bare OFFSET, so each
                // dialect supplies its own "unlimited" form.
                sql.append(" ").append(dialect.offsetOnlyClause(offset));
            }
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
        ensureInsertable(columns, t);
        Object[] values = extractValues(plan, entity, columns.properties);

        ExecuteResult result = db.execute(
            "INSERT INTO " + table + " (" + String.join(", ", columns.names) + ") VALUES (" + placeholders(columns.names.size()) + ")",
            values);

        writeBackGeneratedKey(result, columns, entity, plan);
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

        // read id values and collect raw column names — if any id is unset, plain insert
        List<String> idCols = new ArrayList<>(idProps.size());
        boolean hasFullId = true;
        for (BeanProperty idProp : idProps) {
            if (!hasIdValue(idProp, entity)) hasFullId = false;
            idCols.add(rawColumnName(idProp));
        }

        if (!hasFullId) {
            return insert(entity, t);
        }

        // insertColumns() excludes @Generated properties, so the id column(s)
        // would be missing from the INSERT and the ON CONFLICT could never fire
        // (silently creating duplicate rows). Include the id properties as well.
        ColumnInfo baseColumns = insertColumns(plan);
        List<String> names = new ArrayList<>(baseColumns.names);
        List<String> rawNames = new ArrayList<>(baseColumns.rawNames);
        List<BeanProperty> properties = new ArrayList<>(baseColumns.properties);
        for (BeanProperty idProp : idProps) {
            String raw = rawColumnName(idProp);
            if (!rawNames.contains(raw)) {
                rawNames.add(raw);
                names.add(dialect.quoteName(raw));
                properties.add(idProp);
            }
        }
        ColumnInfo columns = new ColumnInfo(names, rawNames, properties, baseColumns.generated);
        ensureInsertable(columns, t);
        Object[] insertValues = extractValues(plan, entity, columns.properties);

        String sql = "INSERT INTO " + table + " (" + String.join(", ", columns.names) + ") VALUES ("
            + placeholders(columns.names.size()) + ")" + dialect.upsertClause(idCols, columns.rawNames);

        ExecuteResult result = db.execute(sql, insertValues);

        writeBackGeneratedKey(result, columns, entity, plan);
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
        if (setClauses.isEmpty()) {
            throw new SqlException(
                "No updatable properties on " + t.getName()
                    + " (all columns are @Id, @Generated, or @Transient)"
            );
        }

        List<Object> values = new ArrayList<>(setClauses.values());
        Object[] ids = requireIdValues(idProps, entity, t);
        for (Object id : ids) {
            values.add(id);
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

        Object[] ids = requireIdValues(idProps, entity, t);
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
        for (BeanProperty prop : idProperties(plan)) {
            clauses.add(dialect.quoteName(rawColumnName(prop)) + " = ?");
        }
        return String.join(" AND ", clauses);
    }

    /**
     * Writes the generated key back onto the entity after an INSERT (the
     * caller's column info must carry the {@code @Generated} property).
     */
    private void writeBackGeneratedKey(ExecuteResult result, ColumnInfo columns, Object entity, BeanPlan plan) {
        if (result.hasKey() && columns.generated != null && !plan.record()) {
            columns.generated.write(entity, coercer.coerce(result.key(), Types.rawClass(columns.generated.type())));
        }
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

    /**
     * Reads the id property values off the entity, failing with the standard
     * message when any of them is unset.
     */
    private static Object[] requireIdValues(List<BeanProperty> idProps, Object entity, Class<?> type) {
        Object[] ids = new Object[idProps.size()];
        for (int i = 0; i < idProps.size(); i++) {
            ids[i] = idProps.get(i).read(entity);
            if (ids[i] == null) {
                throw new SqlException("No @Id value for '" + idProps.get(i).name() + "' on " + type.getName());
            }
        }
        return ids;
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

    /**
     * Whether the entity carries an actual value for this id property. A null
     * id is always unset; a primitive {@code @Generated} id reads back its
     * type's default (0 / 0L / 0.0 / false) instead of null, so a fresh
     * entity would otherwise look fully identified and {@code save()} would
     * upsert an explicit zero id, bypassing the auto-increment sequence.
     */
    private static boolean hasIdValue(BeanProperty idProp, Object entity) {
        Object value = idProp.read(entity);
        if (value == null) {
            return false;
        }
        if (isGenerated(idProp)) {
            Class<?> raw = Types.rawClass(idProp.type());
            if (raw.isPrimitive()) {
                // Default boxed value of the primitive type (Array.get on a
                // one-element primitive array unboxes, then re-boxes to the
                // default — 0 / 0L / 0.0 / false).
                Object zero = Array.get(Array.newInstance(raw, 1), 0);
                return !zero.equals(value);
            }
        }
        return true;
    }

    /** Rejects an entity with nothing to insert (all properties @Generated/@Transient). */
    private static void ensureInsertable(ColumnInfo columns, Class<?> type) {
        if (columns.names.isEmpty()) {
            throw new SqlException(
                "No insertable properties on " + type.getName()
                    + " (all columns are @Generated or @Transient)"
            );
        }
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
