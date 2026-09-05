# Repository Guidelines

Freeway is a JDK 25+ multi-module Maven project. Keep changes scoped, explicit,
and convention over configuration.

## Build

```
mvn test                           # all core modules
mvn -pl freeway-ioc test           # single module
mvn -pl freeway-http -am test      # module + upstream deps
mvn -pl freeway-cloud -am test     # module + upstream deps
```

## Module Map

| Module | Purpose | Dependencies |
|--------|---------|-------------|
| `freeway-commons` | JSON, coercion, defer, scoped cache, validation, logging | zero |
| `freeway-ioc` | Container, binding DSL, scopes, injection, extensions, symbol config | commons |
| `freeway-boot` | Launcher, runtime lifecycle, profiles, config cascade (hot reload) | ioc |
| `freeway-http` | Routing, built-in HTTP engine, WebSocket, SSE | ioc + commons |
| `freeway-db` | JDBC, ORM, pooling, transactions, migrations | commons (+ ioc, DbModule only) |
| `freeway-flow` | Graph workflow engine — 7 node types, v2 DAG format, `!marker` task resolution | ioc + commons |
| `freeway-cloud` | Cloud-native foundation — discovery, remote invocation (JDK HttpClient), observability, resilience, health, secrets, storage | ioc + commons + http (+ boot, test) |

Extension adapters (Undertow, Jetty, HikariCP, Kafka) live in
[freeway-ext](https://github.com/dzb/freeway-ext). Core modules have zero
external dependencies.

## Naming

- Public interfaces use the domain name: `Container`, `JsonCodec`, `Route`.
- `XDefault` is the framework's default implementation of a role the **outside
  can substitute** — via `.primary()` binding, constructor selection, or config
  activation. `XImpl` marks the **absence of outside substitutability**:
  container-internal assembly pieces (`ContainerImpl`, `DatabaseImpl`),
  engine-internal components (`HttpContextImpl`), or per-owner artifact types
  that coexist with other implementations (`PooledConnectionImpl`).
- Package location is orthogonal to the suffix: `internal` is part of Freeway
  and marks "no stability promise" for callers, not a visibility gate — classes
  there may stay `public` when sibling packages assemble them. A `XDefault`
  may live in `internal` when the module so organizes it (`PoolDefault` in
  `db/internal` is still substituted from outside via `.primary()`, which never
  references the class itself).
- `DefaultX` is avoided — `XDefault` keeps the interface name dominant.

## Design Rules

- No classpath scanning. No bytecode weaving.
- Constructor injection for framework internals; field injection acceptable for
  app code and config values.
- Keep core modules free of external dependencies.
- Prefer small explicit APIs over future-proof abstractions.

## Testing

JUnit 6.1.3. Tests use `*Test` suffix, live beside the module they cover.
Add regression coverage for failure modes on resource and lifecycle boundaries.

## Regressions to Watch

- **Static files**: keep resolved paths inside the mount root; disallow symlink
  traversal.
- **Connections**: release pooled connections exactly once, including exception
  paths and repeated `close()`.
- **Runtime hooks**: fail startup on invalid hook configuration (don't silently
  skip).
- **SQL parameters**: respect strings, comments, PostgreSQL `::` casts, and
  repeated named parameters.

## Further Reading

- [CLAUDE.md](CLAUDE.md) — detailed architecture boundaries, annotations,
  config cascade, and Claude-specific guidance.
- [docs/DEVELOPER-GUIDE.md](docs/DEVELOPER-GUIDE.md) — comprehensive usage
  guide for all modules.
- [docs/](docs/) — config samples, DB usage, and feature summaries.
