package com.jujin.freeway.db.schema;

import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import com.jujin.freeway.commons.bean.ReflectUtils;
import com.jujin.freeway.db.util.Names;
import com.jujin.freeway.commons.validation.NotBlank;
import com.jujin.freeway.commons.validation.NotNull;
import com.jujin.freeway.commons.validation.Size;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.Locale;

/**
 * Java-to-SQL type mapping.
 * Also integrates annotation constraints ({@code @NotNull}, {@code @Size}, etc.)
 * to derive column definitions.
 */
public final class SqlTypeMapping {

    private SqlTypeMapping() {}

    private static final Set<Class<?>> BASIC_TYPES = Set.of(
        String.class,
        Integer.class, int.class,
        Long.class, long.class,
        Double.class, double.class,
        Float.class, float.class,
        Short.class, short.class,
        Byte.class, byte.class,
        Boolean.class, boolean.class,
        Character.class, char.class,
        BigDecimal.class,
        BigInteger.class,
        LocalDate.class,
        LocalDateTime.class,
        LocalTime.class,
        Instant.class,
        UUID.class,
        byte[].class
    );

    public static boolean isBasicType(Class<?> type) {
        return BASIC_TYPES.contains(type) || type.isEnum();
    }

    /** Derives a complete list of column definitions from a BeanPlan and its properties. */
    static List<ColumnDef> columns(BeanPlan plan, Dialect dialect) {
        List<ColumnDef> defs = new ArrayList<>();
        for (BeanProperty property : plan.properties()) {
            if (property.hasAnnotation(Transient.class)) {
                continue;
            }
            defs.add(columnDef(property, dialect));
        }
        return List.copyOf(defs);
    }

    /**
     * Extracts index definitions from a BeanPlan.
     * Fields sharing the same index name are merged into a composite index,
     * with column order matching field declaration order.
     */
    static List<IndexDef> indexes(BeanPlan plan, String tableName) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        Map<String, Boolean> uniqueFlags = new LinkedHashMap<>();

        for (BeanProperty property : plan.properties()) {
            if (property.hasAnnotation(Transient.class)) {
                continue;
            }
            Index idx = property.annotation(Index.class);
            if (idx == null) {
                continue;
            }
            String idxName = idx.name().isBlank()
                ? "idx_" + tableName + "_" + com.jujin.freeway.commons.util.Strings.camelToSnake(property.name())
                : idx.name().trim();
            groups
                .computeIfAbsent(idxName, k -> new ArrayList<>())
                .add(com.jujin.freeway.commons.util.Strings.camelToSnake(property.name()));
            uniqueFlags.put(
                idxName,
                uniqueFlags.getOrDefault(idxName, false) || idx.unique()
            );
        }

        List<IndexDef> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            String name = entry.getKey();
            List<String> cols = entry.getValue();
            boolean unique = uniqueFlags.getOrDefault(name, false);
            // Composite index: unique if any field is marked unique
            result.add(new IndexDef(name, List.copyOf(cols), unique));
        }
        return List.copyOf(result);
    }

    private static ColumnDef columnDef(BeanProperty property, Dialect dialect) {
        Column col = property.annotation(Column.class);
        String colName = columnName(property, col);
        Class<?> javaType = ReflectUtils.rawClass(property.type());
        boolean isNullable = nullable(property, col);
        boolean generated = property.hasAnnotation(Generated.class);
        String sqlType = resolveSqlType(javaType, property, col, dialect, generated);

        return new ColumnDef(
            colName,
            sqlType,
            isNullable,
            property.hasAnnotation(Id.class),
            generated
        );
    }

    /** Column name: @Column.value first, then property name → snake_case. */
    public static String columnName(BeanProperty property, Column col) {
        if (col != null && !col.value().isBlank()) {
            return col.value().trim();
        }
        return com.jujin.freeway.commons.util.Strings.camelToSnake(property.name());
    }

    /** Table name: @Table.value first, then class name → snake_case. */
    public static String tableName(Class<?> type) {
        Table table = type.getAnnotation(Table.class);
        if (table != null && !table.value().isBlank()) {
            return table.value().trim();
        }
        return com.jujin.freeway.commons.util.Strings.camelToSnake(type.getSimpleName());
    }

    private static String resolveSqlType(
        Class<?> javaType,
        BeanProperty property,
        Column col,
        Dialect dialect,
        boolean generated
    ) {
        // Explicit override
        if (col != null && !col.type().isBlank()) {
            String explicit = col.type().trim().toUpperCase(Locale.ROOT);
            return normalizeGeneratedSqlType(explicit, javaType, dialect, generated);
        }

        String baseType = defaultSqlType(javaType, dialect);

        if (baseType.startsWith("VARCHAR") || baseType.startsWith("CHAR")) {
            return normalizeGeneratedSqlType(
                resolveStringType(javaType, property, col, baseType),
                javaType,
                dialect,
                generated
            );
        }

        if (baseType.startsWith("DECIMAL") || baseType.startsWith("NUMERIC")) {
            return normalizeGeneratedSqlType(
                resolveDecimalType(col),
                javaType,
                dialect,
                generated
            );
        }

        return normalizeGeneratedSqlType(baseType, javaType, dialect, generated);
    }

    private static String normalizeGeneratedSqlType(
        String sqlType,
        Class<?> javaType,
        Dialect dialect,
        boolean generated
    ) {
        if (!generated || !(dialect instanceof SqliteDialect)) {
            return sqlType;
        }
        if (isIntegralType(javaType) || "INTEGER".equalsIgnoreCase(sqlType)) {
            return "INTEGER";
        }
        throw new IllegalArgumentException(
            "SQLite AUTOINCREMENT columns must use an integer type: " +
                javaType.getName()
        );
    }

    private static boolean isIntegralType(Class<?> type) {
        return type == Long.class ||
            type == long.class ||
            type == Integer.class ||
            type == int.class ||
            type == Short.class ||
            type == short.class ||
            type == Byte.class ||
            type == byte.class;
    }

    /**
     * String precision: @Column.length > @Size.max > default 255.
     * Enums default to 32.
     */
    private static String resolveStringType(
        Class<?> javaType,
        BeanProperty property,
        Column col,
        String baseType
    ) {
        Size size = property.annotation(Size.class);
        int colLen = col != null ? col.length() : 0;

        int len;
        if (colLen > 0) {
            len = colLen;
        } else if (
            size != null && size.max() > 0 && size.max() < Integer.MAX_VALUE
        ) {
            len = size.max();
        } else if (javaType.isEnum()) {
            len = 32;
        } else {
            len = 255;
        }
        return baseType.replaceFirst("\\(\\d+\\)", "") + "(" + len + ")";
    }

    /**
     * DECIMAL precision: @Column.precision > @Column.length (fallback) > default 30.
     * Scale: @Column.scale (only when precision &gt; 0), default 2.
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
            return dialect.defaultInstantType();
        }
        if (javaType == UUID.class) {
            return dialect.defaultUUIDType();
        }
        if (javaType == byte[].class) {
            return dialect.defaultBinaryType();
        }
        if (javaType.isEnum()) {
            return "VARCHAR(32)";
        }
        throw new IllegalArgumentException(
            "No default SQL type for " +
                javaType.getName() +
                ". Use @Column(type=\"...\") to specify."
        );
    }

    private static boolean nullable(BeanProperty property, Column col) {
        // @Column explicitly sets nullable
        if (col != null && !col.nullable()) {
            return false;
        }
        // @NotNull or @NotBlank forces NOT NULL
        if (
            property.hasAnnotation(NotNull.class) ||
            property.hasAnnotation(NotBlank.class)
        ) {
            return false;
        }
        // @Id forces NOT NULL
        if (property.hasAnnotation(Id.class)) {
            return false;
        }
        // Primitive types are NOT NULL by default
        Class<?> raw = ReflectUtils.rawClass(property.type());
        if (raw.isPrimitive()) {
            return false;
        }
        return true;
    }

}
