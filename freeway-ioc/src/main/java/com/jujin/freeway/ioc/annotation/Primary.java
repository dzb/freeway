package com.jujin.freeway.ioc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation denoting the preferred implementation of a service
 * interface. When multiple services implement the same type, the one
 * marked {@code &#64;Primary} is selected by default.
 * <p>
 * This annotation is equivalent to calling {@code .primary()} on the
 * binding DSL. Internally, {@code .primary()} maps to this marker,
 * so both forms work and resolve through the same marker index.
 *
 * <pre>{@code
 *   // On an implementation class
 *   &#64;Primary
 *   public class FastCache implements Cache {}
 *
 *   // At the injection point
 *   &#64;Inject &#64;Primary Cache cache;
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Primary {
}
