package com.jujin.freeway.db;

public interface Pool extends AutoCloseable {
    PooledConnection borrow();
    void release(PooledConnection conn);
    DatabaseStats stats();
    @Override void close();
}
