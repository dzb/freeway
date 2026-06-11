package com.jujin.freeway.db;

import com.jujin.freeway.db.internal.PooledConnection;

public interface Pool extends AutoCloseable {
    PooledConnection borrow();
    void release(PooledConnection conn);
    DatabaseStats stats();
    @Override void close();
}
