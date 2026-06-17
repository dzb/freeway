package com.jujin.freeway.db.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides column name and type constraints.
 * When absent, the column name is derived from the property name (camelCase → snake_case)
 * and the SQL type is auto-detected by {@code SqlTypeMapping}.
 *
 * <h3>Precision control</h3>
 * <ul>
 *   <li>String — {@link #length()} sets VARCHAR(n); also derived from {@code @Size(max=n)}</li>
 *   <li>DECIMAL — {@link #precision()} + {@link #scale()} sets DECIMAL(p,s)</li>
 *   <li>{@link #type()} directly overrides the SQL type (e.g. {@code "TEXT"})</li>
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface Column {

    /** Column name. Empty string means use the default naming strategy. */
    String value() default "";

    /** VARCHAR / CHAR length, e.g. {@code length=100} → {@code VARCHAR(100)}. 0 uses the default or {@code @Size} derived value. */
    int length() default 0;

    /** DECIMAL precision (total digits), e.g. {@code precision=10, scale=2} → {@code DECIMAL(10,2)}. 0 uses the default. */
    int precision() default 0;

    /** DECIMAL scale (fractional digits). Only applied when precision &gt; 0; defaults to 2. */
    int scale() default 0;

    /** Whether the column is nullable. Defaults to true for boxed types, false for primitives or when {@code @NotNull} is present. */
    boolean nullable() default true;

    /** Explicitly specifies the SQL type (e.g. {@code "TEXT"}, {@code "JSONB"}). Empty string means auto-detect. */
    String type() default "";
}
