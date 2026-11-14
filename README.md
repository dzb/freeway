# Freeway Framework

<div align="left">
  <img width="96" src=".github/assets/logo.png" alt="Freeway Framework Logo">
  <p>Modern · Concise · Clean · Consistent · Solid · Full-Featured · Composition-Only · Agentic-Friendly</p>
</div>

[![Maven Central](https://img.shields.io/maven-central/v/com.jujin8.freeway/freeway-parent?label=Maven%20Central&color=blue)](https://central.sonatype.com/artifact/com.jujin8.freeway/freeway-parent)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-25%2B-orange)](https://jdk.java.net/25/)
[![Dependencies](https://img.shields.io/badge/deps-0-brightgreen)]()

**A modern, concise, compose-first Java application framework for JDK 25+.**
IoC, HTTP, DB, Flow and Cloud are one coherent stack: no classpath scanning, no
bytecode weaving, and no runtime dependencies beyond the SLF4J API.

Freeway is an exercise in engineering taste: every API states its intent, every
concept pulls its weight, and **every shared fact has one owner**. The result is
a framework that is complete without being bloated — and predictable enough for
both humans and coding agents.

> **Latest release: 1.5.7** · **Development line: 2.0.0-SNAPSHOT**
> See the [1.5.7 release notes](docs/releases/release-notes-1.5.7.md) and
> [CHANGELOG.md](CHANGELOG.md) for what changed.

## Why Freeway

The words in the banner are design constraints, not marketing copy. Each one has
a concrete refusal behind it.

| Standard | Why it is non-negotiable | Evidence in the codebase |
|---|---|---|
| **Modern** | JDK 25 is the baseline, not a compatibility tax. Freeway uses records, sealed types, pattern switches and `ScopedValue` directly instead of emulating older Java. | `maven.compiler.release=25`; HTTP/1.1, HTTP/2 and WebSocket in the built-in engine; `ScopeBoundary` on `ScopedValue`; sealed `ResponseBody` and `Http2Frame` families. |
| **Concise** | One accurate verb per action. Aliases and overloads that do different things with the same name make call sites ambiguous and documentation endless. | `find` returns `Optional`, `require` throws; `withX` returns a new value; `HttpResponse.body/text/json/file/sendText`; `Row.asString/asInt/asLong`; no compatibility aliases. |
| **Clean** | Every fact has one owner. If two layers can decide the same thing, they will eventually disagree. | `ResponsePlan` + `HttpTransport` own response execution; `RequestHead` + `RequestViewSupport` own request reads; `FlowContextState` keeps execution writes out of the public read interface; no scanning or bytecode weaving. |
| **Composition-only** | A framework should provide contracts, not a base class to inherit from. Subclassing couples callers to implementation history and lets each adapter drift differently. | No Freeway class extends another Freeway class. Extension seams such as `HttpTransport`, `FlowDriver`, `ServiceRegistry` and `ServiceDiscovery` are interfaces; implementations are final and selected by the container. The only class `extends` clauses in `src/main` are standard-library boundary bases (exceptions, streams/collections, TLS, logging, publication). |
| **Consistent** | A reader should not have to relearn the framework in every module. The same action has the same shape everywhere. | One `find`/`require` contract, one `metadata()`/`putMetadata()` family, one `withX` convention, one `ConfigKeys` per module, one lifecycle, and `ModuleDeclaration` as the single module-composition value. |
| **Solid** | Safety comes from types and tests, not from comments asking callers to be careful. Failure modes should be unrepresentable or covered. | Sealed pattern switches force exhaustive dispatch; `ErrorMapper` returns data while `HttpServer` owns the only write path; `HttpTransport` owns commit/framing; `mvn clean test` passes 2,443 tests with zero failures, errors or skips. |
| **Full-featured** | A framework is only useful when the whole application lifecycle is covered without dragging in a second stack. | Seven core modules plus adapters: IoC, boot/config, HTTP, DB/ORM/migrations, Flow, Cloud discovery/RPC/event mesh/observability/resilience/secrets/storage. HTTP includes routing, filters, multipart, SSE, static resources and WebSocket. |
| **Agentic-friendly** | Coding agents need discoverable metadata and stable contracts, not log archaeology or guesswork. | `Container.describe()` and `AppRuntime.describe()` expose metadata-only composition snapshots; `RouteIndex.descriptions()` and `HttpServer.routeDescriptions()` expose route identity; stable error codes; every config key is declared in `ConfigKeys`; compile errors are the migration path. |

**The common thread:** less vocabulary, fewer owners, sharper contracts. A small
framework is not one with few features; it is one where every feature has one
obvious place to live.

### Composition, not inheritance

Freeway's own object model is **interface contracts + final implementations**.
To customize behavior you implement an SPI or contribute an object; you never
subclass a framework class to reach protected state.

- Swap the HTTP engine by implementing `HttpTransport`; there is no abstract
  `BaseHttpServer` to inherit and override.
- Model a response body as a sealed `ResponseBody` variant; there is no response
  class hierarchy to subclass.
- Plug a workflow driver with `FlowDriver`, a registry with `ServiceRegistry`,
  or discovery with `ServiceDiscovery`; the container selects the implementation.
- Compose request views through `RequestView` / `HttpRequest` / `HttpExchange`
  interfaces. Interface extension is contract composition, not inherited code.

The few `extends` clauses in `src/main` are deliberate standard-library boundary
adapters: exceptions (`RuntimeException` / `IOException`), stream and collection
adapters (`InputStream` / `OutputStream` / `AbstractMap`), TLS managers
(`X509ExtendedKeyManager` / `X509ExtendedTrustManager`), logging handlers
(`Formatter` / `Handler`), and `SubmissionPublisher`. They exist because the
standard library requires those base types — not because Freeway has an
implementation class hierarchy.

## Quick Start

Add the modules you need. The snippets use `${freeway.version}`; use `1.5.7`
for the latest release or `2.0.0-SNAPSHOT` for the current development line.

```xml
<dependency>
    <groupId>com.jujin8.freeway</groupId>
    <artifactId>freeway-boot</artifactId>
    <version>${freeway.version}</version>
</dependency>
```

```java
public final class AppModule implements ModuleEx {
    @Override
    public void bind(Binder binder) {
        binder.bind(Greeter.class).to(GreeterImpl.class);
    }
}

try (AppRuntime runtime = FreewayApp.run(new AppModule())) {
    Greeter greeter = runtime.get(Greeter.class);
    System.out.println(greeter.greet("World"));
}
```

Command-line arguments and discovery are one value object:

```java
FreewayApp.run(Launch.of("--freeway.profile=dev"), new AppModule());
```

Without boot, build just the container:

```java
try (Container container = Freeway.create(
        binder -> binder.bind(Greeter.class).to(GreeterImpl.class))) {
    Greeter greeter = container.get(Greeter.class);
}
```

### A Web App

Add `freeway-http` and contribute routes; `FreewayApp` starts and stops the
server through the runtime lifecycle.

```java
public final class WebApp implements ModuleEx {
    @Override
    public void bind(Binder binder) {
        binder.contribute(Route.class)
            .add(Route.get("/", ctx -> ctx.sendText(200, "Hello Freeway")))
            .add(Route.get("/users/{id}", ctx -> ctx.sendJson(200, Map.of(
                "id", ctx.pathVar("id").orElse(""),
                "name", "Alice"
            ))));
    }

    public static void main(String[] args) {
        FreewayApp.run(Launch.of(args), new HttpModule(), new WebApp());
    }
}
```

```bash
curl http://localhost:8080/           # Hello Freeway
curl http://localhost:8080/users/42   # {"id":"42","name":"Alice"}
```

A runnable REST + DB sample with validation, ORM, transactions and a fat-jar
smoke path lives in [`demo/rest-db`](demo/rest-db/README.md).

## Modules

| Module | Purpose |
|---|---|
| **`freeway-commons`** | JSON, coercion, validation, scoped primitives, logging |
| **`freeway-ioc`** | Container, binding DSL, injection, scopes, contributions, AOP, EventBus |
| **`freeway-boot`** | Launcher, config cascade, profiles, runtime lifecycle |
| **`freeway-http`** | Routing, filters, static resources, multipart, SSE, WebSocket |
| **`freeway-db`** | JDBC, ORM, pooling, transactions, schema, migrations |
| **`freeway-flow`** | Graph workflow engine — 7 node types, v3 DAG format, `@name` tasks |
| **`freeway-cloud`** | Discovery, RPC, event mesh, observability, resilience, health, secrets, storage |

Third-party adapters live in **[freeway-ext](https://github.com/dzb/freeway-ext)**:

| Module | Purpose |
|---|---|
| **`freeway-http-undertow`** | Undertow engine adapter |
| **`freeway-http-jetty`** | Jetty 12 engine adapter |
| **`freeway-db-hikari`** | HikariCP pool adapter |
| **`freeway-cloud-consul`** | Consul registry/discovery adapter |
| **`freeway-mq-kafka`** | Kafka durable event plane (`KafkaEvents`) |

## What You Get

### Commons
`JsonCodec`/`JsonUtils.toJson`, `JsonCodec.coerce`, `Coercer` with contributed
rules, `BeanIntrospector`/`BeanPlan`, `@NotNull`-style validation, and logging.
The scoped primitives are `Defer` (commit-time actions) and
`ScopedCache.computeIfAbsent` (scope-lifetime values); both share one
`ScopeSession`, and manual rollback is `Defer.rollback()`.

### IoC
Explicit `bind`/`to`, named and primary bindings, `SINGLETON`/`PROTOTYPE`/`BOUNDARY`
scopes, constructor and field injection, `${...}` expansion, contributions with
ordering, method advisors, and an in-process EventBus. Class and factory
contributions use `addDeferred(...)`, `Extension` is a read-only view, and
`Container.describe()` exposes metadata-only composition without realizing
services. `ScopeBoundary.within(...)` enters a boundary scope on JDK 25
`ScopedValue`.

### Boot
`ModuleDeclaration` represents a module as either a class declaration or a
configured instance, so mixed composition has one model. `FreewayApp` composes
modules, loads the config cascade through `ConfigFilePlan`, runs `RuntimeHook`s,
publishes `AppLifecycleEvent`, and owns shutdown. ServiceLoader discovery is the
one automatic step — and it happens only in `FreewayApp` (disable with
`autoDiscovery(false)`); bindings are never scanned. `AppRuntime.describe()`
adds a metadata-only runtime snapshot.

### HTTP
`HttpExchange` is the request/response exchange contract. Routing supports path
parameters, regex constraints and wildcards; typed body binding is validated;
filters, `ErrorMapper`, static resources, SPA fallback, multipart, SSE and
WebSocket are included. Responses use the explicit
`body/text/json/file/sendText` family, while `HttpTransport` + `ResponsePlan`
keep commit and framing ownership in one place. The built-in engine speaks
HTTP/1.1, HTTP/2 and WebSocket; Undertow and Jetty swap in through a module.

Request-scoped services are opt-in: set `freeway.http.request-scope.enabled=true`
and `Scope.BOUNDARY` services/caches live for one request (after CORS/health,
before application filters); see
[ScopeBoundary / ScopeFilter](docs/developer-guide.md#request-scoped-services-scopefilter).

### DB
`Database` SQL execution, `Orm` CRUD/upsert, schema-less `Row` with
`asString/asInt/asLong/...`, a programmatic `Sql` builder with `toSql()` and
`value(...)`, `DatabaseRegistry.find`/`PoolStats`, pooling,
`db.transaction(...)` with thread-bound isolation and transaction-aware events,
dialect detection, `H2Mode`, `Schema.ensure()` and versioned migrations with
checksum validation.

### Flow
JSON-defined DAGs with `START`, `END`, `ACTIVITY`, `EXCLUSIVE`, `INCLUSIVE`,
`PARALLEL` and `LOOP`; `@name` container tasks and `#graphId` subflows; static
validation at build/load; iterative `FlowExecution` runs with
`FlowEngine.run/find/require`; metadata and `linkTo`; branch isolation and
PlantUML export.

### Cloud
In-process and fleet registry/discovery; RPC over HTTP with load balancing,
retries, circuit breaking and rate limiting; an at-most-once `MeshEventBus`
event plane; health/readiness, metrics/tracing, secrets, object storage and TLS
rotation. Domain packages use `Rpc*`, `Mesh*` and `Fleet*` vocabulary directly.

## Configuration

The cascade runs lowest to highest:

1. `application.properties`
2. `application.json`
3. `application-{profile}.properties`
4. `application-{profile}.json`
5. environment variables — `FREEWAY_DB_URL` → `freeway.db.url`
6. CLI arguments — `--key=value`, `-Dkey=value`

Activate a profile with `--freeway.profile=dev`. The environment prefix is
replaceable (`-Dfreeway.env.prefix=APP_`), and every key is declared in full in
its module's `ConfigKeys` and documented in
[docs/configuration.md](docs/configuration.md).

## Logging

Freeway uses the SLF4J 2 API and ships a JUL-backed provider as the fallback.
At startup it detects an external provider (Logback, Log4j, slf4j-simple) and
lets it win; an explicit `-Dslf4j.provider` is always respected. Configure the
built-in provider with `freeway-logging.properties` on the classpath root or
with `-D` flags:

```properties
freeway.log.level=INFO
freeway.log.console.enabled=true
freeway.log.file=auto
freeway.log.file.max-size=104857600
freeway.log.file.max-history=30
freeway.log.file.compress=true
```

Route named files and per-logger levels with `freeway.log.files` and any
`*.level` key. Full reference:
[docs/reference/logging.properties.reference](docs/reference/logging.properties.reference).

## Build

Requires JDK 25+.

```bash
mvn clean test                     # all core tests
mvn -pl freeway-ioc test           # one module
mvn -pl freeway-http -am test      # module plus its dependencies
mvn -Pdemos -DskipTests compile    # compile the in-repo consumers
```

## Documentation

The documentation index is [`docs/README.md`](docs/README.md). Highlights:

- [Developer guide](docs/developer-guide.md) — usage for every module
- [Architecture](docs/architecture.md) — boundaries, lifecycle, config internals
- [Configuration keys](docs/configuration.md) · [Logging template](docs/reference/logging.properties.reference)
- [Module model](docs/module-system.md) · [Commons](docs/modules/commons.md) · [DB](docs/modules/db.md) · [Cloud](docs/modules/cloud.md) · [Flow design](docs/design/flow.md)
- [Defer & ScopedCache](docs/design/defer.md) · [Cloud design](docs/design/cloud.md) · [Reflection](docs/reflection.md)
- [Configuration samples](docs/samples/) · [Release notes](docs/releases/)
- [Design taste](TASTE.md) — the judgment rules behind these APIs
- [Changelog](CHANGELOG.md) · [1.5.7 release notes](docs/releases/release-notes-1.5.7.md)

Framework comparisons and engineering evaluations are reference-only and live
under [`demo/reference/`](demo/reference/); they are not part of the normative
documentation.

## Extensions

Adapters and add-ons are released in
**[freeway-ext](https://github.com/dzb/freeway-ext)** at the same version as the
core. Add the modules you need:

```xml
<dependency>
    <groupId>com.jujin8.freeway</groupId>
    <artifactId>freeway-mq-kafka</artifactId>
    <version>${freeway.version}</version>
</dependency>
```

> The Maven group id keeps its original `com.jujin8` spelling while Java
> packages are `com.jujin.freeway`. Renaming either would break every consumer's
> build for no design gain, so the inconsistency is stated here rather than left
> for the first `import` to puzzle over.

## Since 1.5.7: the 2.0 refactor

`2.0.0-SNAPSHOT` is a deliberate architecture and vocabulary cleanup, not a
feature pile-on. The theme is fewer owners, fewer defaults, and fewer ways to
express the same fact. Existing code migrates through compiler errors; no
compatibility alias was added.

### Composition and lifecycle are resolved once

- `ModuleDeclaration` makes class declarations and configured instances the same
  composition value. `FreewayApp`, `Freeway.create(...)` and module discovery no
  longer each keep their own module-instance path.
- `OrderingSupport` and `TopologicalOrder` centralize contribution ordering and
  Kahn sorting; the domain strategies still choose their own edges.
- `ConfigFilePlan` owns standard file names, precedence, startup reads and
  snapshots. CLI keys are literal — `--mode=prod` is an application key named
  `mode`, not silently rewritten into `freeway.mode`.
- Contribution construction has one lifecycle: construct/publish, seal, then
  field injection and `@PostConstruct`. A consumer can no longer capture a
  partial extension view during startup.

### HTTP is one seam, not three implementations of the same fact

- `RequestHead` + `RequestViewSupport` own request method/path/query/header
  reads. `HttpTransport` and WebSocket sessions consume the same facts.
- The implementation-inheritance base classes are gone. `HttpTransport` is the
  adapter SPI; `HttpExchangeSupport` is final composition; commit state and
  framed body streaming belong to transport.
- `ResponsePlan` + sealed `ResponseBody` replace parallel byte/stream/file
  response methods. gzip, `Content-Length`, HEAD/204/205/304 behavior and
  transport handoff each have one owner.
- `ErrorResponse` + pure `ErrorMapper` make error data separate from writing.
  `HealthResult` is shared by HTTP health and cloud readiness.
- The HTTP/2 frame family is a sealed `Http2Frame` with pattern-switch
  dispatch, so a new frame cannot compile without its handling.
- Route metadata and WebSocket paths are inspectable through
  `RouteIndex.descriptions()`, `HttpServer.routeDescriptions()` and
  `HttpServer.webSocketPaths()`. `StaticResourceMount.withSpaFallback(true)`
  handles history-mode deep links without turning every missing asset into HTML.

### DB owns its SQL, not a bag of dialect guesses

- `DatabaseImpl.assemble(...)` is the single standalone/module assembly path.
  `DbModule` binds one selected `Dialect`; `Dialect.ofId(String)` is the catalog
  entry point.
- `H2Dialect` no longer inherits `PostgresDialect`; `H2Mode` describes H2 syntax
  modes without pretending H2 is PostgreSQL or MySQL. Transactional-DDL and
  returning/upsert capabilities are explicit.
- `Orm` query construction goes through `Sql`, not a second hand-built SQL path.
  `offset(n)` works without a limit, and dialect pagination is used by
  `Orm.findAll` instead of hard-coded `LIMIT/OFFSET`.

### IoC and public surfaces state their permissions

- `Extension` is read-only; `add/seal` stay in the internal store. Writes still
  happen only through `Binder.contribute(...)`.
- `Container.describe()` and `AppRuntime.describe()` expose metadata-only
  composition snapshots without instantiating lazy services or leaking values.
- `Scope.THREAD` became `Scope.BOUNDARY`, and `ScopeBoundary.withinChecked(...)`
  gives checked-exception callers the same guaranteed close semantics.
  `ScopeFilter` adds request-scoped `BOUNDARY` services as an explicit opt-in.
- `JsonCodec` is an IoC capability rather than an HTTP side effect; replacing
  the container's codec/coercer still governs HTTP and Cloud RPC.

### Cloud packages and names describe their plane

- Implementation types moved to `cloud.<domain>.internal`; top-level
  `cloud.internal` now contains only cross-domain support such as
  `CloudHttpClients`, `ServiceIdentity` and `BackendTypeGuard`.
- RPC, event mesh and fleet registry use `Rpc*`, `Mesh*` and `Fleet*` vocabulary.
  The event mesh is explicitly `MeshEventBus`, not a second `EventBus`.
- `RpcModule`, `DiscoveryModule`, `HealthModule`, `EventMeshModule` and the other
  domain modules no longer repeat the `Cloud` prefix already supplied by the
  package.
- `Proxies.objectMethod(...)`, `OptionalBinding.get(...)` and shared HTTP-client
  construction remove duplicated Object-method/optional/transport logic.

### Public vocabulary is now consistent

The naming pass is documented in [TASTE.md](TASTE.md) and the detailed mapping
is in [CHANGELOG.md](CHANGELOG.md). The important wins are structural:

- `find(...): Optional<T>` for optional lookup; `require(...)` when absence is
  exceptional; `get(...)` remains the throwing container lookup.
- `withX(...)` means a new value; bare nouns remain reads; `defaults()` remains
  a default value.
- `body/text/json/file/sendText` replace ambiguous `output`/`send` families.
- `Row.asString/asInt/asLong/...`, `metadata()/putMetadata(...)`, `linkTo(...)`,
  `addDeferred(...)`, `EventBus.Stats` and `MeshEventBus.Stats` make method
  families read the same way at every call site.
- `FlowExecution` and `FlowEngine.run/find/require` separate workflow execution
  from expression `eval`; `FlowContextState.setStopped(...)` keeps the write
  side internal.

### Verification

The refactor is backed by the core suite, not just by compile success:

```text
mvn clean test
  commons 422 · ioc 335 · boot 128 · http 527
  db 541 · flow 129 · cloud 361
  total 2,443 — 0 failures, 0 errors, 0 skipped
```

`mvn -Pdemos -DskipTests compile` also compiles the in-repo consumers against
the reactor, and `git diff --check` is clean. The design rule is simple: if a
change removes a second owner or a second answer, it belongs in 2.0; if it only
adds a spelling, it does not.

## License

[Apache 2.0](LICENSE)
