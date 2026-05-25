package com.jujin.freeway2.db;

import java.util.function.Consumer;

public interface Database extends AutoCloseable {
    Query sql(String sql, Object... params);

    BatchQuery batch(String sql);

    void transaction(Consumer<Transaction> work);

    Transaction beginTransaction();

    boolean ping();

    DatabaseStats stats();

    @Override
    void close();
}
