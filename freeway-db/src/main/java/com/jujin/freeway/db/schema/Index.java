package com.jujin.freeway.db.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field for index creation.
 * <p>
 * Multiple fields with the same {@link #name()} are merged into a composite index,
 * with column order matching field declaration order.
 *
 * <h3>Examples</h3>
 * <pre>{@code
 * // Single-column index (auto-named idx_users_email)
 * @Index String email;
 *
 * // Named unique index
 * @Index(name = "uq_username", unique = true) String username;
 *
 * // Composite index
 * @Index(name = "idx_order_lookup") String userId;
 * @Index(name = "idx_order_lookup") LocalDateTime createdAt;
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface Index {

    /**
     * Index name. Empty string auto-generates {@code idx_{table}_{column}}.
     * Multiple fields with the same name are merged into a composite index.
     */
    String name() default "";

    /** Whether this is a unique index. */
    boolean unique() default false;
}
