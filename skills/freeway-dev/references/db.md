# DB Reference

Examples below are minimal snippets. They omit imports and app-specific domain types.

## Stable API

- `DatabaseBuilder`
- `Database`
- `DatabaseHub`
- `Orm`
- `PoolConfig`
- `Pool`
- `DbModule`
- `Dialect`
- `PostgresDialect`, `MySqlDialect`, `SqliteDialect`
- `Schema`
- `SchemaEntity`
- `MigrationRunner`

## Standalone Usage

```java
PoolConfig config = PoolConfig.defaults("jdbc:h2:mem:test", "sa", "");
Database db = new DatabaseBuilder().config(config).build();
Orm orm = Orm.of(db);
```

## IoC Usage

```java
FreewayApp.run(new String[0], new AppModule(), new DbModule());
```

## Configuration Keys

- `freeway.db.url`
- `freeway.db.username`
- `freeway.db.password`
- `freeway.db.dialect`
- `freeway.db.schema.auto`
- `freeway.db.schema.groups`
- `freeway.db.migration.enabled`
- `freeway.db.migration.path`
- `freeway.db.migration.table`
- `freeway.db.migration.lock-ttl` (stale-lock takeover budget, `0`/negative disables)

## Important Behavior

- `DbModule` binds `PoolDefault` as the built-in pool.
- Release a pooled connection only to the pool that borrowed it — a foreign release fails fast with `SqlException` instead of corrupting the other pool.
- Multi-instance migrations are lock-guarded: a stale lock past `lock-ttl` is taken over conditionally, and each holder's release only removes its own lock row.
- `DbModule` contributes a runtime hook with id `freeway.db.migration`.
- The DB migration hook runs before the HTTP server hook when both are present.
- `SchemaEntity` is the contribution type for schema groups.
- Dialect resolution uses config first, then JDBC URL detection, then the default binding.
