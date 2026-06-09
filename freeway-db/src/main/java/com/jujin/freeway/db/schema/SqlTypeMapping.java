package com.jujin.freeway.db.schema;

import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import com.jujin.freeway.commons.validation.NotNull;
import com.jujin.freeway.commons.validation.Size;
import com.jujin.freeway.commons.validation.NotBlank;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Java 类型到 SQL 类型的映射表。
 * 同时整合注解约束（{@code @NotNull}, {@code @Size} 等）推导列定义。
 */
final class SqlTypeMapping {

    private SqlTypeMapping() {
    }

    /**
     * 从 BeanPlan 和属性推导完整的列定义列表。
     */
    static List<ColumnDef> columns(BeanPlan plan, Dialect dialect) {
        List<ColumnDef> defs = new ArrayList<>();
        for (BeanProperty property : plan.properties()) {
            if (hasAnnotation(property, Transient.class)) {
                continue;
            }
            defs.add(columnDef(property, dialect));
        }
        return List.copyOf(defs);
    }

    /**
     * 从 BeanPlan 提取索引定义。
     * 多个字段使用相同索引名时合并为复合索引，列顺序按字段声明顺序。
     */
    static List<IndexDef> indexes(BeanPlan plan, String tableName) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        Map<String, Boolean> uniqueFlags = new LinkedHashMap<>();

        for (BeanProperty property : plan.properties()) {
            if (hasAnnotation(property, Transient.class)) {
                continue;
            }
            Index idx = property.annotation(Index.class);
            if (idx == null) {
                continue;
            }
            String idxName = idx.name().isBlank()
                ? "idx_" + tableName + "_" + camelToSnake(property.name())
                : idx.name().trim();
            groups.computeIfAbsent(idxName, k -> new ArrayList<>())
                .add(camelToSnake(property.name()));
            uniqueFlags.put(idxName, uniqueFlags.getOrDefault(idxName, false) || idx.unique());
        }

        List<IndexDef> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            String name = entry.getKey();
            List<String> cols = entry.getValue();
            boolean unique = uniqueFlags.getOrDefault(name, false);
            // 复合索引中任一字段标记 unique 则整体为 unique
            result.add(new IndexDef(name, List.copyOf(cols), unique));
        }
        return List.copyOf(result);
    }

    private static ColumnDef columnDef(BeanProperty property, Dialect dialect) {
        Column col = property.annotation(Column.class);
        String colName = columnName(property, col);
        Class<?> javaType = rawType(property.type());
        boolean isNullable = nullable(property, col);
        String sqlType = resolveSqlType(javaType, property, col, dialect);

        return new ColumnDef(
            colName,
            sqlType,
            isNullable,
            hasAnnotation(property, Id.class),
            hasAnnotation(property, Generated.class)
        );
    }

    /**
     * 列名：优先 @Column.value，其次字段名转 snake_case。
     */
    static String columnName(BeanProperty property, Column col) {
        if (col != null && !col.value().isBlank()) {
            return col.value().trim();
        }
        return camelToSnake(property.name());
    }

    /**
     * 表名：优先 @Table.value，其次类名转 snake_case。
     */
    static String tableName(Class<?> type) {
        Table table = type.getAnnotation(Table.class);
        if (table != null && !table.value().isBlank()) {
            return table.value().trim();
        }
        return camelToSnake(type.getSimpleName());
    }

    private static String resolveSqlType(Class<?> javaType, BeanProperty property, Column col, Dialect dialect) {
        // 显式覆盖
        if (col != null && !col.type().isBlank()) {
            return col.type().trim().toUpperCase();
        }

        String baseType = defaultSqlType(javaType, dialect);

        if (baseType.startsWith("VARCHAR") || baseType.startsWith("CHAR")) {
            return resolveStringType(javaType, property, col, baseType);
        }

        if (baseType.startsWith("DECIMAL") || baseType.startsWith("NUMERIC")) {
            return resolveDecimalType(col);
        }

        return baseType;
    }

    /**
     * 字符串类型精度：@Column.length > @Size.max > 默认 255。
     * 枚举默认 32。
     */
    private static String resolveStringType(Class<?> javaType, BeanProperty property, Column col, String baseType) {
        Size size = property.annotation(Size.class);
        int colLen = col != null ? col.length() : 0;

        int len;
        if (colLen > 0) {
            len = colLen;
        } else if (size != null && size.max() > 0 && size.max() < Integer.MAX_VALUE) {
            len = size.max();
        } else if (javaType.isEnum()) {
            len = 32;
        } else {
            len = 255;
        }
        return baseType.replaceFirst("\\(\\d+\\)", "") + "(" + len + ")";
    }

    /**
     * DECIMAL 精度：@Column.precision > @Column.length（fallback）> 默认 30。
     * 小数位：@Column.scale（仅当 precision &gt; 0），默认 2。
     */
    private static String resolveDecimalType(Column col) {
        if (col == null) {
            return "DECIMAL(30,2)";
        }
        int precision = col.precision() > 0 ? col.precision() : col.length();
        if (precision <= 0) {
            precision = 30;
        }
        int scale = col.scale();
        return "DECIMAL(" + precision + "," + scale + ")";
    }

    private static String defaultSqlType(Class<?> javaType, Dialect dialect) {
        if (javaType == String.class) {
            return "VARCHAR(255)";
        }
        if (javaType == Integer.class || javaType == int.class) {
            return "INTEGER";
        }
        if (javaType == Long.class || javaType == long.class) {
            return "BIGINT";
        }
        if (javaType == Double.class || javaType == double.class) {
            return "DOUBLE PRECISION";
        }
        if (javaType == Float.class || javaType == float.class) {
            return "REAL";
        }
        if (javaType == Short.class || javaType == short.class) {
            return "SMALLINT";
        }
        if (javaType == Byte.class || javaType == byte.class) {
            return "SMALLINT";
        }
        if (javaType == Boolean.class || javaType == boolean.class) {
            return "BOOLEAN";
        }
        if (javaType == BigDecimal.class) {
            return "DECIMAL(30,2)";
        }
        if (javaType == BigInteger.class) {
            return "DECIMAL(38,0)";
        }
        if (javaType == LocalDate.class) {
            return "DATE";
        }
        if (javaType == LocalDateTime.class) {
            return "TIMESTAMP";
        }
        if (javaType == LocalTime.class) {
            return "TIME";
        }
        if (javaType == Instant.class) {
            return "TIMESTAMP WITH TIME ZONE";
        }
        if (javaType == UUID.class) {
            return dialect.defaultUUIDType();
        }
        if (javaType == byte[].class) {
            return "BYTEA";
        }
        if (javaType.isEnum()) {
            return "VARCHAR(32)";
        }
        throw new IllegalArgumentException(
            "No default SQL type for " + javaType.getName()
                + ". Use @Column(type=\"...\") to specify."
        );
    }

    private static boolean nullable(BeanProperty property, Column col) {
        // @Column explicitly sets nullable
        if (col != null && !col.nullable()) {
            return false;
        }
        // @NotNull or @NotBlank forces NOT NULL
        if (hasAnnotation(property, NotNull.class) || hasAnnotation(property, NotBlank.class)) {
            return false;
        }
        // @Id forces NOT NULL
        if (hasAnnotation(property, Id.class)) {
            return false;
        }
        // Primitive types are NOT NULL by default
        Class<?> raw = rawType(property.type());
        if (raw.isPrimitive()) {
            return false;
        }
        return true;
    }

    static Class<?> rawType(Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof java.lang.reflect.ParameterizedType pt
            && pt.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        throw new IllegalArgumentException("Unsupported type: " + type.getTypeName());
    }

    private static boolean hasAnnotation(BeanProperty property, Class<? extends Annotation> annType) {
        return property.hasAnnotation(annType);
    }

    static String camelToSnake(String camel) {
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
}
