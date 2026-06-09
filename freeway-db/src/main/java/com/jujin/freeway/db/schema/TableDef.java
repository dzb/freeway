package com.jujin.freeway.db.schema;

import java.util.List;

/**
 * 表的完整定义（内部使用）。
 */
record TableDef(String name, List<ColumnDef> columns, List<IndexDef> indexes) {

    TableDef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("table name must not be blank");
        }
        if (columns == null) {
            throw new IllegalArgumentException("columns must not be null");
        }
        if (indexes == null) {
            throw new IllegalArgumentException("indexes must not be null");
        }
    }

    TableDef(String name, List<ColumnDef> columns) {
        this(name, columns, List.of());
    }

    /** 返回主键列名列表。 */
    List<String> primaryKeys() {
        return columns.stream()
            .filter(ColumnDef::primaryKey)
            .map(ColumnDef::name)
            .toList();
    }
}
