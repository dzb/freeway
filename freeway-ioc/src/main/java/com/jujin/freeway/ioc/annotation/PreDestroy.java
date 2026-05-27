package com.jujin.freeway.ioc.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 标记一个无参 void 方法，在容器关闭时由容器自动调用。
 * <p>
 * 适用规则：
 * <ul>
 *   <li>只能标注在方法上</li>
 *   <li>方法不能有参数</li>
 *   <li>方法返回类型必须为 void</li>
 *   <li>方法不能是 static</li>
 *   <li>{@code @PreDestroy} 在 {@link AutoCloseable#close()} 之前执行</li>
 *   <li>一个类中最多只能有一个 {@code @PreDestroy} 方法（含继承链）</li>
 * </ul>
 */
@Documented
@Target(METHOD)
@Retention(RUNTIME)
public @interface PreDestroy {
}
