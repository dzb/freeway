package com.jujin.freeway.ioc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要容器注入的字段或构造参数。
 * <p>
 * 与 {@link Named @Named} 功能等价，二者可互换使用。
 * 当同时标注 {@code @Inject("foo")} 和 {@code @Named("bar")} 时值必须一致，否则抛出异常。
 * <p>
 * 示例：
 * <pre>{@code
 *   @Inject("paypal") PaymentGateway gateway;
 *   // 等价于
 *   @Named("paypal") PaymentGateway gateway;
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PARAMETER})
public @interface Inject {
    String value() default "";
}
