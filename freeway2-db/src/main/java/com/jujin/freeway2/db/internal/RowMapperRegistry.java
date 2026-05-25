package com.jujin.freeway2.db.internal;

import com.jujin.freeway2.db.RowMapper;
import com.jujin.freeway2.db.RowMapping;
import com.jujin.freeway2.db.SqlException;
import com.jujin.freeway2.commons.scalar.Coercer;
import com.jujin.freeway2.ioc.annotation.ExtensionPoint;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RowMapperRegistry {
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

    public RowMapperRegistry(
        Coercer coercer,
        @ExtensionPoint(RowMapping.class) Collection<RowMapping<?>> registrations
    ) {
        this.coercer = coercer;
        this.custom = customMap(registrations);
    }

    @SuppressWarnings("unchecked")
    public <T> RowMapper<T> forType(Class<T> type) {
        RowMapper<?> mapper = custom.get(type);
        if (mapper != null) {
            return (RowMapper<T>) mapper;
        }
        return (RowMapper<T>) cache.computeIfAbsent(type, this::create);
    }

    private static Map<Class<?>, RowMapper<?>> customMap(Collection<RowMapping<?>> registrations) {
        Map<Class<?>, RowMapper<?>> map = new LinkedHashMap<>();
        if (registrations == null) {
            return map;
        }
        for (RowMapping<?> registration : registrations) {
            RowMapper<?> previous = map.put(registration.type(), registration.mapper());
            if (previous != null) {
                throw new IllegalStateException(
                    "Duplicate row mapper registration for " + registration.type().getName()
                );
            }
        }
        return Map.copyOf(map);
    }

    private <T> RowMapper<T> create(Class<T> type) {
        if (isSimpleType(type)) {
            return createSimple(type);
        }
        if (type.isRecord()) {
            return createRecord(type);
        }
        return createBean(type);
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

    private <T> RowMapper<T> createRecord(Class<T> type) {
        RecordComponent[] components = type.getRecordComponents();
        MethodHandle constructor = canonicalConstructor(type, components);

        return (rs, rowNum) -> {
            ResultSetMetaData meta = rs.getMetaData();
            int[] columns = new int[components.length];
            for (int i = 0; i < components.length; i++) {
                columns[i] = findColumn(meta, components[i].getName());
            }
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                int column = columns[i];
                args[i] = column >= 1
                    ? coerce(rs.getObject(column), components[i].getType())
                    : defaultValue(components[i].getType());
            }
            try {
                Object value = constructor.invokeWithArguments(args);
                return type.cast(value);
            } catch (Throwable e) {
                throw new SqlException("Failed to construct " + type.getName(), e);
            }
        };
    }

    private <T> RowMapper<T> createBean(Class<T> type) {
        BeanPlan<T> plan = beanPlan(type);
        return (rs, rowNum) -> {
            ResultSetMetaData meta = rs.getMetaData();
            int[] columns = new int[plan.names.length];
            for (int i = 0; i < plan.names.length; i++) {
                columns[i] = findColumn(meta, plan.names[i]);
            }
            T instance;
            try {
                Object value = plan.constructor.invokeWithArguments();
                instance = type.cast(value);
            } catch (Throwable e) {
                throw new SqlException("Failed to construct " + type.getName(), e);
            }
            for (int i = 0; i < plan.names.length; i++) {
                int column = columns[i];
                if (column < 1) {
                    continue;
                }
                Object value = coerce(rs.getObject(column), plan.types[i]);
                try {
                    plan.setters[i].invokeWithArguments(instance, value);
                } catch (Throwable e) {
                    throw new SqlException(
                        "Failed to set " + plan.names[i] + " on " + type.getName(),
                        e
                    );
                }
            }
            return instance;
        };
    }

    private <T> BeanPlan<T> beanPlan(Class<T> type) {
        try {
            var beanInfo = Introspector.getBeanInfo(type, Object.class);
            List<PropertyDescriptor> descriptors = new ArrayList<>();
            for (PropertyDescriptor descriptor : beanInfo.getPropertyDescriptors()) {
                if (descriptor.getWriteMethod() != null) {
                    descriptors.add(descriptor);
                }
            }
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
            MethodHandle constructor = lookup.unreflectConstructor(type.getDeclaredConstructor());
            String[] names = new String[descriptors.size()];
            Class<?>[] types = new Class<?>[descriptors.size()];
            MethodHandle[] setters = new MethodHandle[descriptors.size()];
            for (int i = 0; i < descriptors.size(); i++) {
                PropertyDescriptor descriptor = descriptors.get(i);
                names[i] = descriptor.getName();
                types[i] = descriptor.getPropertyType();
                setters[i] = lookup.unreflect(descriptor.getWriteMethod());
            }
            return new BeanPlan<>(constructor, names, types, setters);
        } catch (Exception e) {
            throw new SqlException("Failed to introspect " + type.getName(), e);
        }
    }

    private <T> MethodHandle canonicalConstructor(Class<T> type, RecordComponent[] components) {
        try {
            Class<?>[] parameterTypes = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                parameterTypes[i] = components[i].getType();
            }
            Constructor<T> ctor = type.getDeclaredConstructor(parameterTypes);
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup()).unreflectConstructor(ctor);
        } catch (ReflectiveOperationException e) {
            throw new SqlException("Cannot find canonical constructor for " + type.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T coerce(Object value, Class<T> target) {
        if (value == null) {
            return defaultValue(target);
        }
        if (target.isInstance(value)) {
            return target.cast(value);
        }
        // Handle primitive target: Long → long, Integer → int etc.
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
        if (target == Long.class && value instanceof BigDecimal bd) {
            return (T) Long.valueOf(bd.longValue());
        }
        if (target == Integer.class && value instanceof BigDecimal bd) {
            return (T) Integer.valueOf(bd.intValue());
        }
        if (target == Double.class && value instanceof BigDecimal bd) {
            return (T) Double.valueOf(bd.doubleValue());
        }
        if (target == BigInteger.class && value instanceof BigDecimal bd) {
            return (T) bd.toBigInteger();
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

    private record BeanPlan<T>(
        MethodHandle constructor,
        String[] names,
        Class<?>[] types,
        MethodHandle[] setters
    ) {
    }
}
