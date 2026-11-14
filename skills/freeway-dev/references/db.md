# DB Reference

Examples below are minimal snippets. They omit imports and app-specific domain types.

## Stable API

- `Database`
- `Database.Wiring`
- `DatabaseRegistry`, `NamedDatabase`
- `Orm`
- `Orm.FindOptions`
- `Query`, `Row`, `Sql`, `BatchQuery`, `ExecuteResult`, `IsolationLevel`, `Transactional`
- `RowMapper`, `RowMapping`
- `PoolConfig`
- `Pool`, `PoolDefault`, `PooledConnection`, `PoolStats`
- `DbModule`
- `Dialect`
- `PostgresDialect`, `MySqlDialect`, `SqliteDialect`, `H2Dialect`
- `Schema`
- `SchemaEntity`, `SchemaMode`
- `MigrationRunner`, `MigrationRunner.Options`
- `@Table`, `@Column`, `@Id`, `@Generated`, `@Index`, `@Transient`

## Standalone Usage

```java
PoolConfig config = PoolConfig.defaults("jdbc:h2:mem:test", "sa", "");
Database db = Database.create(config);
Orm orm = Orm.of(db);
```

`Database.create(config)` builds the standard assembly: a coercer carrying the JDBC coercion rules, the dialect detected from the JDBC URL, and a `PoolDefault`. Override any part through `Database.Wiring.defaults(config)` and its withers (`withCoercer`, `withPool`, `withDialect`, `withRowMapper`). `Database` is `AutoCloseable`; `close()` closes the pool.

## IoC Usage

```java
FreewayApp.run(new AppModule(), new DbModule());
// Database, Orm, Coercer, Pool, Dialect, MigrationRunner, DatabaseRegistry are injectable
```

`DbModule` builds `Database` from the `PoolConfig` resolved through the config cascade and binds `Orm` as a singleton. `DatabaseRegistry` registers the configured database under `primary` unless a `NamedDatabase` contribution already owns that name.

## Configuration Keys

- `freeway.db.url`
- `freeway.db.username`
- `freeway.db.password`
- `freeway.db.dialect` (default: auto-detect from the JDBC URL)
- `freeway.db.schema.mode` (default `auto`; `auto` | `validate` | `off`)
- `freeway.db.schema.groups` (default: empty = all groups)
- `freeway.db.migration.enabled` (default `true`)
- `freeway.db.migration.path` (default `db/migration/`)
- `freeway.db.migration.table` (default `_migrations`)
- `freeway.db.migration.lock-ttl` (default `1h`; stale-lock takeover budget, `0`/negative disables)
- `freeway.db.pool.*` (`max-size`, `min-idle`, `connection-timeout`, `max-lifetime`, `max-idle-time`, `clean-interval`, `health-check-query`, `health-check-timeout`)
- `freeway.db.query-timeout` (default `15s`; `0` disables the statement timeout)

## Queries

```java
// Positional parameters
List<User> users = db.query("SELECT * FROM users WHERE active = ?", true)
    .list(User.class);

// Named parameters (:name or $name)
User u = db.query("SELECT * FROM users WHERE id = :id")
    .bind("id", 42)
    .one(User.class)
    .orElseThrow();

// Stream - close it with try-with-resources
try (var stream = db.query("SELECT * FROM big_table").stream(Row.class)) {
    stream.forEach(row -> process(row));
}

// Execute (INSERT / UPDATE / DELETE)
ExecuteResult r = db.execute("INSERT INTO users (name) VALUES (?)", "Alice");
r.rows();          // affected rows
r.generatedKey();  // generated key, or null; hasGeneratedKey() / longKey() are also available
```

| Terminal | Returns | Notes |
|----------|---------|-------|
| `list(Class<T>)` | `List<T>` | Every row. |
| `one(Class<T>)` | `Optional<T>` | First row only; a multi-row result is silently truncated, so use `list` when ambiguity matters. |
| `stream(Class<T>)` | `Stream<T>` | Lazy; must be closed. |
| `execute()` | `ExecuteResult` | For INSERT / UPDATE / DELETE; supports named parameters and collection expansion. |

Target types are `Row`, a basic type, a record, or a bean. An interface or abstract class needs a registered mapper:

```java
binder.contribute(RowMapping.class)
    .add(new RowMapping(MyType.class, (rs, rowNum) -> new MyType(rs.getString("name"))));
```

A collection or array bound to one placeholder expands to repeated placeholders (`IN (?)`), and an empty collection fails fast.

## Row Reading

```java
Row r = db.query("SELECT * FROM t").list(Row.class).get(0);
r.asString("name");
r.asInt("age");
r.asLong("id");
r.asBoolean("active");
r.asBigDecimal("amount");
r.asLocalDate("created_at");
r.asLocalDateTime("updated_at");
r.asUuid("uuid_col");
```

| Accessor | Returns |
|----------|---------|
| `asString(String)` | `String` |
| `asInt(String)` | `Integer` |
| `asLong(String)` | `Long` |
| `asBoolean(String)` | `Boolean` |
| `asBigDecimal(String)` | `BigDecimal` |
| `asBigInteger(String)` | `BigInteger` |
| `asLocalDate(String)` | `LocalDate` |
| `asLocalDateTime(String)` | `LocalDateTime` |
| `asLocalTime(String)` | `LocalTime` |
| `asInstant(String)` | `Instant` |
| `asUuid(String)` | `UUID` |
| `asBytes(String)` | `byte[]` |
| `get(String, Class<T>)` | `T`, coerced by the database `Coercer` |
| `raw(String)` | `Object`, the untouched JDBC value |
| `columns()` | `Set<String>` |

Column labels are lowercased by the `Row` mapper and lookup is case-insensitive. A missing column or SQL `NULL` yields `null`.

## Transactions

```java
db.transaction(() -> {
    db.execute("UPDATE ledger SET amount = ? WHERE id = ?", 100L, 1L);
    db.execute("INSERT INTO audit_log (msg) VALUES (?)", "transfer");
});

// Isolation level
db.transaction(IsolationLevel.SERIALIZABLE, () -> { ... });
```

- The work commits on normal return and rolls back on any throwable; auto-commit is restored on exit.
- Nested transactions are rejected with `IllegalStateException`.
- The transaction is thread-bound (`ScopedValue`): a `Query` or `BatchQuery` created inside it must be consumed on the transaction thread before the transaction ends, and DB work on another thread while it is active is rejected with `SqlException`.
- The transaction covers only this `Database`'s connection. Work on another `Database` (for example through `DatabaseRegistry`) commits independently and is not rolled back with it; there is no XA.
- EventBus events published with synchronous `publish()` inside the transaction are deferred by `Defer` until commit; a rollback discards them. `publishAsync` does not participate in `Defer`.

## SQL Builder

```java
Sql sql = Sql.select("u.name, o.total")
    .from("users u")
    .join("orders o").on("u.id = o.user_id")
    .where("u.active = ?", true)
    .orderBy("o.total DESC")
    .limit(10);
db.query(sql).list(UserOrderView.class);
```

`Sql` is a fluent, immutable builder. Roots are `select`, `insert`, `update`, and `delete`; clauses cover CTEs (`with`), `from`, `join` / `leftJoin` / `innerJoin`, `on`, `where` / `orWhere` / `whereNot` and their grouped variants, `groupBy` / `having`, `orderBy`, `limit` / `offset`, `union` / `unionAll`, `value`, `setExpression`, `returning`, and `onConflict(...).doUpdateSet(...)` / `doNothing()`.

`db.query(sql)` and `db.execute(sql)` render with the database's dialect and validate capability flags before execution: `RETURNING` or `ON CONFLICT` on a dialect that lacks it fails with `SqlException` instead of reaching the database. `INSERT ... RETURNING` must be consumed through `db.query(sql)` - `db.execute(sql)` discards the returned rows and reports only affected rows and one generated key. `onConflict` takes column names; the dialect renders its own spelling (`ON CONFLICT ... DO UPDATE SET ... EXCLUDED` or `ON DUPLICATE KEY UPDATE ... VALUES(...)`). Use `sql.toSql(dialect)` and `sql.args()` when executing manually.

## ORM

```java
@Table("posts")
public class Post {
    @Id @Generated private Long id;
    private String title;
    @Column("created_at") private LocalDateTime createdAt;
}

Orm orm = Orm.of(db);
orm.insert(post);               // generated id is written back
orm.findById(Post.class, 1L);   // Optional<Post>
orm.findAll(Post.class);
orm.save(post);                 // upsert
orm.update(post);
orm.delete(post);
orm.deleteById(Post.class, 1L);
```

`@Table` and `@Column` override names, `@Id` marks the key (multiple `@Id` fields form a composite key), `@Generated` marks an auto-increment column, and `@Transient` excludes a field.

| Method | Behavior |
|--------|----------|
| `insert(entity)` | INSERT; a generated key is written back onto the entity. |
| `findById(type, id...)` | `Optional<T>`. |
| `findAll(type)` / `findAll(type, FindOptions)` | All rows; `FindOptions` carries `orderBy`, `limit`, and `offset`. |
| `save(entity)` | Upsert across the whole row: an unset id inserts, a set id uses the dialect conflict grammar (`ON CONFLICT`, `ON DUPLICATE KEY`, or H2 `MERGE`). |
| `update(entity)` | Updates every non-id, non-generated, non-transient column; fails when no updatable column exists. |
| `delete(entity)` / `deleteById(type, id...)` | Deletes by id. |

## Connection Pools

```java
PoolConfig config = PoolConfig.defaults(url, user, pass);

PoolConfig tuned = PoolConfig.defaults(url, user, pass)
    .withMaxSize(20)
    .withMinIdle(5)
    .withConnectionTimeout(Duration.ofSeconds(30));
```

`PoolConfig` is a record. `defaults(url, username, password)` states every default, and per-field `withX` withers change one knob each.

| Field | Default | IoC key |
|-------|---------|---------|
| `maxSize` | `10` | `freeway.db.pool.max-size` |
| `minIdle` | `2` | `freeway.db.pool.min-idle` |
| `connectionTimeout` | `10s` | `freeway.db.pool.connection-timeout` |
| `maxLifetime` | `30min` | `freeway.db.pool.max-lifetime` |
| `maxIdleTime` | `10min` | `freeway.db.pool.max-idle-time` |
| `cleanInterval` | `2min` | `freeway.db.pool.clean-interval` |
| `healthCheckQuery` | `null` (JDBC `isValid`) | `freeway.db.pool.health-check-query` |
| `healthCheckTimeout` | `5s` | `freeway.db.pool.health-check-timeout` |
| `queryTimeout` | `15s` (`0` = no timeout) | `freeway.db.query-timeout` |

`DbModule` binds `PoolDefault` as the built-in pool. IoC pool selection uses `.primary()`: the built-in binding is not primary, so an extension module such as `HikariPoolModule` (in `freeway-ext`) that binds with `.primary()` wins the unpinned `Pool` lookup. Selection itself has no config key. For a standalone override, pass a pool through `Database.create(Database.Wiring.defaults(config).withPool(pool))`.

`Pool` exposes `borrow()`, `release(PooledConnection)`, `invalidate(PooledConnection)`, `stats()`, and `close()`; a borrowed connection must be released to the pool that borrowed it, and a foreign release fails fast with `SqlException` (see Important Behavior).

## Dialects

The dialect controls generated DDL - identifier quoting, generated/auto-increment clauses, type mapping defaults, and schema-introspection queries - plus SQL syntax capability flags and single-clause mappings (`upsert`, `for update`, offset-only pagination, `truncate`).

Selection is by id. With an explicit `freeway.db.dialect=<id>`, a user binding `Dialect@<id>` is checked first, then the built-in catalog `Dialect.ofId(id)`; an unknown id fails startup. Without config, the id is detected from the JDBC URL, and the detected id is resolved through user bindings before the built-in catalog; an H2 URL keeps the mode-aware `H2Dialect` built from its `MODE` instead. A blank URL is the only case that defaults to `PostgresDialect`; an unrecognized URL scheme fails fast instead of falling back to PostgreSQL.

```
freeway.db.dialect=company
  -> user binding Dialect@company       (lookup by id)
  -> Dialect.ofId("company")            (built-in catalog; unknown id fails)
  -> no config: detect id from jdbc:...
  -> detected id: user binding first, then the built-in
  -> PostgresDialect                    (only when the URL is blank)
```

`DbModule` binds one selected `Dialect` service; the built-in dialects are catalog entries, not four separate bindings. An H2 URL is never remapped to PostgreSQL or MySQL.

| id | Class |
|----|-------|
| `postgresql`, `postgres` | `PostgresDialect` |
| `mysql`, `mariadb` | `MySqlDialect` |
| `h2` | `H2Dialect` |
| `sqlite` | `SqliteDialect` |

Custom dialect - implement `Dialect`, bind it under an id, select it through config:

```java
binder.bind(Dialect.class).to(CompanyDialect.class).id("company");
// freeway.db.dialect=company
```

Standalone: `Database.create(Database.Wiring.defaults(config).withDialect(new CompanyDialect()))`.

`SchemaEntity` groups do not carry a dialect. The schema dialect always comes from the `Database`; a group name is a logical label used by `freeway.db.schema.groups`, not a dialect boundary.

| Capability flag | PostgreSQL | MySQL / MariaDB | H2 | SQLite |
|-----------------|------------|-----------------|----|--------|
| `supportsTransactionalDdl()` | yes | no | no | yes |
| `supportsReturning()` | yes | no | no | yes |
| `supportsOnConflict()` | yes | no | no | yes |
| `supportsMergeUpsert()` | no | no | yes | no |
| `supportsIndexIfNotExists()` | yes | no | yes | yes |

H2 uses `MERGE INTO ... KEY (...)` for `Orm.save()`; MySQL and MariaDB render `ON DUPLICATE KEY UPDATE`; PostgreSQL and SQLite use `ON CONFLICT`.

## Schema and Migrations

Freeway provides two complementary mechanisms for database evolution: **Schema** (annotation-driven, current-state DDL) and **Migration** (versioned SQL files). Schema owns "what should exist now"; Migration owns "how we got here".

### Schema - Annotation-Driven Auto-DDL

```java
Schema.ensure(db, User.class, Post.class);          // the database carries its dialect
String ddl = Schema.define(db.dialect(), User.class); // DDL text only - dialect must be explicit
```

| Current state | Action |
|---------------|--------|
| Table missing | `CREATE TABLE IF NOT EXISTS` |
| Column missing | `ALTER TABLE ADD COLUMN` |
| Index missing | `CREATE INDEX IF NOT EXISTS` (or a dialect existence check) |
| Already present | Left untouched; existing columns, types, and indexes are never dropped or altered |

`Schema.ensure()` reads `@Table`, `@Column`, `@Id`, `@Generated`, `@Transient`, and `@Index`, and also recognizes the commons validation annotations `@NotNull`, `@NotBlank`, and `@Size`. It returns the number of schema changes applied - tables created and columns added; indexes are executed but not counted. `Schema.validate(db, types...)` reports drift without applying anything.

`ensure()` is not transactional: each DDL statement runs on its own connection, so a failure mid-way leaves a partially applied schema. On dialects without transactional DDL (H2 and MySQL/MariaDB), calling it inside a user transaction is rejected with `SqlException` because each DDL statement would implicitly commit the transaction's pending work; on PostgreSQL and SQLite, wrapping it in a transaction is safe and rolls the whole schema back on failure.

### SchemaEntity - Startup Schema Groups

```java
public class AppModule implements ModuleEx {
    public void bind(Binder b) {
        b.contribute(SchemaEntity.class)
            .add(SchemaEntity.of("app", User.class, Post.class));
    }
}
```

`SchemaEntity.of(name, entityTypes...)` registers a named group. The group name is only a label; the dialect always comes from the `Database`, so a group cannot override the dialect.

`DbModule` drives the phases from its startup hook (id and HTTP ordering are under Important Behavior). In `auto` mode it runs `Schema.ensure()` for each admitted group, then runs the SQL migrations; in `validate` mode it runs the migrations first and then compares the entities with the migrated database. `freeway.db.schema.groups` is a comma-separated group filter; empty admits every group.

| `freeway.db.schema.mode` | Behavior |
|--------------------------|----------|
| `auto` (default) | At startup, converge each admitted group to the entities. |
| `validate` | Run the migrations first, then compare the entities with the database and fail startup, naming every drift. |
| `off` | Skip the schema layer; SQL migrations still run unless disabled. |

The retired `freeway.db.schema.auto` key is not honored: setting only that key fails startup and names the replacement (its old `true`/`false` mapped to `auto`/`off`); set `freeway.db.schema.mode` instead.

### Migration - Versioned SQL Files

```java
MigrationRunner runner = new MigrationRunner(db, MigrationRunner.Options.defaults());
int ran = runner.run();  // newly applied count; already-applied files are skipped
```

`MigrationRunner.Options.defaults()` covers the common case; `withEnabled`, `withPath`, `withTable`, and `withLockTtl` change one setting each.

File naming is enforced: `V` + digits + `__` + description + `.sql`. Versions may contain dots and underscores (`V1_0_0`), and timestamp versions (`V20240615`) are supported.

```
db/migration/
  V001__create_users.sql
  V002__add_email.sql
  V20240615__add_index.sql
```

- Each migration runs in its own transaction, including the tracking-table row; an already-applied version is skipped.
- Applied migrations are immutable. A SHA-256 checksum mismatch blocks startup; a pure CRLF/LF change is accepted by the normalized second comparison.
- An applied migration file that disappears from the classpath fails fast as a packaging error.
- On dialects without transactional DDL (H2 and MySQL/MariaDB), a migration that contains DDL is rejected before execution, because the DDL would commit while the checksum row would be lost. Those databases accept DML-only migrations: let the Schema layer own DDL, or use a transactional-DDL database for DDL migrations.
- Concurrent instances are serialized by a lock row in the tracking table; stale-lock takeover and owner-scoped release are described under Important Behavior.

IoC: `DbModule` binds `MigrationRunner` and runs it at startup, so no extra configuration is needed.

### Development vs Production

| | Development | Production |
|---|---|---|
| `freeway.db.schema.mode` | `auto` | `validate` |
| Entity change | Add `@Column("phone")`, then restart; the column is added. | Write `V004__add_phone.sql`, derived from the output of `Schema.define()`. |
| Startup | The schema converges. | The migration applies `V004`, then the entities are compared; drift fails startup. |

Schema is the zero-friction development loop; Migration is the auditable, precisely controlled production path. The same entities and SQL files serve both - switching between them is one config key.

## Important Behavior

- `DbModule` binds `PoolDefault` as the built-in pool.
- Release a pooled connection only to the pool that borrowed it - a foreign release fails fast with `SqlException` instead of corrupting the other pool.
- Multi-instance migrations are lock-guarded: a stale lock past `lock-ttl` is taken over conditionally, and each holder's release only removes its own lock row.
- `DbModule` contributes a runtime hook with id `freeway.db.migration`.
- The DB migration hook runs before the HTTP server hook when both are present.
- `SchemaEntity` is the contribution type for schema groups.
- Dialect resolution uses explicit config first (user binding by id, then the built-in catalog), then JDBC URL detection; `PostgresDialect` is the fallback only when the URL is blank.
