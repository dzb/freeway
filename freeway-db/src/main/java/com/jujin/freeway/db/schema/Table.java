package com.jujin.freeway.db.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 指定实体类对应的数据库表名。
 * 未标注时默认将类名从 camelCase 转为 snake_case。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Table {

    /** 表名。空字符串表示使用默认命名策略。 */
    String value() default "";
}
