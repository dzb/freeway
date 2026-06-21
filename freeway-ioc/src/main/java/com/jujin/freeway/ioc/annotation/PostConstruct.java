package com.jujin.freeway.ioc.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks a no-arg void method to be invoked by the container after dependency
 * injection is complete.
 * <p>
 * Rules:
 * <ul>
 *   <li>May only be placed on a method</li>
 *   <li>The method must not have parameters</li>
 *   <li>The return type must be {@code void}</li>
 *   <li>The method must not be {@code static}</li>
 *   <li>At most one {@code @PostConstruct} per class (including inherited methods)</li>
 *   <li>A child method overrides a parent's {@code @PostConstruct}</li>
 * </ul>
 */
@Documented
@Target(METHOD)
@Retention(RUNTIME)
public @interface PostConstruct {
}
