# Freeway 2 Developer Guide

Freeway is a lightweight, modern Java application framework for JDK 25+. Compose-first, zero classpath scanning, zero bytecode weaving, minimal dependencies.

## Quick Start

```java
// A minimal HTTP application
public class App {
    public static void main(String[] args) {
        AppRuntime runtime = FreewayApp.run(args, new AppModule());
    }

    public static final class AppModule implements Module2 {
        public void bind(Binder b) {
            b.contribute(Route.class).add(Route.get("/", ctx ->
                ctx.send(200, "Hello Freeway")));
        }
    }
}
```

```bash
mvn test                          # all modules
mvn -pl freeway-ioc -am test      # single module + deps
mvn -pl freeway-http -am test
mvn -pl freeway-db -am test
mvn test -Dtest=CoercerDefaultTest    # single test class
```

---

## Module Dependency Graph

```
freeway-commons          shared utilities: JSON, coercion, Defer, ScopedCache, beans, validation
freeway-ioc              IoC container: bind, inject, scope, advise, event-bus, extensions
freeway-boot             launcher, config cascade, profiles, runtime lifecycle
freeway-http             HTTP/WebSocket: routing, filters, static, multipart, SSE
  ├ freeway-http-robaho  zero-dep engine with WebSocket (default)
  ├ freeway-http-undertow Undertow adapter
  └ freeway-http-jetty   Jetty adapter
freeway-db               JDBC: ORM, pooling, transactions, SQL builder, migrations
  └ freeway-db-hikari    HikariCP connection pool adapter
freeway-mq-kafka         Kafka adapter for EventBus
```

Dependencies flow downward. Core modules (`commons`, `ioc`) carry zero external dependencies beyond SLF4J API. Starter modules (`freeway-starter-*`) are empty JARs that only bundle dependency sets via POM — they contain no Java source.

```
freeway-boot        freeway-http        freeway-db
      \                 |                 /
       \                |                /
              freeway-ioc
                  |
           freeway-commons
```

---

## Module — The Fundamental Building Block

`Module2` is the central organizing concept in Freeway. Every application and library expresses its composition through modules. A module is a single unit that declares: *what services I provide, what I need from others, and what I contribute to the system.* The name `Module2` avoids a naming conflict with `java.lang.Module` (JDK 9+); conceptually it is simply Freeway's Module.

```java
@FunctionalInterface
public interface Module2 {
    void bind(Binder binder);
}
```

A module does three things in its `bind()` method:

1. **Bind services** — register implementations, set scopes, configure injection
2. **Contribute extensions** — add routes, event subscribers, runtime hooks, coercion rules
3. **Compose with other modules** — passed together to the launcher, sharing a unified binding space

### The Module Contract

Modules are **self-contained** and **declarative**. They don't start work during `bind()` — they only declare what should exist. Actual initialization happens when the container resolves services or when `RuntimeHook.start()` fires. This separation is what makes Freeway testable: you can create a container with a subset of modules and verify bindings without starting servers or opening connections.

### Application Module

Every application starts with a primary module:

```java
public class AppModule implements Module2 {
    public void bind(Binder b) {
        // Bind application services
        b.bind(UserService.class).to(UserServiceImpl.class);
        b.bind(OrderService.class).to(OrderServiceImpl.class);

        // Install framework modules (chainable on Binder)
        b.install(new HttpModule())
         .install(new DbModule());

        // Contribute routes
        b.contribute(Route.class)
            .add(Route.get("/users", ctx -> ctx.sendJson(200, users())))
            .add(Route.post("/orders", (ctx, OrderRequest body) -> {
                orderService.place(body);
                ctx.sendJson(201, Map.of("status", "ok"));
            }, OrderRequest.class));

        // Contribute lifecycle hooks
        b.contribute(RuntimeHook.class)
            .add("cache.warmup", new RuntimeHook() {
                public void start(Container c) { c.get(Cache.class).warmup(); }
                public void stop(Container c) { c.get(Cache.class).close(); }
            }).before("freeway.http.server");
    }
}

// Bootstrap
AppRuntime runtime = FreewayApp.run(args, new AppModule());
```

### Module Composition

Modules compose by passing all of them to the launcher. The container loads them all into a shared space — bindings and extensions merge across module boundaries:

```java
// Compose framework + application modules
FreewayApp.run(args, new AppModule(), new HttpModule(), new DbModule());
// or via Freeway.create()
Freeway.create(new AppModule(), new HttpModule(), new DbModule());
```

Extensions from every module merge into shared contribution lists. `before()`/`after()` ordering works across module boundaries — a security filter contributed by one module can declare it runs before all routes regardless of which module contributed them. This is the key to modular, composable applications: each module contributes its piece, and the container resolves the whole.

### Library Module — The DbModule Pattern

Freeway modules follow a unique design: **the module integrates the library into the IoC container, but the library itself has zero dependency on IoC.** This is the pattern used by `freeway-db`, `freeway-mq-kafka`, and `freeway-db-hikari`:

```
freeway-db (the library)
  ├─ Database, Orm, Pool, SQL, Row, Schema — all work standalone
  └─ DbModule — the IoC integration point
```

**Standalone usage (no IoC, no Container):**

```java
PoolConfig config = PoolConfig.defaults("jdbc:h2:mem:test", "sa", "");
Database db = new DatabaseBuilder().config(config).build();
Orm orm = Orm.of(db);
orm.insert(new Post("Hello", "World"));
```

**IoC usage (with Container):**

```java
FreewayApp.run(args, new AppModule(), new DbModule());
// Database, Orm, Coercer are now injectable
@Inject Database db;
```

`DbModule.bind()` does the integration work: it reads `PoolConfig` from the container's config cascade, creates a `Database`, binds it as a singleton, registers it in `DatabaseHub`, and wires the `RuntimeHook` for connection pool lifecycle. But the library types (`Database`, `Orm`, `Pool`) never import anything from `freeway-ioc`. This means:

- The library can be used in non-Freeway projects.
- The library can be tested without a container.
- The library can be versioned independently.
- The integration surface is a single, auditable class (`DbModule`).

### Writing a Library Module

Follow this pattern for your own libraries:

```
my-library/
├── src/main/java/com/example/mylib/
│   ├── MyService.java           # public API — no IoC imports
│   ├── MyServiceConfig.java     # configuration record
│   └── MyLibModule.java         # IoC integration — implements Module2
```

```java
// The library types — zero IoC dependency
public class MyService {
    public MyService(MyServiceConfig config) { ... }
    public void doWork() { ... }
}

public record MyServiceConfig(String url, int timeout) {
    public static MyServiceConfig from(Container c) {
        return new MyServiceConfig(
            c.get(Coercer.class).coerce(
                c.get(AppConfig.class).get("myservice.url"), String.class),
            c.get(Coercer.class).coerce(
                c.get(AppConfig.class).get("myservice.timeout"), int.class)
        );
    }
}

// The Module — the only file that imports freeway-ioc
public class MyLibModule implements Module2 {
    public void bind(Binder b) {
        b.bind(MyServiceConfig.class).to(c -> MyServiceConfig.from(c));
        b.bind(MyService.class).to(c -> new MyService(c.get(MyServiceConfig.class)));
    }
}
```

### Config-Driven Module Selection

Modules can read config to decide what to bind. The HTTP engine selection and DB pool selection both use this pattern:

```java
// freeway.db.pool=builtin  → binds PoolDefault
// freeway.db.pool=hikari   → binds HikariPool (if HikariPoolModule is installed)
b.bind(Pool.class).to(PoolDefault.class).id("builtin");
// HikariPoolModule binds: b.bind(Pool.class).to(HikariPool.class).id("hikari").primary();
```

User sets `freeway.db.pool=hikari` in config; the primary binding resolves to `HikariPool`. No code change needed.

### Built-in Framework Modules

| Module | Purpose |
|--------|---------|
| `HttpModule` | Registers `WebServer`, `RouteIndex`, `WebSocketIndex`, `CorsFilter`, `RequestTimingFilter`. Contributes `RuntimeHook` with id `"freeway.http.server"`. |
| `DbModule` | Reads `PoolConfig` from config, creates `Database` and `Orm`, binds `Pool` (selectable via `freeway.db.pool`), registers `DatabaseHub`. |
| `HikariPoolModule` | Binds `HikariPool` as a `Pool` implementation with `id("hikari").primary()`. |
| `KafkaModule` | Creates `KafkaConfig`, binds `KafkaEventBridge` and `KafkaSubscriber`, registers `RuntimeHook` for Kafka lifecycle. |

### Module Best Practices

- **One module per library.** Don't split a library's IoC integration across multiple modules.
- **Don't start work in `bind()`.** The `bind()` method declares; `RuntimeHook.start()` activates.
- **Read config at bind time, not at class load time.** Use `@Value` / `@Symbol` or read `AppConfig` through the container.
- **Use stable ids for RuntimeHooks.** Other modules may need to order relative to yours (e.g., `"freeway.http.server"`).
- **Library types should not import IoC types.** Keep the library usable without a container.

---

## IoC Container

### Core API

| Type | Purpose |
|------|---------|
| `Container` | Service lookup: `get(Class)`, `get(Class, String)`, `close()` |
| `Module2` | A module entry point: `bind(Binder)`. Named to avoid `java.lang.Module` conflict |
| `Binder` | Binding and contribution DSL |
| `Binding` | Service binding configuration: target, id, primary, scope, advisor |
| `Freeway` | Container bootstrap: `Freeway.create(Module2...)` |
| `RuntimeHook` | Start/stop lifecycle extension consumed by `AppRuntime` |
| `Scoping` | Executes work inside a `Scope.THREAD` boundary via `within()` |
| `LoggerSource` | Owner-aware logger factory service |

`ServiceId` is intentionally not a public type. String ids keep the API direct. Blank service ids are rejected. Internal normalization is handled by `ServiceIds`.

### Creating a Container

```java
// Direct container (tests, standalone)
Container c = Freeway.create(binder -> {
    binder.bind(MyService.class).to(MyServiceImpl.class);
});
MyService svc = c.get(MyService.class);
c.close();

// Full application (config, profiles, hooks, lifecycle)
AppRuntime runtime = FreewayApp.run(args, new AppModule());
Container c = runtime.container();
```

### Binding Services

```java
Freeway.create(binder -> {
    // interface → implementation
    binder.bind(Greeter.class).to(GreeterImpl.class);

    // named bindings
    binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe");
    binder.bind(PaymentGateway.class).to(PayPalGateway.class).id("paypal");

    // primary binding (injected by default when multiple exist)
    binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe").primary();

    // instance binding (must be SINGLETON)
    binder.bind(Config.class).to(new Config(...));

    // provider binding
    binder.bind(Cache.class).to(c -> new Cache(c.get(Config.class)));

    // scope
    binder.bind(RequestState.class).to(RequestState.class).scope(Scope.THREAD);

    // advice (requires interface binding)
    binder.bind(UserService.class).to(UserServiceImpl.class).advise(advisor ->
        advisor.wrap(inv -> inv.method().getName().startsWith("get"),
                     inv -> { /* before/after logic */ return inv.proceed(); }));
});
```

Advisors require interface-to-class bindings because the container uses JDK proxies.

### Resolution

```java
// by type (requires unique or primary binding)
Greeter g = container.get(Greeter.class);

// by type + id
PaymentGateway pg = container.get(PaymentGateway.class, "stripe");

// Container also available as a service
Container c = container.get(Container.class);
```

### Scopes

`scope()` is the only binding-time API for service lifecycle:

| Scope | Behavior |
|-------|----------|
| `SINGLETON` | Default. One instance per container. Destroyed on `close()`. |
| `PROTOTYPE` | New instance every resolution. Not retained by the container. |
| `THREAD` | One instance per `Scoping.within()` boundary. Auto-destroyed on exit. |

```java
binder.bind(RequestState.class).to(RequestState.class).scope(Scope.THREAD);

Scoping scoping = container.get(Scoping.class);
scoping.within(() -> {
    RequestState state = container.get(RequestState.class);
    // same instance reused within this block
});
// state destroyed — @PreDestroy + AutoCloseable invoked
```

`Scoping.within()` uses JDK 25 `ScopedValue`, so there is no `ThreadLocal` overhead on virtual threads. Nesting is supported — inner scopes shadow outer scopes. Internally, thread-scoped services are cached via `ScopedCache` (see [ScopedCache](#scopedcache--scope-bound-value-cache)).

**Scope compatibility rule:** A singleton cannot directly inject a thread-scoped concrete class. Use an interface with proxy support instead:

```java
// OK — singleton injects interface, gets a lazy proxy
binder.bind(ScopedApi.class).to(ScopedCounter.class).scope(Scope.THREAD);
binder.bind(ScopedSingletonService.class).to(ScopedSingletonService.class);

// WRONG — singleton injects concrete thread-scoped class
binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD);
binder.bind(ScopedSingleton.class).to(ScopedSingleton.class); // fails
```

### Injection

Supported annotations in `com.jujin.freeway.ioc.annotation`:

| Annotation | Purpose |
|------------|---------|
| `@Inject` | Field, constructor, or parameter injection |
| `@Named("id")` | Qualify by binding id |
| `@Symbol("key")` | Strict config lookup — missing key fails |
| `@Value("${key:default}")` | Config expression with optional default |

```java
public class UserService {
    private final UserRepository repo;

    // constructor injection (preferred for framework internals)
    public UserService(UserRepository repo) {
        this.repo = repo;
    }

    // field injection (acceptable for app code)
    @Inject
    private Logger log;

    @Inject("audit")
    private Logger audit;

    @Value("${app.timeout:30}")
    private int timeout;
}
```

Primary resolution uses `binding.primary()` on the binding DSL, not an annotation.

Annotation injection on records is also supported:

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

### Extensions

Extensions are contributed by entry type and injected as `List<V>` or `Extension<V>`:

```java
// Module: contribute
binder.contribute(Route.class).add(Route.get("/hello", ctx -> ctx.send(200, "hi")));

// named contributions with ordering
binder.contribute(RuntimeHook.class)
    .add("myHook", hook).after("freeway.http.server");

binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(OrderCreated.class, e -> notify(e)))
    .after("audit");

// Injection — List<V> is the simplest; Extension<V> gives access to .all() on demand
@Inject List<Route> routes;
routes.forEach(r -> ...);

// Or via constructor
public class Router {
    private final List<Route> routes;
    public Router(List<Route> routes) {
        this.routes = List.copyOf(routes);
    }
}
```

The entry type itself (e.g., `Route.class`) is the extension point identifier. Contributions are ordered via `add(id, value)` with `before/after`.

Rules:
- `add(value)` preserves insertion order.
- `add(id, value)` enables `before/after` constraints for topological ordering.
- Duplicate ids fail immediately. Missing order targets are ignored. Cycles fail at resolution time.
- Constructor parameters are auto-resolved; fields require `@Inject`.

### EventBus

In-process publish-subscribe built on the Extension mechanism. Events are plain objects; subscribers are contributed via `EventSubscriber` or registered at runtime.

```java
// Module-level subscribers (startup-time, supports ordering)
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(PostCreatedEvent.class, e -> index(e.post())))
    .add(EventSubscriber.of("notify", PostCreatedEvent.class, e -> sendEmail(e)))
    .after("index");

// String-topic subscribers (no event class needed)
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of("order.placed", payload -> process(payload)));

// Runtime subscribers (dynamic, no ordering)
@Inject EventBus bus;
Subscription<PostCreatedEvent> sub = bus.subscribe(PostCreatedEvent.class, e -> { ... });
bus.unsubscribe(sub);

// Publish
bus.publish(new PostCreatedEvent(post));    // class-based
bus.publish("order.placed", payload);       // string-topic
bus.publishAsync(new PostCreatedEvent(post)); // fire-and-forget (virtual threads)
```

**Key types:**

| Type | Purpose |
|------|---------|
| `EventBus` | Publish, subscribe, unsubscribe. Injected via `@Inject EventBus` |
| `EventSubscriber<E>` | Module-level subscriber: carries event type, handler, and ordering |
| `Subscription<E>` | Handle returned by `subscribe()`, used to `unsubscribe()` |
| `DeadEvent` | Published when an event has zero subscribers — subscribe for diagnostics |
| `EventBridge` | Bridge to external MQ: `EventBridge.send(topic, event)` |
| `@Topic("kafka.topic")` | Maps an event class to a cross-JVM topic name |
| `EventBus.Stoppable` | Events implementing this can `stop()` the subscriber chain |

**Short-circuit (Stoppable):** Events implementing `EventBus.Stoppable` can stop the subscriber chain — later subscribers are skipped:

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

**Async publishing:** Fire-and-forget dispatched on virtual threads by default:

```java
bus.publishAsync(new PostCreatedEvent(post));
bus.publishAsync("order.placed", payload);

// Custom executor (e.g. platform-thread pool)
Executor pool = Executors.newFixedThreadPool(4);
bus.setAsyncExecutor(pool);
```

Async dispatch defaults to `Executors.newVirtualThreadPerTaskExecutor()`. Call `setAsyncExecutor()` to replace it with a custom executor. Note: `publishAsync` dispatches to a separate thread and therefore does **not** participate in `Defer` scopes (which are thread-bound via `ScopedValue`). For transaction-safe events, use sync `publish()` — it automatically defers inside a transaction and fires on commit.

**DeadEvent diagnostics:** When zero subscribers exist for an event, a `DeadEvent` is published — subscribe to it for logging or monitoring:

```java
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(DeadEvent.class, e ->
        LOG.warn("No subscriber for {}", e.event().getClass())));
```

**Lifecycle events:** Boot publishes `AppStartedEvent` after all hooks start, and `AppStoppingEvent` before shutdown. Subscribe via EventBus instead of implementing `RuntimeHook` for non-critical work:

```java
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(AppStartedEvent.class,  e -> cache.warmup()))
    .add(EventSubscriber.of(AppStoppingEvent.class, e -> cache.flush()));
```

**Transaction-aware:** Events published inside a `db.transaction()` are automatically deferred and fire only after commit. No manual wiring needed — powered by the `Defer` mechanism (see [Defer](#defer--scope-bound-deferred-execution)).

**Cross-JVM:** Add `@Topic("kafka.topic")` on an event class + `KafkaModule` for distributed pub/sub via the `EventBridge` mechanism (see [Kafka](#kafka-freeway-mq-kafka)).

### Type Coercion

Commons owns the reusable scalar coercion mechanics (`Coercer` interface, `CoerceRule<S,T>`, `CoercerDefault`). IoC owns container-aware coercion rules and `@Symbol`/`@Value` integration. The two layers are separate: commons does not import ioc types.

```java
// Built-in: String → primitives, enums, UUID, dates, collections, maps
Coercer coercer = container.get(Coercer.class);
int port = coercer.coerce("8080", int.class);

// Custom rule — register via contribution
binder.contribute(CoerceRule.class).add(new CoerceRule<>(
    String.class,
    Endpoint.class,
    value -> {
        String[] parts = value.split(":", 2);
        return new Endpoint(parts[0], Integer.parseInt(parts[1]));
    }
));
```

### Logging Service

`LoggerSource` is a built-in service. Logger injection is owner-aware: without an explicit id, the logger name is the declaring service type.

```java
public final class UserService {
    @Inject
    private Logger log;           // logger name = "com.example.UserService"

    @Inject("audit")
    private Logger audit;         // logger name = "audit"
}

Logger log = container.get(LoggerSource.class).get(UserService.class);
```

`freeway-commons` provides a JUL-backed SLF4J 2 provider registered via standard `META-INF/services`. When no external logger (Logback, Log4j) is on the classpath, SLF4J discovers the JUL provider automatically. Framework code uses standard `LoggerFactory.getLogger()` everywhere.

---

## Boot

```java
AppRuntime runtime = FreewayApp.run(args, new AppModule());
// or with explicit module instances
AppRuntime runtime = FreewayApp.run(args, new AppModule(), new HttpModule());
```

`FreewayApp.run()` accepts command-line args and `Module2` instances. It loads config, discovers SPI modules, creates the container, starts hooks, logs startup time, and registers a JVM shutdown hook.

	For more control, use `AppBuilder`:

	```java
	AppRuntime app = FreewayApp.of(new MyModule())
	    .add(new HttpModule(), new DbModule())   // additional modules
	    .args("--freeway.profile=dev")            // config overrides
	    .classLoader(customLoader)               // custom class loader for SPI/resources
	    .autoDiscovery(false)                     // disable SPI module discovery
	    .shutdownHook(false)                      // skip JVM shutdown hook
	    .config(myConfigLoader)                   // custom ConfigLoader
	    .start();
	```

	| Type | Purpose |
	|------|---------|
	| `FreewayApp` | Application entry point: `run(args, Module2...)`, `of(Module2...)` |
	| `AppBuilder` | Fluent builder for advanced control: `autoDiscovery`, `shutdownHook`, `classLoader`, `config` |
| `AppRuntime` | Runtime API: container, config, state, start, close, `get(Class)`, `get(Class, String)` |
| `AppState` | `CREATED` → `STARTING` → `RUNNING` → `STOPPING` → `STOPPED` (or `FAILED`) |

### Runtime Hooks

Modules that own resources contribute `RuntimeHook` instead of starting work from constructors or service resolution:

```java
binder.contribute(RuntimeHook.class)
    .add("my.cache", new RuntimeHook() {
        public void start(Container c) { c.get(Cache.class).warmup(); }
        public void stop(Container c) { c.get(Cache.class).close(); }
    }).before("freeway.http.server");
```

Startup invokes hooks in resolved contribution order. Shutdown invokes only started hooks in reverse order, then closes the container.

### Config Cascade

	Lowest to highest priority:

	1. `application.properties`
	2. `application.json`
	3. `application-{profile}.properties`
	4. `application-{profile}.json`
	5. Environment variables (`FREEWAY_` prefix)
	6. CLI arguments (`--key=value`, `-Dkey=value`)

Activate profiles: `--freeway.profile=dev` or `-Dfreeway.profile=dev`.

---

## HTTP

The HTTP package stays flat under `com.jujin.freeway.http`. Public contracts and small built-ins share the same package; transport internals stay package-private where possible.

| Category | Main Types |
|----------|------------|
| Core | `HttpEngine`, `HttpContext`, `HttpFilter`, `HttpModule`, `WebServer`, `JsonCodec` |
| Routing | `Route`, `RouteGroup`, `RouteIndex`, `PathPattern` |
| Body | `BodyHandler`, `RequestContext`, `RequestContextDefault`, `MultipartForm` |
| WebSocket | `WebSocketSession`, `WebSocketListener`, `WebSocketRoute`, `WebSocketGroup`, `WebSocketIndex` |
| SSE | `SseEmitter`, `SseEvent` |
| Built-ins | `JsonCodecDefault`, `CorsFilter`, `RequestTimingFilter`, `StaticResources`, `ExceptionMapper` |

### Routes

```java
// Module contribution
binder.contribute(Route.class)
    .add(Route.get("/", ctx -> ctx.send(200, "Hello")))
    .add(Route.get("/users/:id", ctx -> {
        String id = ctx.pathVar("id");
        ctx.sendJson(200, userService.findById(id));
    }))
    .add(Route.post("/users", (ctx, body) -> {
        // auto-deserialized from JSON
        User created = userService.create(body);
        ctx.sendJson(201, created);
    }, User.class));

// Route groups
binder.contribute(RouteGroup.class)
    .add(RouteGroup.of("/api/v1",
        Route.get("/users", ctx -> ctx.sendJson(200, users())),
        Route.get("/users/:id", ctx -> ctx.sendJson(200, user(ctx.pathVar("id"))))
    ));
```

### Request Context

```java
ctx.method()            // "GET", "POST", etc.
ctx.path()              // "/users/42"
ctx.pathVar("id")       // "42"
ctx.queryParam("q")     // query string parameter
ctx.header("Accept")    // request header
ctx.body()              // raw bytes
ctx.bodyText()          // UTF-8 string
ctx.bodyAsJson(User.class)  // deserialize JSON to object
ctx.param("name")       // pathVar → queryParam → body field (convenience)
ctx.requestContext().correlationId()  // unique request id
```

### Response

```java
ctx.status(201);
ctx.headerSet("X-Custom", "value");
ctx.send(200, "plain text");
ctx.sendJson(200, object);
ctx.output("text".getBytes());
ctx.output("text");  // UTF-8 convenience
```

### Filters

```java
// Implement HttpFilter
public class AuthFilter implements HttpFilter {
    public void doFilter(HttpContext ctx, RouteHandler next) throws Exception {
        if (ctx.header("Authorization") == null) {
            ctx.send(401, "Unauthorized");
            return;
        }
        next.handle(ctx);
    }
}

// Register in module
binder.contribute(HttpFilter.class).add(new AuthFilter());
```

Built-in filters: `RequestTimingFilter` (logs request duration), `CorsFilter` (configurable CORS via `freeway.http.cors.*` keys).

### Static Resources

```java
binder.contribute(StaticResourceMount.class)
    .add(StaticResources.classpath("/", "/public"))     // from classpath
    .add(StaticResources.directory("/uploads", Path.of("/var/uploads")));  // from filesystem

// Options
StaticResources.classpath("/assets", "/static")
    .cacheMaxAgeSeconds(3600)
    .immutable(true)            // sets Cache-Control: immutable
    .fallthrough(true);         // pass to next handler on 404
```

### Multipart

```java
if (ctx.isMultipart()) {
    MultipartForm form = ctx.multipart();
    String title = form.value("title");
    Part file = form.file("attachment").orElseThrow();
    file.saveTo(Path.of("/uploads", file.filename()));
}
```

### SSE (Server-Sent Events)

```java
Route.get("/events", ctx -> {
    SseEmitter sse = ctx.sse();
    sse.send("connected");
    sse.send(new SseEvent("data", "msg-1", "update", null));
    sse.complete();
});
```

### WebSocket

```java
// Route
binder.contribute(WebSocketRoute.class)
    .add(WebSocketRoute.of("/ws/chat", session -> new WebSocketListener() {
        public void onText(String text) {
            session.sendText("Echo: " + text);
        }
        public void onClose(int code, String reason, boolean remote) {
            log.info("Closed: {}", reason);
        }
    }));

// Group
binder.contribute(WebSocketGroup.class)
    .add(WebSocketGroup.of("/ws", WebSocketRoute.of("/chat", listener)));
```

### Exception Mapping

```java
binder.contribute(ExceptionMapper.class).add((ctx, ex) -> {
    if (ex instanceof NotFoundException) {
        ctx.sendJson(404, Map.of("error", ex.getMessage()));
        return true;  // handled
    }
    return false;     // not handled, continues to next mapper
});
```

### Engine Selection

`web.engine` config property selects the transport:

| Value | Engine |
|-------|--------|
| `robaho` (default) | Zero-dep, WebSocket |
| `jdk` | Built-in JDK, HTTP only |
| `undertow` | Undertow adapter |
| `jetty` | Jetty adapter |

Engine adapters bind their engine by string id:

```java
binder.bind(MyEngine.class).to(MyEngine.class).id("my-engine");
```

### Testing with HTTP

When using `Container` directly (not `FreewayApp`), start the server explicitly:

```java
WebServer server = container.get(WebServer.class);
server.start();
try {
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + server.port() + "/api/test"))
            .GET().build(),
        BodyHandlers.ofString());
    assertEquals(200, resp.statusCode());
} finally {
    server.stop();
}
```

---

## Database

`freeway-db` can be used independently outside the IoC container — only `freeway-commons` is required at runtime. `freeway-ioc` is optional and loaded only when using `DbModule`.

### Standalone Usage

```java
PoolConfig config = PoolConfig.defaults("jdbc:h2:mem:test", "sa", "");
Database db = new DatabaseBuilder().config(config).build();
Orm orm = Orm.of(db);
```

### IoC Usage

```java
FreewayApp.run(args, new AppModule(), new DbModule());
// Database and Orm are now injectable
```

### Queries

```java
// Positional params
List<User> users = db.query("SELECT * FROM users WHERE active = ?", true)
    .list(User.class);

// Named params (:$name or :name)
User u = db.query("SELECT * FROM users WHERE id = :id")
    .param("id", 42)
    .one(User.class)
    .orElseThrow();

// Stream (requires try-with-resources)
try (var stream = db.query("SELECT * FROM big_table").stream(Row.class)) {
    stream.forEach(row -> process(row));
}

// Execute (INSERT/UPDATE/DELETE)
ExecuteResult r = db.execute("INSERT INTO users (name) VALUES (?)", "Alice");
r.rows();   // affected rows
r.key();    // generated key (may be null)
```

Query paths:
- `list(Class)` — all rows as a list.
- `one(Class)` — at most one row as `Optional`.
- `stream(Class)` — lazy `Stream` (requires try-with-resources).
- `execute()` — INSERT/UPDATE/DELETE returning `ExecuteResult(rows, key)`.

### Row Mapping

Auto-mapping for records, beans, and basic types (`String`, `Long`, `Integer`, `UUID`, `BigDecimal`, `LocalDate`, `LocalDateTime`, etc.).

```java
// Raw Row access
List<Row> rows = db.query("SELECT * FROM t").list(Row.class);
Row r = rows.get(0);
r.string("name");
r.decimal("amount");
r.dateTime("created_at");

// Custom mapper
binder.contribute(RowMapping.class)
    .add(new RowMapping(MyType.class, (rs, rowNum) -> new MyType(...)));
```

### SQL Builder

```java
SQL sql = SQL.select("u.name, o.total")
    .from("users u")
    .join("orders o").on("u.id = o.user_id")
    .where("u.active = ?", true)
    .orderBy("o.total DESC")
    .limit(10);

db.query(sql).list(UserOrderView.class);
```

Supports: `SELECT`, `INSERT`, `UPDATE`, `DELETE`, CTEs (`WITH`), `JOIN`/`LEFT JOIN`/`INNER JOIN`, `ON`, `WHERE`/`OR WHERE`/`WHERE NOT`, grouped conditions, `GROUP BY`/`HAVING`, `ORDER BY`, `LIMIT`/`OFFSET`, `UNION`/`UNION ALL`, `RETURNING`, `ON CONFLICT DO UPDATE/NOTHING`.

### Transactions

ScopedValue-based implicit transactions — no explicit transaction object:

```java
// Basic
db.transaction(() -> {
    db.execute("UPDATE ledger SET amount = amount + ? WHERE id = ?", 100L, 1L);
    db.execute("INSERT INTO audit_log (msg) VALUES (?)", "transfer");
});

// Isolation level
db.transaction(IsolationLevel.SERIALIZABLE, () -> {
    db.query("SELECT ...").list(User.class);
});
```

Nested transactions are detected and rejected. Auto-commit is restored on exit. Queries inside the transaction automatically use the same connection.

**Transaction-aware side effects:** EventBus events published inside a transaction are automatically deferred and only fire after commit — no manual wiring needed. This is powered by the `Defer` mechanism (see [Defer](#defer--scope-bound-deferred-execution)):

```java
db.transaction(() -> {
    db.execute("INSERT INTO posts (title) VALUES (?)", "hello");
    bus.publish(new PostCreatedEvent(post));  // deferred, fires after commit
});
// EventBus subscribers receive PostCreatedEvent here
```

### ORM

`Orm` provides lightweight CRUD on top of `Database`:

```java
Orm orm = Orm.of(db);

// Entity
@Table("posts")
public class Post {
    @Id @Generated
    private Long id;
    private String title;
    @Column("created_at")
    private LocalDateTime createdAt;
}

// CRUD
Comment c = new Comment("hello", 1L);
orm.insert(c);                           // auto-increment id written back
orm.findById(Post.class, 1L);            // Optional<Post>
orm.findAll(Post.class);                 // List<Post>
orm.findAll(Post.class, "created_at DESC", 20, 0);  // with pagination
orm.save(post);                          // upsert — insert if new, update if exists
c.text = "updated";
orm.update(c);
orm.delete(c);
orm.deleteById(Post.class, 1L);
```

Annotations: `@Table`, `@Column`, `@Id`, `@Generated`, `@Transient`, `@Index`.

### Connection Pool

`Pool` is the connection pool abstraction — `PoolDefault` (built-in) and `HikariPool` (`freeway-db-hikari`) both implement it.

**Standalone — built-in pool:**

```java
PoolConfig config = PoolConfig.defaults("jdbc:postgresql://localhost/db", "user", "pass");

// Or with custom sizing
new PoolConfig(url, user, pass,
    20,     // maxSize
    5,      // minIdle
    Duration.ofSeconds(30),   // connectionTimeout
    Duration.ofMinutes(30),   // maxLifetime
    Duration.ofMinutes(10),   // maxIdleTime
    Duration.ofMinutes(2),    // cleanInterval
    null,                     // healthCheckQuery (uses JDBC isValid)
    Duration.ofSeconds(5),    // healthCheckTimeout
    Duration.ofSeconds(15)    // queryTimeout
);
```

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

`DatabaseBuilder` accepts an optional `pool(Pool)` override:

```java
// Default — DatabaseBuilder creates a PoolDefault from PoolConfig
Database db = new DatabaseBuilder().config(config).build();

// Explicit pool — pass a pre-built instance
Pool pool = new PoolDefault(config);
Database db = new DatabaseBuilder().config(config).pool(pool).build();
```

**IoC pool selection:** when using `DbModule`, the pool is selected via `freeway.db.pool` config property (default `"builtin"`). The built-in `id("builtin")` binding creates a `PoolDefault`; binding another `Pool` implementation with id and primary selects that implementation — mirroring the HTTP engine selection pattern:

```java
// freeway.db.pool=hikari → HikariPool is primary
FreewayApp.run(args, new AppModule(), new DbModule(), new HikariPoolModule());
```

### HikariCP (`freeway-db-hikari`)

Third-party connection pool adapter for [HikariCP](https://github.com/brettwooldridge/HikariCP). Add the `freeway-db-hikari` dependency to your classpath.

**Standalone usage:**

```java
PoolConfig config = PoolConfig.defaults("jdbc:postgresql://localhost/db", "user", "pass");
HikariPool pool = new HikariPool(config);
Database db = new DatabaseBuilder().config(config).pool(pool).build();
```

**IoC usage:** add `HikariPoolModule` to the launcher — it binds `HikariPool` as `id("hikari").primary()`, overriding the built-in `PoolDefault`. Then set `freeway.db.pool=hikari` to activate it.

`HikariPool` maps `PoolConfig` fields to Hikari's configuration (pool size, timeouts, health check query) and adapts Hikari's `HikariPoolMXBean` stats to `DatabaseStats`.

### Batch

```java
List<ExecuteResult> results = db.batch("INSERT INTO t (a, b) VALUES (?, ?)")
    .rows(new Object[]{1, "a"}, new Object[]{2, "b"})
    .execute();
```

### Schema & Migrations

```java
// AutoMigrate from entity classes
Schema.ensure(database, User.class, Post.class);

// SQL-based migrations (files in db/migration/)
// V001__create_users.sql, V002__add_email.sql, ...
MigrationRunner runner = new MigrationRunner(database, true, "db/migration", "schema_version");
runner.run();
```

### DatabaseHub

```java
binder.contribute(DatabaseNamed.class)
    .add(new DatabaseNamed("audit", auditDb))
    .add(new DatabaseNamed("primary", mainDb));

DatabaseHub hub = container.get(DatabaseHub.class);
Database primary = hub.primary();
Database audit = hub.get("audit");
```

---

## Kafka (`freeway-mq-kafka`)

Distributed pub/sub via EventBus. Add `KafkaModule` to enable:

```java
FreewayApp.run(args, new AppModule(), new KafkaModule());
```

Config in `application.properties`:

```properties
freeway.kafka.bootstrap-servers=localhost:9092
freeway.kafka.group-id=my-app
freeway.kafka.topics=post.created,order.placed
```

**Key types:**

| Type | Purpose |
|------|---------|
| `KafkaEventBridge` | Implements `EventBridge`, sends events to Kafka |
| `KafkaSubscriber` | Polls Kafka, publishes to local `EventBus` |
| `KafkaConfig` | Bootstrap servers, group-id, topic list |
| `KafkaModule` | Registers all services + `RuntimeHook` wiring |

**Sending:** EventBus automatically bridges to Kafka when an `EventBridge` is configured:

```java
@Topic("post.created")
public record PostCreatedEvent(Long postId, String title) {}

bus.publish(new PostCreatedEvent(1L, "Hello"));
// → local subscribers + Kafka broker
```

**Receiving:** `KafkaSubscriber` polls Kafka and publishes to local EventBus. Messages carry an `X-Event-Type` header for automatic type deserialization.

---

## Commons Utilities

### JSON

```java
// Parse
JsonObject obj = JsonUtils.parseObject("{\"name\":\"Alice\"}");
JsonArray arr = JsonUtils.parseArray("[1, 2, 3]");

// Build
JsonObject o = JsonUtils.object()
    .put("name", "Alice")
    .object("address").put("city", "NYC");

// Serialize
String json = JsonUtils.stringify(obj);
String pretty = JsonUtils.stringifyPretty(obj);

// Codec (injectable)
@Inject JsonCodec codec;
String json = codec.toJson(user);
User u = codec.fromJson(json, User.class);
```

### Coercion

```java
// Built-in: String → primitives, enums, UUID, dates, collections, maps
Coercer coercer = container.get(Coercer.class);
int port = coercer.coerce("8080", int.class);

// Custom rule
binder.contribute(CoerceRule.class).add(new CoerceRule<>(
    String.class, MyType.class, value -> MyType.parse(value)
));
```

### Validation

```java
public class CreateUserRequest {
    @NotBlank
    private String name;

    @NotNull @Size(min = 1, max = 150)
    private Integer age;

    @Valid
    private Address address;
}

ValidationResult result = BeanValidator.validate(request);
if (result.hasErrors()) {
    result.getErrors().forEach(e ->
        log.warn("{}: {}", e.field(), e.message()));
}
```

Annotations: `@NotNull`, `@NotBlank`, `@Size(min, max)`, `@Min`, `@Max`, `@Valid` (recursive).

### Defer — Scope-bound Deferred Execution

*"Run this side effect, but only after the current boundary commits. If it rolls back, forget it."*

Under the hood it's a `ScopedValue<List<Runnable>>`: inside a scope, `Defer.defer()` appends to the list; outside, it runs immediately. On success, the scope drains the list; on failure, it discards it. The calling code doesn't need to know whether a scope is active.

**Mental model — the "commit tray":** You drop slips of paper into the tray during a unit of work. When the work succeeds, someone picks up the tray and processes every slip in order. If the work fails, the tray is emptied — nothing happened.

**Basic usage:**

```java
// Inside a Defer scope
Defer.within(() -> {
    db.execute("UPDATE ...");
    Defer.defer(() -> cache.invalidate("key"));   // runs after commit
    Defer.defer("index", () -> rebuildIndex());    // ordered
        .after("cache");
});
// cache invalidated → index rebuilt

// Outside scope — runs immediately
Defer.defer(() -> log.info("done"));

// Deferred value (computed at commit time)
Supplier<Snapshot> snap = Defer.supply(() -> buildSnapshot());
```

**Scenario 1 — DB transaction + EventBus (framework-built-in, zero user code):**

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

`DatabaseImpl.transaction()` opens a `Defer` scope. `EventBus.publish()` checks `Defer.isActive()` — yes → defers. The user gets correct commit/rollback semantics without any wiring.

**Scenario 2 — DB transaction + custom cache invalidation:**

```java
db.transaction(() -> {
    db.execute("UPDATE posts SET title = ? WHERE id = ?", title, id);
    Defer.defer(() -> cache.invalidate("posts:" + id));
    // cache only invalidated if the UPDATE actually commits
});
```

**Scenario 3 — HTTP request lifecycle:**

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

**Scenario 4 — Kafka consumer offset commit boundary:**

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

**Scenario 5 — Batch processing with all-or-nothing semantics:**

```java
Defer.within(() -> {
    for (var row : rows) db.execute("INSERT INTO ledger ...", row);
    Defer.defer("index", () -> searchIndex.rebuild()).after("stats");
    Defer.defer("stats", () -> stats.refresh());
    Defer.defer("notify", () -> bus.publish(new BatchDone()));
});
// index/stats/notify run only if all inserts commit, and in order: stats → index → notify
```

**Scenario 6 — Deferred value (audit snapshot):**

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

**Framework scopes (built-in):**

The framework opens `Defer` scopes at these natural boundaries — user code inside them can call `Defer.defer()` without any setup:

| Boundary | Opened by | Commit = | Rollback = |
|----------|----------|----------|------------|
| DB transaction | `DatabaseImpl.transaction()` | `raw.commit()` succeeds | Work throws → `raw.rollback()` |
| HTTP request | `WebServer` request handler | Request completes (even on error — errors are handled internally) | Only if filter chain throws unrecoverably |
| Kafka record | `KafkaSubscriber` poll loop | Record deserializes and publishes successfully | Deserialization or publish fails |

**When NOT to use:**

- **Must fire regardless of success/failure** — `Defer.defer()` inside a scope only fires on success. Use regular code + try/finally for cleanup that always runs.
- **Fire-and-forget across threads** — the `ScopedValue` binding stays on the current thread. Dispatching to another thread loses the scope.
- **Long-running async work** — deferred actions run inline during scope drain. If you need async-after-commit, combine `defer()` with an executor inside the deferred action: `Defer.defer(() -> executor.submit(heavyTask))`.

**Key API:**

| Method | Purpose |
|--------|---------|
| `Defer.within(Runnable)` | Scope — drain on success, discard on failure |
| `Defer.within(Consumer<DeferScope>)` | Scope with `DeferScope.rollback()` for manual discard |
| `Defer.defer(Runnable)` | Unnamed action — run immediately or defer |
| `Defer.defer(String id, Runnable)` | Named action — returns `DeferAction` for `.before()` / `.after()` |
| `Defer.supply(Callable<T>)` | Deferred value — returns `Supplier<T>`, computes at commit |
| `Defer.isActive()` | True inside a `within(...)` block |

### ScopedCache — Scope-bound Value Cache

*"Cached within a scope, cleaned up on exit."*

`ScopedCache` is the dual of `Defer`: instead of buffering actions for commit-time execution, it caches key-value pairs for the lifetime of a scope and cleans them up on exit. Both use `ScopedValue` for implicit context propagation.

**Mental model:** A "scope-lifetime cache" — you look up a value by key inside a scope; the first lookup creates it, subsequent lookups return the same instance. When the scope exits (normally or exceptionally), all cached values are passed through registered cleanup handlers.

**API:**

| Method | Purpose |
|--------|---------|
| `ScopedCache.within(Supplier<T>)` | Scope — caches values, cleans up on exit |
| `ScopedCache.within(Function<Session, T>)` | Scope with `Session` handle for manual close |
| `ScopedCache.get(Object key, Supplier<V>)` | Get or create in current scope; no caching outside scope |
| `ScopedCache.onClose(Consumer<Object>)` | Register a global cleanup callback (applied per-value on exit) |
| `ScopedCache.isActive()` | True inside a `within(...)` block |
| `ScopedCache.currentSession()` | Current `Session`, or null if outside scope |

**IoC integration.** IoC registers its lifecycle callback once via `ScopedCache.onClose()` — this runs `@PreDestroy` and `AutoCloseable.close()` on every thread-scoped service instance when its scope exits:

```java
// ContainerImpl static initializer
ScopedCache.onClose(v -> {
    Lifecycle.invokePreDestroy(v);
    if (v instanceof AutoCloseable c) c.close();
});
```

`ServiceRuntime` resolves thread-scoped services through `ScopedCache.get()`:

```java
// ServiceRuntime.realizeThreadScoped()
ServiceKey key = new ServiceKey(binding.type(), binding.id());
return ScopedCache.get(key, binding::directInstance);
```

**Standalone usage.** `ScopedCache` can be used independently of IoC for any scoped resource management:

```java
ScopedCache.onClose(v -> { if (v instanceof AutoCloseable c) c.close(); });

ScopedCache.within(() -> {
    Connection conn = ScopedCache.get("db", () -> dataSource.getConnection());
    // same key → same connection reused within scope
});
// connection auto-closed on exit
```

---

## Testing

```java
// IoC tests
Container c = Freeway.create(binder -> {
    binder.bind(MyService.class).to(MyServiceImpl.class);
});
MyService svc = c.get(MyService.class);
// ... test ...
c.close();

// HTTP tests (in-process)
WebServer server = container.get(WebServer.class);
server.start();
try {
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + server.port() + "/api/test"))
            .GET().build(),
        BodyHandlers.ofString());
    assertEquals(200, resp.statusCode());
} finally {
    server.stop();
}

// DB tests (H2 in-memory)
PoolConfig config = PoolConfig.defaults("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "");
Database db = new DatabaseBuilder().config(config).build();
Schema.ensure(db, TestEntity.class);
```

For IoC tests, use `Freeway.create(...)`. For application integration tests, use `FreewayApp.run(...)` so runtime hooks and shutdown behavior are exercised.

---

## Architecture & Design

### Architecture Baseline

- **`Container`** is the IoC boundary only. It exposes service lookup and `close()`, not application runtime operations.
- **`AppRuntime`** is the application boundary above `Container`. It owns config, profiles, runtime state, startup, shutdown, and runtime hooks.
- Service ids are **plain strings** and are normalized internally by `ServiceIds`. There is no public `ServiceId` type.
- Service lifecycle is declared only with `bind().scope(...)`: `SINGLETON`, `PROTOTYPE`, `THREAD`.
- **`Defer` and `ScopedCache`** are parallel `ScopedValue`-based primitives in commons. `Defer` buffers actions for commit-time drain; `ScopedCache` caches key-value pairs with lifecycle cleanup on scope exit. IoC's thread scope is built on `ScopedCache`.
- Thread-scoped services use **`Scoping.within()`** to enter an execution boundary; the scope auto-closes when the work lambda completes. Backed by JDK 25 `ScopedValue` — no `ThreadLocal` overhead on virtual threads.
- **`RuntimeHook`** provides start/stop extension points for modules. Ordered via `add(id, value).before()` / `.after()`. HTTP startup uses hook id `"freeway.http.server"` — no longer a side effect of resolving `WebServer`.
- **`LoggerSource`** is the built-in logger service. Commons provides a JUL-backed SLF4J provider via standard `META-INF/services` discovery; it only activates when no external SLF4J provider is detected.

### Naming Rules

- **Public interfaces** use the bare domain name: `Container`, `JsonCodec`, `RequestContext`.
- **Framework-provided default implementations** use `XDefault` suffix: `AppRuntimeDefault`, `JsonCodecDefault`, `CoercerDefault`. This keeps interface names dominant.
- **`DefaultX`** form is avoided — it hides the concept at the end of the name.
- **`Impl` suffix** is reserved for uninteresting concrete implementations where no default strategy is being expressed.
- **Internal helpers** stay package-private where possible, e.g., `ServiceIds`.

### Code Style

- **JDK 25+.**
- **No classpath scanning, no bytecode weaving.**
- Core modules (`commons`, `ioc`) have **zero external dependencies** beyond SLF4J API. Adapter modules are the exception.
- **Constructor injection** is preferred for framework internals. **Field injection** is acceptable for concise app code and config values.
- Prefer **small, explicit APIs** over future-proof abstractions.
- Keep concepts few: **Module**, **Service**, **Extension**, **Scope**, **Runtime**.
