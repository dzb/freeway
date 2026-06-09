package com.jujin.freeway.db.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记字段需要创建数据库索引。
 * <p>
 * 多个字段使用相同的 {@link #name()} 将合并为复合索引，
 * 列顺序按字段在类中的声明顺序。
 *
 * <h3>示例</h3>
 * <pre>{@code
 * // 单列索引（自动命名 idx_users_email）
 * @Index String email;
 *
 * // 命名唯一索引
 * @Index(name = "uq_username", unique = true) String username;
 *
 * // 复合索引
 * @Index(name = "idx_order_lookup") String userId;
 * @Index(name = "idx_order_lookup") LocalDateTime createdAt;
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface Index {

    /**
     * 索引名。空字符串表示自动生成 {@code idx_{table}_{column}}。
     * 多个字段使用相同名称时合并为复合索引。
     */
    String name() default "";

    /** 是否为唯一索引。 */
    boolean unique() default false;
}
