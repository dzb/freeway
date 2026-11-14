# freeway-db User Manual and Dialect Guide

> **Merge (2026-08-19)**: This document was merged from three freeway-db documents—
> `freeway-db-summary.md` (quick overview), `freeway-db-how-to-use.md` (user manual),
> `freeway-db-dialect-how-to-add.md` (guide to adding a dialect); the original three have been removed.
>
> Structure:
> - **Part 1 · Quick Usage Overview** (formerly summary): a summary of the complete path from entity to CRUD
> - **Part 2 · User Manual** (formerly how-to-use): entity definition → DDL → versioned migration →
>   build → ORM → raw SQL → SQL builder → Row → transaction → ExecuteResult → auto-increment ID
> - **Part 3 · Guide to Adding a Database Dialect** (formerly dialect-how-to-add): Dialect contract,
>   steps to add a dialect, template code, 4-dialect difference reference

---

## Freeway-DB Usage Pattern Summary

  ### Basic Usage

  ```java
  // 1. Build the database
  Database db = Database.create(PoolConfig.defaults("jdbc:...", "user", "pass"));

  // 2. Raw query
  List<User> users = db.query("SELECT * FROM users WHERE status = ?", 1).list(User.class);
  Optional<User> user = db.query("SELECT * FROM users WHERE id = ?", 42).one(User.class);

  // 3. Named parameters
  User u = db.query("SELECT * FROM users WHERE id = $id")
      .bind("id", 42L)
      .one(User.class).orElseThrow();

  // 4. Streaming
  try (Stream<User> stream = db.query("SELECT * FROM users").stream(User.class)) {
      stream.forEach(System.out::println);
  }

  // 5. DML
  ExecuteResult r = db.execute("INSERT INTO users (name) VALUES (?)", "Alice");
  long id = r.longKey();
  int affected = r.rows();

  // 6. Batch processing
  db.batch("INSERT INTO users (name) VALUES (?)")
      .rowsRaw(new Object[]{"A"}, new Object[]{"B"})
      .execute();

  // 7. Transaction + event bus awareness
  db.transaction(() -> {
      db.execute("UPDATE accounts SET balance = balance - ? WHERE id = ?", 100, 1);
      db.execute("UPDATE accounts SET balance = balance + ? WHERE id = ?", 100, 2);
      bus.publish(new TransferEvent(1, 2, 100));  // automatically deferred until after commit
  });

  // 8. ORM (Record entity — insert + find are available; update/delete go through raw SQL)
  Orm orm = Orm.of(db);
  orm.insert(new User(null, "Alice", null, "alice@example.com", null));
  User u = orm.findById(User.class, 1L).orElseThrow();
  db.execute("DELETE FROM users WHERE id = ?", 1L);

  // 9. Upsert (id is null → INSERT; id has a value → ON CONFLICT DO UPDATE)
  orm.save(new User(null, "Bob", null, "bob@example.com", null));  // INSERT
  // orm.save(existingUser);                                        // UPSERT

  // 10. Schema auto-migration
  Schema.ensure(db, User.class, Post.class);

  // 11. SQL builder
  Sql q = Sql.select("*").from("users")
      .where("status = ?", 1)
      .orderBy("created_at DESC")
      .limit(10);
  List<User> users = db.query(q).list(User.class);
  ```
### Annotation-Driven Entities

  ```java
  @Table("users")              // table name override (default: User → user)
  public record User(
      @Id @Generated Long id,  // auto-generated primary key
      @Column("user_name")     // column name override
      @Size(max = 100)         // VARCHAR(100)
      String name,
      @Column(type = "TEXT")   // explicit SQL type
      String bio,
      @Index(name = "idx_email", unique = true)  // unique index
      String email,
      @Transient                // not persisted
      String temp
  ) {}
  ```

### Demonstrated freeway-db Capabilities

  ```
  Annotation-driven entities  →  @Table @Id @Generated @Column
  DDL auto-generation         →  Schema.define() / Schema.ensure()
  ORM access                  →  Orm.insert / findById / findAll / save (upsert)
  Raw SQL                     →  db.execute() / db.query()
  SQL builder                 →  Sql.select().from().where().orderBy()
  Row column access           →  row.asString("name") / row.asInt("age")
  Transaction handling        →  db.transaction(() -> ...) + EventBus transaction awareness
  ```

Conclusion: **an LLM can fully understand and use freeway for development**; the complete path from entity definition to DDL table creation to CRUD access has been proven.
# freeway-db Usage Pattern Manual

> A summary of the complete development experience based on the freeway-db module. Covers: entity definition → automatic DDL table creation → versioned SQL migration → ORM access → raw SQL → SQL builder → transaction → auto-increment ID behavior.

---

## I. Entity Definition

Two styles are supported: **Record (immutable)** and **Bean (mutable)**. Annotation-driven metadata is used.

### 1.1 Record Style (immutable, suitable for queries/inserts)

```java
@Table("t_user")                  // specify the table name (if omitted, the class name in snake_case)
record User(
    @Id @Generated Long id,       // auto-increment primary key
    @Column String name,          // column name defaults to the field name in snake_case, VARCHAR(255)
    @Column Integer age           // INTEGER
) {
    // no need to handle id in the constructor; the database generates it automatically
    User(String name, Integer age) { this(null, name, age); }
}
```

### 1.2 Bean Style (mutable, suitable for update scenarios)

```java
@Table("t_user")
static class UserBean {
    @Id @Generated Long id;       // auto-increment primary key
    @Column String name;
    @Column Integer age;

    UserBean() {}
    UserBean(String name, Integer age) { this.name = name; this.age = age; }
}
```

### 1.3 Annotation Description

| Annotation | Purpose | Description |
|------|------|------|
| `@Table("xxx")` | Maps the table name | If not specified, the class name in snake_case is used (`UserProfile` → `user_profile`) |
| `@Id` | Marks the primary key | Required |
| `@Generated` | Auto-increment primary key | Used together with `@Id`; generated automatically by the database |
| `@Column("xxx")` | Maps the column name | If not specified, the field name in snake_case is used (`userName` → `user_name`) |
| `@Transient` | Ignores the field | Not part of persistence |

---

## II. Automatic DDL Generation

### 2.1 Viewing the DDL Statement (without executing)

```java
Database db = ...;                        // see 3.1 / 3.2
String ddl = Schema.define(db.dialect(), User.class);
System.out.println(ddl);
// → CREATE TABLE t_user (
//     id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
//     name VARCHAR(255),
//     age INTEGER
//   )
```

### 2.2 Automatic Table Creation (safe execution; no error if the table already exists)

```java
Database db = ...;
Schema.ensure(db, User.class);
```

### 2.3 Versioned SQL Migration (MigrationRunner)

Put `V1__description.sql` (Flyway-compatible naming: `V` + digits, which may be separated by `.`/`_`, e.g. `V1_2__init.sql`) into the `db/migration/` directory on the classpath. In IoC mode (`DbModule`), migrations that have not yet been applied run at startup before the HTTP server; when used standalone, you can call `new MigrationRunner(db, MigrationRunner.Options.defaults()).run()` manually. Related configuration: `freeway.db.migration.enabled` (default `true`), `freeway.db.migration.path` (default `db/migration/`), `freeway.db.migration.table` (default `_migrations`).

**Checksum dual-track verification (CRLF/LF)**: The SHA-256 of the original bytes of each applied migration is stored in the checksum table. It is re-compared on every startup: identical original bytes → pass; when they differ, the comparison is repeated once with CRLF→LF normalized bytes—a pure line-ending style change (e.g. a Windows checkout turning LF into CRLF) is no longer falsely reported as "file modified", while a real content change still throws `SqlException` (checksum mismatch, startup fails). Stored checksum rows are never rewritten.

**Migrations containing DDL are rejected on MySQL**: Every DDL statement in MySQL/MariaDB commits implicitly (no transactional DDL), so a migration containing DDL cannot be applied atomically—the DDL is committed but the checksum row is lost, and the next startup reruns the DDL and fails. Therefore `MigrationRunner` rejects such migrations before executing any SQL (startup fails with `SqlException`), with guidance attached: split the DDL into a separate migration and make it idempotent (`IF NOT EXISTS`), or switch to a database that supports transactional DDL (PostgreSQL/H2/SQLite). Pure DML migrations are unaffected.

---

## III. Database Construction

### 3.1 Direct Construction

```java
Database db = Database.create(PoolConfig.defaults(
        "jdbc:h2:mem:mydb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "sa", ""));
```

### 3.2 IoC Module Approach

```java
Container container = Freeway.create(new DbModule());
Database db = container.get(Database.class);
```

### 3.3 Multiple Data Sources

```java
Database primary = ...;
Database audit = ...;

Container container = Freeway.create(
    new DbModule(),
    binder -> {
        binder.contribute(NamedDatabase.class)
              .add(new NamedDatabase("primary", primary));
        binder.contribute(NamedDatabase.class)
              .add(new NamedDatabase("audit", audit));
    }
);

DatabaseRegistry registry = container.get(DatabaseRegistry.class);
Database p = registry.find("primary").orElseThrow();
Database a = registry.find("audit").orElseThrow();
Database def = registry.primary();
```

### 3.4 Dialect Detection and Selection

- **URL auto-detection** (`Dialect.of`): `jdbc:mysql` / `jdbc:mariadb` → MySQL; `jdbc:sqlite` → SQLite; `jdbc:h2` → H2 (keeps the URL's `MODE`, does not map to PostgreSQL/MySQL); `jdbc:postgresql` → PostgreSQL.
- URL is `null` or blank → default PostgreSQL.
- **Unknown JDBC URL fails startup (fail-fast)**: an unrecognized scheme (e.g. `jdbc:oracle:...`) **no longer silently falls back to PostgreSQL**, but directly throws `IllegalStateException` (the message includes guidance to `freeway.db.dialect`) — for standalone construction, specify explicitly with `Database.create`'s `withDialect(new XxxDialect())`; in IoC mode, configure `freeway.db.dialect=xxx`. Silent fallback lets the wrong dialect send PG syntax (`ON CONFLICT`, `pg_indexes`) to the target database at runtime before the error surfaces, so it now fails at startup.
- Under IoC, an explicitly configured `freeway.db.dialect` takes precedence over URL detection.

---

## IV. ORM Access (the `Orm` class)

### 4.1 Obtaining an ORM Instance

```java
Orm orm = Orm.of(db);
```

### 4.2 Create — INSERT

```java
// Record style (get the ID from the returned result)
ExecuteResult r = orm.insert(new User("Lightning", 3));
long id = r.longKey();

// Bean style (ID written back to the object)
UserBean bean = new UserBean("Coalball", 5);
orm.insert(bean);
// bean.id has been assigned
```

### 4.3 Read — Query

```java
// Look up by ID
User found = orm.findById(User.class, 1L).orElseThrow();

// Find all
List<User> all = orm.findAll(User.class);

// Sorting + pagination (orderBy, limit, offset)
List<User> ordered = orm.findAll(User.class, "age ASC", 0, 0);  // all, age ascending
List<User> limited = orm.findAll(User.class, "age ASC", 2, 0);  // take the first 2 rows
List<User> offset  = orm.findAll(User.class, "id ASC", 0, 1);   // skip the first row
```

### 4.4 Update — Update (Bean Only)

```java
UserBean bean = orm.findById(UserBean.class, 1L).orElseThrow();
bean.age = 4;                       // modify the field
ExecuteResult r = orm.update(bean); // update by ID
// r.rows() → number of affected rows
```

### 4.5 Delete — Delete

```java
// Delete by object
orm.delete(bean);

// Delete by ID
orm.deleteById(UserBean.class, 1L);
```

### 4.6 Save — Insert or Update

```java
// id=null (or unset) → INSERT; the database assigns the auto-increment key
UserBean u = new UserBean("Lightning", 3);
orm.save(u);
// u.id has been assigned

// Primitive @Generated primary key (e.g. long id): the id read for a new entity is 0 rather than null,
// so save() still treats it as "unset" → INSERT goes through the identity sequence and writes back the generated key (it will not upsert with 0)

// id=existing value → upsert; the clause is determined by the dialect:
//   PostgreSQL / SQLite / H2 → INSERT ... ON CONFLICT (id) DO UPDATE
//   MySQL / MariaDB         → INSERT ... ON DUPLICATE KEY UPDATE
```

---

## V. Raw SQL

### 5.1 Executing INSERT / UPDATE / DELETE

```java
// Parameters use ? placeholders, with automatic injection protection
ExecuteResult r = db.execute("INSERT INTO t_user (name, age) VALUES (?, ?)", "Lightning", 3);
long id = r.longKey();   // auto-increment ID
int rows = r.rows();     // number of affected rows
```

### 5.2 Querying and Mapping

```java
// Map to Record
User user = db.query("SELECT * FROM t_user WHERE id = ?", 1L)
    .one(User.class).orElseThrow();

// Map to List
List<User> users = db.query("SELECT * FROM t_user WHERE age >= ?", 3)
    .list(User.class);

// Map to a single column
Long count = db.query("SELECT count(*) FROM t_user")
    .one(Long.class).orElseThrow();
```

### 5.3 Named Parameters

```java
User user = db.query("SELECT * FROM t_user WHERE id = $id")
    .bind("id", 1L)
    .one(User.class).orElseThrow();
```

### 5.4 Collection Parameters (IN Query)

```java
List<User> users = db.query("SELECT * FROM t_user WHERE id IN (?)", List.of(1L, 2L, 3L))
    .list(User.class);
```

### 5.5 Batch Operations

```java
db.batch("INSERT INTO t_user (name, age) VALUES (?, ?)")
    .rowsRaw(
        new Object[]{"Lightning", 3},
        new Object[]{"Coalball", 5}
    )
    .execute();
```

---

## VI. Row Access (the `Row` class)

When query results are mapped to `Row.class`, values can be retrieved flexibly by column name:

```java
List<Row> rows = db.query("SELECT * FROM t_user WHERE id = ?", 1L)
    .list(Row.class);

Row row = rows.get(0);
String name  = row.asString("name");        // string
Integer age  = row.asInt("age");        // integer
Long    id   = row.asLong("id");       // long integer
BigDecimal score = row.asBigDecimal("score");  // floating-point number (BigDecimal)
List<String> cols = row.columns();        // get all column names
```

---

## VII. SQL Builder (the `Sql` class)

Build SQL statements in a chain, suitable for dynamic-condition scenarios:

```java
List<User> adults = db.query(
    Sql.select("*")
        .from("t_user")
        .where("age >= ?", 3)
        .orderBy("age DESC")
).list(User.class);
```

INSERT builder:

```java
ExecuteResult r = db.execute(
    Sql.insert("t_user")
        .value("name", "Lightning")
        .value("age", 3)
);
```

---

## VIII. Transactions

```java
// Commits successfully
db.transaction(() -> {
    orm.insert(new User("Lightning", 3));
    orm.insert(new User("Coalball", 5));
});

// Rolls back automatically on exception
try {
    db.transaction(() -> {
        orm.insert(new User("WillRollBack", 99));
        throw new RuntimeException("simulated exception");
    });
} catch (RuntimeException ignored) {
    // the transaction has rolled back; data is unchanged
}
```

### Transaction-Aware Event Publishing

Calling `bus.publish()` inside a transaction automatically defers execution until after commit—it is sent only if the commit succeeds, and not sent on rollback. Zero configuration; the framework handles it automatically through `Defer`'s post-commit execution mechanism.

```java
db.transaction(() -> {
    orm.insert(new User("Lightning", 3));
    bus.publish(new UserCreatedEvent(user));  // actually published only after commit
});
```

### Cross-Thread Restriction (ScopedValue Does Not Propagate)

A transaction is bound to the thread that opened it; `ScopedValue` **does not propagate to child threads**. A newly started thread or any other thread inside `transaction()` cannot do DB work—calling `db.query/execute/batch` then throws `SqlException` ("Another thread holds an active transaction on this Database ..."), instead of silently borrowing a separate connection to bypass the transaction and break atomicity. Note that this guard applies per `Database` instance: while a singleton `Database` is in a transaction, all non-transactional DB calls from other threads are rejected; for concurrent work, use separate `Database` instances (e.g. through `DatabaseRegistry`). `Query`/`BatchQuery` created inside a transaction must also be consumed on the transaction thread before the transaction ends, otherwise a `SqlException` is thrown as well.

### No XA Across Databases

`db.transaction()` covers only the connection of the current `Database` itself: writes inside the transaction through other data sources obtained via `DatabaseRegistry` **commit independently**, and are not rolled back if this transaction fails. There is no XA / two-phase commit across databases; do not mix writes to multiple databases in a single-database transaction.

### Schema.ensure and Transactions

MySQL/MariaDB DDL commits implicitly (no transactional DDL): calling `Schema.ensure()` / `Schema.drop()` inside a transaction is rejected (throws `SqlException`) — the DDL would commit the transaction's uncommitted changes along with it. Put schema DDL before opening a transaction. PostgreSQL / H2 / SQLite support transactional DDL; inside a transaction `ensure()` is safe (a failure rolls back everything).

---

## IX. ExecuteResult Return Value

`db.execute()` / `orm.insert()` and similar operations return `ExecuteResult`, used to obtain the execution result:

```java
ExecuteResult r = db.execute("INSERT INTO t_user (name, age) VALUES (?, ?)", "Lightning", 3);

r.rows();        // number of affected rows (int)
r.hasGeneratedKey();      // whether it contains an auto-increment ID (boolean)
r.longKey();     // auto-increment ID value (long)

// Typical assertions
assertTrue(r.hasGeneratedKey());
assertTrue(r.longKey() > 0);
assertEquals(1, r.rows());
```

---

## X. Core Auto-Increment ID Behavior

| Operation | `hasGeneratedKey()` | `longKey()` | Description |
|------|-----------|-------------|------|
| **INSERT** (normal) | `true` | generated ID | The auto-increment value starts at 1 and increases |
| **INSERT** (id explicitly specified) | `true` | the specified value | e.g. `set("id", 100L)` |
| **INSERT** (inside a transaction) | `true` | generated ID | Available even before the transaction commits |
| **INSERT** (SQL builder) | `true` | generated ID | Consistent with raw SQL |
| **INSERT** (SQL with comments) | `true` | generated ID | `/* ... */ insert ...` is still recognized |
| **UPDATE** | `false` | `0` | Does not return an auto-increment ID |
| **DELETE** | `false` | `0` | Does not return an auto-increment ID |

---

## XI. Complete Development Template

```java
// 1. Define the entity
@Table("t_user")
record User(@Id @Generated Long id, @Column String name, @Column Integer age) {
    User(String name, Integer age) { this(null, name, age); }
}

// 2. Create the database + create the table
Database db = Database.create(PoolConfig.defaults("jdbc:h2:mem:demo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
Schema.ensure(db, User.class);
Orm orm = Orm.of(db);

// 3. CRUD
var r = orm.insert(new User("Lightning", 3));           // Create
User u = orm.findById(User.class, r.longKey())...;   // Read
// ... (Bean-style Update/Delete)

// 4. Clean up
db.close();  // Database implements AutoCloseable
```

---

> **Conclusion**: freeway-db provides complete database development capability from DDL to CRUD, with concise annotations, a consistent API, and support for both Record and Bean styles. An LLM can fully understand it and use it for development.
# Guide to Adding a Database Dialect

The `Dialect` interface defines the complete contract of an SQL dialect. This document describes all the steps required to add a database dialect.

## Table of Contents

1. [Overview](#overview)
2. [Step 1: Create the Dialect Class](#step-1-create-the-dialect-class)
3. [Step 2: Abstract Methods That Must Be Implemented (5)](#step-2-abstract-methods-that-must-be-implemented-5)
4. [Step 3: Default Methods That Must Be Overridden (6)](#step-3-default-methods-that-must-be-overridden-6)
5. [Step 4: Default Methods to Override as Needed](#step-4-default-methods-to-override-as-needed)
6. [Step 5: Register with DbModule](#step-5-register-with-dbmodule)
7. [Step 6: Special Handling](#step-6-special-handling)
8. [Step 7: Testing](#step-7-testing)
9. [Template Code](#template-code)
10. [Difference Reference Against Existing Dialects](#difference-reference-against-existing-dialects)

---

## Overview

The `Dialect` interface is located in `com.jujin.freeway.db.dialect` and **only declares syntax features**: identifier quoting (`quoteName`/`identifierQuoteChars`), identity clauses (`generatedClause`/`generatedTypeOverride`), type mapping defaults, single-clause mappings (`upsertClause`/`forUpdateClause`/`offsetOnlyClause`/`truncateTable`), capability flags (`supportsTransactionalDdl`/`supportsIndexIfNotExists`/`supportsReturning`/`supportsOnConflict`, plus `generatedPrimaryKeyInline`/`dropTableCascade`/`alterAddColumnNotNull`), lexical capabilities (`hashLineComments`/`dollarQuoting`/`escapeStringPrefix`/`backslashEscapesStrings`/`bracketQuoting`) and metadata queries.

**DDL assembly (CREATE TABLE / CREATE INDEX / ALTER / DROP) is not part of Dialect**—that is the responsibility of the `com.jujin.freeway.db.schema` package (`Schema`/`SchemaGenerator`), which consume Dialect primitives to generate dialect-correct DDL. The Dialect package is self-contained: `dialect` depends only on `com.jujin.freeway.db.Database` and does not depend back on `schema`, so adding a dialect requires changing only the `dialect` package.

| Category | Count | Description |
|------|------|------|
| Abstract methods (must implement) | 5 | Enforced by the compiler; it will not compile without them |
| Default methods (must override) | ~6 | The defaults are PG-preferred; bugs occur if not overridden |
| Default methods (override as needed) | 4-6 | Depends on database features (including `backslashEscapesStrings`/`supportsTransactionalDdl`) |
| Registration points | 2 | `Dialect.ofId()` catalog + `Dialect.of()` URL detection |

Adding a dialect is estimated at about **100-150 lines of code + tests**.

---

## Step 1: Create the Dialect Class

Create a class under the `com.jujin.freeway.db.dialect` package that implements the `Dialect` interface:

```java
package com.jujin.freeway.db.dialect;

public final class OracleDialect implements Dialect {
    // fill in step by step below
}
```

All `Dialect` implementations must be **stateless and thread-safe** (only constants and pure functions, no mutable fields).

---

## Step 2: Abstract Methods That Must Be Implemented (5)

### 2.1 `dialectId()`

Returns the dialect identifier, used for IoC registration and error messages.

```java
@Override
public String dialectId() {
    return "oracle";  // lowercase, consistent with the ID in DbModule
}
```

### 2.2 `generatedClause()`

Returns the DDL clause for an auto-increment column.

```java
@Override
public String generatedClause() {
    return "GENERATED BY DEFAULT AS IDENTITY";  // PG/H2 style
    // or "AUTO_INCREMENT"                   // MySQL style
    // or "AUTOINCREMENT"                    // SQLite style
}
```

### 2.3 `defaultUUIDType()`

Returns the SQL type corresponding to `UUID`.

```java
@Override
public String defaultUUIDType() {
    return "UUID";         // PG/H2
    // or "VARCHAR(36)"    // MySQL
    // or "TEXT"           // SQLite
}
```

### 2.4 `existingTables(Database db)`

Queries the names of tables that already exist in the database. Returns a lowercase set.

```java
@Override
public Set<String> existingTables(Database db) {
    return querySet(db,
        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ?",
        effectiveSchema());
}
```

- Uses the `querySet()` helper (automatic lowercasing, exception handling)
- Filters the schema as needed (calls `effectiveSchema()`)
- If INFORMATION_SCHEMA case is uncertain, compare with `UPPER()`

### 2.5 `existingColumns(Database db, String tableName)`

Queries the names of columns that already exist in the specified table. Returns a lowercase set.

```java
@Override
public Set<String> existingColumns(Database db, String tableName) {
    return querySet(db,
        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? AND TABLE_SCHEMA = ?",
        tableName.toUpperCase(Locale.ROOT), effectiveSchema());
}
```

---

## Step 3: Default Methods That Must Be Overridden (6)

The return values of the following default methods are PG-preferred and will produce incorrect SQL on other databases. **They must be overridden**.

### 3.1 `reservedWords()`

Returns the set of SQL reserved words for that database (lowercase). If not overridden, no identifier will be quoted at all.

`buildReserved` is a **static method** on the `Dialect` interface (`Dialect.buildReserved(String...)`): it merges the interface constant `COMMON_RESERVED` (common reserved words) with the passed dialect-specific words and returns an immutable set. All four built-in dialects (Postgres/MySql/Sqlite/H2) assemble their reserved-word sets through it in their own classes, and custom dialects should reuse it too—**do not write another merge implementation in the dialect class**.

```java
private static final Set<String> RESERVED = Dialect.buildReserved(
    // just list the reserved words specific to the target database—common reserved words are already provided by COMMON_RESERVED
    "status", "show", "describe", "explain",
    // ... refer to the sets of existing dialects and add the target database's specific reserved words
);

@Override
public Set<String> reservedWords() {
    return RESERVED;
}
```

### 3.2 `quoteChar()`

Returns the identifier quote character.

```java
@Override
public char quoteChar() {
    return '"';   // PG/SQLite/H2
    // or '`'     // MySQL
}
```

There is no need to override `quoteName()` or `needsQuoting()`—their default implementations call `quoteChar()` and `reservedWords()`.

### 3.3 `supportsReturning()`

```java
@Override
public boolean supportsReturning() {
    return true;   // PG/H2/SQLite 3.35+
    // or false   // MySQL
}
```

### 3.4 `supportsOnConflict()`

```java
@Override
public boolean supportsOnConflict() {
    return true;   // PG/SQLite/H2
    // or false   // MySQL (MySQL uses ON DUPLICATE KEY)
}
```

### 3.5 `defaultBinaryType()`

```java
@Override
public String defaultBinaryType() {
    return "BYTEA";       // PG
    // or "LONGBLOB"      // MySQL
    // or "BLOB"          // SQLite
    // or "BINARY VARYING" // H2
}
```

### 3.6 `defaultInstantType()`

```java
@Override
public String defaultInstantType() {
    return "TIMESTAMP WITH TIME ZONE";  // PG/H2 (default, no override needed)
    // or "DATETIME(6)"                // MySQL
    // or "TEXT"                       // SQLite
}
```

---

## Step 4: Default Methods to Override as Needed

### 4.1 `upsertClause(List<String> conflict, List<String> update)`

**If `ON CONFLICT` is not supported** (e.g. MySQL), it must be overridden:

```java
@Override
public String upsertClause(List<String> conflictColumns, List<String> updateColumns) {
    List<String> updates = new ArrayList<>(updateColumns.size());
    for (String col : updateColumns) {
        String q = quoteName(col);
        updates.add(q + " = VALUES(" + q + ")");  // MySQL syntax
    }
    return " ON DUPLICATE KEY UPDATE " + String.join(", ", updates);
}
```

The default implementation uses `ON CONFLICT (target) DO UPDATE SET col = EXCLUDED.col` (PG syntax).

**Notes**:
- The column names passed in are **raw names** (unquoted); the method must call `quoteName()` internally.
- The return value **must start with a space**.

### 4.2 `truncateTable(String tableName)`

```java
@Override
public String truncateTable(String tableName) {
    return "TRUNCATE TABLE " + quoteName(tableName) + " RESTART IDENTITY";  // PG/H2
    // or "TRUNCATE TABLE " + quoteName(tableName)                           // MySQL (default)
    // or "DELETE FROM " + quoteName(tableName)                              // SQLite
}
```

### 4.3 `supportsIndexIfNotExists()`

```java
@Override
public boolean supportsIndexIfNotExists() {
    return true;   // PG/SQLite/H2
    // or false   // MySQL
}
```

If it returns `false`, `existingIndexes()` must also be overridden to query existing indexes.

### 4.4 `existingIndexes(Database db, String tableName)`

**Only needed when `supportsIndexIfNotExists()` returns `false`**.

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
    return "FOR UPDATE";   // PG/MySQL/H2 (default)
    // or ""               // SQLite (not supported)
}
```

The return value has **no leading space**.

### 4.6 `generatedTypeOverride(String sqlType, Class<?> javaType)`

**Override only when the auto-increment column requires a specific SQL type** (e.g. SQLite requires `INTEGER`):

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

### 4.7 `generatedPrimaryKeyInline()`

**Override only when the auto-increment primary key must be declared inline on the column** (SQLite: `"id" INTEGER PRIMARY KEY AUTOINCREMENT`; other databases use a trailing `PRIMARY KEY (...)` clause).

```java
@Override
public boolean generatedPrimaryKeyInline() {
    return true; // SQLite
}
```

### 4.8 `alterAddColumnNotNull()`

**Override only when ALTER TABLE ADD COLUMN cannot carry NOT NULL** (SQLite cannot add NOT NULL without a DEFAULT, and the constraint would be stripped).

```java
@Override
public boolean alterAddColumnNotNull() {
    return false; // SQLite
}
```

> **Note**: The complete statement assembly for CREATE TABLE / CREATE INDEX / ALTER / DROP lives in `SchemaGenerator` / `Schema` (`com.jujin.freeway.db.schema`), not on Dialect. Dialect only provides primitives such as `quoteName`, `generatedClause`, and the capability flags above.

### 4.9 `effectiveSchema()`

```java
@Override
public String effectiveSchema() {
    return "public";   // PG
    // or "dbo"        // SQL Server
    // or "PUBLIC"      // H2
}
```

### 4.10 `backslashEscapesStrings()`

**MySQL must override it to `true`**: whether `\` escapes the next character in an ordinary single-quoted string literal (`'it\'s'` = `it's`). The default is `false`—the SQL standard defines only `''` doubling; PostgreSQL's backslash escape uses the `E'...'` prefix (`escapeStringPrefix()`), and in ordinary PG strings `\` is literal text.

```java
@Override
public boolean backslashEscapesStrings() {
    return true; // MySQL / MariaDB
}
```

### 4.11 `supportsTransactionalDdl()`

**MySQL must override it to `false`**: whether DDL statements participate in transactions. The default is `true` (PG/H2/SQLite). Every DDL statement in MySQL/MariaDB commits implicitly: a migration containing DDL cannot be applied atomically (the DDL is committed but the checksum row is lost, and the next startup reruns the DDL and fails), so `MigrationRunner` rejects such migrations outright; `Schema.ensure()`/`Schema.drop()` are also rejected inside a transaction.

```java
@Override
public boolean supportsTransactionalDdl() {
    return false; // MySQL / MariaDB (every DDL statement commits implicitly)
}
```

---

## Step 5: Register Built-in Dialects in the Catalog

Built-in dialects are resolved by the `Dialect.ofId(String)` catalog; `DbModule` no longer binds them one by one. Two locations are changed:

### 5.1 Register id → implementation

```java
// in the switch of Dialect.ofId()
case "oracle" -> new OracleDialect();
```

`id` must match the value returned by `dialectId()`.

### 5.2 Add URL detection

```java
// in Dialect.of(url): first map the scheme to an id, then hand it to ofId()
if (url.contains("jdbc:oracle")) return Dialect.ofId("oracle");
```

The only implementation of URL detection is in `Dialect.of(String)` (case-insensitive)—
standalone use (`Database.create`) and IoC use (`DbModule`) share the same place. `DbModule` binds only one
final selected `Dialect` service. Third-party dialects do not need to enter the built-in catalog: bind by id
(`binder.bind(Dialect.class).to(OracleDialect.class).id("oracle")`) and configure
`freeway.db.dialect=oracle`.

**Unknown schemes fail fast**: `Dialect.of` throws `IllegalStateException` for an unrecognized JDBC URL (e.g. `jdbc:oracle:...`), with the message carrying guidance to `freeway.db.dialect`—**it no longer silently falls back to PostgreSQL** (silent fallback would only expose the error at runtime, after PG syntax had already been sent to the target database). Only when the URL is `null` or blank does it default to returning `PostgresDialect`. H2's `MODE` stays inside `H2Dialect`: `MODE` changes H2's syntax surface, not its introspection, type, or DDL transaction semantics; `DbModule` auto-detection directly preserves the H2 instance with the mode. When adding a dialect, add the corresponding URL prefix match in this method.

---

## Step 6: Special Handling

### 6.1 ColumnDef Helper Methods

`ColumnDef` is a package-private record that provides the following methods for dialects:

| Method | Purpose |
|------|------|
| `toSql(Dialect)` | Complete `"col" TYPE [NOT NULL] [GENERATED]` |
| `toAlterSql(Dialect, includeNotNull, includeGenerated)` | `ADD COLUMN ...` prefix + complete definition, stripping NOT NULL / the generated clause as needed (package-private, used by `Schema`'s ALTER assembly) |

### 6.2 querySet Helper Method

`Dialect.querySet(db, sql, params...)` executes an introspection query and lowercases the results. **An introspection failure throws `SqlException`** (never silently treat it as an "empty database"—otherwise `Schema` would generate misleading DDL against an unknown current state, such as creating duplicate indexes or issuing CREATE again for tables that already exist); `Schema.ensure` catches that exception, skips the affected DDL phases, and logs a warning.

---

## Step 7: Testing

### 7.1 Unit Tests

Add to `SchemaGeneratorTest.java`:

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

### 7.2 Recommended Test Coverage

- `supportsReturning()`
- `supportsOnConflict()`
- `supportsTransactionalDdl()` (when overridden)
- `supportsIndexIfNotExists()`
- `backslashEscapesStrings()` (when overridden)
- `dialectId()`
- `upsertClause()` — 2-arg signature
- `truncateTable()`
- `forUpdateClause()`
- `defaultBinaryType()`
- `generatedTypeOverride()` (if overridden)
- `quoteName()` handling of reserved words
- DDL generation (via `new SchemaGenerator(dialect).generate(Entity.class)`)

---

## Template Code

```java
package com.jujin.freeway.db.dialect;

import com.jujin.freeway.db.Database;
import java.util.*;

public final class XxxDialect implements Dialect {

    private static final Set<String> RESERVED = Dialect.buildReserved(
        "user", "order", "group", "table", "select", "from", "where"
        // TODO: add reserved words specific to the target database (common reserved words are provided by COMMON_RESERVED)
    );

    // ====================== Abstract methods (5) ======================

    @Override public String dialectId()               { return "xxx"; }
    @Override public String generatedClause()         { return "GENERATED BY DEFAULT AS IDENTITY"; }
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

    // ====================== Default method overrides (at least 6) ======================

    @Override public Set<String> reservedWords()      { return RESERVED; }
    @Override public char quoteChar()                 { return '"'; }
    @Override public boolean supportsReturning()      { return true; }
    @Override public boolean supportsOnConflict()     { return true; }
    @Override public String defaultBinaryType()       { return "BYTEA"; }
    @Override public String defaultInstantType()      { return "TIMESTAMP WITH TIME ZONE"; }

    // ====================== Override as needed ======================

    // truncate: if RESTART IDENTITY or DELETE FROM is needed
    // upsertClause: if the ON CONFLICT syntax differs
    // supportsIndexIfNotExists + existingIndexes: if IF NOT EXISTS is not supported
    // forUpdateClause: if FOR UPDATE is not supported
    // generatedTypeOverride: if the auto-increment column needs a specific type
    // generatedPrimaryKeyInline: if the auto-increment primary key must be inline (SQLite)
    // alterAddColumnNotNull: if ALTER ADD COLUMN cannot carry NOT NULL (SQLite)
    // backslashEscapesStrings: if \ escapes the next character in ordinary string literals (MySQL)
    // supportsTransactionalDdl: if DDL does not participate in transactions (MySQL → false)
    // dropTableCascade: if DROP TABLE needs CASCADE (PG)
    // effectiveSchema: if the default schema name is not "public"
}
```

---

## Difference Reference Against Existing Dialects

| Capability | PG | MySQL | SQLite | H2 |
|------|----|-------|--------|----|
| `quoteChar` | `"` | `` ` `` | `"` | `"` |
| `generatedClause` | `GENERATED BY DEFAULT AS IDENTITY` | `AUTO_INCREMENT` | `AUTOINCREMENT` | `GENERATED BY DEFAULT AS IDENTITY` |
| `defaultUUIDType` | `UUID` | `VARCHAR(36)` | `TEXT` | `UUID` |
| `defaultInstantType` | `TIMESTAMP WITH TIME ZONE` | `DATETIME(6)` | `TEXT` | `TIMESTAMP WITH TIME ZONE` (default) |
| `defaultBinaryType` | `BYTEA` | `LONGBLOB` | `BLOB` | `BINARY VARYING` |
| `supportsReturning` | ✓ | ✗ | ✓ | ✓ |
| `supportsOnConflict` | ✓ | ✗ | ✓ | ✓ |
| `supportsTransactionalDdl` | ✓ (default) | ✗ (overridden) | ✓ (default) | ✓ (default) |
| `supportsIndexIfNotExists` | ✓ | ✗ | ✓ | ✓ |
| `hashLineComments` | ✗ (default) | ✓ (overridden) | ✗ (default) | ✗ (default) |
| `dollarQuoting` | ✓ (overridden) | ✗ (default) | ✗ (default) | ✓ (inherits PG) |
| `escapeStringPrefix` | ✓ (overridden) | ✗ (default) | ✗ (default) | ✓ (inherits PG) |
| `backslashEscapesStrings` | ✗ (default) | ✓ (overridden) | ✗ (default) | ✗ (default) |
| `forUpdateClause` | `FOR UPDATE` | `FOR UPDATE` | `""` | `FOR UPDATE` |
| `upsertClause` | `ON CONFLICT ... EXCLUDED` | `ON DUPLICATE KEY ... VALUES` | `ON CONFLICT ... EXCLUDED` | `ON CONFLICT ... EXCLUDED` (default) |
| `truncateTable` | `TRUNCATE ... RESTART IDENTITY` | `TRUNCATE ...` (default) | `DELETE FROM ...` | `TRUNCATE ... RESTART IDENTITY` |
| `dropTableCascade` | **overridden** (DROP ... CASCADE) | default | default | default |
| `generatedPrimaryKeyInline` | default | default | **overridden** (AUTOINCREMENT inline on the primary key column) | default |
| `alterAddColumnNotNull` | default | default | **overridden** (ALTER strips NOT NULL) | default |
| `generatedTypeOverride` | default | default | **overridden** (forces INTEGER) | default |
| `effectiveSchema` | default `"public"` | unused (`DATABASE()`) | unused (`sqlite_master`) | default `"public"` |
| `existingIndexes` | `pg_indexes` | `INFORMATION_SCHEMA.STATISTICS` | default `Set.of()` | `INFORMATION_SCHEMA.INDEXES` |

---

## Checklist

When adding a dialect, check each item:

- [ ] All 5 abstract methods implemented
- [ ] `reservedWords()` overridden (including at least the common reserved words + the target database's specific reserved words)
- [ ] `quoteChar()` correctly set
- [ ] `supportsReturning()` correct
- [ ] `supportsOnConflict()` correct
- [ ] `defaultBinaryType()` correct
- [ ] `defaultInstantType()` overridden as needed
- [ ] `upsertClause()` overridden as needed (when the syntax differs from PG)
- [ ] `truncateTable()` overridden as needed
- [ ] `supportsIndexIfNotExists()` + `existingIndexes()` paired
- [ ] `forUpdateClause()` overridden as needed
- [ ] `generatedTypeOverride()` overridden as needed
- [ ] `generatedPrimaryKeyInline()` / `dropTableCascade()` / `alterAddColumnNotNull()` overridden as needed
- [ ] `backslashEscapesStrings()` overridden as needed (MySQL: `\` escapes in ordinary string literals)
- [ ] `supportsTransactionalDdl()` overridden as needed (MySQL: DDL does not participate in transactions → `false`)
- [ ] `effectiveSchema()` overridden as needed
- [ ] `Dialect.ofId()` registration (`id` consistent with `dialectId()`)
- [ ] `Dialect.of()` URL detection (case-insensitive; unknown schemes fail fast)
- [ ] `dialectId()` unit test
- [ ] `upsertClause()` unit test
- [ ] DDL generation integration test
