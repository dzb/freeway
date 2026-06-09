package com.jujin.freeway.db;

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

    void transaction(Transactional work);

    void transaction(IsolationLevel isolation, Transactional work);

    boolean ping();

    DatabaseStats stats();

    @Override
    void close();
}
