# Architecture

How Freeway is put together — module boundaries, the framework's internal
contracts, the config cascade mechanics and lifecycle rules. It is the
architecture counterpart of [AGENTS.md](../AGENTS.md), which holds the repo
conventions (build, naming, design rules, testing, commit rules); per-module
*usage* is the [DEVELOPER-GUIDE](DEVELOPER-GUIDE.md).

Read this when changing framework internals; it is deliberately not
auto-loaded into agent context, so a task that touches module boundaries,
hooks, config resolution or lifecycle should open it.

## Module Dependency Graph

```
freeway-commons         zero deps
 ├─ freeway-ioc         depends on commons
 │   ├─ freeway-boot    depends on ioc + commons
 │   ├─ freeway-http    depends on ioc + commons (boot is test-scope only)
 │   ├─ freeway-flow    depends on ioc + commons (no extra deps)
 │   └─ freeway-cloud   depends on ioc + commons + http (boot is test-scope only)
 └─ freeway-db          depends on commons (+ ioc, DbModule only)
```

## Architecture Boundaries

- **`Container`** — IoC boundary only: `get(type[, id | markers])`,
  `isActiveBinding`, `extension`, `create` (a factory — full injection without
  caching), `moduleTree()` (the loaded `ModuleNode`: structure via `children()`
  / `render()`, bind order via `bindOrder()`), `close()`.
  Created via `Freeway.create(ModuleEx...)` or `Freeway.create(ModuleNode)`.
- **`AppRuntime`** — application boundary above Container: owns config,
  profiles, startup/shutdown and runtime hooks. Created via
  `FreewayApp.run(args, ModuleEx...)`.
- **`ModuleEx`** — declares its bindings in `bind(Binder)`; how it is placed is
  not its concern. **`ModuleNode`** is the composition — an immutable tree built
  at the entry point (`app` / `of`), validated while it is built: the same
  instance reached twice collapses, two declarations of one module class fail
  with both paths named, cycles are refused (through values and through
  `@SubModule`). A module whose class declares `@SubModule` is a **bundle**: its
  submodules follow it in the tree as static metadata the entry point reads, and
  any submodule can be placed on its own instead. Each node holds its
  declaration — a class to instantiate at load time, or a configured instance
  (the only form a lambda/anonymous module can take) — so composition runs no
  module constructor and the same class-only tree can be loaded by more than one
  container. The container resolves and binds the tree's module nodes in
  pre-order (the application root binds nothing, a module binds before the
  modules below it, siblings in declaration order) and holds the value it bound.
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
  `freeway-logging.properties` from the classpath root (not bundled in the JAR);
  `-D` values override file values, and all defaults are built into code.
  Framework code uses `LoggerFactory.getLogger()`.
- **HTTP** — `FreewayHttpEngine` (virtual threads, synchronous socket I/O,
  HTTP/1.x + HTTP/2 h2c/h2 + WebSocket + HTTPS) is the only engine type that
  forms public API; everything else in `engine/` and its `http2/`, `ws/`
  sub-packages is an intra-engine contract with no stability promise.
  `HttpServer` orchestrates it (CorsFilter → HealthFilter → custom filters →
  route dispatch, events published through a `Consumer<Object>`), and
  `HttpModule` bridges that consumer to the EventBus while registering the
  engine as default. `HttpModule` is the *only* assembler of `HttpServer`:
  without boot a caller places the module in a lightweight container
  (`Freeway.create(new HttpModule(), …)`) and overrides the `HttpEngine` /
  `HttpServerConfig` bindings with `.primary()` — there is no second wiring to
  drift from the first. The transport verdict is the engine's (`HttpEngine.secure()`),
  so `HttpServer.secure()` reports what is actually terminating TLS rather than
  re-deriving a config presence rule. Structurally the server has two halves:
  the **assembly half** before start — four anchors: `HttpEngine` (capability),
  `HttpServerConfig` (transport declaration), `HttpPipeline` (handling
  declaration), `HttpServer` (the one derivation) — and the **runtime half**
  during handle: the seam, `ExchangeHandler` (behaviour) + `HttpContext`
  (data). The two declarations cross the seam in opposite directions: config
  rides `engine.start(config, handler)` down into the engine, the pipeline is
  compiled into the `ExchangeHandler` above it and never crosses intact, and
  `HttpContext` is where both meet per request (writers partitioned by phase).
  Route path variables use `:name` or
  `{name}`, with `{name:regex}` for constraints.
- **DB** — `Database` is the entry point: named params (`:name`/`$name`),
  programmatic transactions, built-in pooling, dialect auto-detection from the
  JDBC URL, `DatabaseRegistry` for multi-datasource. Schema (annotation-driven DDL)
  and Migration (versioned SQL) are complementary evolution paths.
- **Flow** — in-JVM graph orchestration (lineage: solon-flow 4.0.2, Apache 2.0;
  schema and semantics are freeway-native): 7 node types
  (START/END/ACTIVITY/EXCLUSIVE/INCLUSIVE/PARALLEL/LOOP), JSON definitions via
  `Graph.fromText(json)` (`version=3`), a hand-written `ExprEvaluator` and
  `FlowEventBus`, PlantUML export, subgraph calls (`#graphId`), contributed
  interceptor chains, and an iterative frontier walk (no path-length stack
  limit). `GraphSpec.create()` is a boot-time gate: cycles, bad references,
  unknown task vocabulary, non-compiling `when` expressions and bad `join`
  declarations all fail the build. Branch writes are isolated by a
  `PARALLEL` node's `join` meta (`merge`, default — with conflict detection —
  or `shared`). Tasks resolve from a closed vocabulary — `@name` (IoC binding
  id) or `#graph` (nested subflow) — plus a node's `data` field for static
  values; there is no marker/`$meta` string syntax. The module runs in-process;
  it is not a durable workflow engine and offers no pause/resume. Zero
  dependencies beyond commons + ioc.

## Injection Annotations

All in `com.jujin.freeway.ioc.annotation`:

- `@Inject` — field/constructor/parameter injection; `@Inject("id")` for
  qualified injection.
- `@Symbol("key")` — strict config lookup; a missing key fails.
- `@Symbol("${key:default}")` — expression expansion with optional default.
- `@PostConstruct` / `@PreDestroy` — lifecycle callbacks after injection
  completes and before the instance is destroyed.

`binding.primary()` maps to the `@Primary` marker internally, and the marker is
the only record of "primary": `.primary()`, `@Primary` on the implementation
class and a module-level `@Marker(Primary.class)` select the same binding for a
lookup by type and for a lookup by marker.

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

**Two override files carrying the same key** is a silent override (the later
file wins) whose loser is invisible in either file, so `AppConfigDefault` names
both in a startup WARN — once per key and file pair, so a hot reload does not
repeat it. Shadowing a *packaged* baseline value is not reported: that is what
the file tier is for. Two properties follow for deployments that split config
per module: name the files in the order they should win, and keep each key in
one file.

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
one-step `resolve(spec)`, which parses through the chain's `Coercer`). There is one
chain implementation, and the container builds it with its own system-properties
tier and its own `Coercer`; a caller without a container (an adapter, a test)
assembles the same chain itself and gets the same semantics —
`SymbolSource.of(coercer, SymbolProvider.systemProperties())`. `AppConfig` is
not a reader and exposes no value map: it owns the active profiles, the symbol
sources it contributes (`providers()`) and the hot-reload lifecycle. A module
with a domain-specific source (secrets, ...) contributes a `SymbolProvider`
through `binder.contribute(SymbolProvider.class)` and slots in by declaring its
`order()`.

## Lifecycle notes

- `Container.close()` runs `@PreDestroy` before sealing the container, so
  cleanup code can still resolve services; only realized singletons are cleaned
  up (a never-invoked lazy proxy gets no `@PreDestroy`).
- Thread-scope values stay registered after close so scope-exit hooks still
  clean them up.
