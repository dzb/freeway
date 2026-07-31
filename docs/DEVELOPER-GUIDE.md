# Freeway 2 Developer Guide

Freeway is a lightweight, modern Java application framework for JDK 25+. Compose-first, zero classpath scanning, zero bytecode weaving, minimal dependencies.

## Quick Start

Lambda handlers are the simplest way to get started:

```java
// A minimal HTTP application
public class App {
    public static void main(String[] args) {
        try (AppRuntime runtime = FreewayApp.run(args, new AppModule())) {
            // use runtime
        }
    }

    public static final class AppModule implements ModuleEx {
        public void bind(Binder b) {
            b.contribute(Route.class).add(Route.get("/", ctx ->
                ctx.send(200, "Hello Freeway")));
        }
    }
}
```

When a handler needs injected services, use a handler class instead.
The container creates it and injects its dependencies via the constructor:

```java
public class App {
    public static void main(String[] args) {
        try (AppRuntime runtime = FreewayApp.run(args, new AppModule())) {
        }
    }

    public static final class AppModule implements ModuleEx {
        public void bind(Binder b) {
            b.bind(UserService.class).to(UserService.class);
            b.contribute(Route.class)
                .add(Route.get("/api/users/{id}", UserHandlers.GetUser.class));
        }
    }

    // Route handler with injected service
    public static final class UserHandlers {
        public static final class GetUser implements RouteHandler {
            private final UserService service;
            public GetUser(UserService service) { this.service = service; }

            public void handle(HttpContext ctx) throws Exception {
                var user = service.findById(ctx.pathVar("id"));
                user.ifPresentOrElse(
                    u -> ctx.sendJson(200, u),
                    () -> ctx.send(404, "Not found"));
            }
        }
    }
}
```

**Rule of thumb:** use a lambda when the handler is stateless. Use a handler
class with constructor injection when it depends on services.

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
freeway-commons          shared utilities: JSON, coercion, scoped primitives, beans, validation
freeway-ioc              IoC container: bind, inject, scope, advise, event-bus, extensions
freeway-boot             launcher, config cascade, profiles, runtime lifecycle
freeway-http             HTTP/WebSocket: routing, filters, static, multipart, SSE
  └ built-in              FreewayHttpEngine (HTTP/1.1 + HTTP/2 + WebSocket + HTTPS)
  └ engine adapters       Undertow, Jetty → see freeway-ext
freeway-db               JDBC: ORM, pooling, transactions, SQL builder, migrations
  └ connection pool       HikariCP adapter → see freeway-ext
freeway-flow             Graph workflow engine — 7 node types, JSON graphs, tracing
freeway-mq-kafka         Kafka adapter for EventBus → see freeway-ext
```

Dependencies flow downward. Core modules (`commons`, `ioc`) carry zero external dependencies beyond SLF4J API. Engine/connection-pool/MQ adapters with third-party library integrations live in the [freeway-ext](https://github.com/dzb/freeway-ext) repository, keeping the core modules dependency-free.

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

> **Auto-discovery**: `FreewayApp.run(...)` / `AppBuilder.start()` load additional
> `ModuleEx` implementations declared via `META-INF/services/com.jujin.freeway.ioc.ModuleEx`
> (ServiceLoader SPI) **by default**. This is opt-out — call
> `FreewayApp.of(...).autoDiscovery(false)` (or `.shutdownHook(false)` for the JVM
> shutdown hook) when you want purely explicit wiring.

Module is the central organizing concept in Freeway. A module declares services, contributions, and composition. `ModuleEx` is only the Java type name used to avoid a conflict with `java.lang.Module`; conceptually, Freeway talks about modules.

The module contract is simple:

- `bind()` declares services and contributions
- `bind()` does not start work
- initialization happens when services resolve or runtime hooks fire
- modules compose explicitly at startup

Typical module shapes:

- application modules wire application services and framework modules together
- library modules keep the public API free of IoC imports and expose one integration module
- framework modules register infrastructure defaults and runtime hooks
- config-driven modules select one implementation from a set of bindings

For the full module patterns, see [freeway-module.md](freeway-module.md).

---

## IoC Container

### Core API

| Type | Purpose |
|------|---------|
| `Container` | Service lookup: `get(Class)`, `get(Class, String)`, `get(Class, Annotation...)`, `extension(Class)`, `create(Class)`, `close()` |
| `ModuleEx` | Module entry-point type: `bind(Binder)`. Named to avoid `java.lang.Module` conflict |
| `Binder` | Binding and contribution DSL |
| `Binding` | Service binding configuration: target, id, primary, scope, advisor |
| `Freeway` | Container bootstrap for composing modules: `Freeway.create(ModuleEx...)` |
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
AppRuntime runtime = FreewayApp.run(new String[0], new AppModule());
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

### Binding Registration & Conflict Resolution

Bindings are registered after each module's `bind()` method completes — not during the fluent chain. This means `.id()`, `.scope()`, `.primary()` etc. are all resolved before the binding enters the index. Combined with unique default ids (internal, not user-facing), the container avoids false collisions during module loading.

| Scenario | Outcome |
|---|---|
| Two modules bind same type, one sets `.id("xxx")` | ✅ Registered under different ids — no collision |
| Two modules bind same type, both keep default id | ✅ Both registered, `get()` → `findUnique` reports multiple matches and asks for `.primary()` |
| Same module binds same type twice, no explicit id | ✅ Both registered, `get()` → `findUnique` reports multiple matches |
| Two modules bind same type with the **same** explicit id | ❌ `updateId()` detects the collision at registration time |
| Same module binds same type twice with the same explicit id | ❌ Caught at registration time |

### Resolution

```java
// by type (requires unique or primary binding)
Greeter g = container.get(Greeter.class);

// by type + id
PaymentGateway pg = container.get(PaymentGateway.class, "stripe");

// by type + marker annotations (containsAll semantics)
Cache cache = container.get(Cache.class, Fast.class);

// Container also available as a service
Container c = container.get(Container.class);
```

### Markers

Marker annotations enable type-safe service disambiguation:

```java
// Define a marker annotation
@Retention(RUNTIME) @Target({TYPE, PARAMETER, FIELD})
public @interface Fast {}

// Bind with marker
binder.bind(Cache.class).to(FastCache.class).marker(Fast.class);

// Module-level markers propagate to all bindings
@Marker(Builtin.class)
public class AppModule implements ModuleEx { ... }
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

`@Symbol` and `@Value` serve two distinct purposes:

- **`@Symbol("key")`** — for **required** configuration. The key *must* exist;
  if absent the container refuses to start. Use this for mandatory settings
  like server ports, database URLs, or credentials — values without which
  the application cannot function.

- **`@Value("${key:default}")`** — for **optional** configuration. The
  expression is expanded from the config cascade; if the key is missing the
  default value after the colon is used. Use this for settings with sensible
  fallbacks like timeouts, feature flags, or cosmetic names.

```java
// Required — startup fails if absent
@Symbol("db.url") String dbUrl;

// Optional — defaults to 30 if app.timeout is not set
@Value("${app.timeout:30}") int timeout;

// Expression expansion — any config key can be interpolated
@Value("${app.name:freeway}") String appName;
```

Both paths use the container's type coercion mechanism, so values are
automatically converted to the target type (`int`, `boolean`, `Duration`, etc.).

To emit a literal `${` in a value (e.g. shell-style text), escape it with a
backslash — `\${total}` stays as-is and is never expanded. An even run of
backslashes keeps the expression active (`\\${key}` → a literal `\` plus the
expanded value).

### Extensions

Extensions are contributed by entry type and consumed via `List<V>` or
`Map<String, V>` injection:

```java
// Module: contribute
binder.contribute(Route.class).add(Route.get("/hello", ctx -> ctx.send(200, "hi")));

// named contributions with ordering
binder.contribute(RuntimeHook.class)
    .add("myHook", hook).after("freeway.http.server");

binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(OrderCreated.class, e -> notify(e)))
    .after("audit");

// Consumption — inject List<V> or Map<String, V>
@Inject List<Route> routes;           // all contributions, ordered
routes.forEach(r -> ...);

@Inject Map<String, FlowDriver> drivers;  // only named contributions, keyed by id
FlowDriver custom = drivers.get("custom");

// Or via constructor
public class Router {
    private final List<Route> routes;
    public Router(List<Route> routes) {
        this.routes = List.copyOf(routes);
    }
}
```

**Choosing between List and Map:**

| Injection | Returns | When to use |
|---|---|---|
| `List<V>` | All contributions (named + unnamed), ordered | Iteration, registration, filter chains |
| `Map<String, V>` | Named contributions only, keyed by contribution id | Look up a specific entry by name |

**How contributions map to injection targets:**

| Contribution | Appears in `List<V>` | Appears in `Map<String, V>` |
|---|---|---|
| `add(value)` — unnamed | ✅ | ❌ |
| `add("id", value)` — named | ✅ | ✅ (keyed by id) |
| `add(Class)` — auto-instantiated | ✅ | ✅ (keyed by auto-generated id) |

This means `List<V>` always gives you every contribution in order.
`Map<String, V>` gives you only the ones you can look up by name.

`Extension<V>` is intentionally not injectable — it is a mutable aggregation
handle used by framework modules. Application code uses `List<V>` or
`Map<String, V>` for consumption.

The entry type itself (e.g., `Route.class`) is the extension point identifier. Contributions are ordered via `add(id, value)` with `before/after`.

The three `add` variants are deliberately distinct, not an API gap waiting for a unifying default:

- `add(value)` — unnamed, insertion order. No id. Not in `asMap()`. Use when only iteration order matters (routing, filters) and id-based lookup is irrelevant.
- `add(id, value)` — named, with explicit id. Supports `before/after`. Included in `asMap()`. Use when the consumer needs to resolve a specific entry by name (drivers, runtime hooks).
- `add(Class)` — named, with auto-generated canonical id (`snake_name@package`). Supports `before/after`. Included in `asMap()`. Use when the class itself is the natural identifier.

`Extension.asMap()` returns only named contributions — this is by design, not a limitation. Unnamed entries serve iteration order; named entries serve identity. Forcing auto-generated ids onto unnamed entries would blur this distinction without solving a real problem.

Rules:
- `add(value)` preserves insertion order.
- `add(id, value)` enables `before/after` constraints for topological ordering.
- `add(Class)` auto-instantiates the contributed class from the container and generates a canonical id as `snake_name@package` (e.g. `email_sender@com.example.flow`). Supports `before`/`after` ordering on the returned `Contribution`.
- Duplicate ids fail immediately. Unknown order targets throw `IllegalArgumentException` at resolution time. Cycles fail at resolution time.
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

`freeway-commons` provides a JUL-backed SLF4J 2 provider — zero-dependency, enabled by default. When no external logger (Logback, Log4j) is on the classpath, SLF4J discovers the JUL provider automatically. Adding Logback switches seamlessly without code changes.

Logging works **out of the box** with sensible defaults: ANSI-colored console output, rotating file logging at `logs/{app.name}.log`. Configuration is through `freeway-log.properties` on the classpath root (not bundled in the JAR). System properties (`-D`) and `FREEWAY_` env vars override file values.

```properties
freeway.log.level=INFO
freeway.log.file=auto                    # logs/{app.name}.log, rotation + GZIP
```

For multi-file logging, per-logger levels, env var support, and the full config reference, see [Commons Reference](freeway-commons.md#logging).

---

## Boot

```java
AppRuntime runtime = FreewayApp.run(new String[0], new AppModule());
// or with explicit module instances
AppRuntime runtime = FreewayApp.run(new String[0], new AppModule(), new HttpModule());
```

`FreewayApp.run()` accepts command-line args and module instances. It loads config, discovers SPI modules, creates the container, starts hooks, logs startup time, and registers a JVM shutdown hook.

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
| `FreewayApp` | Application entry point: `run(args, ModuleEx...)`, `of(ModuleEx...)` |
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
5. Environment variables — `FREEWAY_DB_URL` → `freeway.db.url` (prefix stripped, `_` → `.`, `freeway.` prepended)
6. CLI arguments (`--key=value`, `-Dkey=value`)

CLI keys without a dot (e.g. `--profile=dev`) auto-receive the `freeway.`
prefix, so `--profile=dev` and `--freeway.profile=dev` are equivalent.
Dotted keys (`--app.name=foo`) pass through unchanged.
Activate profiles: `--profile=dev` or `--freeway.profile=dev`.

---

## HTTP

Three-layer architecture: **engine layer** handles transport (socket I/O, protocol parsing), **orchestration layer** (`WebServer`) wires filters and routing, **integration layer** (`HttpModule`) bridges to IoC.

| Category | Main Types |
|----------|------------|
| Core | `HttpEngine`, `HttpContext`, `HttpFilter`, `HttpModule`, `WebServer`, `JsonCodec` |
| Engine (shared) | `FreewayHttpEngine`, `FreewayHttpContext`, `HttpSession`, `ServerHandle`, `BufferedInputStream/OutputStream`, `FixedLengthInputStream`, `ChunkedInputStream` |
| Engine (HTTP/1.1) | `Http11Connection`, `HttpParser` in `engine/http11/` |
| Engine (HTTP/2) | `Http2Connection`, `Http2Stream`, `FrameSerializer`, `HPackContext` etc in `engine/http20/` |
| Engine (WebSocket) | `WebSocketFrame`, `WebSocketSessionImpl`, `WsUtil` etc in `engine/ws/` |
| Routing | `Route`, `RouteGroup`, `RouteIndex`, `PathPattern` |
| Body | `BodyHandler`, `RequestContext`, `RequestContextDefault`, `MultipartForm` |
| WebSocket | `WebSocketSession`, `WebSocketListener`, `WebSocketRoute`, `WebSocketGroup`, `WebSocketIndex` |
| SSE | `SseEmitter`, `SseEvent` |
| Built-ins | `JsonCodecDefault`, `CorsFilter`, `HealthFilter`, `HealthCheck`, `RequestTimingFilter`, `StaticResourceMount`, `ExceptionMapper` |

Engine internals (`engine/` and sub-packages) are implementation details — `FreewayHttpEngine` is the only public class. `JdkHttpEngine`/`JdkHttpContext` have been removed; the built-in engine is the sole default.

### Routes

```java
// Module contribution
binder.contribute(Route.class)
    .add(Route.get("/", ctx -> ctx.send(200, "Hello")))
    .add(Route.get("/users/{id}", ctx -> {
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
        Route.get("/users/{id}", ctx -> ctx.sendJson(200, user(ctx.pathVar("id"))))
    ));
```

### Routes with Dependencies

When a handler needs injected services, use a **handler class** instead of a
lambda. Pass the class reference to `Route.get()` — the container creates the
instance and resolves its constructor parameters:

```java
// 1. Define a handler class implementing RouteHandler
public static final class UserHandlers {
    public static final class List implements RouteHandler {
        private final UserService service;

        public List(UserService service) {
            this.service = service;
        }

        public void handle(HttpContext ctx) throws Exception {
            int page = Integer.parseInt(ctx.queryParam("page").orElse("1"));
            ctx.sendJson(200, service.list(page));
        }
    }

    public static final class Get implements RouteHandler {
        private final UserService service;

        public Get(UserService service) {
            this.service = service;
        }

        public void handle(HttpContext ctx) throws Exception {
            var user = service.findById(ctx.pathVar("id"));
            user.ifPresentOrElse(
                u -> ctx.sendJson(200, u),
                () -> ctx.send(404, "Not found"));
        }
    }
}

// 2. Register with the handler class (not a lambda)
binder.contribute(Route.class)
    .add(Route.get("/api/users", UserHandlers.List.class))
    .add(Route.get("/api/users/{id}", UserHandlers.Get.class));

// 3. Works with RouteGroup too
binder.contribute(RouteGroup.class)
    .add(RouteGroup.of("/api/v1",
        Route.get("/users", UserHandlers.List.class),
        Route.get("/users/{id}", UserHandlers.Get.class)));
```

The container instantiates each handler class once at startup via
`container.create()`, injecting all `@Inject`-annotated constructor
parameters. The same handler instance is reused for every request.

**Choosing lambda vs handler class:**

| Style | When to use |
|---|---|
| Lambda `ctx -> { ... }` | Stateless handler — no injected dependencies needed |
| Handler class `MyHandler.class` | Stateful handler — depends on services, needs constructor injection |

Handler classes also support `POST`/`PUT`/`PATCH` with request bodies.
Call `ctx.bodyAsJson(BodyType.class)` inside the handler to deserialize
the request body — equivalent to the `BodyHandler<T>` convenience but
with full access to constructor-injected services.

If you find yourself calling a static method like
`AppContext.get(SomeService.class)` inside a lambda handler, switch to a
handler class — that is the signal that you need constructor injection.

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

Built-in filters: `RequestTimingFilter` (logs request duration), `CorsFilter` (configurable CORS via `freeway.http.cors.*` keys), `HealthFilter` (health endpoint, see below).

### Health Check

The health endpoint is powered by `HealthFilter` and `HealthCheck`. By default it responds `{"status":"ok"}` at `GET /healthz`. Customize the path via config or replace the check logic:

```java
// Custom health check — bind your own implementation
binder.bind(HealthCheck.class).to(MyDbHealthCheck.class);

public class MyDbHealthCheck implements HealthCheck {
    private final Database db;
    public MyDbHealthCheck(Database db) { this.db = db; }
    public Object check() {
        return Map.of("status", db.ping() ? "ok" : "degraded", "db", db.ping());
    }
}
```

Config:

| Key | Default | Purpose |
|-----|---------|---------|
| `freeway.http.health.enabled` | `true` | Enable/disable the health endpoint |
| `freeway.http.health.path` | `/healthz` | Path for the health check endpoint |

The health endpoint responds before routing and static files, ensuring it always returns quickly regardless of registered routes.

### Static Resources

```java
binder.contribute(StaticResourceMount.class)
    .add(StaticResourceMount.classpath("/", "/public"))     // from classpath
    .add(StaticResourceMount.directory("/uploads", Path.of("/var/uploads")));  // from filesystem

// Options
StaticResourceMount.classpath("/assets", "/static")
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

Built-in mappers in `HttpModule`: `BodyTooLargeException` → 413, `ValidationException` → 400.

### Engine Selection

`HttpModule` registers `FreewayHttpEngine` as the only default engine (high-performance, built-in HTTP/2 + WebSocket + HTTPS). Extension modules bind their engine with `.primary()`:

| Engine | Module | Features |
|--------|--------|----------|
| `FreewayHttpEngine` | Built-in in `HttpModule` (default) | VT-based, HTTP/1.1 + h2c/h2 + WebSocket + HTTPS |
| `UndertowEngine` | `freeway-http-undertow` (ext) | XNIO-based, production-grade |
| `JettyEngine` | `freeway-http-jetty` (ext) | Jetty 12, HTTP/1.1 + HTTP/2 + WebSocket + TLS |

```java
// Default — FreewayHttpEngine
FreewayApp.run(new String[0], new AppModule(), new HttpModule());

// Undertow — just add the module, container picks it via .primary()
FreewayApp.run(new String[0], new AppModule(), new HttpModule(), new UndertowModule());
```

**How it works:**

1. `HttpModule` binds `HttpEngine` → `FreewayHttpEngine` **without** `.primary()`
2. `UndertowModule` binds `HttpEngine` → `UndertowEngine` **with** `.primary()`
3. With only `HttpModule`, `FreewayHttpEngine` is the sole binding and used automatically
4. With both modules, the container resolves `.primary()` → `UndertowEngine`
5. `WebServerBuilder.engine(…)` bypasses container resolution entirely for programmatic override

No config keys needed — just add or remove the extension module. Same `.primary()` pattern used by `freeway-db-hikari` and custom database dialects.

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
FreewayApp.run(new String[0], new AppModule(), new DbModule());
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

**IoC pool selection:** `DbModule` binds `PoolDefault` (id `"builtin"`, no `.primary()`). Extension modules like `HikariPoolModule` bind their pool with `.primary()`. The container automatically selects the primary pool when multiple bindings exist — same pattern as HTTP engine selection:

```java
// Default — PoolDefault
FreewayApp.run(new String[0], new AppModule(), new DbModule());

// HikariCP — add the module, container selects it via .primary()
FreewayApp.run(new String[0], new AppModule(), new DbModule(), new HikariPoolModule());
```

No config keys needed — just add or remove the extension module.

### HikariCP (`freeway-db-hikari`)

Third-party connection pool adapter for [HikariCP](https://github.com/brettwooldridge/HikariCP). Add the `freeway-db-hikari` dependency to your classpath.

**Standalone usage:**

```java
PoolConfig config = PoolConfig.defaults("jdbc:postgresql://localhost/db", "user", "pass");
HikariPool pool = new HikariPool(config);
Database db = new DatabaseBuilder().config(config).pool(pool).build();
```

**IoC usage:** add `HikariPoolModule` to the launcher — it binds `HikariPool` as `id("hikari").primary()`, overriding the built-in `PoolDefault`. No config keys needed.

`HikariPool` maps `PoolConfig` fields to Hikari's configuration (pool size, timeouts, health check query) and adapts Hikari's `HikariPoolMXBean` stats to `DatabaseStats`.

### Batch

```java
List<ExecuteResult> results = db.batch("INSERT INTO t (a, b) VALUES (?, ?)")
    .rows(new Object[]{1, "a"}, new Object[]{2, "b"})
    .execute();
```

    ### Dialect

Database dialect controls DDL generation — identifier quoting, auto-increment clauses, and schema-introspection queries. Selection mirrors the config-driven pattern already used for pool engine and HTTP engine: bind an implementation by id, select it via a config key, and fall back to auto-detection from the JDBC URL.

**Resolution chain:**

```
freeway.db.dialect=mysql
    │
    └─ configured? ──yes──→ container.get(Dialect.class, "mysql")
    │                            │
    │                            ├─ found? ──yes──→ use it
    │                            └─ not found ──→ warn + fall through
    │
    └─ not configured ──→ detect from freeway.db.url
                              │
                              ├─ :postgresql:  → "postgresql"
                              ├─ :mysql:       → "mysql"
                              ├─ :mariadb:     → "mysql"
                              ├─ :h2:          → "h2"
                              └─ unknown       → ""
                                   │
                                   └─→ container.get(Dialect.class)
                                           → PostgresDialect (primary)
```

**Built-in dialects:**

| id | Class | Target |
|----|-------|--------|
| `postgresql` | `PostgresDialect` | PostgreSQL, H2 (all modes except MySQL) — **default** |
| `mysql` | `MySqlDialect` | MySQL, MariaDB, H2 with `MODE=MySQL` |
| `sqlite` | `SqliteDialect` | SQLite |

`DbModule` binds `PostgresDialect` as `id("postgresql").primary()`, plus `MySqlDialect` (`id("mysql")`) and `SqliteDialect` (`id("sqlite")`) — all three built-in. Custom dialects can be contributed by users or third-party modules — same pattern as `HikariPoolModule` for pool selection.

**Custom dialect — write once, select via config:**

```java
// 1. Implement Dialect
public class MySqlDialect implements Dialect {
    @Override public String quoteName(String name) { return "`" + name + "`"; }
    @Override public String generatedClause() { return "AUTO_INCREMENT"; }
    // ... remaining methods
}

// 2. Bind in your module (or a dedicated MySqlDialectModule)
binder.bind(Dialect.class).to(MySqlDialect.class).id("mysql").primary();

// 3. Select via config
// freeway.db.dialect=mysql
```

**Per-group override** — a `SchemaEntity` can carry its own dialect, overriding the global one:

```java
binder.contribute(SchemaEntity.class)
    .add(SchemaEntity.of("audit", new MySqlDialect(), AuditLog.class));
```

Config:

| Key | Default | Purpose |
|-----|---------|---------|
| `freeway.db.dialect` | (auto-detect) | Dialect id to use; overrides URL detection |

### Schema & Migrations

Freeway provides two complementary mechanisms for database evolution: **Schema** (annotation-driven, current-state DDL) and **Migration** (versioned SQL files). They work together — Schema handles the "what should exist now," Migration handles the "how we got here."

#### Schema — Annotation-Driven Auto-DDL

`Schema.ensure()` reads `@Table` / `@Column` / `@Id` / `@Generated` / `@Index` annotations and generates the corresponding DDL. It never drops or modifies existing columns.

```java
/// Standalone usage
// The database carries its dialect — no explicit Dialect needed
Schema.ensure(db, User.class, Post.class);

// AutoMigrate strategy
// Table missing    → CREATE TABLE IF NOT EXISTS
// Column missing   → ALTER TABLE ADD COLUMN
// Index missing    → CREATE INDEX IF NOT EXISTS
// Existing columns → never touched
```

**IoC integration** — contribute entity classes via `SchemaEntity`:

```java
public class AppModule implements ModuleEx {
    public void bind(Binder b) {
        b.install(new HttpModule())
         .install(new DbModule());

        // Register entities for auto-DDL on startup
        b.contribute(SchemaEntity.class)
            .add(SchemaEntity.of("app", User.class, Post.class, Comment.class));

        // Custom dialect
        b.contribute(SchemaEntity.class)
            .add(SchemaEntity.of("profile", new PostgresDialect(), UserProfile.class));
    }
}
```

`DbModule` contributes a `RuntimeHook` (`"freeway.db.migration"`, before `"freeway.http.server"`). On startup it calls `Schema.ensure()` for contributed entities, then runs SQL migrations. The ordering is automatic — tables exist before migration SQL attempts to insert or alter them.

Config:

| Key | Default | Purpose |
|-----|---------|---------|
| `freeway.db.schema.auto` | `true` | Enable annotation-driven auto-DDL |
| `freeway.db.schema.groups` | (all) | Comma-separated group names to run; empty = all |

#### Migration — Versioned SQL Files

SQL files live under `db/migration/` (configurable) on the classpath:

```
db/migration/
├── V001__create_users.sql          ← create tables
├── V002__add_email_column.sql      ← alter existing tables
├── V003__seed_categories.sql       ← insert reference data
└── V20240615120000__add_index.sql  ← timestamp-based versions
```

**Naming rules (enforced):**
- Must start with `V` followed by digits: `V001`, `V20240615`, `V1_0_0`
- Separator: `__` (double underscore) between version and description
- Extensions: `.sql` only

**Standalone usage:**

```java
MigrationRunner runner = new MigrationRunner(database, true, "db/migration", "_migrations");
int applied = runner.run();  // returns count of newly-applied files
```

**IoC usage** — `DbModule` binds `MigrationRunner` and runs it automatically on startup:

```java
MigrationRunner runner = container.get(MigrationRunner.class);
runner.run();  // idempotent — already-applied files are skipped
```

Each migration runs in its own database transaction. The tracking table is `_migrations` (configurable). Applied migrations are **immutable** — modifying a SQL file that has already been applied causes a checksum mismatch error at startup.

Config:

| Key | Default | Purpose |
|-----|---------|---------|
| `freeway.db.migration.enabled` | `true` | Set to `false` to skip SQL migrations |
| `freeway.db.migration.path` | `db/migration/` | Classpath resource directory for `.sql` files |
| `freeway.db.migration.table` | `_migrations` | Tracking table name |

#### Dev vs Production Workflow

Schema and Migration serve different roles across environments:

```
Development                           Production
──────────                            ──────────
freeway.db.schema.auto=true           freeway.db.schema.auto=false
                                      freeway.db.migration.enabled=true

① Add @Column("phone") to User
   → restart → column auto-added     ② Write V004__add_phone.sql
                                         from Schema.define() output
                                     ③ Deploy → Migration applies V004
```

**Schema** shines in development — zero-friction iteration. Add a field, restart, the column appears. **Migration** shines in production — every schema change is versioned, auditable, reviewed, and can include data transformations and index tuning that annotations can't express.

The same entity classes and SQL files work in both environments. Switching modes is just one config key.

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
FreewayApp.run(new String[0], new AppModule(), new KafkaModule());
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

## Flow (`freeway-flow`)

Lightweight graph-based workflow engine. v1 format is a port of solon-flow for compatibility; v2 (`GraphSpec2` with `nodes`+`links`) is the native Freeway format. Zero external dependencies beyond commons + ioc. Supports two DAG definition formats with a unified runtime.

**7 node types:**

| Node | Purpose |
|------|---------|
| `START` | Graph entry point |
| `END` | Graph termination |
| `ACTIVITY` | Executes a task |
| `EXCLUSIVE` | Exclusive gateway — single path |
| `INCLUSIVE` | Inclusive gateway — multiple paths |
| `PARALLEL` | Parallel fork |
| `LOOP` | Loop until condition |

**Graph definition — v2 format (recommended):**

```java
// Programmatic
GraphSpec2 bp = GraphSpec2.create("orderFlow", spec -> {
    spec.entry("start");
    spec.addStart("start").linkAdd("approve");
    spec.addActivity("approve").task("!channel:order").linkAdd("end");
    spec.addEnd("end");
});
Graph graph = bp.create();

// JSON — Graph.fromText() auto-detects format
Graph graph = Graph.fromText("""
{
    "id": "orderFlow", "version": 2, "entry": "start",
    "nodes": [
        {"id": "start", "type": "start"},
        {"id": "approve", "type": "activity", "task": "!channel:order"},
        {"id": "end", "type": "end"}
    ],
    "links": [
        {"from": "start", "to": "approve"},
        {"from": "approve", "to": "end"}
    ]
}
""");
```

v1 `layout`-format JSON is still supported — `Graph.fromText()` auto-detects and internally converts to the unified runtime path. `GraphSpec2.normalize()` validates link references and BFS reachability at `create()` time.

**Task resolution** — nodes use prefix syntax to specify what to execute. Each prefix has different resolution logic:

| Prefix | Syntax | Resolves to |
|--------|--------|-------------|
| `!` (marker) | `!channel:order !priority:high` | `TaskComponent` by `@FlowMarker` intersection — most markers wins |
| `@` (bean) | `@orderService` | `TaskComponent` from IoC by binding id. Also works for conditions — `@validator` resolves to `ConditionComponent` |
| `#` (sub-graph) | `#approvalFlow` | Another loaded graph, executed as a nested subflow |
| `$` (meta) | `$app.name` | Reads graph metadata into execution context — no component is resolved |

`@FlowMarker("channel:order")` on a `TaskComponent` implementation auto-registers it in the marker index.

**Execution:**

```java
FlowEngine engine = container.get(FlowEngine.class);
engine.load(graph);
engine.eval("orderFlow", FlowContext.of());
```

**Key types:**

| Type | Purpose |
|------|---------|
| `Graph` | Immutable runtime model — built from v1 or v2 definitions |
| `GraphSpec2` | v2 DAG authoring surface with explicit `entry` and separated `nodes`/`links` |
| `FlowEngine` | Graph executor: load, eval, pause, resume |
| `FlowDriver` | Pluggable task executor — contributed via `binder.contribute(FlowDriver.class)` |
| `FlowDriverDefault` | Built-in driver: resolves `@beanName` via IoC container, `!markerName` via `FlowMarkerIndex` |
| `@FlowMarker` | String-based marker annotation for `TaskComponent` resolution |
| `FlowMarkerIndex` | Reverse index from marker names to handlers with `containsAll` matching |
| `FlowEventBus` | Node lifecycle events (enter, exit, error) |
| `ExprEvaluator` | Self-written recursive descent expression evaluator (~280 lines) |

**Driver:** Graphs select their driver via the `"driver"` field (null/"" → `"default"`). `FlowModule` binds `FlowContainer` (for `@beanName` resolution), creates `FlowDriverDefault` with id `"default"`, and merges contributed drivers from `Extension<FlowDriver>.asMap()`. Register a custom driver by contributing to the same extension point:

```java
binder.contribute(FlowDriver.class)
    .add("custom", new MyCustomDriver());

// Or use add(Class) for auto-instantiation via container.create()
binder.contribute(FlowDriver.class)
    .add(MyCustomDriver.class);
```

Graph definition: `{ "driver": "custom", ... }`

Supports PlantUML export, execution tracing with pause/resume, subgraph calls, and interceptor chains.

---

## Commons Utilities

Commons contains the shared runtime primitives used across Freeway: JSON, coercion, validation, `Defer`, `ScopedCache`, and logging support.

- JSON parsing, building, and serialization live in `JsonUtils` and `JsonCodec`
- coercion lives in `Coercer` and `CoerceRule`
- validation lives in `BeanValidator`
- scoped primitives are described in [freeway-commons.md](freeway-commons.md)

For more detail:

- [docs/freeway-commons.md](freeway-commons.md)
- [Defer summary](freeway-defer-summary.md)
- [DB usage guide](freeway-db-how-to-use.md)

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

- **`Container`** is the IoC boundary. Created via `Freeway.create()` for tests, received as a parameter in `RuntimeHook` callbacks and provider lambdas. Container is not injectable — use `@Inject` for service dependencies, not for the container itself.
- **`AppRuntime`** is the application boundary above `Container`. It owns config, profiles, runtime state, startup, shutdown, and runtime hooks. Access services through `app.get(Class)` rather than reaching for the Container.
- Service ids are **plain strings** and are normalized internally by `ServiceIds`. There is no public `ServiceId` type.
- Service lifecycle is declared only with `bind().scope(...)`: `SINGLETON`, `PROTOTYPE`, `THREAD`.
- **`Defer` and `ScopedCache`** are the two scope-bound primitives in Commons. `Defer` buffers actions until the enclosing unit of work commits; `ScopedCache` memoizes values for the lifetime of a scope and closes them on exit. IoC's thread scope is built on `ScopedCache`.
- **Decision rule:** if the work should happen only after success, use `Defer`; if a value should be created once per scope and reused until cleanup, use `ScopedCache`.
- **`Defer` triggers:** DB transaction commit, HTTP request completion, batch/job success boundaries, ordered post-commit side effects such as invalidate → rebuild → notify.
- **`ScopedCache` triggers:** request-scoped lookup tables, per-scope connections or handles, repeated resolution of thread-scoped services, values that need one cleanup action when the scope exits.
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
