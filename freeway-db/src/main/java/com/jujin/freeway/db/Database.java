package com.jujin.freeway.db;

import java.util.function.Consumer;

public interface Database extends AutoCloseable {

    Query query(String sql, Object... params);

    default Query query(SQL sql) {
        return query(sql.sql(), sql.args());
    }

    ExecuteResult execute(String sql, Object... params);

    default ExecuteResult execute(SQL sql) {
        return execute(sql.sql(), sql.args());
    }

    BatchQuery batch(String sql);

    void transaction(Consumer<Transaction> work);

    void transaction(Consumer<Transaction> work, IsolationLevel isolation);

    Transaction beginTransaction();

    Transaction beginTransaction(IsolationLevel isolation);

    boolean ping();

    DatabaseStats stats();

    @Override
    void close();
}
