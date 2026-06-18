package com.jujin.freeway.db.internal;

import com.jujin.freeway.commons.util.Strings;
import com.jujin.freeway.commons.bean.BeanConstructor;
import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import com.jujin.freeway.commons.bean.ReflectUtils;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.db.util.Names;
import com.jujin.freeway.db.Row;
import com.jujin.freeway.db.RowMapper;
import com.jujin.freeway.db.RowMapping;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.db.schema.Column;
import com.jujin.freeway.db.schema.SqlTypeMapping;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;

public final class RowMapperResolver {

    private final Coercer coercer;
    private final Map<Class<?>, RowMapper<?>> custom;
    private final ConcurrentHashMap<Class<?>, RowMapper<?>> cache =
        new ConcurrentHashMap<>();

    /**
     * IoC constructor — {@code List<RowMapping>} populated from module
     * contributions via {@code binder.contribute(RowMapping.class).add(...)}.
     */
    public RowMapperResolver(Coercer coercer, List<RowMapping> registrations) {
        this(coercer, Map.of(), toMap(registrations));
    }

    public RowMapperResolver(
        Coercer coercer,
        Map<Class<?>, RowMapper<?>> manualMappings,
        Map<Class<?>, RowMapper<?>> registrations
    ) {
        this.coercer = Objects.requireNonNull(coercer, "coercer");
        this.custom = customMap(manualMappings, registrations);
    }

    private static Map<Class<?>, RowMapper<?>> toMap(List<RowMapping> registrations) {
        if (registrations.isEmpty()) return Map.of();
        Map<Class<?>, RowMapper<?>> map = new LinkedHashMap<>();
        for (RowMapping entry : registrations) {
            map.put(
                Objects.requireNonNull(entry.type()),
                Objects.requireNonNull(entry.mapper())
            );
        }
        return map;
    }

    public <T> RowMapper<T> resolve(Class<T> type) {
        RowMapper<?> mapper = custom.get(type);
        if (mapper != null) {
            return narrow(mapper);
        }
        return narrow(cache.computeIfAbsent(type, this::createCached));
    }

    private static Map<Class<?>, RowMapper<?>> customMap(
        Map<Class<?>, RowMapper<?>> manual,
        Map<Class<?>, RowMapper<?>> registrations
    ) {
        if (manual.isEmpty() && registrations.isEmpty()) {
            return Map.of();
        }
        Map<Class<?>, RowMapper<?>> map = new LinkedHashMap<>();
        addAll(map, registrations);
        addAll(map, manual);
        return Map.copyOf(map);
    }

    private static void addAll(
        Map<Class<?>, RowMapper<?>> map,
        Map<Class<?>, RowMapper<?>> source
    ) {
        for (Map.Entry<Class<?>, RowMapper<?>> entry : source.entrySet()) {
            map.put(
                Objects.requireNonNull(entry.getKey()),
                Objects.requireNonNull(entry.getValue())
            );
        }
    }

    private RowMapper<?> create(Class<?> type) {
        if (type == Row.class) {
            return createRowMapper();
        }
        if (SqlTypeMapping.isBasicType(type)) {
            return (rs, rowNum) -> coercer.coerce(rs.getObject(1), type);
        }
        if (type.isInterface()) {
            throw new SqlException(
                "Cannot map interface " +
                    type.getName() +
                    ": register a custom RowMapper"
            );
        }
        if (Modifier.isAbstract(type.getModifiers())) {
            throw new SqlException(
                "Cannot map abstract class " +
                    type.getName() +
                    ": register a custom RowMapper"
            );
        }
        BeanPlan plan;
        try {
            plan = BeanIntrospector.plan(type);
        } catch (RuntimeException e) {
            throw new SqlException("Failed to introspect " + type.getName(), e);
        }
        if (plan.record()) {
            return createRecord(type, plan);
        }
        return createBean(type, plan);
    }

    private RowMapper<?> createCached(Class<?> type) {
        RowMapper<?> resolved = create(type);
        if (type.isPrimitive()) {
            return resolved;
        }
        return (rs, rowNum) -> type.cast(resolved.map(rs, rowNum));
    }

    private RowMapper<Row> createRowMapper() {
        return (rs, rowNum) -> {
            var meta = rs.getMetaData();
            int count = meta.getColumnCount();
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 1; i <= count; i++) {
                String label = meta.getColumnLabel(i);
                values.put(
                    label != null ? label.toLowerCase(Locale.ROOT) : "",
                    rs.getObject(i)
                );
            }
            return new Row(values, coercer);
        };
    }

    private RowMapper<?> createRecord(Class<?> type, BeanPlan plan) {
        BeanConstructor constructor = plan.constructor();
        List<BeanProperty> properties = plan.properties();
        ColumnCache columns = new ColumnCache(properties);
        return (rs, rowNum) -> {
            ResultSetMetaData meta = rs.getMetaData();
            int[] indexes = columns.resolve(meta);
            Object[] args = new Object[properties.size()];
            for (int i = 0; i < properties.size(); i++) {
                BeanProperty property = properties.get(i);
                int column = indexes[i];
                args[i] =
                    column >= 1
                        ? coercer.coerce(
                              rs.getObject(column),
                              ReflectUtils.rawClass(property.type())
                          )
                        : coercer.coerce(null, ReflectUtils.rawClass(property.type()));
            }
            try {
                return type.cast(constructor.newInstance(args));
            } catch (RuntimeException e) {
                throw new SqlException(
                    "Failed to construct " + type.getName(),
                    e
                );
            }
        };
    }

    private RowMapper<?> createBean(Class<?> type, BeanPlan plan) {
        if (!plan.isConstructable()) {
            throw new SqlException(
                "Cannot map " + type.getName() + ": no default constructor"
            );
        }
        BeanConstructor constructor = plan.constructor();
        List<BeanProperty> properties = plan
            .properties()
            .stream()
            .filter(BeanProperty::isWritable)
            .toList();
        ColumnCache columns = new ColumnCache(properties);
        return (rs, rowNum) -> {
            ResultSetMetaData meta = rs.getMetaData();
            int[] indexes = columns.resolve(meta);
            Object instance;
            try {
                instance = type.cast(constructor.newInstance());
            } catch (RuntimeException e) {
                throw new SqlException(
                    "Failed to construct " + type.getName(),
                    e
                );
            }
            for (int i = 0; i < properties.size(); i++) {
                int column = indexes[i];
                if (column < 1) {
                    continue;
                }
                BeanProperty property = properties.get(i);
                Object value = coercer.coerce(
                    rs.getObject(column),
                    ReflectUtils.rawClass(property.type())
                );
                try {
                    property.write(instance, value);
                } catch (RuntimeException e) {
                    throw new SqlException(
                        "Failed to set " +
                            property.name() +
                            " on " +
                            type.getName(),
                        e
                    );
                }
            }
            return instance;
        };
    }

    static int findColumn(
        ResultSetMetaData meta,
        String propertyName,
        String columnOverride
    ) throws SQLException {
        int columnCount = meta.getColumnCount();
        // 1. @Column annotation override — exact match, highest priority
        if (columnOverride != null && !columnOverride.isEmpty()) {
            for (int i = 1; i <= columnCount; i++) {
                String label = meta.getColumnLabel(i);
                if (label == null) label = meta.getColumnName(i);
                if (label == null) continue;
                if (columnOverride.equalsIgnoreCase(label)) {
                    return i;
                }
            }
        }
        // 2. Property name → camelCase / snake_case matching
        String snake = Strings.camelToSnake(propertyName);
        for (int i = 1; i <= columnCount; i++) {
            String label = meta.getColumnLabel(i);
            if (label == null) label = meta.getColumnName(i);
            if (label == null) continue;
            if (
                propertyName.equalsIgnoreCase(label) ||
                snake.equalsIgnoreCase(label)
            ) {
                return i;
            }
        }
        return -1;
    }

    private static final class ColumnCache {

        private final String[] names;
        private final String[] columnOverrides;
        private volatile Signature signature;
        private volatile int[] columns;

        private ColumnCache(List<BeanProperty> properties) {
            int n = properties.size();
            this.names = new String[n];
            this.columnOverrides = new String[n];
            for (int i = 0; i < n; i++) {
                BeanProperty prop = properties.get(i);
                this.names[i] = prop.name();
                Column col = prop.annotation(Column.class);
                this.columnOverrides[i] =
                    (col != null && !col.value().isEmpty()) ? col.value() : null;
            }
        }

        int[] resolve(ResultSetMetaData meta) throws SQLException {
            Signature current = Signature.of(meta);
            Signature cached = signature;
            if (cached != null && cached.equals(current)) {
                int[] resolved = columns;
                if (resolved != null) {
                    return resolved;
                }
            }
            int[] resolved = new int[names.length];
            for (int i = 0; i < names.length; i++) {
                resolved[i] = findColumn(meta, names[i], columnOverrides[i]);
            }
            signature = current;
            columns = resolved;
            return resolved;
        }
    }

    private record Signature(List<String> labels) {
        static Signature of(ResultSetMetaData meta) throws SQLException {
            int columnCount = meta.getColumnCount();
            List<String> labels = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                String label = meta.getColumnLabel(i);
                if (label == null || label.isBlank()) {
                    label = meta.getColumnName(i);
                }
                labels.add(label == null ? "" : label);
            }
            return new Signature(List.copyOf(labels));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> RowMapper<T> narrow(RowMapper<?> mapper) {
        return (RowMapper<T>) mapper;
    }

}
