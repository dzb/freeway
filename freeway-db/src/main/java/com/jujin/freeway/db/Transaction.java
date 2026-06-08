package com.jujin.freeway.db;

public interface Transaction extends AutoCloseable {
    Query sql(String sql, Object... params);

    /**
     * 使用 {@link SQL} 构建器在事务中执行 SQL。
     */
    default Query sql(SQL sql) {
        return sql(sql.sql(), sql.args());
    }

    BatchQuery batch(String sql);

    /**
     * 设置当前事务的隔离级别，需在首次执行 SQL 前调用。
     */
    Transaction isolation(IsolationLevel level);

    void commit();

    void rollback();

    @Override
    void close();
}
