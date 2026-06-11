# Freeway 2 Developer Guide

This document describes Freeway 2's design shape. It is intentionally biased toward the concepts a framework developer needs while keeping compatibility baggage out.

## Module Dependency Graph

```
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
- Thread-scoped services use `Scoping.within()` to enter an execution boundary; the scope auto-closes when the work lambda completes.
- `RuntimeHook` provides start/stop extension points for modules. Hooks are contributed through `Contributions<RuntimeHook>`.
- Ordered list contributions are supported through `add(id, value).before(...)` and `.after(...)`.
- HTTP startup no longer happens as a side effect of resolving `WebServer`. `HttpModule` contributes hook id `freeway.http.server`.
- Logger access is represented by `LoggerSource`; commons provides a JUL-backed SLF4J provider via standard `META-INF/services` discovery.
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
| `Scoping` | Executes work inside a `Scope.THREAD` boundary via `within()` |
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

Thread scope is entered through the built-in `Scoping` service:

```java
Scoping scoping = container.get(Scoping.class);
scoping.within(() -> {
    RequestState state = container.get(RequestState.class);
    return null;
});
```

The scope lives for the duration of the `within()` lambda and is backed by JDK 25 `ScopedValue`, so there is no `ThreadLocal` overhead on virtual threads. The `bind().scope(Scope.THREAD)` DSL declares lifecycle, while `Scoping.within()` enters the boundary. Direct injection of a thread-scoped concrete service into a singleton is rejected because it would capture one boundary-local instance permanently. Thread-scoped interface services can be injected through lazy proxies.

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

Supported annotations live under `com.jujin.freeway.ioc.annotation` and include `@Inject`, `@Named`, `@Symbol`, and `@Value`. Primary resolution uses `binding.primary()` on the binding DSL, not an annotation.

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

Extensions are contributed by entry type and injected via `Extension<V>`:

```java
// contribution
binder.contribute(Route.class).add(Route.get("/hello", ctx -> ctx.send(200, "hello")));

// ordering via named id
binder.contribute(RuntimeHook.class)
    .add("myHook", hook).after("freeway.http.server");

// injection
@Inject Extension<Route> routes;
routes.all().forEach(...);
```

`Extension<V>` is a concrete class — no sub-interfaces needed. The entry type itself (e.g., `Route.class`) is the extension point identifier. Named extensions are supported via `contribute(entryType, "name")` for same-type discrimination.

Rules:

- `add(value)` preserves insertion order.
- `add(id, value)` enables `before/after` constraints for topological ordering.
- Duplicate ids fail immediately. Missing order targets are ignored. Cycles fail at resolution time.

### EventBus

In-process publish-subscribe built on the Extension mechanism. Events are plain objects; subscribers are contributed via `EventSubscriber` or registered at runtime.

```java
// Module contribution — startup-time, supports before/after ordering
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(PostCreatedEvent.class, e -> indexService.index(e.post())));

binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of("notify", PostCreatedEvent.class, e -> notificationService.send(e)))
    .after("index");

// Runtime subscription — dynamic, no ordering
@Inject EventBus bus;
Subscription<PostCreatedEvent> sub = bus.subscribe(PostCreatedEvent.class, e -> { ... });
bus.unsubscribe(sub);

// Publish
bus.publish(new PostCreatedEvent(post));
```

**Key types:**

| Type | Purpose |
|------|---------|
| `EventBus` | Publish, subscribe, unsubscribe. Injected via `@Inject EventBus` |
| `EventSubscriber<E>` | Module-level subscriber: carries event type, handler, and ordering |
| `Subscription<E>` | Handle returned by `subscribe()`, used to `unsubscribe()` |
| `DeadEvent` | Published when an event has zero subscribers — subscribe for logging |
| `EventBridge` | Bridge to external MQ: `EventBridge.send(topic, event)` |
| `@Topic("kafka.topic")` | Maps an event class to a cross-JVM topic name |
| `EventBus.Stoppable` | Events implementing this can `stop()` the subscriber chain |

**Short-circuit (Stoppable):**

```java
public class PostCreatedEvent implements EventBus.Stoppable {
    private final AtomicBoolean stopped = new AtomicBoolean();
    @Override public void stop() { stopped.set(true); }
    @Override public boolean isStopped() { return stopped.get(); }
}

// First subscriber validates and stops if unauthorized — later subscribers are skipped
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(PostCreatedEvent.class, e -> { if (!loggedIn) e.stop(); }));
```

**String-topic mode:**

```java
// Lighter weight — no event class needed
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of("post.created", payload -> { ... }));

bus.publish("post.created", payload);
```

**Async publishing:**

```java
// Fire-and-forget — published on virtual threads by default
bus.publishAsync(new PostCreatedEvent(post));
bus.publishAsync("post.created", payload);

// Custom executor (e.g. platform-thread pool)
Executor pool = Executors.newFixedThreadPool(4);
bus.setAsyncExecutor(pool);
```

Async dispatch defaults to `Executors.newVirtualThreadPerTaskExecutor()`. Call `setAsyncExecutor()` to replace it with a custom executor.

Note: `publishAsync` dispatches to a separate thread and therefore does not participate in `Defer` scopes (which are thread-bound via `ScopedValue`). For transaction-safe events, use sync `publish()` — it automatically defers inside a transaction and fires on commit.

**DeadEvent logging:**

```java
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(DeadEvent.class, e -> LOG.warn("No subscriber for {}", e.event().getClass())));
```

**Lifecycle events:** Boot publishes `AppStartedEvent` after all hooks start, and `AppStoppingEvent` before shutdown. Subscribe via EventBus instead of implementing RuntimeHook for non-critical work:

```java
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(AppStartedEvent.class,  e -> cache.warmup()))
    .add(EventSubscriber.of(AppStoppingEvent.class, e -> cache.flush()));
```

### Kafka (`freeway-mq-kafka`)

Distributed pub/sub via EventBus. Add `KafkaModule` to enable:

```java
Launcher.run(AppModule.class, new KafkaModule());
```

**Sending:** EventBus automatically bridges to Kafka:

```java
bus.publish(new PostCreatedEvent(post));
// → local subscribers + KafkaEventBridge → broker
```

**Receiving:** KafkaSubscriber polls Kafka, publishes to local EventBus. Messages carry an `X-Event-Type` header for automatic type deserialization.

**Configuration:**

```properties
freeway.kafka.bootstrap-servers=localhost:9092
freeway.kafka.group-id=my-app
freeway.kafka.topics=post.created,comment.added
```

**Key types:**

| Type | Purpose |
|------|---------|
| `KafkaEventBridge` | Implements `EventBridge`, sends to Kafka |
| `KafkaSubscriber` | Polls Kafka, publishes to local `EventBus` |
| `KafkaConfig` | Bootstrap servers, group-id, topic list |
| `KafkaModule` | Registers all services + RuntimeHook wiring |

### Type Coercion

The IoC layer keeps the original Freeway strength: external strings can be expanded and coerced into target types.

```java
binder.contribute(CoerceRule.class).add(new CoerceRule<>(
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
binder.contribute(RuntimeHook.class)
    .add("app.cache", new RuntimeHook() {
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

### Logging

`freeway-commons` provides a JUL-backed SLF4J 2 provider registered via standard `META-INF/services`. When no external logger (Logback, Log4j) is on the classpath, SLF4J discovers the JUL provider automatically.

Framework code uses standard `LoggerFactory.getLogger()` everywhere. Services can inject `LoggerSource` for owner-aware loggers:

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

`freeway-db` can be used independently outside the IoC container — only `freeway-commons` is required at runtime. `freeway-ioc` is optional and loaded only when using `DbModule`.

```java
// Standalone usage (no IoC)
PoolConfig config = PoolConfig.defaults("jdbc:h2:mem:test", "sa", "");
Database db = new DatabaseBuilder().config(config).build();
Orm orm = Orm.of(db);
orm.insert(new Post("Hello", "World"));
```

### Connection Pool

`Pool` is the connection pool abstraction — `PoolDefault` (built-in) and `HikariPool` (freeway-db-hikari) both implement it. `DatabaseBuilder` accepts an optional `pool(Pool)` override:

```java
// Default — DatabaseBuilder creates a PoolDefault from PoolConfig
Database db = new DatabaseBuilder().config(config).build();

// Explicit pool — pass a pre-built instance
Pool pool = new PoolDefault(config);
Database db = new DatabaseBuilder().config(config).pool(pool).build();
```

**IoC pool selection:** when using `DbModule`, the pool is selected via `freeway.db.pool` config property (default `"builtin"`). The built-in `id("builtin")` binding creates a `PoolDefault`; binding another `Pool` implementation with a different id and setting it as primary selects that implementation — aligning with the HTTP engine selection paradigm.

```java
// Select HikariCP via config
// freeway.db.pool=hikari
```

**PoolConfig** records all connection parameters with sensible defaults:

| Property | Default |
|----------|---------|
| `maxSize` | 10 |
| `minIdle` | 2 |
| `connectionTimeout` | 10s |
| `maxLifetime` | 30min |
| `maxIdleTime` | 10min |
| `cleanInterval` | 2min |
| `healthCheckQuery` | null (JDBC `isValid` only) |
| `healthCheckTimeout` | 5s |
| `queryTimeout` | 15s |

`Database` is the main entry point for SQL execution:

```java
// positional params
List<User> users = db.query("SELECT * FROM users WHERE active = ?", true)
    .list(User.class);

// named params
User user = db.query("SELECT * FROM users WHERE id = :id")
    .param("id", 42)
    .one(User.class)
    .orElseThrow();

// SQL builder
db.execute(SQL.insert("users").set("name", "alice").set("email", "a@b.com"));
```

### Query Paths

- `list(Class)` — all rows as a list.
- `one(Class)` — at most one row as Optional.
- `stream(Class)` — lazy Stream (requires try-with-resources).
- `execute()` — INSERT/UPDATE/DELETE returning `ExecuteResult(rows, id)`.

### Row Mapping

Row mappers resolve automatically: records, beans, basic types (`String`, `Long`, `UUID`, etc.), and the built-in `Row` type for schema-less access:

```java
List<Row> rows = db.query("SELECT * FROM t").list(Row.class);
Row r = rows.get(0);
r.string("name");
r.decimal("amount");
r.dateTime("created_at");
```

Custom mappers register via `RowMapping` contributions:

```java
binder.contribute(RowMapping.class).add(new RowMapping(MyType.class, myMapper));
```

### Batch Operations

```java
db.batch("INSERT INTO ledger (id, name) VALUES (?, ?)")
    .rows(new Object[]{1L, "alpha"}, new Object[]{2L, "beta"})
    .execute();
// returns List<ExecuteResult> with auto-increment IDs for INSERT statements
```

### Transactions

ScopedValue-based implicit transactions — no explicit transaction object:

```java
db.transaction(() -> {
    db.execute("UPDATE ledger SET amount = amount + ? WHERE id = ?", 100L, 1L);
    db.execute("INSERT INTO audit_log (msg) VALUES (?)", "ledger updated");
});

// with isolation level
db.transaction(IsolationLevel.SERIALIZABLE, () -> {
    db.query("SELECT ...").list(User.class);
});
```

Nested transactions are detected and rejected. Auto-commit is restored on exit.

**Transaction-aware side effects:** EventBus events published inside a transaction are automatically deferred and only fire after commit. No manual wiring needed — `bus.publish()` detects the active transaction and behaves correctly:

```java
db.transaction(() -> {
    db.execute("INSERT INTO posts (title) VALUES (?)", "hello");
    bus.publish(new PostCreatedEvent(post));  // deferred, fires after commit
});
// EventBus subscribers receive PostCreatedEvent here
```

This is powered by the `Defer` mechanism (see [Defer / Scope-bound deferred execution](#defer--scope-bound-deferred-execution)). Any code can use `Defer.defer()` to register commit-side effects; the framework handles commit/rollback automatically.

### ORM

`Orm` provides lightweight CRUD on top of `Database`:

```java
Orm orm = Orm.of(db);

// insert — auto-increment id written back to beans
Comment c = new Comment("hello", 1L);
orm.insert(c);  // c.id is now set

// find
Post p = orm.findById(Post.class, 1L).orElseThrow();
List<Post> recent = orm.findAll(Post.class, "created_at DESC", 20, 0);

// update / delete
c.text = "updated";
orm.update(c);
orm.delete(c);

// upsert — insert if new, update if exists
orm.save(c);
```

Entities use `@Table`/`@Column`/`@Id`/`@Generated`/`@Transient` annotations from `com.jujin.freeway.db.schema`.

### Schema & Migrations

```java
// AutoMigrate — create missing tables / columns
Schema.ensure(database, Post.class, Comment.class);

// SQL-based migrations from db/migration/
// V001__create_users.sql, V002__add_email.sql, ...
```

### DatabaseHub

```java
Database primary = hub.primary();
Database audit = hub.get("audit");
```

### HikariCP (`freeway-db-hikari`)

Third-party connection pool adapter for [HikariCP](https://github.com/brettwooldridge/HikariCP). Add the `freeway-db-hikari` dependency to your classpath.

**Standalone usage:**

```java
PoolConfig config = PoolConfig.defaults("jdbc:postgresql://localhost/db", "user", "pass");
HikariPool pool = new HikariPool(config);
Database db = new DatabaseBuilder().config(config).pool(pool).build();
```

**IoC usage:** add `HikariPoolModule` to the launcher or install it through another module — it binds `HikariPool` as `id("hikari").primary()`, overriding the built-in `PoolDefault`. Then set `freeway.db.pool=hikari` to activate it.

```java
Launcher.run(AppModule.class, new DbModule(), new HikariPoolModule());
```

`HikariPool` maps `PoolConfig` fields to Hikari's configuration (pool size, timeouts, health check query) and adapts Hikari's `HikariPoolMXBean` stats to `DatabaseStats`.

## Defer / Scope-bound deferred execution

`Defer` is a generic mechanism in `freeway-commons` for the pattern: *"run this side effect, but only after the current boundary commits — if it rolls back, forget it."*

Under the hood it's a `ScopedValue<List<Runnable>>`: inside a scope, `Defer.defer()` appends to the list; outside, it runs immediately. On success, the scope drains the list; on failure, it discards it. The calling code doesn't need to know whether a scope is active.

### Mental model

Think of it as a "commit tray". You drop slips of paper into the tray during a unit of work. When the work succeeds, someone picks up the tray and processes every slip in order. If the work fails, the tray is emptied — nothing happened.

### Scenarios

#### DB transaction + EventBus (framework-built-in, zero user code)

The most common case — you don't write any Defer code. The framework handles it:

```java
db.transaction(() -> {
    db.execute("INSERT INTO posts ...");         // ① SQL
    bus.publish(new PostCreatedEvent(post));     // ② looks immediate, actually deferred
    db.execute("UPDATE counts ...");             // ③ more SQL
});
// ④ commit succeeds → bus fires PostCreatedEvent
//    or rollback → event never fires
```

`DatabaseImpl.transaction()` opens a `Defer` scope. `EventBus.publish()` checks `Defer.isActive()` — yes → defers. The user just calls `bus.publish()` inside a transaction and gets correct commit/rollback semantics without any wiring.

#### DB transaction + cache invalidation (manual defer)

Same transaction boundary, custom side effect:

```java
db.transaction(() -> {
    db.execute("UPDATE posts SET title = ? WHERE id = ?", title, id);
    Defer.defer(() -> cache.invalidate("posts:" + id));
    // cache only invalidated if the UPDATE actually commits
});
```

A `publishAsync` inside a transaction dispatches to another thread, where the `ScopedValue` is not bound — it will fire immediately. Use sync `publish()` for commit-safe events.

#### HTTP request lifecycle

Request-scoped side effects that should fire only after the response is written successfully:

```java
Defer.within(scope -> {
    // ... handle request, run filters, render response ...
    Defer.defer(() -> metrics.record(method, path, duration));
    Defer.defer(() -> accessLog.write(entry));

    if (response.status() >= 400) {
        scope.rollback();  // error response — don't record as success
        return;
    }
});
// metrics / accessLog only fire for successful responses
```

#### Kafka consumer — offset commit boundary

Side effects that must wait until the offset is confirmed:

```java
Defer.within(() -> {
    for (var record : records) {
        process(record);
        Defer.defer(() -> bus.publish(new RecordProcessed(record)));
    }
    consumer.commitSync();  // offset confirmed
});
// events fire only after offset commit succeeds
```

#### Batch processing — index rebuild

All-or-nothing batch with deferred heavy work:

```java
Defer.within(() -> {
    for (var row : rows) db.execute("INSERT INTO ledger ...", row);
    Defer.defer("index", () -> searchIndex.rebuild()).after("stats");
    Defer.defer("stats", () -> stats.refresh());
    Defer.defer("notify", () -> bus.publish(new BatchDone()));
});
// index/stats/notify run only if all inserts commit, and in order: stats → index → notify
```

#### Deferred value — audit snapshot

A value computed from committed state:

```java
var snap = Defer.supply(() -> snapshotDao.build());

db.transaction(() -> {
    orderService.place(order);
    // snapshotDao.build() hasn't run yet — defers until commit
});

// After transaction commits, snapshot reflects consistent state
AuditSnapshot s = snap.get();
```

### Framework scopes (built-in)

The framework opens `Defer` scopes at three natural boundaries — user code inside them can call `Defer.defer()` without any setup:

| Boundary | Opened by | Commit = | Rollback = |
|----------|----------|----------|------------|
| DB transaction | `DatabaseImpl.transaction()` | `raw.commit()` succeeds | Work throws → `raw.rollback()` |
| HTTP request | `WebServer` request handler | Request completes (even on error — errors are handled internally) | Only if filter chain throws unrecoverably |
| Kafka record | `KafkaSubscriber` poll loop | Record deserializes and publishes successfully | Deserialization or publish fails |

**In practice:** inside a DB transaction, events published via `bus.publish()` are automatically deferred. Inside any HTTP request handler, `Defer.defer()` is available. Inside a Kafka message processor, side effects are scoped to the record boundary. Users don't need to think about opening scopes — the framework already does it at every meaningful boundary.

### When NOT to use

- **Must fire regardless of success/failure** — `Defer.defer()` inside a scope only fires on success. Use regular code + try/finally for cleanup that always runs.
- **Fire-and-forget across threads** — the `ScopedValue` binding stays on the current thread. Dispatching to another thread loses the scope.
- **Long-running async work** — deferred actions run inline during scope drain. If you need async-after-commit, combine `defer()` with an executor inside the deferred action: `Defer.defer(() -> executor.submit(heavyTask))`.

### Key API

| Method | Purpose |
|--------|---------|
| `Defer.within(Runnable)` | Scope — drain on success, discard on failure |
| `Defer.within(Consumer<DeferScope>)` | Scope with `DeferScope.rollback()` for manual discard |
| `Defer.defer(Runnable)` | Unnamed action — run immediately or defer |
| `Defer.defer(String id, Runnable)` | Named action — returns `DeferAction` for `.before()` / `.after()` |
| `Defer.supply(Callable<T>)` | Deferred value — returns `Supplier<T>`, computes at commit |
| `Defer.isActive()` | True inside a `within(...)` block |

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
