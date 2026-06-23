package com.jujin.freeway.ioc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field or constructor parameter for container injection.
 * <p>
 * Functionally equivalent to {@link Named @Named}; the two can be used
 * interchangeably. When both {@code @Inject("foo")} and
 * {@code @Named("bar")} are present on the same target their values must
 * match, otherwise an exception is thrown.
 * <p>
 * Examples:
 * <pre>{@code
 *   @Inject("paypal") PaymentGateway gateway;
 *   // equivalent to
 *   @Named("paypal") PaymentGateway gateway;
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PARAMETER})
public @interface Inject {
    String value() default "";
}
