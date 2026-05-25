package com.jujin.freeway2.db;

import java.sql.ResultSet;
import java.sql.SQLException;

@FunctionalInterface
public interface RowMapper<T> {
    T map(ResultSet rs, int rowNum) throws SQLException;
}
