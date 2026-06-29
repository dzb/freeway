package com.jujin.freeway.ioc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field or constructor parameter for container injection.
 * <p>
 * A value attribute qualifies the injection by binding id:
 * <pre>{@code
 *   @Inject("paypal") PaymentGateway gateway;  // named binding
 *   @Inject           Logger log;              // unqualified
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PARAMETER})
public @interface Inject {
    String value() default "";
}
