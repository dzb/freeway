package com.jujin.freeway2.db;

public interface Transaction extends AutoCloseable {
    Query sql(String sql, Object... params);

    BatchQuery batch(String sql);

    void commit();

    void rollback();

    @Override
    void close();
}
