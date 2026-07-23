# 新增数据库方言指南

`Dialect` 接口定义了 SQL 方言的完整契约。本文档描述新增一个数据库方言所需的全部步骤。

## 目录

1. [概述](#概述)
2. [步骤 1：创建方言类](#步骤-1创建方言类)
3. [步骤 2：必须实现的抽象方法（5 个）](#步骤-2必须实现的抽象方法5-个)
4. [步骤 3：必须覆写的默认方法（6 个）](#步骤-3必须覆写的默认方法6-个)
5. [步骤 4：按需覆写的默认方法](#步骤-4按需覆写的默认方法)
6. [步骤 5：注册到 DbModule](#步骤-5注册到-dbmodule)
7. [步骤 6：特殊处理](#步骤-6特殊处理)
8. [步骤 7：测试](#步骤-7测试)
9. [模板代码](#模板代码)
10. [与现有方言的差异参考](#与现有方言的差异参考)

---

## 概述

`Dialect` 接口位于 `com.jujin.freeway.db.schema`，有 5 个抽象方法 + 13 个默认方法。新增方言需要：

| 类别 | 数量 | 说明 |
|------|------|------|
| 抽象方法（必须实现） | 5 | 编译器强制，不实现无法编译 |
| 默认方法（必须覆写） | ~6 | 默认值是 PG 偏好，不覆写会出 bug |
| 默认方法（按需覆写） | 2-4 | 视数据库特性而定 |
| 注册点 | 2 | `DbModule.bind()` + `detectDialect()` |

新增一个方言预计工作量约 **100-150 行代码 + 测试**。

---

## 步骤 1：创建方言类

在 `com.jujin.freeway.db.schema` 包下创建类，实现 `Dialect` 接口：

```java
package com.jujin.freeway.db.schema;

public final class OracleDialect implements Dialect {
    // 以下逐步填充
}
```

所有 `Dialect` 实现必须是**无状态、线程安全**的（只有常量和纯函数，无可变字段）。

---

## 步骤 2：必须实现的抽象方法（5 个）

### 2.1 `dialectId()`

返回方言标识符，用于 IoC 注册和错误消息。

```java
@Override
public String dialectId() {
    return "oracle";  // 小写，与 DbModule 中的 ID 保持一致
}
```

### 2.2 `generatedClause()`

返回自增列的 DDL 子句。

```java
@Override
public String generatedClause() {
    return "GENERATED ALWAYS AS IDENTITY";  // PG/H2 风格
    // 或 "AUTO_INCREMENT"                   // MySQL 风格
    // 或 "AUTOINCREMENT"                    // SQLite 风格
}
```

### 2.3 `defaultUUIDType()`

返回 `UUID` 对应的 SQL 类型。

```java
@Override
public String defaultUUIDType() {
    return "UUID";         // PG/H2
    // 或 "VARCHAR(36)"    // MySQL
    // 或 "TEXT"           // SQLite
}
```

### 2.4 `existingTables(Database db)`

查询数据库中已存在的表名。返回小写集合。

```java
@Override
public Set<String> existingTables(Database db) {
    return querySet(db,
        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ?",
        effectiveSchema());
}
```

- 使用 `querySet()` 辅助方法（自动小写、异常处理）
- 按需过滤 schema（调用 `effectiveSchema()`）
- 如果 INFORMATION_SCHEMA 大小写不确定，使用 `UPPER()` 比较

### 2.5 `existingColumns(Database db, String tableName)`

查询指定表中已存在的列名。返回小写集合。

```java
@Override
public Set<String> existingColumns(Database db, String tableName) {
    return querySet(db,
        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? AND TABLE_SCHEMA = ?",
        tableName.toUpperCase(Locale.ROOT), effectiveSchema());
}
```

---

## 步骤 3：必须覆写的默认方法（6 个）

以下默认方法的返回值是 PG 偏好，在其他数据库上会产生错误 SQL。**必须覆写**。

### 3.1 `reservedWords()`

返回该数据库的 SQL 保留字集合（小写）。不覆写则所有标识符都不会被引号包裹。

```java
private static final Set<String> RESERVED = Set.of(
    "user", "order", "group", "table", "select", "from", "where",
    // ... 参考现有方言的集合，加上目标数据库特有保留字
);

@Override
public Set<String> reservedWords() {
    return RESERVED;
}
```

### 3.2 `quoteChar()`

返回标识符引号字符。

```java
@Override
public char quoteChar() {
    return '"';   // PG/SQLite/H2
    // 或 '`'     // MySQL
}
```

不需要覆写 `quoteName()` 或 `needsQuoting()`——它们的默认实现会调用 `quoteChar()` 和 `reservedWords()`。

### 3.3 `supportsReturning()`

```java
@Override
public boolean supportsReturning() {
    return true;   // PG/H2/SQLite 3.35+
    // 或 false   // MySQL
}
```

### 3.4 `supportsOnConflict()`

```java
@Override
public boolean supportsOnConflict() {
    return true;   // PG/SQLite/H2
    // 或 false   // MySQL（MySQL 用 ON DUPLICATE KEY）
}
```

### 3.5 `defaultBinaryType()`

```java
@Override
public String defaultBinaryType() {
    return "BYTEA";       // PG
    // 或 "LONGBLOB"      // MySQL
    // 或 "BLOB"          // SQLite
    // 或 "BINARY VARYING" // H2
}
```

### 3.6 `defaultInstantType()`

```java
@Override
public String defaultInstantType() {
    return "TIMESTAMP WITH TIME ZONE";  // PG/H2（默认，无需覆写）
    // 或 "DATETIME(6)"                // MySQL
    // 或 "TEXT"                       // SQLite
}
```

---

## 步骤 4：按需覆写的默认方法

### 4.1 `upsertClause(List<String> conflict, List<String> update)`

**如果不支持 `ON CONFLICT`**（如 MySQL），必须覆写：

```java
@Override
public String upsertClause(List<String> conflictColumns, List<String> updateColumns) {
    List<String> updates = new ArrayList<>(updateColumns.size());
    for (String col : updateColumns) {
        String q = quoteName(col);
        updates.add(q + " = VALUES(" + q + ")");  // MySQL 语法
    }
    return " ON DUPLICATE KEY UPDATE " + String.join(", ", updates);
}
```

默认实现使用 `ON CONFLICT (target) DO UPDATE SET col = EXCLUDED.col`（PG 语法）。

**注意**：
- 传入的列名是**原始名**（未引用），方法内部需调用 `quoteName()`。
- 返回值**必须以空格开头**。

### 4.2 `truncateTable(String tableName)`

```java
@Override
public String truncateTable(String tableName) {
    return "TRUNCATE TABLE " + quoteName(tableName) + " RESTART IDENTITY";  // PG/H2
    // 或 "TRUNCATE TABLE " + quoteName(tableName)                           // MySQL（默认）
    // 或 "DELETE FROM " + quoteName(tableName)                              // SQLite
}
```

### 4.3 `supportsIndexIfNotExists()`

```java
@Override
public boolean supportsIndexIfNotExists() {
    return true;   // PG/SQLite/H2
    // 或 false   // MySQL
}
```

如果返回 `false`，必须同时覆写 `existingIndexes()` 来查询已有索引。

### 4.4 `existingIndexes(Database db, String tableName)`

**仅当 `supportsIndexIfNotExists()` 返回 `false` 时需要**。

```java
@Override
public Set<String> existingIndexes(Database db, String tableName) {
    return querySet(db,
        "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_NAME = ?",
        tableName);
}
```

### 4.5 `forUpdateClause()`

```java
@Override
public String forUpdateClause() {
    return "FOR UPDATE";   // PG/MySQL/H2（默认）
    // 或 ""               // SQLite（不支持）
}
```

返回值**没有前导空格**。

### 4.6 `generatedTypeOverride(String sqlType, Class<?> javaType)`

**仅当自增列需要特定 SQL 类型时覆写**（如 SQLite 要求 `INTEGER`）：

```java
@Override
public String generatedTypeOverride(String sqlType, Class<?> javaType) {
    if (javaType == Long.class || javaType == long.class
        || javaType == Integer.class || javaType == int.class) {
        return "INTEGER";
    }
    throw new IllegalArgumentException(
        "AUTOINCREMENT columns must use an integer type: " + javaType.getName());
}
```

### 4.7 `createTable(TableDef table)`

**仅当 CREATE TABLE 语法与标准不同时覆写**（如 SQLite 的 AUTOINCREMENT 必须内联在列定义上）。

```java
@Override
public String createTable(TableDef table) {
    // 自定义实现
}
```

### 4.8 `addColumn(String tableName, ColumnDef column)`

**仅当 ALTER TABLE ADD COLUMN 有限制时覆写**（如 SQLite 不能加 NOT NULL 或 AUTOINCREMENT）。

```java
@Override
public String addColumn(String tableName, ColumnDef column) {
    String def = column.toAlterSqlWithoutNotNull(this);
    return "ALTER TABLE " + quoteName(tableName) + " " + def;
}
```

可用的辅助方法：
- `column.toAlterSql(this)` — 完整列定义
- `column.toAlterSqlWithoutNotNull(this)` — 去掉 NOT NULL
- `column.toAlterSqlWithoutGeneratedAndNotNull(this)` — 去掉 NOT NULL 和生成子句

### 4.9 `effectiveSchema()`

```java
@Override
public String effectiveSchema() {
    return "public";   // PG
    // 或 "dbo"        // SQL Server
    // 或 "PUBLIC"      // H2
}
```

---

## 步骤 5：注册到 DbModule

修改 `DbModule.java` 两个位置：

### 5.1 注册绑定

```java
// DbModule.bind() 中
binder.bind(Dialect.class).to(OracleDialect.class).id("oracle");
```

`id()` 的参数**必须与 `dialectId()` 返回值一致**。

### 5.2 添加 URL 检测

```java
// DbModule.detectDialect() 中
if (url.contains(":oracle:")) return "oracle";
```

使用 `url.toUpperCase().contains(...)` 进行大小写不敏感匹配（与现有 H2 MODE 检测保持一致）。

---

## 步骤 6：特殊处理

### 6.1 ColumnDef 辅助方法

`ColumnDef` 是 package-private 记录，提供以下方法给方言使用：

| 方法 | 用途 |
|------|------|
| `toSql(Dialect)` | 完整的 `"col" TYPE [NOT NULL] [GENERATED]` |
| `toAlterSql(Dialect)` | `ADD COLUMN ...` 前缀 + 完整定义 |
| `toAlterSqlWithoutNotNull(Dialect)` | 同上，但去掉 NOT NULL |
| `toAlterSqlWithoutGeneratedAndNotNull(Dialect)` | 去掉 NOT NULL 和生成子句 |

### 6.2 querySet 辅助方法

`Dialect.querySet(db, sql, params...)` 执行 introspection 查询并将结果小写。异常时记录 WARN 日志并返回空集。

---

## 步骤 7：测试

### 7.1 单元测试

在 `SchemaGeneratorTest.java` 中添加：

```java
@Test
void oracleUpsertUsesMerge() {
    String clause = new OracleDialect().upsertClause(List.of("id"), List.of("name"));
    assertEquals(" MERGE ...", clause);
}

@Test
void oracleTruncateIsSimple() {
    assertEquals("TRUNCATE TABLE users", new OracleDialect().truncateTable("users"));
}

@Test
void oracleDialectId() {
    assertEquals("oracle", new OracleDialect().dialectId());
}
```

### 7.2 推荐覆盖的测试项

- `supportsReturning()`
- `supportsOnConflict()`
- `supportsIndexIfNotExists()`
- `dialectId()`
- `upsertClause()` — 2-arg 签名
- `truncateTable()`
- `forUpdateClause()`
- `defaultBinaryType()`
- `generatedTypeOverride()`（如果覆写了）
- `quoteName()` 对保留字的处理
- DDL 生成（通过 `new SchemaGenerator(dialect).generate(Entity.class)`）

---

## 模板代码

```java
package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;
import java.util.*;

public final class XxxDialect implements Dialect {

    private static final Set<String> RESERVED = Set.of(
        "user", "order", "group", "table", "select", "from", "where"
        // TODO: 添加目标数据库特有保留字
    );

    // ====================== 抽象方法（5 个） ======================

    @Override public String dialectId()               { return "xxx"; }
    @Override public String generatedClause()         { return "GENERATED ALWAYS AS IDENTITY"; }
    @Override public String defaultUUIDType()         { return "UUID"; }

    @Override public Set<String> existingTables(Database db) {
        return querySet(db,
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_SCHEMA) = UPPER(?)",
            effectiveSchema());
    }

    @Override public Set<String> existingColumns(Database db, String tableName) {
        return querySet(db,
            "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? AND UPPER(TABLE_SCHEMA) = UPPER(?)",
            tableName.toUpperCase(Locale.ROOT), effectiveSchema());
    }

    // ====================== 默认方法覆写（至少 6 个） ======================

    @Override public Set<String> reservedWords()      { return RESERVED; }
    @Override public char quoteChar()                 { return '"'; }
    @Override public boolean supportsReturning()      { return true; }
    @Override public boolean supportsOnConflict()     { return true; }
    @Override public String defaultBinaryType()       { return "BYTEA"; }
    @Override public String defaultInstantType()      { return "TIMESTAMP WITH TIME ZONE"; }

    // ====================== 按需覆写 ======================

    // truncate: 如果需要 RESTART IDENTITY 或 DELETE FROM
    // upsertClause: 如果 ON CONFLICT 语法不同
    // supportsIndexIfNotExists + existingIndexes: 如果不支持 IF NOT EXISTS
    // forUpdateClause: 如果不支持 FOR UPDATE
    // generatedTypeOverride: 如果自增列需要特定类型
    // createTable: 如果 CREATE TABLE 语法不同
    // addColumn: 如果 ALTER TABLE ADD COLUMN 有限制
    // effectiveSchema: 如果默认 schema 名不是 "public"
}
```

---

## 与现有方言的差异参考

| 能力 | PG | MySQL | SQLite | H2 |
|------|----|-------|--------|----|
| `quoteChar` | `"` | `` ` `` | `"` | `"` |
| `generatedClause` | `GENERATED ALWAYS AS IDENTITY` | `AUTO_INCREMENT` | `AUTOINCREMENT` | `GENERATED ALWAYS AS IDENTITY` |
| `defaultUUIDType` | `UUID` | `VARCHAR(36)` | `TEXT` | `UUID` |
| `defaultInstantType` | `TIMESTAMP WITH TIME ZONE` | `DATETIME(6)` | `TEXT` | `TIMESTAMP WITH TIME ZONE`（默认） |
| `defaultBinaryType` | `BYTEA` | `LONGBLOB` | `BLOB` | `BINARY VARYING` |
| `supportsReturning` | ✓ | ✗ | ✓ | ✓ |
| `supportsOnConflict` | ✓ | ✗ | ✓ | ✓ |
| `supportsIndexIfNotExists` | ✓ | ✗ | ✓ | ✓ |
| `forUpdateClause` | `FOR UPDATE` | `FOR UPDATE` | `""` | `FOR UPDATE` |
| `upsertClause` | `ON CONFLICT ... EXCLUDED` | `ON DUPLICATE KEY ... VALUES` | `ON CONFLICT ... EXCLUDED` | `ON CONFLICT ... EXCLUDED`（默认） |
| `truncateTable` | `TRUNCATE ... RESTART IDENTITY` | `TRUNCATE ...`（默认） | `DELETE FROM ...` | `TRUNCATE ... RESTART IDENTITY` |
| `dropTable` | 覆写加 `CASCADE` | 默认 | 默认 | 默认 |
| `createTable` | 默认 | 默认 | **覆写**（AUTOINCREMENT 内联） | 默认 |
| `addColumn` | 默认 | 默认 | **覆写**（剥离 NOT NULL） | 默认 |
| `generatedTypeOverride` | 默认 | 默认 | **覆写**（强制 INTEGER） | 默认 |
| `effectiveSchema` | 默认 `"public"` | 未使用（`DATABASE()`） | 未使用（`sqlite_master`） | 默认 `"public"` |
| `existingIndexes` | `pg_indexes` | `INFORMATION_SCHEMA.STATISTICS` | 默认 `Set.of()` | `INFORMATION_SCHEMA.INDEXES` |

---

## 检查清单

新增一个方言时，逐项检查：

- [ ] 5 个抽象方法全部实现
- [ ] `reservedWords()` 已覆写（包含至少通用保留字 + 目标数据库特有保留字）
- [ ] `quoteChar()` 已正确设置
- [ ] `supportsReturning()` 正确
- [ ] `supportsOnConflict()` 正确
- [ ] `defaultBinaryType()` 正确
- [ ] `defaultInstantType()` 按需覆写
- [ ] `upsertClause()` 按需覆写（语法与 PG 不同时）
- [ ] `truncateTable()` 按需覆写
- [ ] `supportsIndexIfNotExists()` + `existingIndexes()` 配套
- [ ] `forUpdateClause()` 按需覆写
- [ ] `generatedTypeOverride()` 按需覆写
- [ ] `createTable()` / `addColumn()` 按需覆写
- [ ] `effectiveSchema()` 按需覆写
- [ ] `DbModule.bind()` 注册（`id` 与 `dialectId()` 一致）
- [ ] `DbModule.detectDialect()` URL 检测（大小写不敏感）
- [ ] `dialectId()` 单测
- [ ] `upsertClause()` 单测
- [ ] DDL 生成集成测试
