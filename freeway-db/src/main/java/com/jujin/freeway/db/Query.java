package com.jujin.freeway.db;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public interface Query {

    Query param(String name, Object value);

    <T> List<T> list(Class<T> targetType);

    <T> Optional<T> one(Class<T> targetType);

    /**
     * Execute the query and return a lazy stream of results.
     * The underlying database connection is held open until the stream is closed.
     * Always use {@code stream()} inside a try-with-resources block or ensure
     * the stream is closed after consumption.
     */
    <T> Stream<T> stream(Class<T> targetType);

    /**
     * 执行 INSERT / UPDATE / DELETE。
     * <p>
     * 返回 {@link ExecuteResult}，同时携带影响行数和自增键信息：
     * <pre>{@code
     * // 插入并获取自增 ID
     * long id = db.sql("INSERT INTO users (name) VALUES (?)", name)
     *              .execute().id();
     *
     * // 只关心影响行数
     * int rows = db.sql("UPDATE users SET status = ? WHERE id = ?", 1, id)
     *               .execute().rows();
     * }</pre>
     */
    ExecuteResult execute();
}
