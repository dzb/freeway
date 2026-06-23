# Freeway 2

**A brand-new, modern, lightweight Java application framework built on JDK 25+.**

Zero classpath scanning. Compose-first API. No magic.

| Module | Description                                               |
|--------|-----------------------------------------------------------|
| `freeway-commons` | Shared utilities: JSON, coercion, Defer, ScopedCache, logging |
| `freeway-ioc` | IoC container: bind, inject, coerce, advise, event-bus    |
| `freeway-boot` | Application launcher, config, profiles, runtime lifecycle |
| `freeway-http` | HTTP layer: routing, filters, static, multipart, websocket |
| `├ built-in` | FreewayHttpEngine, high-performance, HTTP/2 + WebSocket       |
| `└ engine adapters` | Undertow — available in [freeway-ext](https://github.com/dzb/freeway-ext) |
| `freeway-db` | JDBC data access: ORM, pooling, transactions, migrations  |
| `└ connection pool` | HikariCP adapter — available in [freeway-ext](https://github.com/dzb/freeway-ext) |
| `freeway-mq-kafka` | Kafka EventBus bridge — available in [freeway-ext](https://github.com/dzb/freeway-ext) |

Core modules have zero external dependencies. Extension modules with third-party
library integrations live in **[freeway-ext](https://github.com/dzb/freeway-ext)**.
Pick only the adapters you need.

## Philosophy

Freeway 2 is a compose-first framework. Instead of scanning the classpath,
applications explicitly wire modules together:

```java
Freeway.create(
    binder -> binder.bind(Greeter.class).to(GreeterImpl.class),
    binder -> binder.bind(Store.class).to(Store.class)
);
```

This gives you:

- Fast startup - no bytecode scanning, no reflection-heavy discovery.
- Total control - every binding is explicit, every dependency is visible.
- Small footprint - core modules keep external dependencies out of the way.

## Core Design

Freeway 2 keeps its core concepts and public API intentionally small:

- `Container` is the service composition and lookup boundary: `get(Class)`, `get(Class, String)`, `close()`.
- `AppRuntime` sits above `Container` and owns runtime state, startup, shutdown, profiles, config, and runtime hooks.
- Service ids are plain strings: `.id("stripe")`, `get(PaymentGateway.class, "stripe")`. There is no public `ServiceId` type.
- Service lifecycles are declared only through `bind().scope(...)`: `SINGLETON`, `PROTOTYPE`, `THREAD`.
- `Scoping` executes work inside a `Scope.THREAD` boundary via `within()`, backed by JDK 25 `ScopedValue`.
- `RuntimeHook` is the module-level start/stop extension. Hooks are contributed through the normal contribution mechanism and can be ordered with `before/after`.
- `HttpModule` contributes the web server hook with stable id `freeway.http.server`; app launch starts and stops the server through `AppRuntime`.
- `LoggerSource` is the built-in logger service. Commons provides a JUL-backed SLF4J provider with ANSI-colored single-line console output (auto-detected via TTY). Opt out with `-Dfreeway.log.format=simple` or `FREEWAY_LOG_FORMAT=simple`.
- Framework-provided implementation names use the `XDefault` suffix form, such as `AppRuntimeDefault`, `JsonCodecDefault`, and `RequestContextDefault`.

## Quick Start

```java
public final class AppModule implements Module2 {
    @Override
    public void bind(Binder binder) {
        binder.bind(Greeter.class).to(GreeterImpl.class);
    }
}

AppRuntime runtime = FreewayApp.run(args, new AppModule());
Greeter greeter = runtime.get(Greeter.class);
System.out.println(greeter.greet("World"));
runtime.close();
```

Or compose inline without boot:

```java
Container container = Freeway.create(
    binder -> binder.bind(Greeter.class).to(GreeterImpl.class)
);
```

### Web App

```java
public final class App implements Module2 {
    @Override
    public void bind(Binder b) {
        b.install(new HttpModule());

        b.contribute(Route.class)
            .add(Route.get("/", ctx -> ctx.send(200, "Hello Freeway")))
            .add(Route.get("/users/:id", ctx ->
                ctx.sendJson(200, Map.of("id", ctx.pathVar("id"), "name", "Alice"))));
    }

    public static void main(String[] args) {
        FreewayApp.run(args, new App());
    }
}
```

```bash
curl http://localhost:8080/           # Hello Freeway
curl http://localhost:8080/users/42   # {"id":"42","name":"Alice"}
```

## Build

Requires JDK 25.

```bash
mvn test
mvn -pl freeway-ioc test
mvn -pl freeway-http -am test
mvn -pl freeway-db -am test
```

## Modules at a Glance

### Commons (`freeway-commons`)

Shared utilities usable independently of the framework:

- JSON — `JsonCodec` for object↔JSON mapping, `JsonUtils` for parsing/serialization.
- Coercion — `Coercer` type conversion with pluggable `CoerceRule` extensions.
- Defer — scope-bound deferred execution. Actions buffered inside a scope drain on commit, discard on rollback. Backed by `ScopedValue`.
- ScopedCache — scope-bound value cache. Keys are lazily created and reused within a scope, cleaned up on exit via registered close handlers.
- Bean — `BeanIntrospector`/`BeanPlan` for record/bean reflection.
- Validation — `@NotNull`/`@NotBlank`/`@Size`/`@Min`/`@Max` with `BeanValidator`.

### IoC (`freeway-ioc`)

The IoC module provides the framework core:

- Service binding - `binder.bind(X.class).to(Y.class)`.
- Named services - `.id("primary")`.
- Primary resolution - `.primary()` for the default binding when no id is supplied.
- Scopes - `SINGLETON`, `PROTOTYPE`, `THREAD`.
- Injection - constructor and field injection with `@Inject`, `@Named`, `@Symbol`, `@Value`.
- Value expansion - `${...}` placeholder expansion for external configuration.
- Type coercion - scalar and domain-specific conversions through contributed coercion rules.
- Extension points - `binder.contribute(Route.class).add(...)` and ordered `add(id, value).before/after(...)`, with `Extension<V>` for typed injection.
- Runtime hooks - `RuntimeHook` lets modules attach start/stop behavior to `AppRuntime`.
- Advisors - method interception for interface services.
- EventBus - process-local pub/sub: class-based or string-topic, module-contributed (ordered) or runtime-subscribed, with `Stoppable` short-circuit, `DeadEvent` logging, and `publishAsync`. Transaction-aware: events published inside a DB transaction automatically defer until commit. Lifecycle events (`AppStartedEvent`, `AppStoppingEvent`) published automatically by boot.

### Boot (`freeway-boot`)

Boot turns a composed container into an application runtime:

- `FreewayApp.run(args, Module2...)` - accepts command-line args and Module2 instances. Loads config, discovers SPI modules, starts the full application lifecycle. Use `FreewayApp.of(...).autoDiscovery(false).shutdownHook(false)` for full control.
- `AppRuntime` - owns config, profiles, runtime state, and runtime hooks.
- Shutdown hook - closes the runtime on JVM shutdown.
- Startup timing - logs elapsed startup time.
- Config providers - properties files, JSON, environment, system properties, CLI args.

**Lifecycle:** state machine with six states:

```
CREATED ──start()──▶ STARTING ──ok──▶ RUNNING ──close()──▶ STOPPING ──▶ STOPPED
  │                    │                 │                    │
  └── close() ───────────────────────────────────────────────┘
                       │                 │                    │
                       └── error ──▶ FAILED ◀── error ───────┘
```

`start()` runs RuntimeHooks in contribution order (supports `before/after` ordering). Any hook failure rolls back already-started hooks. `close()` stops hooks in reverse order, then closes the container. Failed stop produces `FAILED` state with suppressed exceptions.

Lifecycle events are published on the EventBus: `AppStartedEvent` after start, `AppStoppingEvent` before shutdown — modules like cache can subscribe to warmup/flush without implementing RuntimeHook.

### HTTP (`freeway-http`)

The HTTP layer is deliberately thin:

- Routing - explicit `Route` and `RouteGroup` contributions.
- Route index - trie-based path matching with path variables, regex constraints, and wildcards.
- Request body binding - `Route.post(path, BodyType.class, handler)` deserializes and validates.
- Static resources - classpath and filesystem mounts.
- Multipart upload - file upload handling.
- Filters - `HttpFilter` chain.
- Exception mapping - `ExceptionMapper` and built-in validation/body-size handling.
- SSE - `HttpContext.sse()` returns `SseEmitter`.
- WebSocket - listener callbacks for open/text/binary/close/error.
- Pluggable engines — `FreewayHttpEngine` built-in (high-performance, HTTP/2 + WebSocket); Undertow adapter available in [freeway-ext](https://github.com/dzb/freeway-ext) for alternative deployment.

Switch engines by adding the extension module — the container selects it via `.primary()`:

```java
// FreewayHttpEngine (default)
FreewayApp.run(args, new AppModule(), new HttpModule());

// Undertow — just add the module
FreewayApp.run(args, new AppModule(), new HttpModule(), new UndertowModule());
```

### DB (`freeway-db`)

A compact JDBC data access layer with ORM:

- `Database` - SQL execution with positional/named parameters and collection expansion.
- `Orm` - lightweight CRUD: `insert`, `update`, `delete`, `findById`, `findAll`, `save` (upsert).
- `Row` - schema-less query result with type-safe column access.
- `SQL` - programmatic SQL builder: `SQL.insert("t").set("col", v)`.
- `RowMapper` - auto-mapping for records, beans, and basic types; `@Column` annotation drives column name matching.
- Transactions - `db.transaction(() -> { ... })` with ScopedValue isolation, transaction-aware EventBus.
- Connection pooling - `Pool` interface + `PoolDefault` built-in impl; pluggable via module `.primary()` (same pattern as HTTP engine). HikariCP adapter available in [freeway-ext](https://github.com/dzb/freeway-ext).
- **Dialect** — config-driven selection via `freeway.db.dialect`, JDBC URL auto-detection. Built-in: `PostgresDialect` (default), `MySqlDialect`, `SqliteDialect`. H2 auto-detected as PostgreSQL-compatible (or MySQL if `MODE=MySQL`).
- **Schema** — `@Table`/`@Column`/`@Id`/`@Generated` annotations + `Schema.ensure()` auto-DDL. Entity groups contributed via `SchemaEntity.of("core", User.class)`, filterable via `freeway.db.schema.groups`.
- **Migrations** — versioned SQL files (`V001__name.sql`) with SHA-256 checksum validation, format enforcement, and database-level concurrency lock. `MigrationRunner` runs after Schema at startup via `RuntimeHook` (`"freeway.db.migration"`).
- `DatabaseHub` - multi-datasource routing.

Freeway-db is independently usable outside of the IoC container — only `freeway-commons` is required at runtime. `freeway-ioc` is optional and only needed when loading via `DbModule`.

### Extensions

Third-party integrations are available in the **[freeway-ext](https://github.com/dzb/freeway-ext)** repository:

| Module | Description |
|--------|-------------|
| `freeway-http-undertow` | Undertow web server adapter (HTTP + WebSocket) |
| `freeway-db-hikari` | HikariCP connection pool adapter |
| `freeway-mq-kafka` | Kafka EventBus bridge for distributed pub/sub |

Add the snapshot repository and the extensions you need:

```xml
<dependency>
    <groupId>com.jujin8.freeway</groupId>
    <artifactId>freeway-mq-kafka</artifactId>
    <version>${freeway.version}</version>
</dependency>
```

## Configuration

Configuration flows in a layered cascade, from lowest to highest priority:

1. `application.properties`
2. `application.json`
3. `application-{profile}.properties`
4. `application-{profile}.json`
5. Environment variables — `FREEWAY_DB_URL` → `freeway.db.url` (prefix stripped, `_` → `.`, `freeway.` prepended)
6. CLI arguments (`--key=value`, `-Dkey=value`)

Activate profiles with:

```bash
--freeway.profile=dev
```

## License

[Apache 2.0](LICENSE)
