package com.jujin.freeway.db.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies the database table name for an entity class.
 * Defaults to converting the class name from camelCase to snake_case.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Table {

    /** Table name. Empty string means use the default naming strategy. */
    String value() default "";
}
