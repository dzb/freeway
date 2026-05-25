package com.jujin.freeway2.db;

import java.util.List;
import java.util.Optional;

public interface Query {
    Query param(String name, Object value);

    <T> List<T> list(Class<T> targetType);

    <T> Optional<T> one(Class<T> targetType);

    int execute();
}
