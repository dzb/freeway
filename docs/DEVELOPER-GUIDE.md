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
mvn test -Dtest=CoercerImplTest    # single test class
```

---

## Module Dependency Graph

```
freeway-commons          shared utilities: JSON, coercion, scoped primitives, beans, validation, config specs
freeway-ioc              IoC container: bind, inject, scope, advise, event-bus, extensions, symbol config
freeway-boot             launcher, config cascade (hot reload), profiles, runtime lifecycle
freeway-http             HTTP/WebSocket: routing, filters, static, multipart, SSE
  └ built-in              FreewayHttpEngine (HTTP/1.1 + HTTP/2 + WebSocket + HTTPS)
  └ engine adapters       Undertow, Jetty → see freeway-ext
freeway-db               JDBC: ORM, pooling, transactions, SQL builder, migrations
  └ connection pool       HikariCP adapter → see freeway-ext
freeway-flow             Graph workflow engine — 7 node types, JSON graphs, tracing
freeway-cloud            Cloud-native foundation: discovery, remote RPC, observability,
                         resilience, health, secrets, object storage
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

    // bind a pre-built instance by returning it from a singleton provider
    Config config = new Config(...);
    binder.bind(Config.class).to(c -> config);

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

#### When to use a provider binding

`.to(Class)` is the smallest form: the container constructs the implementation and injects
every constructor dependency itself. Use it whenever an `@Inject`-friendly constructor is
enough. The provider form `.to(c -> ...)` is the explicit construction space for everything
ordinary constructor injection cannot express:

| Need | Why class binding is not enough | Provider example |
|---|---|---|
| Constructor mixes plain/runtime values with services | Config values and durations are not container bindings; they must be read and passed explicitly | `PoolConfig`, `HttpConfig`, `KafkaConfig` |
| Construction must branch on configuration | `.to(Class)` always uses the same constructor path | HTTP engine chooses plaintext or TLS constructor from `ssl.enabled` |
| Object graph aggregates contributions | Constructor injection sees bound services, not collected extension points | `RouteIndex` collects `Route`/`RouteGroup`; `WebServer` assembles filters, mounts and error handlers |
| A pre-created or externally owned object must be bound | The container must not re-construct it, but should still own its lifecycle | boot's `AppConfig`; a `PeerHub` shared with a sink and WS route |
| Construction must wait until all modules are composed | Realization happens on first resolution, after contributions are final | lazy builtins such as `EventBus`/`CallBus` resolving a user-supplied primary `Metrics` |

```java
// Class binding: the container injects GreeterImpl's constructor.
binder.bind(Greeter.class).to(GreeterImpl.class);

// Provider: explicit construction that needs container services plus a
// runtime value, or returns an existing object instead of rebuilding it.
binder.bind(Cache.class).to(c -> new Cache(c.get(Config.class), ttl));
```

The provider is not an escape hatch from IoC: it only owns **how the object is built**.
The object still follows the binding's scope — a singleton provider runs once per container —
and still receives field injection, `@PostConstruct` and `@PreDestroy` through the normal
lifecycle.

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

### Choosing Between id, primary, marker, and Contributions

These mechanisms answer different questions. The decision table:

| What you are modelling | Use | Consumer side |
|---|---|---|
| One implementation per type | `.to(Class)` or `.to(c -> ...)` | Plain `get(type)` |
| Several alternatives, one default chosen by assembly | Several bindings + `.primary()` on the default | Plain `get(type)` — consumers do not choose |
| Several alternatives, consumer picks by name at runtime | `.id("stripe")` | `get(type, "stripe")` or `@Inject("stripe")` |
| Several alternatives, consumer commits at compile time | `.marker(Fast.class)` | `get(type, Fast.class)` or `@Inject @Fast` |
| An open set of pluggable items | `contribute(Foo.class)` | `List<Foo>` / `Map<String, Foo>` |

Rules of thumb:

- `primary()` is a **supply-side default**: it picks the implementation when the consumer does not
  specify one. Extension modules (Jetty, Undertow, Hikari, cloud backends) replace built-ins by
  binding `.primary()`; application injection points do not need to mention the extension.
- `id` and `marker` are **demand-side qualifiers**: they exist when consumers need to pick a
  specific binding. Prefer `marker` for compile-time safety; use `id` when the selection is
  dynamic or no annotation is appropriate.
- `contribute` is not service selection at all. Use it for collections of open-ended extensions;
  do not bind N services just to hand consumers a list, and do not expect a contributed item to
  be resolvable as a single service.
- `scope` is orthogonal to all of the above: it answers "how long does an instance live", never
  "which implementation should I get".

When an unqualified `get(type)` has multiple bindings, the wiring is ambiguous — fix the
assembly by adding `.primary()` or a qualifier, rather than making consumers depend on order.


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
  fallbacks like timeouts, feature flags, or cosmetic names. The shell-style
  `:-` separator is also supported: `@Value("${port:-8080}")` strips a single
  leading dash from the default and yields `"8080"` (not `"-8080"`), while
  `${port:8080}` keeps the default verbatim and `${port:}`/`${port:-}` both
  yield the empty string.

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

Field injection into a **final** field (or any other non-writable property)
carrying `@Inject`, `@Symbol`, or `@Value` fails fast with a clear error —
the field would otherwise be silently skipped and keep its default value.
Use constructor injection for final fields.

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

// Or via constructor — constructor parameters consume contributions
// implicitly, no @Inject required
public class Router {
    private final List<Route> routes;
    public Router(List<Route> routes) {
        this.routes = List.copyOf(routes);
    }
}
```

Contribution injection: **constructor parameters consume contributions
implicitly** — the constructor is the single mandatory injection point, so
resolution failure is loud at startup and there is no silent-miss risk.
Fields require an explicit `@Inject` (they are writable and can be
forgotten). An explicit `@Inject("id")` on a `List`/`Map` injection point
prefers a bound service with that id (letting you bind your own
`List<Foo>`/`Map<String, Foo>` service and inject it by id); only when no
such binding exists does resolution fall back to the contributed view.

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

Missing ids in `before/after` are ignored with a WARN (the reference may be a typo, or the contributing module may not be installed). Cycles between resolved references still fail fast.

The three `add` variants are deliberately distinct, not an API gap waiting for a unifying default:

- `add(value)` — unnamed, insertion order. No id. Not in `asMap()`. Use when only iteration order matters (routing, filters) and id-based lookup is irrelevant.
- `add(id, value)` — named, with explicit id. Supports `before/after`. Included in `asMap()`. Use when the consumer needs to resolve a specific entry by name (drivers, runtime hooks).
- `add(Class)` — named, with auto-generated canonical id (`snake_name@package`). Supports `before/after`. Included in `asMap()`. Use when the class itself is the natural identifier.

`Extension.asMap()` returns only named contributions — this is by design, not a limitation. Unnamed entries serve iteration order; named entries serve identity. Forcing auto-generated ids onto unnamed entries would blur this distinction without solving a real problem.

Rules:
- `add(value)` preserves insertion order.
- `add(id, value)` enables `before/after` constraints for topological ordering.
- `add(Class)` auto-instantiates the contributed class from the container and generates a canonical id as `snake_name@package` (e.g. `email_sender@com.example.flow`). Supports `before`/`after` ordering on the returned `Contribution`.
- Duplicate ids fail immediately. Generic `all()` ordering treats unknown order targets leniently — they are WARNed and ignored (a missing sibling is harmless for most extension points); strict consumers call `Extension.validateOrdering()`, which fails fast on any unknown reference (runtime-hook ordering in the boot layer does this, so a typo fails startup). Cycles fail at resolution time.
- Constructor parameters are auto-resolved; fields require `@Inject`. (Contribution consumption via `List`/`Map` follows the same rule: constructor parameters implicit, fields explicit — see above.)

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

Note the single-argument `publish("order.placed")` is **not** a topic publish —
a one-arg call dispatches a `String` *class event*, which only subscribers on
`String.class` (or a supertype) receive. Topic subscribers registered via
`EventSubscriber.of("order.placed", ...)` or `subscribe("order.placed", ...)`
only receive two-argument `publish(topic, payload)` calls.

**Key types:**

| Type | Purpose |
|------|---------|
| `EventBus` | Publish, subscribe, unsubscribe. Injected via `@Inject EventBus` |
| `EventSubscriber<E>` | Module-level subscriber: carries event type, handler, and ordering |
| `Subscription<E>` | Handle returned by `subscribe()`, used to `unsubscribe()` |
| `DeadEvent` | Published when an event has zero subscribers — subscribe for diagnostics |
| `EventSink` | Sends events to an external MQ: `EventSink.send(topic, event)` |
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

**Ordered channel:** `publishOrdered(event)` dispatches strictly in submission order (single-threaded FIFO) — the channel for transaction-outbox-style sequences: events published inside one `Defer` scope drain in call order after the scope commits.

**Reactive streams:** `stream(Class)` / `stream(String)` expose the same subscriptions as a JDK `Flow.Publisher` — no external dependency. A consumer that cannot keep up overflow-drops (non-blocking) rather than stalling bus dispatch; `close()` completes all live streams. Fan out by calling `stream()` once per consumer, not by sharing one publisher.

```java
bus.stream(OrderPlaced.class).subscribe(new Flow.Subscriber<>() {
    public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
    public void onNext(OrderPlaced item) { handle(item); }
    public void onError(Throwable t) { LOG.error("stream failed", t); }
    public void onComplete() { }
});
// cold-lazy: the bus subscription is created on first downstream subscribe
```

**Lifecycle events:** Boot publishes `AppStartedEvent` after all hooks start, and `AppStoppingEvent` before shutdown. Subscribe via EventBus instead of implementing `RuntimeHook` for non-critical work:

```java
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(AppStartedEvent.class,  e -> cache.warmup()))
    .add(EventSubscriber.of(AppStoppingEvent.class, e -> cache.flush()));
```

**Transaction-aware:** Events published inside a `db.transaction()` are automatically deferred and fire only after commit. No manual wiring needed — powered by the `Defer` mechanism (see [Defer](#defer--scope-bound-deferred-execution)).

**Cross-JVM:** Add `@Topic("kafka.topic")` on an event class + `KafkaModule` for distributed pub/sub via the `EventSink` mechanism (see [Kafka](#kafka-freeway-mq-kafka)).

### Type Coercion

Commons owns the reusable scalar coercion mechanics (`Coercer` interface, `CoerceRule<S,T>`, `CoercerImpl`). IoC owns container-aware coercion rules and `@Symbol`/`@Value` integration. The two layers are separate: commons does not import ioc types.

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

`freeway-commons` registers a JUL-backed SLF4J 2 provider **unconditionally** via `META-INF/services` — zero-dependency. Because SLF4J 2.x does not prefer any provider on its own, `LogBootstrap.ensureProvider()` runs at startup (`FreewayApp`/`Freeway` static initialization, before any `LoggerFactory.getLogger()` call) and probes the classpath for external SLF4J 2.x providers (Logback, Log4j 2, slf4j-simple, in that priority). When one is present it pins the `slf4j.provider` system property to it, so the external provider deterministically wins over the JUL fallback; the JUL provider takes effect only when no external provider exists. A user-supplied `-Dslf4j.provider` is always respected and never overridden. Adding Logback switches seamlessly without code changes.

Logging works **out of the box** with sensible defaults: ANSI-colored console output, rotating file logging at `logs/{app.name}.log`. Configuration is through `freeway-log.properties` on the classpath root (not bundled in the JAR). System properties (`-D`) and env vars override file values — the env prefix follows `freeway.env.prefix` (default `FREEWAY_`), same convention as the config cascade: `freeway.log.level` ↔ `FREEWAY_LOG_LEVEL`, or `APP_FREEWAY_LOG_LEVEL` under a custom prefix.

```properties
freeway.log.level=INFO
freeway.log.file=auto                    # logs/{app.name}.log, rotation + GZIP
freeway.log.file.max-size=104857600      # 100 MB before size-based rotation
freeway.log.file.max-history=30          # days kept
freeway.log.file.compress=true           # GZIP rotated archives
freeway.log.file.flush-interval=250      # ms; 0 = flush per record
# (the four values above are the auto defaults — omit to keep, override to change)
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

Adding the **same module class twice with two different instances** (e.g.
`add(new DbModule(), new DbModule())`, or an explicit module that SPI
auto-discovery also loads) fails fast at startup — silently dropping one
instance's configuration would be worse. The identical instance added twice
is deduplicated harmlessly, and SPI-discovered duplicates of an explicit
module are dropped in favor of the explicit one.

For more control, use `AppBuilder`:

```java
AppRuntime app = FreewayApp.of(new MyModule())
    .add(new HttpModule(), new DbModule())   // additional modules
    .args("--freeway.profile=dev")            // config overrides
    .classLoader(customLoader)               // custom class loader for SPI/resources
    .autoDiscovery(false)                     // disable SPI module discovery
    .shutdownHook(false)                      // skip JVM shutdown hook
    .config(myConfigLoader)                     // custom ConfigLoader
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

Hook ordering is **strict**: `before`/`after` references to an unknown hook id
fail startup (unlike generic extension ordering, which WARNs) — a typo like
`after("freeway.http.serve")` is caught instead of silently running hooks in
the wrong order.

### Config Cascade

The symbol chain resolves every config lookup (`@Symbol`/`@Value` and
`SymbolSource.resolve` — the single read entry; `AppConfig` only carries
profiles, the snapshot and lifecycle). Lowest to highest priority —
every tier is a `SymbolProvider` with a declared `order()`, and each
answers one ownership question:

1. Config files (`TIER_FILES` 20): `application.properties` →
   `application.json` → `application-{profile}.*` (classpath baseline, then
   filesystem overrides, hot-reloadable)
2. Module-contributed sources (e.g. the cloud secret store, declared order 15)
3. **The process-environment band** — the ambient, operator-provided values
   surrounding the process. Two declared tiers, one band: both are
   "environment", but they differ in key semantics and owner —
   - Environment variables (`TIER_ENV` 10) — **mapped keys**: `FREEWAY_DB_URL`
     → `freeway.db.url` (prefix stripped, `_` → `.`, `freeway.` prepended);
     the prefix policy is boot's (`freeway.env.prefix`), so this tier exists
     only with the boot cascade
   - JVM system properties (`TIER_SYS_PROPS` 5) — **verbatim keys**:
     `-Dserver.port=9090` sets `server.port` as-is; provided by the container
     itself, so bare containers (`Freeway.create` without boot) resolve them
     too — and tests can mutate them per test
4. CLI arguments (`TIER_CLI` 0, `--key=value`, `-Dkey=value`) — the app
   launcher's verbatim overrides, parsed by boot

The two band tiers stay separate — merging them would pair a verbatim
mechanism with a mapped one under one name, force the prefix policy into
ioc or strip sysprops from bare containers, and reintroduce
wiring-order-dependent tie-breaks — while dropping the rung that lets a
module source beat env but lose to JVM flags (order 5–10).

There is deliberately **no raw-env fallback tier**: environment variables
reach the chain only through the declared prefix mapping, so an unknown
symbol fails fast instead of silently matching an unrelated variable (on
Windows, where env names are case-insensitive, `path` would otherwise
resolve to `PATH`). To read a raw variable, map it via the prefix or call
`System.getenv` directly.

Modules slot their own sources between the framework tiers by declaring an
`order()` — precedence is declared, never derived from module install order.

CLI keys without a dot (e.g. `--profile=dev`) auto-receive the `freeway.`
prefix, so `--profile=dev` and `--freeway.profile=dev` are equivalent.
Dotted keys (`--app.name=foo`) pass through unchanged.
Activate profiles: `--profile=dev` or `--freeway.profile=dev`.

Three CLI styles are supported: `--key=value`, `--key value` / `--key`
(boolean flag, value defaults to `"true"`; a following negative number like
`--port -1` is consumed as a value), and `-Dkey=value`. A bare `--` / `-D`
(empty key) and `--=x` (key containing `=`) are **rejected** with an
`IllegalArgumentException` — they would otherwise produce garbage keys.
Anything that is not a flag (a positional argument) is ignored with a WARN.

An empty or whitespace-only `application.json` is treated as "no config"
(same as a missing file or an empty `application.properties`) — not a parse
error. Malformed non-blank JSON still fails startup. `freeway.profile` set
inside a profile file is stripped from the merged config: profiles are
activated from the base layers (`application.properties`/`application.json`,
env, CLI) only, so a profile file cannot re-select profiles.

**Environment variables and namespaces:** The `FREEWAY_` prefix maps into the
`freeway.*` namespace (`FREEWAY_LOG_FILE_MAX_SIZE` → `freeway.log.file.max.size`).
A single configurable prefix, `freeway.env.prefix` (default `FREEWAY_`), can
**replace** it entirely: set `-Dfreeway.env.prefix=APP_` and the app owns the
mapping — prefix stripped, `_` → `.`, no namespace inference:

```bash
-Dfreeway.env.prefix=APP_
APP_SERVER_PORT       → server.port
APP_FREEWAY_HTTP_PORT → freeway.http.port
```

With a custom prefix, `FREEWAY_*` variables are no longer read by the config
cascade (logging's own `FREEWAY_LOG_*` env support is a separate mechanism and
is unaffected).

**Reading values — one entry point:** the symbol chain is the single way to
read configuration; `AppConfig` is not a reader (it owns profiles, the
cascade snapshot and lifecycle only). Direct lookups resolve through the
chain, and typing is an explicit post-processing step with a declared
`SymbolSpec` — key, type, default and description stated once, parse errors
naming the key:

```java
public static final SymbolSpec<Integer> PORT =
    SymbolSpec.of("http.server.port", Integer.class, 8080);

int port = PORT.parse(symbols.resolve(PORT.key(), null));               // parser spec
Duration ttl = LOCK_TTL.parse(symbols.resolve(LOCK_TTL.key(), null),    // coercer spec
    coercer);   // container Coercer: Duration syntax, user CoerceRules
```

Absent/blank values fall back to the spec default, required specs fail
fast, and the same chain backs `@Symbol`/`@Value` injection.

---

## HTTP

Three-layer architecture: **engine layer** handles transport (socket I/O, protocol parsing), **orchestration layer** (`WebServer`) wires filters and routing, **integration layer** (`HttpModule`) bridges to IoC.

| Category | Main Types |
|----------|------------|
| Core | `HttpEngine`, `HttpContext`, `HttpFilter`, `HttpModule`, `WebServer`, `JsonCodec` |
| Engine (shared) | `FreewayHttpEngine`, `HttpContextImpl`, `HttpSession`, `HttpServerHandleDefault`, `SessionBufferedInputStream/OutputStream`, `FixedLengthInputStream`, `ChunkedInputStream` |
| Engine (HTTP/1.x) | `HttpConnection`, `Http1xSession`, `Http1xParser` in `engine/` |
| Engine (HTTP/2) | `Http2Connection`, `Http2Stream`, `FrameSerializer`, `HPackContext` etc in `engine/http2/` |
| Engine (WebSocket) | `WebSocketFrame`, `WebSocketSessionImpl`, `WebSocketUtil` etc in `engine/ws/` |
| Routing | `Route`, `RouteGroup`, `RouteIndex`, `PathPattern` |
| Body | `BodyHandler`, `MultipartForm` |
| WebSocket | `WebSocketSession`, `WebSocketListener`, `WebSocketRoute`, `WebSocketGroup`, `WebSocketIndex` |
| SSE | `SseEmitter`, `SseEvent` |
| Built-ins | `JsonCodecDefault`, `AccessLogFilter`, `CorsFilter`, `HealthFilter`, `HealthCheck`, `StaticResourceMount`, `ErrorHandler` |

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
ctx.param("name")       // queryParam → pathVar (convenience)
ctx.correlationId()     // unique request id
```

`bodyAsJson()` validates the request `Content-Type` before deserializing: it
accepts `application/json` and structured-syntax suffixes
(`application/*+json`, e.g. `application/vnd.api+json`); any other media type
(including a missing header) is a client error mapped to **415 Unsupported
Media Type** — never a 500. `maxBodySize` (default 10 MiB, configurable per
server and per request via `ctx.maxBodySize(...)`) is enforced on the body
stream itself, not just on buffered reads: a counting wrapper sits outside the
framing decision (chunked, fixed-length, and unknown-length), so every byte
delivered to any consumer — buffered `body()` reads or streaming reads during
parsing — is counted against the live limit, and an over-limit read throws
`BodyTooLargeException` → 413.

On a keep-alive connection the context is reused between requests, but all
per-request exchange state is reset: the security principal, request
attributes, and the correlation id are cleared and a fresh id is rolled for
request N+1 (an incoming `X-Request-Id` header is then applied on top).

### Response

```java
ctx.setStatus(201);
ctx.setHeader("X-Custom", "value");
ctx.send(200, "plain text");
ctx.sendJson(200, object);
ctx.output("text".getBytes());
ctx.output("text");  // UTF-8 convenience
```

Response header values must be **ISO-8859-1 encodable** (the charset the
HTTP/1.1 writers serialize header values with) and must not contain CR/LF:
setting a header with a non-Latin-1 character (above U+00FF) or a line break
throws `IllegalArgumentException` instead of silently writing a corrupted or
injected header on the wire. Header names must be RFC 7230 tokens.

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

Built-in filters: `AccessLogFilter` (optional text access log), `CorsFilter` (configurable CORS via `freeway.http.cors.*` keys), `HealthFilter` (health endpoint, see below). Request timing is measured by `WebServer` and published as `HttpExchangeEvent`.

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

Directory requests serve that directory's `index.html` — both at the mount
root and in subdirectories (`/docs/` resolves to `docs/index.html`; a request
naming a real directory without a trailing slash resolves the same way).
Files up to 50 MB are fully memory-loaded when needed; larger files are
streamed (sendfile fast path for real files on plain HTTP, otherwise a body
stream), so large assets never force the whole file into memory. For route
matching, a single decoded path segment is capped at 1024 characters before
regex-constrained matching (and constraint regexes at 64 chars), preventing
ReDoS on developer-registered patterns.

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
binder.contribute(ErrorHandler.class).add((ctx, ex) -> {
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

**HTTP/2 request validation:** the built-in engine validates request
pseudo-headers and rejects malformed ones with a `PROTOCOL_ERROR` — unknown
pseudo-headers, pseudo-headers appearing after regular headers, duplicate
pseudo-headers, connection-specific headers (`connection`,
`transfer-encoding`, `keep-alive`, `proxy-connection`, `upgrade`), a `te`
header other than `trailers`, and a missing/blank `:method`. `:path` must be
origin-form (start with `/`, no `//host` or `scheme://` authority/absolute
forms, no whitespace or control characters; `*` is allowed only for
`OPTIONS`) and `:scheme` is required for non-CONNECT requests; `:authority`,
when present, must satisfy the same character rules as an HTTP/1.1 `Host`
header (no `@`, whitespace, `/`, `\`, or control characters) and is required
for CONNECT.

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
Sql sql = Sql.select("u.name, o.total")
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

Nested transactions are detected and rejected. Auto-commit is restored on exit. Queries inside the transaction automatically use the same connection. The transaction is **thread-bound** (backed by `ScopedValue`, which does not propagate to child threads): DB calls made on a different thread while a transaction is active, or consuming a `Query`/`BatchQuery` created inside a transaction on another thread (or after the transaction ends), throw a `SqlException` — the work would otherwise silently borrow an independent pooled connection and run outside the transaction, breaking atomicity. Cross-`Database` work (e.g. via `DatabaseHub`) commits independently and is not rolled back with the transaction.

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

`save()` treats an unset `@Generated` id as "insert": a primitive id field
(`long`, `int`) reads back its type's default (`0`/`0L`/`0.0`/`false`)
instead of `null`, so a fresh entity with a primitive `@Generated` id is
recognized as new and inserts through the auto-increment sequence rather than
upserting an explicit zero id. The generated key is written back onto the
entity after the insert (boxed types and primitives alike).

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
    │                            └─ not found ──→ fail fast (unknown dialect id)
    │
    └─ not configured ──→ detect from freeway.db.url
                              │
                              ├─ :postgresql:  → "postgresql"
                              ├─ :mysql:       → "mysql"
                              ├─ :mariadb:     → "mysql"
                              ├─ :h2:          → "h2" (MODE=MySQL/MariaDB → "mysql",
                              │                  MODE=PostgreSQL → "postgresql")
                              ├─ :sqlite:      → "sqlite"
                              └─ unknown scheme → fail fast: "No SQL dialect for
                                    JDBC URL ..." — an unrecognized URL
                                    (e.g. jdbc:oracle:...) never silently
                                    falls back to PostgreSQL
```

An explicit `freeway.db.dialect` always wins, and an unknown explicit id fails
fast. Auto-detection from the JDBC URL is shared between standalone
`DatabaseBuilder` and `DbModule`; a `null`/blank URL (no database configured
yet) is the only case that defaults to `PostgresDialect`.

**Built-in dialects:**

| id | Class | Target |
|----|-------|--------|
| `postgresql` | `PostgresDialect` | PostgreSQL — **default** (primary binding) |
| `mysql` | `MySqlDialect` | MySQL, MariaDB, H2 with `MODE=MySQL`/`MODE=MariaDB` |
| `h2` | `H2Dialect` | H2 (all modes except MySQL/PostgreSQL) |
| `sqlite` | `SqliteDialect` | SQLite |

`DbModule` binds all four: `PostgresDialect` as `id("postgresql").primary()`,
plus `MySqlDialect` (`id("mysql")`), `H2Dialect` (`id("h2")`), and
`SqliteDialect` (`id("sqlite")`). Custom dialects can be contributed by users
or third-party modules — same pattern as `HikariPoolModule` for pool
selection.

**Dialect capabilities:** the `Dialect` interface declares capability flags
the framework consults at runtime. Notably `supportsTransactionalDdl()`
(PostgreSQL, H2, SQLite: true; MySQL/MariaDB: false — DDL implicitly commits)
governs whether DDL can run inside a transaction, and
`backslashEscapesStrings()` (MySQL/MariaDB: true) tells the SQL scanner that a
backslash escapes the next character inside ordinary single-quoted literals.
Other flags cover `CREATE INDEX IF NOT EXISTS`, `RETURNING`, `ON CONFLICT`,
`ALTER ... ADD COLUMN NOT NULL`, and lexer features (dollar quoting, bracket
quoting, `#` comments, `E'...'` literals).

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

`Schema.ensure()` reads `@Table` / `@Column` / `@Id` / `@Generated` / `@Index` annotations and generates the corresponding DDL. It never drops or modifies existing columns. It is **not transactional** — each DDL statement runs on its own connection. On databases without transactional DDL (MySQL/MariaDB, see `Dialect.supportsTransactionalDdl()`), calling `ensure()` inside a user transaction is rejected with a `SqlException` (the DDL would implicitly commit and silently commit the transaction's pending work); on transactional-DDL databases (PostgreSQL, H2, SQLite) wrapping `ensure()` in a transaction is safe and rolls the whole schema back on failure.

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

        // Another schema group
        b.contribute(SchemaEntity.class)
            .add(SchemaEntity.of("profile", UserProfile.class));
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

Each migration runs in its own database transaction. The tracking table is `_migrations` (configurable). Applied migrations are **immutable** — modifying a SQL file that has already been applied causes a checksum mismatch error at startup. Checksum validation is **dual-track for line-ending compatibility**: the stored SHA-256 of the raw file bytes is compared first, and if that mismatches, the file is compared a second time with CRLF line endings normalized to LF — a pure line-ending change (e.g. a Windows checkout of a file recorded from LF) is accepted, while any real content change still fails. The stored row is never rewritten.

On databases without transactional DDL (MySQL/MariaDB — DDL statements
implicitly commit there), a migration that **contains DDL is rejected** with a
`SqlException` before execution: the DDL would commit but the checksum row
would be lost, and the next startup would re-run the DDL and fail. Split such
DDL into separate migrations and make statements idempotent (`IF NOT EXISTS`),
or use a transactional-DDL database. Migrations are also locked against
concurrent runners (a `__LOCK__` row in the tracking table), and
already-applied migration files that disappear from the classpath fail fast as
a packaging error.

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
binder.contribute(NamedDatabase.class)
    .add(new NamedDatabase("audit", auditDb))
    .add(new NamedDatabase("primary", mainDb));

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
| `KafkaEventSink` | Implements `EventSink`, sends events to Kafka |
| `KafkaSubscriber` | Polls Kafka, publishes to local `EventBus` |
| `KafkaConfig` | Bootstrap servers, group-id, topic list |
| `KafkaModule` | Registers all services + `RuntimeHook` wiring |

**Sending:** EventBus automatically sends to Kafka when an `EventSink` is configured:

```java
@Topic("post.created")
public record PostCreatedEvent(Long postId, String title) {}

bus.publish(new PostCreatedEvent(1L, "Hello"));
// → local subscribers + Kafka broker
```

**Receiving:** `KafkaSubscriber` polls Kafka and publishes to local EventBus. Messages carry an `X-Event-Type` header for automatic type deserialization.

---

## Remote CallBus (`freeway-cloud.rpc`)

Cross-process request-reply for the `CallBus` channel. A provider registers
handlers in its own JVM; a consumer calls them through the same interface —
dispatch rides `CloudHttpClient`, so discovery, load balancing, retry,
circuit breaking, and propagation all apply.

**Server side — export an explicit mapping:**

```java
public final class UserRpcModule implements ModuleEx {
    @Override
    public void bind(Binder binder) {
        var bus = container.get(CallBus.class);            // container builtin
        bus.register("user", new UserHandlers());          // public methods become topics

        binder.contribute(Route.class)
            .add("user-rpc", RpcEndpoint.of("user", bus, new JsonCodecDefault()));
    }
}
```

Only mappings passed to `RpcEndpoint.of(...)` are reachable over HTTP —
nothing is auto-exported. Each call contributes its own route serving
`POST /rpc/<mapping>/{method}` (the mapping is a path literal, so several
mappings can be exported side by side), with positional arguments as a JSON
array. The mapping name is validated when you export it: `[A-Za-z0-9_.]` only.

**Consumer side — three shapes:**

```java
RemoteCaller caller = new RemoteCaller(container.get(CloudHttpClient.class),
    container.get(JsonCodec.class));

// 1. direct call
Greeting g = caller.invoke("user", "user", "greet", List.of("bob"), Greeting.class);

// 2. typed proxy, always remote
UserApi api = RemoteProxyFactory.of(null, caller)
    .serviceId("user").mapping("user").remoteOnly()
    .build(UserApi.class);

// 3. typed proxy, local-first: same-process modules hit the in-memory bus,
//    DeadCall falls through to the remote service — the smooth path from
//    monolith to services.
UserApi api2 = RemoteProxyFactory.of(callBus, caller)
    .serviceId("user").mapping("user").localFirst()
    .build(UserApi.class);
```

**Error model — two classes, two instincts:**

| Failure | Thrown as | Retry? |
|---------|-----------|--------|
| Transport (connect/timeout/5xx) | `CloudException`, `retryable()==true` | yes — Retryer/breaker apply |
| Remote handler threw | `CloudException` (4xx) with `RemoteInvocationException` as cause | **no** — deterministic |

Remote exceptions are rebuilt by class name and message only; the original
type is not reconstructed (it may not exist on the caller's classpath).
Cross-process calls are **outside any local transaction** — post-commit side
effects belong on the EventBus (Defer buffering), not on RPC.

---

## CloudEventBus (`freeway-cloud.events`)

Cross-node broadcast for the EventBus fact channel, over a WebSocket mesh —
CloudEvents 1.0 on the wire. Add `CloudEventModule` to every node that
participates:

```java
FreewayApp.run(new String[0],
    new AppModule(), new HttpModule(), new CloudEventModule());
```

Config (`freeway.cloud.events.*`):

```properties
freeway.cloud.events.enabled=true              # module is inert without this
freeway.cloud.events.peers=10.0.0.11:8080,10.0.0.12:8080
freeway.cloud.events.subscriptions=order.,user.created
freeway.cloud.events.allowed-types=com.acme.OrderCreated
freeway.cloud.events.allowed-topics=order.
freeway.cloud.events.token=mesh-secret         # blank = no peer auth (warned); MUST be set in production
```

- `peers` — nodes to dial; a registry backend (Nacos) can feed these
  dynamically instead. The endpoint rides the existing HTTP server at
  `/cloud/events`. IPv6 literals work bracketed (`[::1]:8080`) or bare.
- `subscriptions` — CloudEvents `type` prefixes this node pulls from the
  mesh; empty = outbound-only. Prefixes match the event class FQN and the
  `@Topic` value.
- `allowed-types` / `allowed-topics` — CLASS/TOPIC channel inbound
  whitelists, with **different empty-list semantics**: `allowed-types` is
  deny-by-default (empty = every CLASS-channel frame is dropped — there is
  no fallback to "allow any class"), while an empty `allowed-topics` allows
  any topic. Both warn at startup when left open.
- `token` — shared secret every peer must present in the hello frame;
  compared constant-time. Blank (default) disables peer auth — any host
  that can reach the endpoint may connect. **Multi-node production
  deployments must set it**: the value must be identical on every node (a
  mismatch closes the connection with WS `1008`), injected via
  `FREEWAY_CLOUD_EVENTS_TOKEN` rather than committed to a config file, and
  rotated with a rolling restart.

**Publishing is unchanged** — the same `EventBus.publish` fans out locally
and into the mesh; remote events arrive as `publishInbound` on peers:

```java
@Topic("order.created")
record OrderCreated(String orderId) implements EventBus.Keyed {
    @Override public String key() { return orderId; }   // → CE subject: per-key ordering
}

bus.publish(new OrderCreated("order-42"));
// → local subscribers + every mesh peer subscribed to "order."
```

**Who receives what — the one rule that bites:** a remote node receives an
event only if *its own* `subscriptions` declares a matching prefix. A local
`EventBus.subscribe` alone is **not** enough — a subscriber without a
declared subscription prefix silently never fires, and the publisher gets no
error. Same for CLASS events: the receiver's `allowed-types` must contain
the event class, or the frame is dropped (again silently). Deployment
checklist per node: module installed + `enabled=true` + `subscriptions`
declared + `allowed-types` whitelisted — missing any one of the four is a
silent partition, not an error.

**Delivery semantics:** at-most-once, real-time. A peer offline during a
publish misses that event (no replay queue) — for durable delivery use the
Kafka bridge (`freeway-mq-kafka`), which shares the same envelope translator.
`Stoppable` short-circuits are JVM-local: a vetoed event does not leave the
node, but remote peers cannot veto each other's copies.

**Error model:** inbound frames run through contributed interceptors
(`contribute(CloudEventInterceptor.class)`) for audit, tenant checks, and
custom filtering. Frames for types outside the whitelist are dropped, not
errors.

**Duplicate delivery — and how to turn it off.** A node reachable over two
transports (the mesh *and* a Kafka broker) receives every event **once per
transport**. That is fan-out working as designed, but it means the same event
reaches local subscribers twice unless they are idempotent.

Every event now carries one id across every transport it is bridged to — the
publishing `EventBus` mints it once per dispatch and hands it to each bridge,
rather than each bridge minting its own. When inbound dedup is armed, the
second copy is recognized and dropped:

```java
bus.enableInboundDeduplication(4096);   // remember the last 4096 inbound ids
```

or declaratively, which `CloudEventModule` does on your behalf:

```properties
freeway.cloud.events.dedup.enabled=true
freeway.cloud.events.dedup.capacity=4096
```

Dedup is **off by default**: it changes delivery semantics and costs memory,
so it must not be a side effect of installing a second transport. `capacity`
is the window in which a straggling second copy is still recognized — too
small and a slow copy slips through, too large and the window costs memory
for nothing. Events arriving with no id (an older producer without the
`X-Event-Id` header) are always delivered. Dedup applies to inbound events
only; local `publish` calls are never deduplicated.

---

## Flow (`freeway-flow`)

Lightweight graph-based workflow engine. Graphs are defined by the canonical `GraphSpec` (`nodes`+`links` structure with explicit `entry`). Zero external dependencies beyond commons + ioc. The legacy solon-flow `layout` format was removed — only the canonical shape loads.

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

**Graph definition:**

```java
// Programmatic
GraphSpec bp = GraphSpec.create("orderFlow", spec -> {
    spec.entry("start");
    spec.addStart("start").linkAdd("approve");
    spec.addActivity("approve").task("!channel:order").linkAdd("end");
    spec.addEnd("end");
});
Graph graph = bp.create();

// JSON — canonical nodes+links format
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

`Graph.fromText()` accepts **only** the canonical v2 format above — a document
must carry `"version": 2` plus `nodes` and `links`; anything else (including
the legacy solon-flow `layout` format, or a missing/other version field) is
rejected with an `IllegalArgumentException`. `GraphSpec.normalize()`
validates link references, requires exactly one entry, checks BFS reachability
at `create()` time, rejects cycles (LOOP iteration is driven by `$in`, never
by link back-edges), and rejects duplicate unconditional links between the
same pair of nodes (multi-edges must carry distinct `when` conditions).

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

**Gateway dead ends fail loudly:** a run that finishes without reaching the
END node throws a `FlowException` — e.g. an `EXCLUSIVE` node whose conditions
all evaluated false and that has no default link, or a join gateway that never
received all its incoming branches (including a `PARALLEL` fork where a branch
died). The exception names the dead-end node and its graph. Explicitly
stopped runs and interceptor-blocked runs are exempt.

**Condition expressions** (`when` on nodes/links) are evaluated by
`ExprEvaluator`: `&&`/`||` (and `and`/`or`) **short-circuit** — the right
operand is only evaluated when it can affect the result — and unary `-` (plus
`+`, `!`/`not`) is supported with type-preserving negation. Mixed
number/string comparisons are numeric when the string parses (`"10" > 9` is
true; `"10" == 10` is true), otherwise lexicographic; `"true"`/`"1"` are
truthy and `"false"`/`"0"` falsy in boolean contexts.

**Key types:**

| Type | Purpose |
|------|---------|
| `Graph` | Immutable runtime model — built from `GraphSpec` blueprints |
| `GraphSpec` | Canonical DAG authoring surface with explicit `entry` and separated `nodes`/`links` |
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

Supports PlantUML export, execution tracing with pause/resume, subgraph calls, and interceptor chains. **Sub-graph calls inherit the caller's per-eval interceptors**: the parent evaluation's `FlowOptions` (interceptors added via `interceptorAdd`, covering `interceptFlow`/`onNodeStart`/`onNodeEnd`) are propagated into `#subGraph` evals, so per-eval interceptors cover sub-graph nodes too (the engine-level interceptor list is merged exactly once per eval).

---

## Commons Utilities

Commons contains the shared runtime primitives used across Freeway: JSON, coercion, validation, `Defer`, `ScopedCache`, and logging support.

- JSON parsing, building, and serialization live in `JsonUtils` and `JsonCodec`
- coercion lives in `Coercer` and `CoerceRule`
- validation lives in `BeanValidator`
- scoped primitives are described in [freeway-commons.md](freeway-commons.md)

**JSON parsing limits (enforced by the built-in parser):** a single string
value is capped at 10 MB, a single number token at 10 MB, and streamed input
at 32 MB total (the string path applies the same 32 MB budget to the char
count); nesting is capped at 1000 levels and arrays/objects at 1,000,000
entries. Duplicate object keys are **last-wins** — `{"a":1,"a":2}` parses to
`a=2`, plain `Map.put` semantics, never an error.

**Bean serialization:** bean properties come from the field set, a
`getX()`/`isX()` accessor is the preferred read path when the class declares
one (transforming getters are honored), and getter-only (computed) properties
serialize as read-only members.

For more detail:

- [docs/freeway-commons.md](freeway-commons.md)
- [Defer summary](freeway-defer-summary.md)
- [DB usage guide](freeway-db.md)

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
- **`LoggerSource`** is the built-in logger service. Commons registers a JUL-backed SLF4J provider unconditionally via `META-INF/services`; at startup `LogBootstrap.ensureProvider()` probes the classpath for external SLF4J providers (Logback, Log4j, slf4j-simple) and pins the `slf4j.provider` system property so the external provider wins — the JUL provider is the fallback only when no external provider is present (or the user sets `-Dslf4j.provider` explicitly).

### Naming Rules

- **Public interfaces** use the bare domain name: `Container`, `JsonCodec`, `RequestContext`.
- **Framework-provided default implementations** use `XDefault` suffix: `AppRuntimeDefault`, `JsonCodecDefault`, `CoercerImpl`. This keeps interface names dominant.
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
