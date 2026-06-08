package com.jujin.freeway.db;

import java.util.function.Consumer;

public interface Database extends AutoCloseable {
    Query sql(String sql, Object... params);

    /**
     * 使用 {@link SQL} 构建器执行 SQL。
     * <pre>{@code
     * var q = SQL.insert("users").set("name", name);
     * long id = db.sql(q).execute().id();
     * }</pre>
     */
    default Query sql(SQL sql) {
        return sql(sql.sql(), sql.args());
    }

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
