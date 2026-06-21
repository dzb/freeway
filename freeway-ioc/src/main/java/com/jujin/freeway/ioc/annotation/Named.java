package com.jujin.freeway.ioc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Qualifier annotation for named injection.
 * <p>
 * Functionally equivalent to {@link Inject @Inject("id")}; the two can be
 * used interchangeably. When both are present on the same target their
 * values must match.
 * <p>
 * Example:
 * <pre>{@code
 *   @Named("paypal") PaymentGateway gateway;
 *   // equivalent to
 *   @Inject("paypal") PaymentGateway gateway;
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PARAMETER})
public @interface Named {
    String value() default "";
}
