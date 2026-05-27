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

    int execute();
}
