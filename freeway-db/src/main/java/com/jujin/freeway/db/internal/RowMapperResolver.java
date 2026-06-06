package com.jujin.freeway.db.internal;

import com.jujin.freeway.commons.bean.BeanConstructor;
import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.db.RowMapper;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.annotation.Extension;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RowMapperResolver {
    private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_WRAPPER = new HashMap<>(8);

    static {
        PRIMITIVE_TO_WRAPPER.put(boolean.class, Boolean.class);
        PRIMITIVE_TO_WRAPPER.put(byte.class, Byte.class);
        PRIMITIVE_TO_WRAPPER.put(short.class, Short.class);
        PRIMITIVE_TO_WRAPPER.put(int.class, Integer.class);
        PRIMITIVE_TO_WRAPPER.put(long.class, Long.class);
        PRIMITIVE_TO_WRAPPER.put(float.class, Float.class);
        PRIMITIVE_TO_WRAPPER.put(double.class, Double.class);
        PRIMITIVE_TO_WRAPPER.put(char.class, Character.class);
    }

    private final Coercer coercer;
    private final Map<Class<?>, RowMapper<?>> custom;
    private final ConcurrentHashMap<Class<?>, RowMapper<?>> cache = new ConcurrentHashMap<>();

    @Inject
    public RowMapperResolver(
        Coercer coercer,
        @Extension(RowMapper.class) Map<Class<?>, RowMapper<?>> registrations
    ) {
        this(coercer, Map.<Class<?>, RowMapper<?>>of(), registrations);
    }

    public RowMapperResolver(
        Coercer coercer,
        Map<Class<?>, RowMapper<?>> manualMappings,
        @Extension(RowMapper.class) Map<Class<?>, RowMapper<?>> registrations
    ) {
        this.coercer = Objects.requireNonNull(coercer, "coercer");
        this.custom = customMap(manualMappings, registrations);
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
        Map<Class<?>, RowMapper<?>> manualMappings,
        Map<Class<?>, RowMapper<?>> registrations
    ) {
        if ((manualMappings == null || manualMappings.isEmpty())
            && (registrations == null || registrations.isEmpty())) {
            return Map.of();
        }
        Map<Class<?>, RowMapper<?>> map = new LinkedHashMap<>();
        if (registrations != null) {
            for (Map.Entry<Class<?>, RowMapper<?>> entry : registrations.entrySet()) {
                Class<?> type = Objects.requireNonNull(entry.getKey(), "registration.key");
                RowMapper<?> mapper = Objects.requireNonNull(entry.getValue(), "registration.value");
                map.put(type, mapper);
            }
        }
        if (manualMappings != null) {
            for (Map.Entry<Class<?>, RowMapper<?>> entry : manualMappings.entrySet()) {
                Class<?> type = Objects.requireNonNull(entry.getKey(), "manual.key");
                RowMapper<?> mapper = Objects.requireNonNull(entry.getValue(), "manual.value");
                map.put(type, mapper);
            }
        }
        return Map.copyOf(map);
    }

    private <T> RowMapper<T> create(Class<T> type) {
        if (isSimpleType(type)) {
            return createSimple(type);
        }
        if (type.isInterface()) {
            throw new SqlException("Cannot map interface " + type.getName() + ": register a custom RowMapper");
        }
        if (Modifier.isAbstract(type.getModifiers())) {
            throw new SqlException("Cannot map abstract class " + type.getName() + ": register a custom RowMapper");
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

    private boolean isSimpleType(Class<?> type) {
        return type == String.class
            || type == Integer.class || type == int.class
            || type == Long.class || type == long.class
            || type == Double.class || type == double.class
            || type == Float.class || type == float.class
            || type == Short.class || type == short.class
            || type == Byte.class || type == byte.class
            || type == Boolean.class || type == boolean.class
            || type == Character.class || type == char.class
            || type == BigDecimal.class
            || type == BigInteger.class
            || type == LocalDate.class
            || type == LocalDateTime.class
            || type == LocalTime.class
            || type == Instant.class
            || type == UUID.class
            || type == byte[].class;
    }

    private <T> RowMapper<T> createSimple(Class<T> type) {
        return (rs, rowNum) -> coerce(rs.getObject(1), type);
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
                args[i] = column >= 1
                    ? coerce(rs.getObject(column), propertyType(property))
                    : defaultValue(propertyType(property));
            }
            try {
                return type.cast(constructor.newInstance(args));
            } catch (RuntimeException e) {
                throw new SqlException("Failed to construct " + type.getName(), e);
            }
        };
    }

    private <T> RowMapper<T> createBean(Class<T> type, BeanPlan plan) {
        if (!plan.constructable()) {
            throw new SqlException("Cannot map " + type.getName() + ": no default constructor");
        }
        BeanConstructor constructor = plan.constructor();
        List<BeanProperty> properties = plan.properties().stream()
            .filter(BeanProperty::writable)
            .toList();
        ColumnCache columns = new ColumnCache(properties);
        return (rs, rowNum) -> {
            ResultSetMetaData meta = rs.getMetaData();
            int[] indexes = columns.resolve(meta);
            T instance;
            try {
                instance = type.cast(constructor.newInstance());
            } catch (RuntimeException e) {
                throw new SqlException("Failed to construct " + type.getName(), e);
            }
            for (int i = 0; i < properties.size(); i++) {
                int column = indexes[i];
                if (column < 1) {
                    continue;
                }
                BeanProperty property = properties.get(i);
                Object value = coerce(rs.getObject(column), propertyType(property));
                try {
                    property.write(instance, value);
                } catch (RuntimeException e) {
                    throw new SqlException(
                        "Failed to set " + property.name() + " on " + type.getName(),
                        e
                    );
                }
            }
            return instance;
        };
    }

    private static Class<?> propertyType(BeanProperty property) {
        return rawClass(property.type());
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        throw new SqlException("Unsupported bean property type: " + type.getTypeName());
    }

    @SuppressWarnings("unchecked")
    private <T> T coerce(Object value, Class<T> target) {
        if (value == null) {
            return defaultValue(target);
        }
        if (target.isInstance(value)) {
            return target.cast(value);
        }
        if (target.isPrimitive()) {
            Class<?> wrapper = PRIMITIVE_TO_WRAPPER.get(target);
            if (wrapper != null && wrapper.isInstance(value)) {
                return (T) value;
            }
        }
        try {
            return coercer.coerce(value, target);
        } catch (RuntimeException e) {
            return sqlFallback(value, target);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T sqlFallback(Object value, Class<T> target) {
        if (target == LocalDate.class && value instanceof java.sql.Date date) {
            return (T) date.toLocalDate();
        }
        if (target == LocalDateTime.class && value instanceof Timestamp timestamp) {
            return (T) timestamp.toLocalDateTime();
        }
        if (target == Instant.class && value instanceof Timestamp timestamp) {
            return (T) timestamp.toInstant();
        }
        if (target == LocalTime.class && value instanceof java.sql.Time time) {
            return (T) time.toLocalTime();
        }

        throw new SqlException("Cannot coerce " + value.getClass().getName() + " to " + target.getName());
    }

    @SuppressWarnings("unchecked")
    private static <T> T defaultValue(Class<T> target) {
        if (!target.isPrimitive()) {
            return null;
        }
        if (target == boolean.class) return (T) Boolean.FALSE;
        if (target == byte.class) return (T) Byte.valueOf((byte) 0);
        if (target == short.class) return (T) Short.valueOf((short) 0);
        if (target == int.class) return (T) Integer.valueOf(0);
        if (target == long.class) return (T) Long.valueOf(0L);
        if (target == float.class) return (T) Float.valueOf(0f);
        if (target == double.class) return (T) Double.valueOf(0d);
        if (target == char.class) return (T) Character.valueOf('\0');
        return null;
    }

    static int findColumn(ResultSetMetaData meta, String propertyName) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String label = meta.getColumnLabel(i);
            if (label == null) {
                label = meta.getColumnName(i);
            }
            if (propertyName.equalsIgnoreCase(label)) {
                return i;
            }
        }
        String snake = camelToSnake(propertyName);
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String label = meta.getColumnLabel(i);
            if (label == null) {
                label = meta.getColumnName(i);
            }
            if (snake.equalsIgnoreCase(label)) {
                return i;
            }
        }
        return -1;
    }

    private static String camelToSnake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final class ColumnCache {
        private final String[] names;
        private volatile Signature signature;
        private volatile int[] columns;

        private ColumnCache(List<BeanProperty> properties) {
            this.names = properties.stream().map(BeanProperty::name).toArray(String[]::new);
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
            List<String> labels = new java.util.ArrayList<>(columnCount);
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
