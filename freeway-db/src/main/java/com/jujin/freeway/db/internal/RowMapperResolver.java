package com.jujin.freeway.db.internal;

import com.jujin.freeway.commons.bean.BeanConstructor;
import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.db.Names;
import com.jujin.freeway.db.Row;
import com.jujin.freeway.db.RowMapper;
import com.jujin.freeway.db.RowMapping;
import com.jujin.freeway.db.SqlException;
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

    @SuppressWarnings("unchecked")
    public <T> RowMapper<T> resolve(Class<T> type) {
        RowMapper<?> mapper = custom.get(type);
        if (mapper != null) {
            return (RowMapper<T>) mapper;
        }
        return (RowMapper<T>) cache.computeIfAbsent(type, this::create);
    }

    private static Map<Class<?>, RowMapper<?>> customMap(
        Map<Class<?>, RowMapper<?>> manual,
        Map<Class<?>, RowMapper<?>> registrations
    ) {
        if (
            (manual == null || manual.isEmpty()) &&
            (registrations == null || registrations.isEmpty())
        ) {
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
        if (source == null) return;
        for (Map.Entry<Class<?>, RowMapper<?>> entry : source.entrySet()) {
            map.put(
                Objects.requireNonNull(entry.getKey()),
                Objects.requireNonNull(entry.getValue())
            );
        }
    }

    private <T> RowMapper<T> create(Class<T> type) {
        if (type == Row.class) {
            return (RowMapper<T>) createRowMapper();
        }
        if (isBasicType(type)) {
            return createBasic(type);
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

    private boolean isBasicType(Class<?> type) {
        return (
            type == String.class ||
            type == Integer.class ||
            type == int.class ||
            type == Long.class ||
            type == long.class ||
            type == Double.class ||
            type == double.class ||
            type == Float.class ||
            type == float.class ||
            type == Short.class ||
            type == short.class ||
            type == Byte.class ||
            type == byte.class ||
            type == Boolean.class ||
            type == boolean.class ||
            type == Character.class ||
            type == char.class ||
            type == BigDecimal.class ||
            type == BigInteger.class ||
            type == LocalDate.class ||
            type == LocalDateTime.class ||
            type == LocalTime.class ||
            type == Instant.class ||
            type == UUID.class ||
            type == byte[].class
        );
    }

    private <T> RowMapper<T> createBasic(Class<T> type) {
        return (rs, rowNum) -> coercer.coerce(rs.getObject(1), type);
    }

    private RowMapper<Row> createRowMapper() {
        return (rs, rowNum) -> {
            var meta = rs.getMetaData();
            int count = meta.getColumnCount();
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 1; i <= count; i++) {
                String label = meta.getColumnLabel(i);
                values.put(
                    label != null ? label.toLowerCase() : "",
                    rs.getObject(i)
                );
            }
            return new Row(values, coercer);
        };
    }

    private <T> RowMapper<T> createRecord(Class<T> type, BeanPlan plan) {
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
                              rawClass(property.type())
                          )
                        : coercer.coerce(null, rawClass(property.type()));
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

    private <T> RowMapper<T> createBean(Class<T> type, BeanPlan plan) {
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
            T instance;
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
                    rawClass(property.type())
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

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (
            type instanceof ParameterizedType parameterized &&
            parameterized.getRawType() instanceof Class<?> raw
        ) {
            return raw;
        }
        throw new SqlException(
            "Unsupported bean property type: " + type.getTypeName()
        );
    }

    static int findColumn(ResultSetMetaData meta, String propertyName)
        throws SQLException {
        String snake = Names.camelToSnake(propertyName);
        int columnCount = meta.getColumnCount();
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
        private volatile Signature signature;
        private volatile int[] columns;

        private ColumnCache(List<BeanProperty> properties) {
            this.names = properties
                .stream()
                .map(BeanProperty::name)
                .toArray(String[]::new);
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
                resolved[i] = findColumn(meta, names[i]);
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
}
