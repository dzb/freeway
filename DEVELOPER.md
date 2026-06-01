# Freeway 2 Developer Guide

This document describes Freeway 2's design shape. It is intentionally biased toward the concepts a framework developer needs while keeping compatibility baggage out.

## Module Dependency Graph

```
freeway-starter
      |
freeway-starter-web                 freeway-starter-db
      |                                     |
freeway-starter-boot                   freeway-starter-boot
      |                                     |
freeway-boot        freeway-http        freeway-db
      \                 |                 /
       \                |                /
              freeway-ioc
                  |
           freeway-commons

freeway-http-robaho / freeway-http-undertow / freeway-http-jetty
      |
freeway-http
```

Dependencies flow downward. Core modules do not depend on higher-level modules. Starter modules define fixed dependency bundles so applications do not need to assemble raw module graphs themselves.

## Architecture Baseline

Freeway 2 uses these architectural boundaries:

- `Container` is the IoC boundary only. It exposes service lookup and close, not application runtime operations.
- `AppRuntime` is the application boundary above `Container`. It owns config, profiles, runtime state, startup, shutdown, and runtime hooks.
- Service ids are plain strings and are normalized internally. There is no public `ServiceId` type.
- Service lifecycle is declared only with `bind().scope(...)`: `SINGLETON`, `PROTOTYPE`, `THREAD`.
- Thread-scoped services use `ScopeGate.open()` to enter an execution boundary; this keeps lookup and scope entry separate.
- `RuntimeHook` provides start/stop extension points for modules. Hooks are contributed through `Contributions<RuntimeHook>`.
- Ordered list contributions are supported through `add(id, value).before(...)` and `.after(...)`.
- HTTP startup no longer happens as a side effect of resolving `WebServer`. `HttpModule` contributes hook id `freeway.http.server`.
- Logger access is represented by `LoggerSource`; commons provides non-invasive SLF4J-over-JUL fallback through `LoggingBootstrap`.
- Default implementation class names use the `XDefault` suffix form to keep interface names dominant.

## IoC Container (`freeway-ioc`)

### Core API

| Type | Purpose |
|------|---------|
| `Container` | Service lookup: `get(Class)`, `get(Class, String)`, `close()` |
| `Module` | A module entry point: `bind(Binder)` |
| `Binder` | Binding and contribution DSL |
| `Binding` | Service binding configuration: target, id, primary, scope, advisor |
| `Freeway` | Container bootstrap: `Freeway.create(Module...)` |
| `RuntimeHook` | Start/stop lifecycle extension consumed by `AppRuntime` |
| `ScopeGate` | Opens a `Scope.THREAD` execution boundary |
| `ScopeHandle` | Auto-closeable handle returned by `ScopeGate.open()` |
| `LoggerSource` | Owner-aware logger factory service |

`ServiceId` is intentionally not a public type. String ids keep the API direct:

```java
binder.bind(PaymentGateway.class)
    .to(StripeGateway.class)
    .id("stripe")
    .primary();

PaymentGateway gateway = container.get(PaymentGateway.class, "stripe");
```

Blank service ids are rejected. Internal normalization is handled by `ServiceIds`.

### Binding DSL

```java
Container container = Freeway.create(binder -> {
    binder.bind(Greeter.class).to(GreeterImpl.class);

    binder.bind(PaymentGateway.class)
        .to(StripeGateway.class)
        .id("stripe")
        .primary();

    binder.bind(PaymentGateway.class)
        .to(PaypalGateway.class)
        .id("paypal");

    binder.bind(RequestState.class)
        .to(RequestState.class)
        .scope(Scope.THREAD);

    binder.bind(Greeter.class)
        .to(GreeterImpl.class)
        .advise(advisor -> advisor.wrap(
            inv -> inv.method().getName().equals("greet"),
            inv -> {
                long start = System.nanoTime();
                try {
                    return inv.proceed();
                } finally {
                    System.out.println(System.nanoTime() - start);
                }
            }
        ));
});
```

Advisors require interface-to-class bindings because the container uses JDK proxies.

### Service Lifecycles

`scope()` is the only binding-time API for service lifecycle:

| Scope | Meaning |
|-------|---------|
| `Scope.SINGLETON` | Default container-level singleton, closed by `Container.close()` |
| `Scope.PROTOTYPE` | New instance per resolution, not retained by the container |
| `Scope.THREAD` | One instance per active thread execution boundary |

Thread scope is entered through the built-in `ScopeGate` service:

```java
ScopeGate scopeGate = container.get(ScopeGate.class);
try (ScopeHandle ignored = scopeGate.open()) {
    RequestState state = container.get(RequestState.class);
}
```

This keeps the API aligned with the binding DSL: `bind().scope(Scope.THREAD)` declares lifecycle, while `ScopeGate.open()` enters the boundary. Direct injection of a thread-scoped concrete service into a singleton is rejected because it would capture one boundary-local instance permanently. Thread-scoped interface services can be injected through lazy proxies.

### Injection

Constructor injection is preferred. Field injection is supported for concise application code and config values.

```java
public final class UserService {
    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    @Inject("audit")
    private Logger audit;

    @Inject("${server.port}")
    private int port;
}
```

Supported annotations live under `com.jujin.freeway.ioc.annotation` and include `@Inject`, `@Named`, `@Primary`, `@Symbol`, `@Value`, and `@Extension`.
`@Extension` can be placed on `TYPE`, `FIELD`, and `PARAMETER`. On a type it supplies the default extension point for collection/map members in that class; member-level usage overrides the class default.

### Symbol and Value Injection

```java
public record ServerConfig(
    @Symbol("server.port") int port,
    @Value("${app.name:freeway}") String appName
) {}
```

| Annotation | Behavior |
|------------|----------|
| `@Symbol("key")` | Strict lookup; missing key fails |
| `@Value("${key:default}")` | Expression expansion with optional default |

Both paths use the container type coercion mechanism.

### Extension Points

`@Extension` marks extension consumers. On a type it sets the default extension point for collection/map members in that class. Member-level annotations override the default.

```java
@Extension(AppFeature.class)
public final class AppConfig {
    private final List<AppFeature> features;

    public AppConfig(Collection<AppFeature> features) {
        this.features = List.copyOf(features);
    }
}
```

```java
@Extension(AppFeature.class)
public final class AppFlags {
    private List<AppFeature> features;

    @Extension(AppFlag.class)
    private Map<String, AppFlag> flags;
}
```

Rules:

- Unnamed `add(value)` preserves insertion order.
- Named `add(id, value)` enables `before/after` constraints.
- Duplicate ids fail immediately.
- Missing order targets are ignored.
- Cycles fail when the extension point is resolved.
- `@Extension` on a type supplies the default extension point for collection/map members in that class.
- `@Extension` on a field or parameter overrides the class default.
- `@Extension` only applies to collection and map injection sites.

Mapped contributions stay separate because keyed maps and ordered lists have different semantics:

```java
MappedContributions<String, AppFlag> flags = binder.contributeMapped(String.class, AppFlag.class);
flags.put("debug", new AppFlag("debug", true));
```

`K` can be an enum, a class, or any non-null key type. String is just the common case. `@Extension` on a Map injection site resolves the full `Map<K, V>` extension point, not just the value type.

Mapped contribution keys are generic. Keys are stored as provided, null keys fail immediately, duplicate entries fail immediately, and `override(key, value)` is the explicit replacement path for an existing key.

Use `put(key, value)` for new entries and `override(key, value)` only when the key is already present.

Because modules bind in load order, an override must run after the contribution it replaces.

### Type Coercion

The IoC layer keeps the original Freeway strength: external strings can be expanded and coerced into target types.

```java
binder.contribute(CoercionRule.class).add(new CoercionRule<>(
    String.class,
    Endpoint.class,
    value -> {
        String[] parts = value.split(":", 2);
        return new Endpoint(parts[0], Integer.parseInt(parts[1]));
    }
));
```

Commons owns the reusable scalar mechanics. IoC owns container-aware coercion rules and symbol/value integration.

### Logging Service

`LoggerSource` is a built-in service:

```java
public final class UserService {
    @Inject
    private Logger log;

    @Inject("audit")
    private Logger audit;
}

Logger log = container.get(LoggerSource.class).get(UserService.class);
```

Logger injection is owner-aware. Without an explicit id, the logger name is the declaring service type. With `@Inject("name")` or `@Named("name")`, the explicit name is used.

## Boot (`freeway-boot`)

### Public Shape

```java
AppRuntime runtime = Launcher.run(AppModule.class, args);
```

| Type | Purpose |
|------|---------|
| `Launcher` | Thin public entry point |
| `AppBootstrap` | Internal boot orchestration |
| `AppRuntime` | Runtime API: container, config, state, start, close |
| `AppState` | `CREATED`, `STARTING`, `RUNNING`, `STOPPING`, `STOPPED`, `FAILED` |

`Launcher.run()` creates the container, builds the runtime, starts hooks, logs startup time, and registers a JVM shutdown hook.

### Runtime Hooks

Modules that own runtime resources should contribute `RuntimeHook` instead of starting work from constructors or service resolution:

```java
binder.contribute(RuntimeHook.class).add("app.cache", new RuntimeHook() {
    @Override
    public void start(Container container) {
        container.get(Cache.class).warmup();
    }

    @Override
    public void stop(Container container) {
        container.get(Cache.class).close();
    }
}).before(HttpModule.SERVER_HOOK);
```

Startup invokes hooks in resolved contribution order. Shutdown invokes only started hooks in reverse order, then closes the container.

### Logging Bootstrap

`freeway-commons` provides `LoggingBootstrap` and a JUL-backed SLF4J 2 provider. It is deliberately not registered through `META-INF/services`, so it does not override an application logger.

Decision path:

- If `slf4j.provider` is already set, leave it alone.
- If a known external provider is present, leave it alone.
- Otherwise set the provider to Freeway's JUL fallback.

Lower-level modules that need static loggers should call `LoggingBootstrap.logger(...)` rather than touching `LoggerFactory` directly.

### Config Cascade

Config priority from high to low:

1. CLI arguments: `--key=value`, `-Dkey=value`
2. Environment variables (`FREEWAY_` prefix)
3. `application-{profile}.json`
4. `application-{profile}.properties`
5. `application.json`
6. `application.properties`

Profiles are activated with `--freeway.profile=dev` or `-Dfreeway.profile=dev`.

## HTTP Layer (`freeway-http`)

The HTTP package stays flat under `com.jujin.freeway.http`. Public contracts and small built-ins share the same package; transport internals stay package-private where possible.

| Category | Main Types |
|----------|------------|
| Core | `HttpEngine`, `HttpContext`, `HttpFilter`, `HttpModule`, `WebServer`, `JsonCodec` |
| Routing | `Route`, `RouteGroup`, `RouteIndex`, `PathPattern` |
| Body | `BodyHandler`, `RequestContext`, `RequestContextDefault`, `MultipartForm` |
| WebSocket | `WebSocketSession`, `WebSocketListener`, `WebSocketRoute`, `WebSocketGroup`, `WebSocketIndex` |
| SSE | `SseEmitter`, `SseEvent` |
| Built-ins | `JsonCodecDefault`, `CorsFilter`, `RequestTimingFilter`, `StaticResources`, `ExceptionMapper` |

`WebServer` has explicit `start()` and `stop()` methods. In normal boot, `HttpModule` contributes a runtime hook using stable id `freeway.http.server`, so the server is started and stopped by `AppRuntime`.

When using `Container` directly in tests or tools, start the server explicitly:

```java
WebServer server = container.get(WebServer.class);
server.start();
try {
    // test HTTP calls
} finally {
    server.stop();
}
```

### Engine Selection

`web.engine` selects the transport:

| Value | Engine |
|-------|--------|
| `robaho` | Default zero-dependency engine with WebSocket support |
| `jdk` | Built into `freeway-http`, HTTP only |
| `undertow` | Undertow adapter module |
| `jetty` | Jetty adapter module |

Engine adapters bind their engine by string id:

```java
binder.bind(MyEngine.class).to(MyEngine.class).id("my-engine");
```

## DB Layer (`freeway-db`)

`Database` is the main entry point:

```java
List<User> users = db.sql("select * from users where active = ?", true)
    .list(User.class);

User user = db.sql("select * from users where id = :id")
    .param("id", 42)
    .one(User.class)
    .orElseThrow();
```

### Usage Patterns

Use `sql(...)` for one-off reads and writes:

```java
List<User> users = db.sql("select * from users where active = ?", true)
    .list(User.class);

db.sql("update users set last_login_at = now() where id = ?", 42L)
    .execute();
```

Use named parameters when the call site reads better:

```java
User user = db.sql("select * from users where id = :id")
    .param("id", 42)
    .one(User.class)
    .orElseThrow();
```

Use `batch(...)` for repeated statements:

```java
db.batch("insert into ledger (id, name) values (?, ?)")
    .rows(
        new Object[] { 1L, "alpha" },
        new Object[] { 2L, "beta" }
    )
    .execute();
```

Use `transaction(...)` for atomic multi-step work:

```java
db.transaction(tx -> {
    tx.sql("update ledger set amount_cents = amount_cents + ? where id = ?", 100L, 1L)
        .execute();
    tx.batch("insert into audit_log (id, message) values (?, ?)")
        .rows(new Object[] { 1L, "ledger updated" })
        .execute();
});
```

Use the isolation overload only when the database behavior needs it:

```java
db.transaction(tx -> {
    tx.sql("update ledger set amount_cents = amount_cents + ? where id = ?", 100L, 1L)
        .execute();
}, IsolationLevel.READ_COMMITTED);
```

Use `DatabaseHub` when there are multiple datasources:

```java
Database primary = hub.primary();
Database audit = hub.get("audit");
```

Key capabilities:

- Positional and named parameters.
- Collection expansion for `IN` clauses.
- `one`, `list`, `stream`, and `execute` query paths.
- Programmatic transactions.
- Built-in connection pool and leak statistics.
- SQL migrations from `db/migration/`.
- Record/bean row mapping with cached column lookup.
- Manually registered row mappers and user-contributed row mappers merge in one resolver.
- `DatabaseHub` for multiple datasources.

## Naming Rules

- Public interfaces use the domain name directly: `Container`, `JsonCodec`, `RequestContext`.
- Framework-provided implementation classes use `XDefault`: `AppRuntimeDefault`, `JsonCodecDefault`, `RequestContextDefault`, `CoercerDefault`.
- `DefaultX` is avoided because it hides the dominant concept at the end of the name.
- `Impl` is reserved for uninteresting concrete implementations where no default strategy is being expressed.
- Internal normalization helpers stay internal, for example `ServiceIds`.

## Testing Guidelines

```bash
mvn test
mvn -pl freeway-ioc -am test
mvn -pl freeway-http -am test
mvn -pl freeway-db -am test
```

For IoC tests, use `Freeway.create(...)`. For application integration tests, use `Launcher.run(...)` so runtime hooks and shutdown behavior are exercised.

## Code Style

- JDK 25+.
- Core modules avoid external dependencies unless the module's purpose is an adapter.
- No classpath scanning.
- No bytecode weaving.
- Prefer constructor injection for framework internals.
- Field injection is acceptable for concise app code and config values.
- Prefer small explicit APIs over future-proof abstractions.
- Keep concepts few: Module, Service, Extension, Scope, Runtime.
