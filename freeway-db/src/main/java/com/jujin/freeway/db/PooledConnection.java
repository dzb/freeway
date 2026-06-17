package com.jujin.freeway.db;

import java.sql.Connection;

public interface PooledConnection {
    Connection connection();
}
