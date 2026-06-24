# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

Requires JDK 25+.

```bash
mvn test                          # all core modules
mvn -pl freeway-ioc -am test      # single module + dependencies
mvn -pl freeway-http -am test
mvn -pl freeway-db -am test
```

Extension modules are in [freeway-ext](https://github.com/dzb/freeway-ext).
Build core first (`mvn install`), then extensions.

JUnit 5.12, SLF4J 2.0.17.

## Module Dependency Graph

```
freeway-commons         zero deps
 ├─ freeway-ioc         depends on commons
 │   ├─ freeway-boot    depends on ioc
 │   └─ freeway-http    depends on ioc (+ commons transitive)
 └─ freeway-db          depends on commons (ioc optional)
```

Core modules are in this repository and have zero external dependencies.
Engine adapters, connection pools, and MQ bridges with third-party
library integrations live in [freeway-ext](https://github.com/dzb/freeway-ext).
Only Undertow remains as an external HTTP engine; Robaho and Jetty
adapters have been removed.

## Architecture Boundaries

- **`Container`** — IoC boundary only: `get(Class)`, `get(Class, String)`, `close()`. Created via `Freeway.create(Module2...)`.
- **`AppRuntime`** — Application boundary above Container. Owns config, profiles, startup/shutdown, runtime hooks. Created via `FreewayApp.run(new String[0], Module2...)`.
- **`ServiceId`** is intentionally not a public type — service ids are plain strings, normalized internally by `ServiceIds`.
- **`Defer` / `ScopedCache`** — commons-level `ScopedValue` primitives. `Defer` buffers actions for commit-time drain; `ScopedCache` caches key-value pairs with lifecycle cleanup on scope exit. IoC's thread scope is built on `ScopedCache`.
- **Scopes** declared only via `bind().scope(...)`: `SINGLETON`, `PROTOTYPE`, `THREAD`. Thread scope is entered through `Scoping.within()`.
- **`RuntimeHook`** — module-level start/stop extension. Contributed through `Contribution<RuntimeHook>`, ordered with `before/after`. `HttpModule` contributes the server hook with stable id `"freeway.http.server"`.
- **`LoggerSource`** — built-in logger service. Commons provides a JUL-backed SLF4J provider via standard `META-INF/services` discovery; activates only when no external SLF4J provider is detected. Framework code uses standard `LoggerFactory.getLogger()` everywhere.
- **`.primary()` pattern** — used for engine, pool, and dialect selection. Default implementation bound without `.primary()`; extension modules bind their alternative with `.primary()`. Container resolves the primary binding automatically — no config keys needed. Same pattern across HTTP engine (`FreewayHttpEngine` vs `UndertowEngine`), connection pool (`PoolDefault` vs `HikariPool`), and DB dialect (`PostgresDialect` vs custom).
- **HTTP** — `FreewayHttpEngine` is the built-in engine (virtual threads, synchronous I/O, HTTP/1.1 + HTTP/2 h2c/h2 + WebSocket + HTTPS). `WebServer` has explicit `start()`/`stop()`. In boot, the `HttpModule` runtime hook handles this. In tests using `Container` directly, start/stop the server explicitly. HttpParser's `bodyStream()` provides the request body stream including any bytes buffered past the header boundary.
- **DB** — `Database` is the entry point. Named params (`:name`/`$name`), programmatic transactions, built-in pooling, dialect auto-detection from JDBC URL, `DatabaseHub` for multi-datasource. Schema (annotation-driven DDL) and Migration (versioned SQL) provide complementary DB evolution.

## Naming Rules

- Public interfaces use the domain name directly: `Container`, `JsonCodec`, `RequestContext`.
- Framework-provided implementations use `XDefault`: `AppRuntimeDefault`, `JsonCodecDefault`, `RequestContextDefault`, `CoercerDefault`.
- `DefaultX` is avoided — `XDefault` keeps the interface name dominant.
- `Impl` is reserved for uninteresting concrete implementations where no default strategy is being expressed.
- Internal normalization helpers stay internal (e.g., `ServiceIds`).

## Injection Annotations

All in `com.jujin.freeway.ioc.annotation`: `@Inject`, `@Named`, `@Symbol`, `@Value`, `@PostConstruct`, `@PreDestroy`.

- `@Inject` — field/constructor/parameter injection; `@Inject("id")` for qualified injection.
- `@Named("id")` — alias for `@Inject("id")`, qualifier by binding id.
- `@Symbol("key")` — strict config lookup, missing key fails.
- `@Value("${key:default}")` — expression expansion with optional default.
- `@PostConstruct` — lifecycle callback after injection is complete.
- `@PreDestroy` — lifecycle callback before the instance is destroyed.

Primary resolution uses `binding.primary()` on the binding DSL, not an annotation.

## Design Rules

- No classpath scanning. No bytecode weaving.
- Constructor injection for framework internals; field injection acceptable for app code and config values.
- Core modules keep external dependencies out. Adapter modules with third-party deps live in freeway-ext.
- Prefer small explicit APIs over future-proof abstractions.
- Keep concepts few: Module, Service, Extension, Scope, Runtime.

## Config Cascade (high to low priority)

1. CLI args (`--key=value`, `-Dkey=value`)
2. Env vars (`FREEWAY_` prefix)
3. `application-{profile}.json`
4. `application-{profile}.properties`
5. `application.json`
6. `application.properties`

CLI keys without a dot (e.g. `--profile=dev`) auto-receive the `freeway.`
prefix, so `--profile=dev` and `--freeway.profile=dev` are equivalent.
Dotted keys (`--app.name=foo`) pass through unchanged.
Activate profiles: `--profile=dev`

## Commit Rules

- Never include `Co-Authored-By`, AI tool names, or any form of AI attribution in commit messages.
- Commit messages describe the change itself, never the process or tooling used.
- All commits appear under the user's name only.

## Further Reading

- [docs/DEVELOPER-GUIDE.md](docs/DEVELOPER-GUIDE.md) — comprehensive guide: modules, HTTP, DB, config, boot
- [docs/](docs/) — config samples, DB usage, Defer summary
