package com.jujin.freeway.db;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public interface Query {

    Query param(String name, Object value);

    <T> List<T> list(Class<T> targetType);

    <T> Optional<T> one(Class<T> targetType);

    <T> Stream<T> stream(Class<T> targetType);

    /** 执行 INSERT / UPDATE / DELETE 并返回影响行数。支持命名参数和集合展开。 */
    ExecuteResult execute();
}
