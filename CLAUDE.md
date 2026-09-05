# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

Requires JDK 25+.

```bash
mvn test                          # all core modules
mvn -pl freeway-ioc -am test      # single module + dependencies
mvn -pl freeway-http -am test
mvn -pl freeway-db -am test
mvn -pl freeway-flow -am test
mvn -pl freeway-cloud -am test
mvn test -Dtest=CoercerDefaultTest  # single test class
```

Extension modules are in [freeway-ext](https://github.com/dzb/freeway-ext).
Build core first (`mvn install`), then extensions.

JUnit 6.1.3, SLF4J 2.0.18.

## Module Dependency Graph

```
freeway-commons         zero deps
 ├─ freeway-ioc         depends on commons
 │   ├─ freeway-boot    depends on ioc
 │   ├─ freeway-http    depends on ioc + commons
 │   ├─ freeway-flow    depends on ioc + commons (no extra deps)
 │   └─ freeway-cloud   depends on ioc + commons + http (boot is test-scope only)
 └─ freeway-db          depends on commons (+ ioc, DbModule only)
```

Core modules have no external dependencies beyond SLF4J.
Engine adapters, connection pools, and MQ bridges with third-party
library integrations live in [freeway-ext](https://github.com/dzb/freeway-ext).
External HTTP engines available in freeway-ext: Undertow and Jetty;
Robaho adapter has been removed.

## Architecture Boundaries

- **`Container`** — IoC boundary only: `get(Class)`, `get(Class, String)`, `extension(Class)`, `create(Class)`, `close()`. `create()` is a factory method — full injection without caching. Created via `Freeway.create(ModuleEx...)`.
- **`AppRuntime`** — Application boundary above Container. Owns config, profiles, startup/shutdown, runtime hooks. Created via `FreewayApp.run(new String[0], ModuleEx...)`.
- **`ServiceId`** is intentionally not a public type — service ids are plain strings, normalized internally by `ServiceIds`.
- **`Defer` / `ScopedCache`** — commons-level `ScopedValue` primitives. `Defer` buffers actions for commit-time drain; `ScopedCache` caches key-value pairs with lifecycle cleanup on scope exit. IoC's thread scope is built on `ScopedCache`.
- **Scopes** declared only via `bind().scope(...)`: `SINGLETON`, `PROTOTYPE`, `THREAD`. Thread scope is entered through `Scoping.within()`.
- **`RuntimeHook`** — module-level start/stop extension. Contributed through `Contribution<RuntimeHook>`, ordered with `before/after`. `HttpModule` contributes the server hook with stable id `"freeway.http.server"`.
- **`LoggerSource`** — built-in logger service. Commons registers a JUL-backed SLF4J provider unconditionally via `META-INF/services`; at startup `LogBootstrap.ensureProvider()` probes the classpath for external SLF4J providers (Logback, Log4j, slf4j-simple) and pins the `slf4j.provider` system property so the external provider wins — the JUL provider is the fallback only when no external provider is present (or the user sets `-Dslf4j.provider` explicitly). The JUL provider reads `freeway-log.properties` from the classpath root (user-provided, not bundled) for logging configuration (console, file logging, multi-file, per-logger levels) — system properties (`-D`) override file values. All defaults are built into code. Framework code uses standard `LoggerFactory.getLogger()` everywhere.
- **`.primary()` pattern** — used for engine, pool, and dialect selection. Default implementation bound without `.primary()`; extension modules bind their alternative with `.primary()`. Container resolves the primary binding automatically — no config keys needed. Same pattern across HTTP engine (`FreewayHttpEngine` vs `UndertowEngine`) and connection pool (`PoolDefault` vs `HikariPool`). DB dialects differ: the built-in dialects are bound by id (`dialectId()`), `PostgresDialect` is the `.primary()`-bound default, and a custom dialect binds under its own id and is selected via the `freeway.db.dialect` config key — a second `.primary()` `Dialect` would be ambiguous.
- **HTTP** — Built-in engine architecture:
  - **Engine layer** (`engine/`): `FreewayHttpEngine` — virtual threads, synchronous socket I/O, HTTP/1.x + HTTP/2 h2c/h2 + WebSocket + HTTPS. HTTP/1.x session/parsing (`Http1xSession`, `Http1xParser`) and the connection wrapper (`HttpConnection`) live directly in `engine/`; sub-packages are `engine/http2/` (Http2Connection, frame serialization, HPACK) and `engine/ws/` (WebSocket frame protocol). All engine classes are implementation details. `FreewayHttpEngine` is the only one that forms public API; the other `public` types in `engine/` and its sub-packages exist only because Java has no sub-package visibility — they are intra-engine cross-subpackage contracts, not API (no stability promise).
  - **Orchestration layer** (`WebServer`): filter chain (CorsFilter → HealthFilter → custom filters → route dispatch), event publishing via `Consumer<Object>`, server lifecycle. `RequestComponents` record bundles filter config for cleaner constructors.
  - **Integration layer** (`HttpModule`): bridges `Consumer<Object>` → EventBus, registers `FreewayHttpEngine` as default.
  - `JdkHttpEngine` / `JdkHttpContext` have been removed — the built-in engine is now the only default.
  - Route path variables use `:name` or `{name}` syntax; `{name:regex}` for regex constraints.
  - `Http1xParser` uses a reusable 4KB bulk-read buffer per connection; `HttpContextImpl` writes responses into a reusable byte buffer for a single socket write.
- **DB** — `Database` is the entry point. Named params (`:name`/`$name`), programmatic transactions, built-in pooling, dialect auto-detection from JDBC URL, `DatabaseHub` for multi-datasource. Schema (annotation-driven DDL) and Migration (versioned SQL) provide complementary DB evolution.
- **Flow** — Lightweight graph orchestration engine ported from solon-flow. 7 node types (START/END/ACTIVITY/EXCLUSIVE/INCLUSIVE/PARALLEL/LOOP). JSON-based graph definitions via `Graph.fromText(json)`. Self-written expression evaluator (`ExprEvaluator`, a ~600-line recursive-descent parser) and event bus (`FlowEventBus`). Supports PlantUML export, execution tracing with pause/resume, subgraph calls (`#graphId`), and interceptor chains. Task resolution: `@bean` / `#graph` / `$meta`. Zero extra dependencies beyond commons + ioc.

## Naming Rules

- Public interfaces use the domain name directly: `Container`, `JsonCodec`, `RequestContext`.
- `ModuleEx` is the module entry-point interface — spelled `ModuleEx` (not `Module`) to avoid colliding with `java.lang.Module`.
- `XDefault` is the framework's default implementation of a role the **outside can substitute** — an extension module or adapter binds its own implementation of the same interface via `.primary()` (e.g. `PoolDefault` vs a Hikari-backed pool), or an adapter picks the default as its own baseline (extension engines construct `ExchangeMetaDefault`). Examples: `AppRuntimeDefault`, `JsonCodecDefault`, `PoolDefault`, `FlowDriverDefault`, `ExchangeMetaDefault`, `FlowEngineDefault` and the twelve cloud defaults.
- `XImpl` marks the **absence of outside substitutability** for that role: container-internal assembly pieces (`ContainerImpl`, `BindingImpl`, `DatabaseImpl`, `QueryImpl`), engine-internal components bound to the built-in engine (`HttpContextImpl`), or per-owner artifact types that coexist with other implementations (`PooledConnectionImpl` vs a pool adapter's own connection type).
- **Choosing Default vs Impl**: the deciding question is *outside substitutability* — can an extension module, adapter or user assembly choose an alternative implementation of the role (via `.primary()` binding, constructor selection, or config activation)? Yes → the framework's implementation is `XDefault`, even where the framework wires it concretely (`ExchangeMetaDefault` is embedded by the HTTP context, yet extension engines choose it as their default exchange metadata; `FlowEngineDefault` is the default flow engine). No — or the type is one of several implementations genuinely in use per use-site → `XImpl` (`HttpContextImpl` exists only inside the built-in engine; `LoggerSourceImpl` is hard-wired into the injection path).
- **Package location is orthogonal to the suffix**: `internal` is part of Freeway, and a `XDefault` may live there when the module so organizes it (`PoolDefault` sits in `db/internal` yet is substituted from outside by binding a `Pool` implementation `.primary()` — substitution never needs to reference the default class itself). What `internal` still means is "no stability promise" for callers that reference its classes across releases.
- `DefaultX` is avoided — `XDefault` keeps the interface name dominant.
- `internal` packages hold **non-public, unstable implementation details** (e.g. `ServiceIds` normalization) — the name marks "no stability promise", not "physically hidden": classes there may stay `public` (the container assembles them from sibling packages), but callers must not depend on their shape across releases.

## Injection Annotations

All in `com.jujin.freeway.ioc.annotation`: `@Inject`, `@Symbol`, `@Value`, `@PostConstruct`, `@PreDestroy`.

- `@Inject` — field/constructor/parameter injection; `@Inject("id")` for qualified injection.
- `@Symbol("key")` — strict config lookup, missing key fails.
- `@Value("${key:default}")` — expression expansion with optional default.
- `@PostConstruct` — lifecycle callback after injection is complete.
- `@PreDestroy` — lifecycle callback before the instance is destroyed.

Primary resolution uses `binding.primary()` on the binding DSL — it maps to the `@Primary` marker internally, so both forms resolve through the same marker index.

## Design Rules

- No classpath scanning. No bytecode weaving.
- Constructor injection for framework internals; field injection acceptable for app code and config values.
- Core modules keep external dependencies out. Adapter modules with third-party deps live in freeway-ext.
- Prefer small explicit APIs over future-proof abstractions.
- Keep concepts few: Module, Service, Extension, Scope, Runtime.

## Config Cascade (high to low priority)

1. CLI args (`--key=value`, `-Dkey=value`)
2. Env vars (`FREEWAY_` prefix by default; replaceable via `freeway.env.prefix` — a custom prefix passes through verbatim, `APP_SERVER_PORT` → `server.port`)
3. `application-{profile}.json`
4. `application-{profile}.properties`
5. `application.json`
6. `application.properties`

CLI keys without a dot (e.g. `--profile=dev`) auto-receive the `freeway.`
prefix, so `--profile=dev` and `--freeway.profile=dev` are equivalent.
Dotted keys (`--app.name=foo`) pass through unchanged.
Activate profiles: `--profile=dev`

The framework owns config reading: `ConfigLoaderDefault` returns an
`AppConfigDefault` whose file tier merges the packaged classpath baseline
with filesystem overrides (the same standard file names in the working
directory, plus any files listed in the `freeway.config.file` system
property). Filesystem wins, later files win; the file tier is
WatchService-hot-reloaded and reaches the symbol chain because each
`SymbolProvider` lookup reads the current snapshot — no restart, no push
API. Profile activation stays startup-static.
SymbolProvider precedence is declared via `order()` (CLI 0 > JVM system
properties 5 > env 10 > cloud secret store 15 > files 20) — four tiers,
each answering one ownership question (app launch args; the
process-environment band, JVM-level verbatim overrides above boot's
declared env mapping; the deployable file baseline); never module
install order. All providers live in one ordered list. `SymbolSource` is
the single read entry (raw strings); typed reading is explicit
post-processing — declare a `SymbolSpec`, parse the resolved value
(`spec.parse(symbols.resolve(spec.key(), null))`). `AppConfig` is not a
reader: it owns profiles, the cascade snapshot and the hot-reload
lifecycle. Modules with domain-specific sources (secrets, ...) contribute
their own `SymbolProvider` via `Extension` and slot in by declaring their
order.

## Commit Rules

- Never include `Co-Authored-By`, AI tool names, or any form of AI attribution in commit messages.
- Commit messages describe the change itself, never the process or tooling used.
- All commits appear under the user's name only.

## Lifecycle notes

- `Container.close()` runs `@PreDestroy` before sealing the container, so
  cleanup code can still resolve services; only realized singletons are
  cleaned up (a never-invoked lazy proxy gets no `@PreDestroy`).
- Thread-scope values stay registered after close so scope-exit hooks still
  clean them up.

## Further Reading

- [docs/DEVELOPER-GUIDE.md](docs/DEVELOPER-GUIDE.md) — comprehensive guide: modules, HTTP, DB, config, boot
- [docs/](docs/) — config samples, DB usage, Defer summary
