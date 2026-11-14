# IoC Reference

Examples below are minimal snippets. They omit imports and app-specific domain types.

## Stable API

- `Container` - `get(Class)`, `get(Class, String)`, `get(Class, Annotation...)`, `isActiveBindingMarked(Class, Annotation...)`, `extension(Class)`, `create(Class)`, `close()`
- `Binder` - `bind(Class)`, `contribute(Class)`
- `Freeway` - `create(ModuleEx...)`, `create(String appName, ModuleEx...)`, `create(Class...)`
- Module composition - the ordered modules handed to `Freeway.create(...)` / `FreewayApp.run(...)`; a module class with `@SubModule` is a bundle and placing it places its submodules; `Freeway.create(appName, ...)` names the root line the startup log shows
- `Binding<T>` - `to(Class)`, `to(c -> ...)` (provider), `id(String)`, `primary()`, `marker(Annotation...)`, `scope(Scope)`, `advise(...)`; there is no `to(instance)`
- `ModuleEx` - module entry-point type: `bind(Binder)`
- `Scope` - `SINGLETON`, `BOUNDARY`, `PROTOTYPE`
- `ScopeBoundary` - `within(...)`
- `Extension<V>` - framework-internal aggregation handle. Access via `container.extension(Class)`. Application code injects `List<V>` or `Map<String, V>`, not `Extension<V>` directly.
- `Contribution<T>` - contribution DSL from `Binder.contribute(Class)`: `add(T)`, `add(id, T)`, `addDeferred(Class)`, `addDeferred(id, factory)`; composition-time only - the stores seal when the container is built
- `Ordering` - handle returned by a named `add` or `addDeferred`: `before(String...)`, `after(String...)`, `beforeIfPresent(String...)`, `afterIfPresent(String...)` (bind-time only, same seal)
- `EventBus` - `publish`, `publish(String, Object)`, `publishAsync`, `subscribe`, `unsubscribe`
- `RuntimeHook` - `start(Container)`, `stop(Container)`
- `LoggerSource` - owner-aware logger lookup

## Container Creation

```java
// Direct container: tests and standalone use.
Container c = Freeway.create(binder -> {
    binder.bind(MyService.class).to(MyServiceImpl.class);
});
MyService svc = c.get(MyService.class);
c.close();

// Full application: config, profiles, runtime hooks, lifecycle.
AppRuntime runtime = FreewayApp.run(new AppModule());
MyService appSvc = runtime.get(MyService.class);
```

`Freeway.create(...)` builds a bare container; `FreewayApp.run(...)` starts the application around one (see [Boot reference](boot.md)). `AppRuntime` resolves services through `get(Class)` / `get(Class, String)` and exposes no live `Container`; keep the container handle for tests and standalone use.

## Canonical Patterns

```java
Container c = Freeway.create(binder -> {
    binder.bind(Greeter.class).to(GreeterImpl.class);
});

Freeway.create(binder -> {
    binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe").primary();
    binder.bind(PaymentGateway.class).to(PayPalGateway.class).id("paypal");
});
```

## Binding Modes

The DSL has exactly two binding modes:

```java
// 1. Class: the container selects an @Inject constructor and builds the service.
binder.bind(Greeter.class).to(GreeterImpl.class);

// 2. Provider: explicit construction with Container access.
binder.bind(Cache.class).to(c -> new Cache(c.get(Config.class), ttl));
```

There is deliberately no `to(instance)`. Bind a pre-created or external object by returning it
from a singleton provider:

```java
Config config = new Config(...);
binder.bind(Config.class).to(c -> config); // singleton provider returns the same instance
```

Prefer `.to(Class)` whenever constructor injection is sufficient. Use the provider form when:

- construction mixes services with plain values or runtime config (`PoolConfig`, `HttpConfig`, `KafkaConfig`);
- construction must branch on configuration or select between constructors (e.g. SSL enabled/disabled);
- the object graph aggregates `List`/`Map` contributions or extension data (`RouteIndex`, `HttpServer`);
- the instance already exists and must not be rebuilt (`AppConfig`, a shared `PeerHub`);
- realization must be deferred until after all modules are composed (lazy builtins).

The provider owns only *how the object is built*. Scope and lifecycle still apply: a singleton
provider runs once per container, and the produced object still receives field injection,
`@PostConstruct`, and `@PreDestroy`.

### Full Chain

```java
Freeway.create(binder -> {
    // Base binding: interface -> implementation.
    binder.bind(Greeter.class).to(GreeterImpl.class);

    // Pre-created instance: return it from a singleton provider.
    // The container still owns its lifecycle.
    Config config = new Config(...);
    binder.bind(Config.class).to(c -> config);

    // Provider binding: factory called according to scope.
    binder.bind(Cache.class).to(c -> new Cache(c.get(Config.class)));

    // Named bindings.
    binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe");
    binder.bind(PaymentGateway.class).to(PayPalGateway.class).id("paypal");

    // Primary binding: used when no id is given.
    binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe").primary();

    // Scope.
    binder.bind(RequestState.class).to(RequestState.class).scope(Scope.BOUNDARY);

    // AOP advice: interface -> implementation bindings only.
    binder.bind(UserService.class).to(UserServiceImpl.class).advise(advisor ->
        advisor.wrap(inv -> inv.method().getName().startsWith("get"),
                     inv -> { /* before/after logic */ return inv.proceed(); }));
});
```

### Binding DSL Quick Table

| Method | Purpose | Constraint |
|---|---|---|
| `.to(Class)` | interface -> implementation | prefer a single `@Inject` constructor (see Constructor selection); the lower tiers infer |
| `.to(c -> ...)` | provider factory; returning an existing instance is the way to bind it | invoked per scope policy; once under `SINGLETON` |
| `.id("name")` | named binding | unique per type + id |
| `.primary()` | marks the default binding | used when no id is given |
| `.marker(Annotation...)` | attaches marker annotations | e.g. `.marker(Fast.class)` |
| `.scope(Scope)` | lifetime | `SINGLETON` (default), `BOUNDARY`, `PROTOTYPE` |
| `.advise(Consumer<Advisor>)` | AOP weaving | interface bindings only |

### Constructor selection

`BeanIntrospector.selectConstructor(type, @Inject.class)` decides which constructor the container
calls, in three tiers:

1. exactly one constructor annotated `@Inject` — that one wins (two or more throw
   `Multiple @Inject constructors found on <type>`);
2. otherwise a no-arg constructor — **even when a larger one exists**;
3. otherwise the constructor with the most parameters.

Write to tier 1 and leave the other tiers unreachable: a class the container constructs should have
**one constructor, annotated `@Inject`**. Only that tier is a decision the source states; tiers 2 and
3 are the container inferring, and the inference is what goes wrong quietly. Tier 2 is why a
convenience constructor left unannotated next to the intended one silently drops every parameter
injection — no warning, the object is just built without its dependencies. Two constructors of the
same arity are the quietest failure of all: the `>` in tier 3's comparison keeps whichever appears
first in `getDeclaredConstructors()`, an order the JVM does not specify, so the winner can change
with the compiler or a source reorder. When construction genuinely branches (settings, plain values,
runtime config), bind a provider — do not leave a second constructor for the container to infer.

## Service Lookup

```java
Greeter g = c.get(Greeter.class);                          // unique binding, or the primary
PaymentGateway pg = c.get(PaymentGateway.class, "stripe"); // type + id
Cache cache = c.get(Cache.class, Fast.class);              // type + markers (containsAll)

List<Route> allRoutes = c.extension(Route.class).all();
List<Route> namedRoutes = c.extension(Route.class).asMap().values().stream().toList();
```

`get(type, markers)` requires the selected binding to carry every listed marker. `get(type)` fails
with `AmbiguousBindingException` when several bindings exist and none is the unique primary, and
with `MissingBindingException` when no binding matches.

## Markers

A marker is an empty annotation used as a compile-time qualifier. Markers declared on a module
propagate to every binding that module declares:

```java
@Marker(Builtin.class)
public class AppModule implements ModuleEx { ... }

// Or attach a marker to one binding.
binder.bind(Cache.class).to(FastCache.class).marker(Fast.class);
```

Markers accumulate from the module-level `@Marker`, the implementation class (including
`@Primary`), and explicit `.marker(...)` calls. Injection points match with `containsAll` semantics:
`@Inject @Fast Cache cache` and `c.get(Cache.class, Fast.class)` both require the selected binding
to carry `@Fast`. Marker annotations must have `RUNTIME` retention.

## Choosing id, primary, marker, and contributions

| Need | Use | Consumer |
|---|---|---|
| Single implementation | `.to(Class)` / `.to(c -> ...)` | `get(type)` |
| Alternatives with one assembly default | several bindings + `.primary()` | plain `get(type)` |
| Runtime selection by name | `.id("name")` | `get(type, "name")` / `@Inject("name")` |
| Compile-time qualifier | `.marker(Anno.class)` | `get(type, Anno.class)` / `@Inject @Anno` |
| Open-ended pluggable set | `contribute(...)` | `List<T>` / `Map<String,T>` |

- `primary()` is a supply-side default for extension override; consumers do not name it.
- `id` and `marker` are demand-side qualifiers; prefer marker unless selection is dynamic.
- Contributions are not single-service selection; do not model a list of extensions with bindings.
- `scope` is orthogonal: it controls lifetime, not which implementation is selected.

```java
binder.contribute(Route.class)
    .add(Route.get("/", ctx -> ctx.sendText(200, "Hello")));

binder.contribute(RuntimeHook.class)
    .add("cache.warmup", new RuntimeHook() {
        public void start(Container c) { c.get(Cache.class).warmup(); }
        public void stop(Container c) { c.get(Cache.class).close(); }
    }).before("freeway.http.server");
```

## Injection Annotations

The annotations live in `com.jujin.freeway.ioc.annotation`:

| Annotation | Purpose | Example |
|---|---|---|
| `@Inject` | constructor, field, or constructor-parameter injection | `@Inject private Logger log;` |
| `@Inject("id")` | named-binding injection | `@Inject("audit") Logger audit;` |
| `@Symbol("key")` | strict config lookup; a missing key fails startup | `@Symbol("server.port") int port;` |
| `@Symbol("${key:default}")` | config expression with an optional default | `@Symbol("${app.timeout:30}") int timeout;` |
| `@PostConstruct` | initialization callback | `void init() { ... }` |
| `@PreDestroy` | destruction callback | `void cleanup() { ... }` |

On a `Logger` field, `@Inject("name")` names the logger instead of selecting a binding. Annotation
injection also works on records:

```java
public record ServerConfig(
    @Symbol("server.port") int port,
    @Symbol("${app.name:freeway}") String appName
) {}
```

## Injection Styles

```java
public class UserService {
    private final UserRepository repo;

    // Constructor injection: the one constructor carries @Inject
    // (see Constructor selection; without it tier 1 is unreachable).
    @Inject
    public UserService(UserRepository repo) {
        this.repo = repo;
    }

    // Field injection: acceptable in application code.
    @Inject private Logger log;
    @Inject("audit") private Logger audit;              // logger named "audit"
    @Symbol("${app.timeout:30}") private int timeout;
}
```

Prefer constructor injection for required collaborators; field injection is acceptable for
application code and config values. Primary selection is a supply-side decision: `.primary()` on
the binding, or `@Primary` on the implementation class (both set the same marker). The injection
point stays unqualified — `@Inject Cache cache` resolves the primary.

## Scopes and Boundaries

| Scope | Behavior | Destroyed |
|---|---|---|
| `SINGLETON` | one instance per container (the default) | container `close()` |
| `PROTOTYPE` | a new instance on every resolution | not held by the container; never auto-destroyed |
| `BOUNDARY` | one instance per `ScopeBoundary.within(...)` boundary | boundary close; when attached to a `Defer` action scope, that action scope's close |

`ScopeBoundary.within()` is backed by JDK 25 `ScopedValue`: no `ThreadLocal` overhead on virtual
threads, and boundaries nest (an inner boundary shadows the outer one, then the outer one is
restored). `ScopedValue` does not propagate to child threads, so work handed to another thread
must open its own boundary. A boundary opened inside `Defer.within(...)` attaches to that action
scope: scoped values and beans live until the action scope closes, not until the block returns.

**Scope compatibility rule:** a singleton cannot directly inject a boundary-scoped concrete class.
Bind the boundary-scoped service to an interface and let the container supply a lazy proxy:

```java
// Correct: the singleton injects the interface and receives a lazy proxy.
binder.bind(ScopedApi.class).to(ScopedCounter.class).scope(Scope.BOUNDARY);
binder.bind(ScopedSingletonService.class).to(ScopedSingletonService.class);

// Wrong: a singleton directly injects the boundary-scoped concrete class.
binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.BOUNDARY);
binder.bind(ScopedSingleton.class).to(ScopedSingleton.class); // container fails at startup
```

Boundary usage:

```java
binder.bind(RequestState.class).to(RequestState.class).scope(Scope.BOUNDARY);

ScopeBoundary boundary = c.get(ScopeBoundary.class);
boundary.within(() -> {
    RequestState state = c.get(RequestState.class);
    state.set(...); // same instance for the whole boundary
});
// Boundary closes: @PreDestroy and AutoCloseable run on every scoped service.
```

The boundary is caller-defined — an HTTP request, a consumed message, a batch, a WebSocket
callback, a test. Resolving a boundary-scoped service outside an open boundary fails with
`No open scope for type ...`.

## Type Coercion

```java
Coercer coercer = c.get(Coercer.class);
int port = coercer.coerce("8080", int.class);

// Custom rule: String -> Endpoint.
binder.contribute(CoerceRule.class).add(new CoerceRule<>(
    String.class, Endpoint.class,
    value -> {
        String[] parts = value.split(":", 2);
        return new Endpoint(parts[0], Integer.parseInt(parts[1]));
    }
));
```

`Coercer` is a container builtin. The same coercer types `@Symbol` values and `SymbolSpec` reads,
so a contributed `CoerceRule` reaches configuration injection as well as direct calls.

## Extensions

Extensions are Freeway's plugin mechanism: contribute by entry type, consume by type.

### Architecture

```
Module.bind()
  |
  +- binder.contribute(Route.class)          <- declare the extension point type
  |    +- .add(value) / .add(id, value)      <- contribute values
  |
  +- the container collects and merges at startup
       |
       +- @Inject List<Route> routes           <- ordered list of all contributions
       +- @Inject Map<String, Route> routes    <- id -> value, named contributions only
       +- c.extension(Route.class).all()       <- framework-side runtime access
```

### Contribution API

```java
public interface Binder {
    <V> Contribution<V> contribute(Class<V> entryType);
}

public interface Contribution<T> {
    Contribution<T> add(T value);                        // unnamed, insertion order
    Ordering add(String id, T value);                    // named, supports before/after
    Ordering addDeferred(Class<? extends T> implClass);  // class contribution, auto id
    Ordering addDeferred(String id, Function<Container, ? extends T> factory);
                                                         // built after every module has bound
}

public interface Ordering {
    Ordering before(String... ids);          // must precede these ids
    Ordering after(String... ids);           // must follow these ids
    Ordering beforeIfPresent(String... ids); // conditional; absent ids are vacuous
    Ordering afterIfPresent(String... ids);
}
```

`add(value)` returns the contribution for chaining; a named or deferred add returns an `Ordering`
handle. `addDeferred(Class)` generates the id as `snake_name@package` — the class simple name in
camel-to-snake form plus the package, so two packages may contribute the same simple name. Both
deferred forms are built after every module has bound: the config layer (`SymbolProvider`,
`CoerceRule`) is finalized first, then the remaining entries.

### Consumption

```java
// 1. List injection: all contributions, in resolved order.
@Inject List<Route> routes;
routes.forEach(r -> register(r));

// 2. Map injection: named contributions only, keyed by id.
@Inject Map<String, FlowDriver> drivers;
FlowDriver custom = drivers.get("custom");

// 3. Constructor injection.
public class Router {
    private final List<Route> routes;
    private final Map<String, FlowDriver> drivers;
    public Router(List<Route> routes, Map<String, FlowDriver> drivers) {
        this.routes = List.copyOf(routes);
        this.drivers = Map.copyOf(drivers);
    }
}

// 4. Container.extension() for framework modules.
List<Route> all = c.extension(Route.class).all();
Map<String, Route> named = c.extension(Route.class).asMap();
```

| Contribution form | In `List<V>` | In `Map<String, V>` |
|---|---|---|
| `add(value)` - unnamed | yes | no |
| `add("id", value)` - named | yes | yes, id as key |
| `addDeferred(Class)` - auto id | yes | yes, generated id as key |
| `addDeferred("id", factory)` - named | yes | yes, id as key |

`List<V>` holds every contribution in resolved order; `Map<String, V>` holds only named ones.
`Extension<V>` is the framework's read-only aggregation view (`all()`, `asMap()`,
`validateOrdering()`), not an injection target: application code injects `List<V>` or
`Map<String, V>`, which are immutable views. `asMap()` returns an immutable point-in-time
snapshot; later contributions are visible only through a fresh call.

### Built-in Extension Points

| Entry type | Purpose |
|---|---|
| `Route.class` | HTTP routes |
| `RouteGroup.class` | route groups with a prefix |
| `HttpFilter.class` | HTTP request filters |
| `RuntimeHook.class` | lifecycle hooks |
| `EventSubscriber.class` | module-level event subscribers |
| `CoerceRule.class` | type-coercion rules |
| `ErrorMapper.class` | HTTP exception mappers (return `Optional<ErrorResponse>`; only the server writes the response) |
| `StaticResourceMount.class` | static-file mounts |
| `WebSocketRoute.class` | WebSocket routes |
| `WebSocketGroup.class` | WebSocket route groups |
| `RowMapping.class` | database row mappings |
| `NamedDatabase.class` | named database registrations |

## Config Injection: @Symbol

`@Symbol` is the config-injection annotation, with two forms:

- **`@Symbol("key")`** — strict, for **required** config. A missing key fails startup
  (`UnknownSymbolException`). Use for mandatory settings: database URLs, server ports,
  credentials.

- **`@Symbol("${key:default}")`** — relaxed, for **optional** config with a default. Use for
  settings with sensible fallbacks: timeouts, feature flags, cosmetic names. The `${...}` form
  also expands nested references.

```java
@Symbol("db.url") String dbUrl;                    // required
@Symbol("${app.timeout:30}") int timeout;           // optional, default 30
@Symbol("${app.name:freeway}") String appName;      // optional, default "freeway"
```

## EventBus

`EventBus` is the in-process publish-subscribe bus, available as a container builtin
(`@Inject EventBus bus`). Delivery is at-most-once, best-effort: a throwing subscriber is isolated
and counted, never retried.

### Module-Level Subscriptions

Contribute subscribers at bind time; the contribution id carries the ordering.

```java
var subscribers = binder.contribute(EventSubscriber.class);
subscribers.add("index", EventSubscriber.of(PostCreatedEvent.class, e -> index(e.post())));
subscribers.add("notify", EventSubscriber.of(PostCreatedEvent.class, e -> sendEmail(e)))
    .after("index");

// String-topic subscription: the first argument is the topic.
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of("order.placed", payload -> process(payload)));
```

Module-level subscribers are sealed at composition time and support the usual `before`/`after`
ordering. Within one dispatch, module subscribers run first (in contribution order), then runtime
subscribers (in subscription order). An unnamed `add(...)` cannot be referenced by
`before`/`after`; name the contribution to order it.

### Runtime Subscriptions

```java
@Inject EventBus bus;

Subscription<PostCreatedEvent> sub = bus.subscribe(PostCreatedEvent.class, e -> { ... });
bus.unsubscribe(sub);

// String-topic subscription.
Subscription<Object> topicSub = bus.subscribe("order.placed", payload -> { ... });
```

Runtime subscriptions are registered after composition, receive events until `unsubscribe` (or bus
close), and are not part of contribution ordering.

### Publishing

```java
bus.publish(new PostCreatedEvent(post));        // class event
bus.publish("order.placed", payload);           // string topic
bus.publishAsync(new PostCreatedEvent(post));   // async executor (virtual threads by default)
```

`publish(event)` matches subscribers on the event's runtime type (and supertypes);
`publish(topic, payload)` matches the topic string. The two channels do not cross. Inside a
`Defer` scope, publishes are buffered and dispatched only after the scope commits; a rollback
discards them. `publishAsync` dispatches on a separate thread, so the handler does not run inside
the publisher's `Defer` scope (it cannot join that transaction); the submission itself is still
released only when the scope commits.

### Stoppable Events

```java
public class PostCreatedEvent implements EventBus.Stoppable {
    private final AtomicBoolean stopped = new AtomicBoolean();

    @Override public void stop() { stopped.set(true); }
    @Override public boolean isStopped() { return stopped.get(); }
}

// The first subscriber validates and can stop propagation.
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(PostCreatedEvent.class, e -> {
        if (!loggedIn) e.stop();
    }));
```

A subscriber calls `stop()` to short-circuit the remaining subscribers in the dispatch chain.
Stoppable applies to both the class-event and string-topic channels.

### Lifecycle Events

- `AppLifecycleEvent(Container, AppState)` — published by boot on the `EventBus`:
  `AppState.RUNNING` after all runtime hooks have started; `AppState.STOPPING` before shutdown
  (before the hooks stop). See [Boot reference](boot.md) for the runtime lifecycle.

## AOP

### Advisor API

```java
public interface Advisor {
    Advisor wrap(Predicate<MethodInvocation> selector, MethodAdvice advice);
}

public interface MethodAdvice {
    Object invoke(MethodInvocation invocation) throws Throwable;
}

public interface MethodInvocation {
    Object proceed() throws Throwable;   // invoke the target method
    Object target();                     // target instance
    Method method();                     // method being invoked
    Object[] arguments();                // defensive copy of the arguments
}
```

### Basic Usage

```java
binder.bind(UserService.class).to(UserServiceImpl.class).advise(advisor ->
    advisor.wrap(
        inv -> inv.method().getName().startsWith("get"),
        inv -> {
            // Before the call.
            long start = System.nanoTime();
            try {
                Object result = inv.proceed();    // invoke the target method
                return result;
            } finally {
                // After the call.
                long elapsed = System.nanoTime() - start;
                log.info("{}.{} took {}ns",
                    inv.target().getClass().getSimpleName(),
                    inv.method().getName(), elapsed);
            }
        }
    )
);
```

### Multiple Advisors

One binding can stack several `advisor.wrap()` calls:

```java
binder.bind(UserService.class).to(UserServiceImpl.class).advise(advisor -> {
    // First advice: performance timing.
    advisor.wrap(inv -> true, inv -> {
        long start = System.nanoTime();
        try { return inv.proceed(); }
        finally { log.info("{} took {}ms", inv.method().getName(),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)); }
    });

    // Second advice: security check.
    advisor.wrap(inv -> inv.method().isAnnotationPresent(Secured.class), inv -> {
        if (!hasRole("ADMIN")) throw new SecurityException("Access denied");
        return inv.proceed();
    });
});
```

Advice is applied in registration order: the first matching advice is outermost, and `proceed()`
enters the next matching advice or the target method.

### Selectors

The selector is a `Predicate<MethodInvocation>` evaluated for every interface method call:

```java
advisor.wrap(inv -> true, advice);                                           // all methods
advisor.wrap(inv -> inv.method().getName().startsWith("get"), advice);       // getters
advisor.wrap(inv -> inv.method().isAnnotationPresent(Timed.class), advice);  // @Timed methods
advisor.wrap(inv -> inv.method().getParameterCount() == 0, advice);          // no-arg methods
```

### Limitations

- **Interface bindings only.** Advice is applied through a JDK dynamic proxy, so the bound service
  type must be an interface. Binding a concrete class
  (`.bind(ConcreteClass.class).to(ConcreteClass.class).advise(...)`) fails at bind time with
  `Advice is not supported on non-interface type ...`.
- **Only interface methods are intercepted.** `static` and `private` methods are never proxied;
  `final` does not block an interface method, because the JDK proxy invokes the implementation
  through the interface.

### Boundary-Scoped Proxy

Cross-scope injection is the container's implicit AOP. When a singleton injects a boundary-scoped
interface, the container creates a lazy proxy; every call delegates to the instance of the current
boundary, so the proxy itself is not the scoped object.

```java
public interface RequestContext {
    String correlationId();
}

binder.bind(RequestContext.class).to(RequestContextDefault.class).scope(Scope.BOUNDARY);

public class Handler {
    @Inject
    private RequestContext ctx; // proxy, not the actual instance
}
```

The proxy resolves its target on each call, so it must be invoked inside
`ScopeBoundary.within(...)`; outside a boundary the call fails with `No open scope for type ...`.

## Important Behavior

- `@Inject`, `@Symbol` are the main injection annotations; `@PostConstruct` and `@PreDestroy` are
  the lifecycle callbacks.
- `List<Foo>` and `Map<String, Foo>` are injectable for contribution consumption. `Extension<Foo>` is not injectable — use `List` or `Map` instead.
- `Extension.asMap()` returns an immutable point-in-time snapshot; later contributions are visible only through a fresh call.
- `Container` is not injectable — access it via `RuntimeHook.start(Container)` or `Freeway.create()`.
- Singleton services should not directly inject boundary-scoped concrete classes.
- AOP applies only to interface-typed bindings.
- Blank ids are rejected.
- `Binding.primary()` is the DSL for primary resolution; `@Primary` on the implementation class is equivalent (both set the same marker).

## Contribution Ordering

- Contributions are accepted only while modules bind — the container seals every extension store at the end of composition; later `add`/`before`/`after` fail with a "sealed" error. Late subscribers use `EventBus.subscribe`, not the store.
- `add(value)` — unnamed, insertion order.
- `add(id, value)` — named, supports `before()` / `after()` topological ordering; duplicate ids are rejected immediately.
- `addDeferred(id, factory)` / `addDeferred(Class)` — built in the deferred phase after every module has bound (config-layer entry types drain first); `addDeferred(Class)` generates a canonical id as `snake_name@package`. Both return `Ordering` for `before`/`after` chaining.
- After all modules have bound, the container topologically sorts named contributions by their `before`/`after` constraints; cycles fail resolution.
- Unknown ids in `before`/`after` are WARNed and ignored by generic ordering (a missing sibling is harmless — the module may not be installed). Strict extension points (runtime hooks) call `Extension.validateOrdering()`, which fails startup on any unknown reference instead.
- `beforeIfPresent` / `afterIfPresent` order against a contribution only when it exists: an absent id is vacuous in both `all()` and `validateOrdering()`.
