package com.jujin.freeway.db.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 覆盖列名和类型约束。
 * 未标注时默认从属性名推导列名（camelCase → snake_case），
 * 类型由 {@code SqlTypeMapping} 根据 Java 类型自动推导。
 *
 * <h3>精度控制</h3>
 * <ul>
 *   <li>字符串 — {@link #length()} 控制 VARCHAR(n)，也可用 {@code @Size(max=n)} 推导</li>
 *   <li>DECIMAL — {@link #precision()} + {@link #scale()} 控制 DECIMAL(p,s)</li>
 *   <li>{@link #type()} 直接覆写 SQL 类型（如 {@code "TEXT"}）</li>
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface Column {

    /** 列名。空字符串表示使用默认命名策略。 */
    String value() default "";

    /** VARCHAR / CHAR 的长度，如 {@code length=100} → {@code VARCHAR(100)}。0 表示使用默认值或 {@code @Size} 推导。 */
    int length() default 0;

    /** DECIMAL 精度（总位数），如 {@code precision=10, scale=2} → {@code DECIMAL(10,2)}。0 表示使用默认值。 */
    int precision() default 0;

    /** DECIMAL 小数位数。仅当 precision &gt; 0 时生效，默认 2。 */
    int scale() default 0;

    /** 是否可为空。默认 true（包装类型）/ false（原始类型或标注 @NotNull 时自动 NOT NULL）。 */
    boolean nullable() default true;

    /** 显式指定 SQL 类型（如 {@code "TEXT"}、{@code "JSONB"}）。空字符串表示自动推导。 */
    String type() default "";
}
