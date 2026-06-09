package com.jujin.freeway.db;

public interface Transaction extends AutoCloseable {

    Query query(String sql, Object... params);

    default Query query(SQL sql) {
        return query(sql.sql(), sql.args());
    }

    ExecuteResult execute(String sql, Object... params);

    default ExecuteResult execute(SQL sql) {
        return execute(sql.sql(), sql.args());
    }

    BatchQuery batch(String sql);

    Transaction isolation(IsolationLevel level);

    void commit();

    void rollback();

    @Override
    void close();
}
