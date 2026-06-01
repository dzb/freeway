package com.jujin.freeway.ioc.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an extension consumption site.
 *
 * <p>When placed on a type, the value acts as the default extension point for
 * collection members and, for map members, as the map value type. Map key type
 * is resolved from the member's generic signature, so the full map extension
 * point is identified by {@code Map<K, V>}.</p>
 *
 * <p>Member-level usage on fields or parameters overrides the class default.</p>
 */
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Extension {
    Class<?> value();
}
