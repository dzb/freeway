# Freeway 2

**A brand-new, modern, lightweight Java application framework built on JDK 25+.**

Zero classpath scanning. Compose-first API. No magic.

| Module | Description                                               |
|--------|-----------------------------------------------------------|
| `freeway-commons` | Shared utilities: JSON, scalar coercion, logging fallback |
| `freeway-ioc` | IoC container: bind, inject, coerce, advise               |
| `freeway-boot` | Application launcher, config, profiles, runtime lifecycle |
| `freeway-http` | HTTP/WebSocket layer: routing, filters, static, multipart |
| `├ built-in` | JDK HttpServer engine, HTTP only                          |
| `├ freeway-http-robaho` | Zero-dep engine with WebSocket (default)                  |
| `├ freeway-http-undertow` | Undertow transport adapter                                |
| `└ freeway-http-jetty` | Jetty transport adapter                                   |
| `freeway-db` | JDBC data access: ORM, pooling, transactions, migrations  |

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
- `LoggerSource` is the built-in logger service. Commons provides a JUL fallback for SLF4J only when no external SLF4J provider is present.
- Framework-provided implementation names use the `XDefault` suffix form, such as `AppRuntimeDefault`, `JsonCodecDefault`, and `RequestContextDefault`.

## Quick Start

```java
public final class AppModule implements Module {
    @Override
    public void bind(Binder binder) {
        binder.bind(Greeter.class).to(GreeterImpl.class);
    }
}

AppRuntime runtime = Launcher.run(AppModule.class, args);
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

## Build

Requires JDK 25.

```bash
mvn test
mvn -pl freeway-ioc test
mvn -pl freeway-http -am test
mvn -pl freeway-db -am test
```

## Modules at a Glance

### IoC (`freeway-ioc`)

The IoC module provides the framework core:

- Service binding - `binder.bind(X.class).to(Y.class)`.
- Named services - `.id("primary")`.
- Primary resolution - `.primary()` for the default binding when no id is supplied.
- Scopes - `SINGLETON`, `PROTOTYPE`, `THREAD`.
- Injection - constructor and field injection with `@Inject`, `@Named`, `@Symbol`, `@Value`, and `@Extension`.
- Value expansion - `${...}` placeholder expansion for external configuration.
- Type coercion - scalar and domain-specific conversions through contributed coercion rules.
- Extension points - `binder.contribute(X.class).add(...)` and ordered `add(id, value).before/after(...)`, with `@Extension` consuming contributed lists and maps.
- Runtime hooks - `RuntimeHook` lets modules attach start/stop behavior to `AppRuntime`.
- Advisors - method interception for interface services.
- EventBus - process-local pub/sub: class-based (`PostCreatedEvent`) or string-topic (`"post.created"`), module-contributed (ordered `before/after`) or runtime-subscribed, with `Stoppable` short-circuit and `DeadEvent` logging. Lifecycle events (`AppStartedEvent`, `AppStoppingEvent`) published automatically by boot.

### Boot (`freeway-boot`)

Boot turns a composed container into an application runtime:

- `Launcher.run()` - thin entry that delegates to `AppBootstrap`.
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
- Pluggable engines - JDK, Robaho, Undertow, and Jetty adapters.

Switch engines with config:

```properties
web.engine=robaho
web.engine=jdk
web.engine=undertow
web.engine=jetty
```

### DB (`freeway-db`)

A compact JDBC data access layer with ORM:

- `Database` - SQL execution with positional/named parameters and collection expansion.
- `Orm` - lightweight CRUD: `insert`, `update`, `delete`, `findById`, `findAll`, `save` (upsert).
- `Row` - schema-less query result with type-safe column access.
- `SQL` - programmatic SQL builder: `SQL.insert("t").set("col", v)`.
- `RowMapper` - auto-mapping for records, beans, and basic types.
- Transactions - `db.transaction(() -> { ... })` with ScopedValue isolation.
- Connection pooling - built-in pool with leak detection.
- Schema - `@Table`/`@Column`/`@Id`/`@Generated` annotations + AutoMigrate.
- Migrations - SQL files in `db/migration/` with checksum tracking.
- `DatabaseHub` - multi-datasource routing.

## Configuration

Configuration flows in a layered cascade, from lowest to highest priority:

1. `application.properties`
2. `application.json`
3. `application-{profile}.properties`
4. `application-{profile}.json`
5. Environment variables (`FREEWAY_` prefix)
6. CLI arguments (`--key=value`, `-Dkey=value`)

Activate profiles with:

```bash
--freeway.profile=dev
```

## License

[Apache 2.0](LICENSE)
