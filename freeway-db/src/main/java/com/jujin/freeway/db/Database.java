package com.jujin.freeway.db;

import java.util.function.Consumer;

public interface Database extends AutoCloseable {
    Query sql(String sql, Object... params);

    BatchQuery batch(String sql);

    void transaction(Consumer<Transaction> work);

    /**
     * 在指定隔离级别下执行事务。
     */
    void transaction(Consumer<Transaction> work, IsolationLevel isolation);

    Transaction beginTransaction();

    /**
     * 以指定隔离级别开启事务。
     */
    Transaction beginTransaction(IsolationLevel isolation);

    boolean ping();

    DatabaseStats stats();

    @Override
    void close();
}
