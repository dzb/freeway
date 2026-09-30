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
 * <p>
 * <b>Not a method parameter.</b> {@code PARAMETER} is in the target because
 * the constructor parameters above need it, but the container resolves a
 * type's constructor parameters and its fields and nothing else — there is no
 * bytecode weaving to intercept a call to a concrete class, and an advisor
 * cannot substitute arguments either. An annotation on a method parameter
 * therefore compiles and does nothing, so the container refuses to inject such
 * a class: at the first realization of the declaring type it scans that type,
 * its superclasses and every interface it implements, and fails naming the
 * method, the parameter and the way out.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PARAMETER})
public @interface Inject {
    String value() default "";
}
