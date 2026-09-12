# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

Requires JDK 25+ (JUnit 6.1.3, SLF4J 2.0.18).

```bash
mvn test                            # all core modules
mvn -pl freeway-boot -am test       # one module + its upstream deps
mvn test -Dtest=CoercerDefaultTest  # one test class
```

Third-party adapters (Undertow/Jetty engines, HikariCP, Kafka) live in
[freeway-ext](https://github.com/dzb/freeway-ext): `mvn install` the core first.

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

## Architecture Boundaries

- **`Container`** — IoC boundary only: `get(type[, id | markers])`,
  `isActiveBinding`, `extension`, `create` (a factory — full injection without
  caching), `modules()` (the loaded module tree in bind order), `close()`.
  Created via `Freeway.create(ModuleEx...)`.
- **`AppRuntime`** — application boundary above Container: owns config,
  profiles, startup/shutdown and runtime hooks. Created via
  `FreewayApp.run(args, ModuleEx...)`.
- **`ModuleEx`** — a module declares its bindings in `bind(Binder)` and its
  composition in `subModules()`, a stable view the framework reads more than
  once. The container resolves the whole tree before binding anything: a module
  binds before its sub-modules, siblings in declaration order. The same
  instance reached twice binds once; two instances of one class fail startup.
- **`ServiceId`** is intentionally not a public type — service ids are plain
  strings, normalized internally by `ServiceIds`.
- **Scopes** are declared only through `bind().scope(...)`: `SINGLETON`,
  `PROTOTYPE`, `THREAD`. Thread scope is entered through `Scoping.within(...)`,
  the `Scoping` service obtained from the container.
- **`Defer` / `ScopedCache`** (`commons.scoped`, built on `ScopedValue`) —
  `Defer` buffers actions for commit-time drain, `ScopedCache` caches
  key/value pairs with lifecycle cleanup on scope exit. IoC's thread scope is
  built on `ScopedCache`.
- **`RuntimeHook`** — module-level start/stop extension, contributed through
  `Contribution<RuntimeHook>` and ordered with `before/after`; a reference to
  an unknown hook id fails startup. `HttpModule` contributes the server hook
  under the stable id `"freeway.http.server"`.
- **`.primary()`** — one substitution pattern for engine, pool and dialect
  selection: the framework's default binds without `.primary()`, an adapter
  binds its alternative with it, and the container resolves the primary with no
  config key (`FreewayHttpEngine` vs Undertow, `PoolDefault` vs Hikari).
  Dialects are instead selected by id: `PostgresDialect` is the primary
  `Dialect`, and a custom dialect binds under its own id for
  `freeway.db.dialect` (a second primary would be ambiguous).
- **Logging** — commons registers a JUL-backed SLF4J provider; at startup
  `LogBootstrap.ensureProvider()` probes the classpath for an external provider
  (Logback, Log4j, slf4j-simple) and pins `slf4j.provider` so that one wins,
  leaving JUL as the fallback. That provider reads the user-provided
  `freeway-log.properties` from the classpath root (not bundled in the JAR);
  `-D` values override file values, and all defaults are built into code.
  Framework code uses `LoggerFactory.getLogger()`.
- **HTTP** — `FreewayHttpEngine` (virtual threads, synchronous socket I/O,
  HTTP/1.x + HTTP/2 h2c/h2 + WebSocket + HTTPS) is the only engine type that
  forms public API; everything else in `engine/` and its `http2/`, `ws/`
  sub-packages is an intra-engine contract with no stability promise.
  `WebServer` orchestrates it (CorsFilter → HealthFilter → custom filters →
  route dispatch, events published through a `Consumer<Object>`), and
  `HttpModule` bridges that consumer to the EventBus while registering the
  engine as default. Route path variables use `:name` or `{name}`, with
  `{name:regex}` for constraints.
- **DB** — `Database` is the entry point: named params (`:name`/`$name`),
  programmatic transactions, built-in pooling, dialect auto-detection from the
  JDBC URL, `DatabaseHub` for multi-datasource. Schema (annotation-driven DDL)
  and Migration (versioned SQL) are complementary evolution paths.
- **Flow** — graph orchestration ported from solon-flow: 7 node types
  (START/END/ACTIVITY/EXCLUSIVE/INCLUSIVE/PARALLEL/LOOP), JSON definitions via
  `Graph.fromText(json)`, a hand-written `ExprEvaluator` and `FlowEventBus`,
  PlantUML export, tracing with pause/resume, subgraph calls (`#graphId`) and
  interceptor chains. Tasks resolve as `@bean` / `#graph` / `$meta`. Zero
  dependencies beyond commons + ioc.

## Naming Rules

- Public interfaces use the domain name: `Container`, `JsonCodec`, `Route`.
- `ModuleEx` is the module entry point — spelled that way to avoid colliding
  with `java.lang.Module`.
- **`XDefault` vs `XImpl`** — the deciding question is whether the *outside can
  substitute* the implementation for that role:
  - `XDefault` — it can: an extension binds an alternative with `.primary()`,
    an adapter builds on the default, or config activates another one.
    Examples: `AppRuntimeDefault`, `JsonCodecDefault`, `PoolDefault`,
    `FlowDriverDefault`, `FlowEngineDefault`, `ExchangeMetaDefault` and the
    twelve cloud defaults.
  - `XImpl` — it cannot: container-internal assembly (`ContainerImpl`,
    `BindingImpl`, `DatabaseImpl`, `QueryImpl`), engine-internal components
    (`HttpContextImpl`), or per-owner types that coexist with other
    implementations (`PooledConnectionImpl`). A type stays `XDefault` even
    where the framework wires it concretely.
- `DefaultX` is avoided — `XDefault` keeps the interface name dominant.
- Package location is orthogonal to the suffix: `internal` means "no stability
  promise" for callers, not "physically hidden" — classes there stay `public`
  where sibling packages assemble them, and an `XDefault` may live there
  (`PoolDefault` sits in `db/internal` yet is substituted from outside).
- An owner's collaborators live in the owner's package, package-private: a
  type is `public` only when another package must assemble or substitute it.
  `ioc.internal` therefore holds exactly one public type — `ContainerImpl`,
  the thing `Freeway` constructs.

## Injection Annotations

All in `com.jujin.freeway.ioc.annotation`:

- `@Inject` — field/constructor/parameter injection; `@Inject("id")` for
  qualified injection.
- `@Symbol("key")` — strict config lookup; a missing key fails.
- `@Value("${key:default}")` — expression expansion with optional default.
- `@PostConstruct` / `@PreDestroy` — lifecycle callbacks after injection
  completes and before the instance is destroyed.

`binding.primary()` maps to the `@Primary` marker internally, so both forms
resolve through the same marker index.

## Design Rules

- No classpath scanning. No bytecode weaving.
- Constructor injection for framework internals; field injection acceptable for
  app code and config values.
- Core modules keep external dependencies out; adapters with third-party deps
  live in freeway-ext.
- Prefer small explicit APIs over future-proof abstractions.
- **Optional inputs**: one or two of them use a documented overload ladder, each
  step stating what it adds (`RemoteCaller.invoke`); three or more use a
  parameter record with `defaults()` and per-field withers
  (`CloudHttpClientDefault.Wiring`), so a call site cannot drift between
  overloads. A record's canonical constructor changes shape whenever a component
  is added, so a record used for adapter assembly keeps its previous arity as a
  delegating constructor — an already-compiled adapter must not break on a new
  knob (the ext engine tests did, once).
- Keep concepts few: Module, Service, Extension, Scope, Runtime.

## Config Cascade

Every source is a `SymbolProvider` with a declared `order()`; the symbol chain
consults them in ascending order, so precedence is declared rather than derived
from module install order:

| order | tier | source |
|-------|------|--------|
| 0 | CLI args | `--key=value`, `--key value`, `--key` (boolean), `-Dkey=value` |
| 5 | JVM system properties | `-Dkey=value` set before the main class, verbatim keys |
| 10 | environment | `FREEWAY_*` mapped to keys |
| 15 | module-contributed | e.g. the cloud secret store |
| 20 | config files | classpath baseline + filesystem overrides |

**CLI shortcut:** a key without a dot takes the `freeway.` prefix
(`--profile=dev` ≡ `--freeway.profile=dev`); dotted keys pass through
unchanged. A positional argument is ignored with a WARN; a bare `--` / `-D` or
a key containing `=` is rejected.

**Files:** each source merges `application.properties` → `application.json` →
`application-{profile}.properties` → `application-{profile}.json`, later
winning — inside the profile band the format beats the profile order. Over that
packaged classpath baseline win the same file names in the working directory,
then any files named by the `freeway.config.file` bootstrap key
(comma-separated). That file tier is WatchService-hot-reloaded: the tier is
swapped in place while its `SymbolProvider` reads the live snapshot on every
lookup, so an edit is visible without a restart or a push API. With nothing to
watch — no override file whose directory exists — the tier is simply static.
Profile activation is startup-static.

**Bootstrap keys** configure the cascade itself, so they have exactly two
channels: the JVM system property `-D<key>`, then the fixed
`FREEWAY_`-spelled environment variable. They are `freeway.env.prefix`
(default `FREEWAY_`) and `freeway.config.file`; a value declared in a config
file or passed as a CLI argument is ignored and named in a startup WARN.
`freeway.profile` is *not* bootstrap-only — it is read from the base layers
(files, environment, CLI), while `-Dfreeway.profile` and an extra file named by
`freeway.config.file` set the key without activating a profile.

**Environment mapping:** the prefix is stripped and `_` becomes `.`; every
other character — a hyphen above all — is carried through verbatim, so
`key-store` and `key.store` stay distinct keys. The default `FREEWAY_` maps
into the `freeway.*` namespace (`FREEWAY_HTTP_SERVER_PORT` →
`freeway.http.server.port`); a custom prefix passes through verbatim
(`APP_SERVER_PORT` → `server.port`).

**Reading:** `SymbolSource` is the single read entry, returning raw strings.
Typed reading is explicit post-processing — declare a `SymbolSpec` and parse
the resolved value (`spec.parse(symbols.resolve(spec.key(), null))`, or the
one-step `resolve(spec)` once the container `Coercer` is wired). `AppConfig` is
not a reader and exposes no value map: it owns the active profiles, the symbol
sources it contributes (`providers()`) and the hot-reload lifecycle. A module
with a domain-specific source (secrets, ...) contributes a `SymbolProvider`
through `binder.contribute(SymbolProvider.class)` and slots in by declaring its
`order()`.

## Commit Rules

- Never include `Co-Authored-By`, AI tool names, or any form of AI attribution in commit messages.
- Commit messages describe the change itself, never the process or tooling used.
- All commits appear under the user's name only.

## Lifecycle notes

- `Container.close()` runs `@PreDestroy` before sealing the container, so
  cleanup code can still resolve services; only realized singletons are cleaned
  up (a never-invoked lazy proxy gets no `@PreDestroy`).
- Thread-scope values stay registered after close so scope-exit hooks still
  clean them up.

## Further Reading

- [docs/DEVELOPER-GUIDE.md](docs/DEVELOPER-GUIDE.md) — comprehensive guide: modules, HTTP, DB, config, boot
- [docs/freeway-config.md](docs/freeway-config.md) — every config key, by module
- [docs/](docs/) — config samples, DB usage, Defer summary
