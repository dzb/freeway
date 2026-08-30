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
| `freeway-boot` | Launcher, runtime lifecycle, profiles, config cascade | ioc |
| `freeway-http` | Routing, built-in HTTP engine, WebSocket, SSE | ioc |
| `freeway-db` | JDBC, ORM, pooling, transactions, migrations | commons |
| `freeway-flow` | Graph workflow engine — 7 node types, v2 DAG format, `!marker` task resolution | ioc + commons |
| `freeway-cloud` | Cloud-native foundation — discovery, remote invocation (JDK HttpClient), dynamic config, observability, resilience, health, secrets, storage | ioc + commons + boot + http |

Extension adapters (Undertow, Jetty, HikariCP, Kafka) live in
[freeway-ext](https://github.com/dzb/freeway-ext). Core modules have zero
external dependencies.

## Naming

- Public interfaces use the domain name: `Container`, `JsonCodec`, `Route`.
- Framework defaults use `XDefault`: `AppRuntimeDefault`, `JsonCodecDefault`.
- `Impl` is reserved for uninteresting concrete implementations.
- Internal normalization helpers stay internal (`ServiceIds`).

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
