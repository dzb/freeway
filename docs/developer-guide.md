# Freeway 2 Developer Guide

Freeway is a lightweight, modern Java application framework for JDK 25+. Compose-first, zero classpath scanning, zero bytecode weaving, minimal dependencies.

## Quick Start

Lambda handlers are the simplest way to get started:

```java
import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.http.HttpExchange;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;

// A minimal HTTP application
public class App {
    public static void main(String[] args) {
        try (AppRuntime runtime = FreewayApp.run(Launch.of(args), new AppModule())) {
            // use runtime
        }
    }

    public static final class AppModule implements ModuleEx {
        public void bind(Binder b) {
            b.contribute(Route.class).add(Route.get("/", ctx ->
                ctx.sendText(200, "Hello Freeway")));
        }
    }
}
```

One import must carry its full path — the JDK ships a different
`com.sun.net.httpserver.HttpServer` under the same simple name, and an
auto-import of that one compiles through `container.get(HttpServer.class)`
only to fail the binding lookup at runtime:

```java
import com.jujin.freeway.http.HttpServer;
```

When a handler needs injected services, use a handler class instead.
The container creates it and injects its dependencies via the constructor:

```java
public class App {
    public static void main(String[] args) {
        try (AppRuntime runtime = FreewayApp.run(Launch.of(args), new AppModule())) {
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

            public void handle(HttpExchange ctx) throws Exception {
                // send/sendJson throw IOException, so the branches cannot
                // sit in ifPresentOrElse lambdas — use explicit statements.
                var user = service.findById(ctx.pathVar("id").orElseThrow());
                if (user.isEmpty()) {
                    ctx.sendText(404, "Not found");
                    return;
                }
                ctx.sendJson(200, user.orElseThrow());
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
freeway-commons          shared utilities: JSON, coercion, scoped primitives, beans, validation, config specs
freeway-ioc              IoC container: bind, inject, scope, advise, event-bus, extensions, symbol config
freeway-boot             launcher, config cascade (hot reload), profiles, runtime lifecycle
freeway-http             HTTP/WebSocket: routing, filters, static, multipart, SSE
  └ built-in              FreewayHttpEngine (HTTP/1.1 + HTTP/2 + WebSocket + HTTPS)
  └ engine adapters       Undertow, Jetty → see freeway-ext
freeway-db               JDBC: ORM, pooling, transactions, SQL builder, migrations
  └ connection pool       HikariCP adapter → see freeway-ext
freeway-flow             Graph workflow engine — 7 node types, v3 JSON graphs, branch isolation
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

> **Auto-discovery**: `FreewayApp.run(...)` and `FreewayApp.create(...).start()` load additional
> `ModuleEx` implementations declared via `META-INF/services/com.jujin.freeway.ioc.ModuleEx`
> (ServiceLoader SPI) **by default**. This is opt-out — call
> `FreewayApp.create(...).autoDiscovery(false)` (or `.shutdownHook(false)` for the JVM
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

For the full module patterns, see [module-system.md](module-system.md).

---

## IoC Container

### Core API

| Type | Purpose |
|------|---------|
| `Container` | Service lookup: `get(Class)`, `get(Class, String)`, `get(Class, Annotation...)`, `extension(Class)`, `create(Class)`, `describe()`, `close()`. `create` is `new` **plus dependency resolution** — constructor and `@Inject` fields are satisfied, but it is caller-owned, so it runs no `@PostConstruct` (see [Scopes](#scopes)) |
| `ModuleEx` | Module entry-point type: `bind(Binder)`. Named to avoid `java.lang.Module` conflict |
| `ModuleDeclaration` | A placed module: class declaration or configured instance; the single owner of no-arg construction and `@SubModule` metadata |
| `Binder` | Binding and contribution DSL |
| `Binding` | Service binding configuration: target, id, primary, scope, advisor |
| `Freeway` | Container bootstrap for composing modules: `Freeway.create(ModuleEx...)`, `Freeway.create(Class...)`, or a mixed `ModuleDeclaration...` list |
| `RuntimeHook` | Start/stop lifecycle extension consumed by `AppRuntime` |
| `ScopeBoundary` | Executes work inside a `Scope.BOUNDARY` boundary via `within()` |
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
AppRuntime runtime = FreewayApp.run(new AppModule());
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
    binder.bind(RequestState.class).to(RequestState.class).scope(Scope.BOUNDARY);

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
| Object graph aggregates contributions | Constructor injection sees bound services, not collected extension points | `RouteIndex` collects `Route`/`RouteGroup`; `HttpServer` assembles filters, mounts and error handlers |
| A pre-created or externally owned object must be bound | The container must not re-construct it, but should still own its lifecycle | boot's `AppConfig`; a `PeerHub` shared with a sink and WS route |
| Construction must wait until all modules are composed | Realization happens on first resolution, after contributions are final | the lazy `EventBus` builtin resolving a user-supplied primary `Metrics` |

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

### Choosing Between id, primary, marker, and contributions

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
| `BOUNDARY` | One instance per **scope session**, built as the scope opens and destroyed when its owning session closes; inside a `Defer` action scope it attaches and lives until that action scope closes. `ScopedValue` does not propagate, so a child thread sees none of it. |

```java
binder.bind(RequestState.class).to(RequestState.class).scope(Scope.BOUNDARY);

ScopeBoundary boundary = container.get(ScopeBoundary.class);
boundary.within(() -> {
    RequestState state = container.get(RequestState.class);
    // same instance reused within this block
});
// state destroyed — @PreDestroy + AutoCloseable invoked
// (inside a Defer action scope, destruction happens when that scope closes)
```

`ScopeBoundary.within()` uses JDK 25 `ScopedValue`, so there is no `ThreadLocal` overhead on virtual threads. Nesting is supported — inner scopes shadow outer scopes. Internally, boundary-scoped services are cached via `ScopedCache` (see [Scope primitives](#scope-primitives-defer--scopedcache--scopeboundary)). A value scope opened inside a `Defer` action scope attaches to it: beans are resolvable inside the `ScopeBoundary.within` block, live until the action scope closes, and are not resolvable from outside that block.

**Scope compatibility rule:** A singleton cannot directly inject a boundary-scoped concrete class. Use an interface with proxy support instead:

```java
// OK — singleton injects interface, gets a lazy proxy
binder.bind(ScopedApi.class).to(ScopedCounter.class).scope(Scope.BOUNDARY);
binder.bind(ScopedSingletonService.class).to(ScopedSingletonService.class);

// WRONG — singleton injects concrete boundary-scoped class
binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.BOUNDARY);
binder.bind(ScopedSingleton.class).to(ScopedSingleton.class); // fails
```

#### Where a `Scope.BOUNDARY` service can be used

The scope is **session**-level, not request-level: an instance is built as
`ScopeBoundary.within(...)` opens and destroyed when its owning session closes — at
the end of `within`, or at the enclosing `Defer` action scope's close when it
attached to one. `ScopedValue` does not propagate, so a child thread inherits
nothing. Whatever the unit of work
is, that unit is what you wrap.

The framework opens no boundary scope on its own, so **the caller opens it**:

```java
ScopeBoundary boundary = container.get(ScopeBoundary.class);
boundary.within(() -> {
    RequestState state = container.get(RequestState.class);  // one per within()
    handle(state);
});
// @PreDestroy + AutoCloseable ran for everything resolved inside
```

Two consequences worth knowing before you reach for it:

- **A class-based route handler cannot take one.** `HttpModule` builds
  handlers at startup, on a thread with no open scope, so a
  boundary-scoped constructor argument fails at boot with
  `No open scope for type X — wrap the call in container.get(ScopeBoundary.class).within(() -> ...)`.
  That refusal is the design working: resolving outside a scope throws rather
  than quietly handing back a process-wide singleton, which is the one
  behaviour that would make a boundary-scoped service a lie.
- **Reaching a boundary-scoped service from a singleton** goes through an
  interface the singleton can hold a proxy for — see the compatibility rule
  above. The proxy resolves the instance when a method is actually called, so
  it resolves inside whatever scope is open at that moment.

### Service Lifecycle

`@PostConstruct` and `@PreDestroy` belong to **managed** services, and they are
paired: a service the container realizes through a binding gets both.

| How the instance is produced | `@PostConstruct` | `@PreDestroy` |
|---|---|---|
| `binder.bind(X.class).to(X.class)` (SINGLETON) | yes | yes, on `close()` |
| `Scope.BOUNDARY` | yes | on owning session close — one instance per `within()` block; attached to an enclosing `Defer` action scope, it closes with that scope |
| `Scope.PROTOTYPE` | yes | never retained, so never |
| `contribute(X.class)` | yes | **no** — a contribution is not a binding, so nothing tracks it for shutdown |
| `container.create(X.class)` | **no** | no |

**Cleanup: `AutoCloseable` first, `@PreDestroy` only for what it cannot express.**

A service that owns something — a pool, a client, a file, a thread — should
`implements AutoCloseable`. The container finds it by type, so nothing can be
forgotten, and `try-with-resources` reads the same way:

```java
public final class OrderClient implements AutoCloseable {
    @Override public void close() { /* release */ }
}
```

`@PreDestroy` is the other path, **not a replacement for it** — a class may carry
both, and then both callbacks run, in this order:

| | runs | why there |
|---|---|---|
| `@PreDestroy` | first | explicit lifecycle callback; may still want to publish |
| `EventBus` | deferred past both | a `@PreDestroy` may still publish on it |
| `AutoCloseable.close()` | last | compiler-enforced, so the least surprising shape goes last |

Each phase deduplicates by identity, so no callback runs twice — but the phases
do not deduplicate *against each other*: a class carrying both runs
`@PreDestroy` and then `close()`. Mark the method with only one, and pick by
what it needs: `AutoCloseable` unless the cleanup must still reach the
`EventBus`. Carrying both means both run, so each must be able to stand alone.

`@PostConstruct` is not part of this choice. It has no substitute: AOP
intercepts *method calls*, and this fires before anyone has an object to call a
method on.

`Container.create` is deliberately the odd one out. It is `new` plus dependency
resolution — the constructor arguments and `@Inject` fields are satisfied from
the container, which is what makes it worth preferring over a bare `new` — but
the instance is **yours**: it is not registered, not cached, and not returned
by later `get()` calls. A `@PostConstruct` there would have no `@PreDestroy` to
pair with, since nothing walks the result at shutdown, so a bean that opened a
connection or registered a callback in it would never release either. If a type
needs lifecycle, bind it.

### Injection

Supported annotations in `com.jujin.freeway.ioc.annotation`:

| Annotation | Purpose |
|------------|---------|
| `@Inject` | Constructor parameter or field injection — **not** a method parameter |
| `@Symbol("key")` | Strict config lookup — missing key fails |
| `@Symbol("${key:default}")` | Config expression with optional default |

**The two injection points are the whole surface.** The container resolves a
type's *constructor parameters* and its *fields*, and reads nothing else — so
`call(@Inject Greeter greeter)` compiles (`PARAMETER` is in the target, and the
constructor parameters that do work need it) and then does nothing, leaving the
parameter as whatever the caller passed. Nothing could make it work: the
framework does no bytecode weaving, so it cannot intercept a call to a concrete
class, and an advisor cannot substitute arguments either. Such an annotation
makes the container refuse to inject the declaring class — it scans the type,
its superclasses and every interface it implements when it first realizes that
class, and names the method, the parameter and the way out.

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

    @Symbol("${app.timeout:30}")
    private int timeout;
}
```

Primary resolution uses `binding.primary()` on the binding DSL, not an annotation.

Annotation injection on records is also supported:

```java
public record ServerConfig(
    @Symbol("server.port") int port,
    @Symbol("${app.name:freeway}") String appName
) {}
```

`@Symbol` serves two purposes based on syntax:

- **`@Symbol("key")`** — for **required** configuration. The key *must* exist;
  if absent the container refuses to start. Use this for mandatory settings
  like server ports, database URLs, or credentials — values without which
  the application cannot function.

- **`@Symbol("${key:default}")`** — for **optional** configuration. The
  expression is expanded from the config cascade; if the key is missing the
  default value after the colon is used. Use this for settings with sensible
  fallbacks like timeouts, feature flags, or cosmetic names. The shell-style
  `:-` separator is also supported: `@Symbol("${port:-8080}")` strips a single
  leading dash from the default and yields `"8080"` (not `"-8080"`), while
  `${port:8080}` keeps the default verbatim and `${port:}`/`${port:-}` both
  yield the empty string.

```java
// Required — startup fails if absent
@Symbol("db.url") String dbUrl;

// Optional — defaults to 30 if app.timeout is not set
@Symbol("${app.timeout:30}") int timeout;

// Expression expansion — any config key can be interpolated
@Symbol("${app.name:freeway}") String appName;
```

Both paths use the container's type coercion mechanism, so values are
automatically converted to the target type (`int`, `boolean`, `Duration`, etc.).

Field injection into a **final** field (or any other non-writable property)
carrying `@Inject` or `@Symbol` fails fast with a clear error —
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
binder.contribute(Route.class).add(Route.get("/hello", ctx -> ctx.sendText(200, "hi")));

// named contributions with ordering
binder.contribute(RuntimeHook.class)
    .add("myHook", hook).after("freeway.http.server");

// conditional ordering: orders against the id only when some module
// contributes it — for ordering against an optional companion
// (a hook that must precede the server, in an app that may have no server)
binder.contribute(RuntimeHook.class)
    .add("db.migration", hook).beforeIfPresent("freeway.http.server");

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

// A consumer that is itself constructed during composition takes the
// deferred form; it reads the sealed store after startup.
public class LazyRouter {
    private final Supplier<List<Route>> routes;
    public LazyRouter(Supplier<List<Route>> routes) {
        this.routes = routes;
    }
    List<Route> routes() { return routes.get(); }
}
```

Contribution injection: **constructor parameters consume contributions
implicitly** — the constructor is the single mandatory injection point, so
resolution failure is loud at startup and there is no silent-miss risk.
Fields require an explicit `@Inject` (they are writable and can be
forgotten). An explicit `@Inject("id")` on a `List`/`Map`/`Supplier`
injection point prefers a bound service with that id (letting you bind your own
`List<Foo>`/`Map<String, Foo>` service and inject it by id); only when no
such binding exists does resolution fall back to the contributed view.

`List<V>`, `Map<String, V>` and their deferred
`Supplier<List<V>>`/`Supplier<Map<String, V>>` forms all consume the
same store. The `List`/`Map` read is **complete-or-throw**: while any
declared contribution of that entry type is still unpublished, the store
refuses the read instead of freezing the prefix that has landed. A
contribution whose constructor runs during composition takes the
`Supplier` form and reads after startup. The config chain
(`SymbolProvider`/`CoerceRule`) is exempt because `SymbolSource` reads
it live.

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
| `addDeferred(Class)` — auto-instantiated | ✅ | ✅ (keyed by auto-generated id) |

This means `List<V>` always gives you every contribution in order.
`Map<String, V>` gives you only the ones you can look up by name.

`Extension<V>` is intentionally not injectable — it is the read-only view of an
aggregation point. Writes go through `Contribution` only while modules bind;
application code consumes `List<V>` or `Map<String, V>`.

**Common built-in entry types** (contribute these; consumers inject the
corresponding `List`/`Map`):

| Entry type | Contributed by | Consumed by |
|---|---|---|
| `Route`, `RouteGroup`, `WebSocketRoute` | application routes | `HttpServer` (route index) |
| `HttpFilter` | filters | HTTP pipeline, ordered by `order()` |
| `StaticResourceMount` | static mounts | HTTP server |
| `HealthCheck` | health checks | `HealthFilter` |
| `ErrorMapper` | exception mappers | HTTP error mapping (first match; built-in last) |
| `RuntimeHook` | startup/shutdown work | `AppRuntime`, ordered by id |
| `EventSubscriber` | module-level subscribers | `EventBus`, ordered |
| `FlowDriver`, `FlowInterceptor` | flow drivers/interceptors | `FlowEngine` |
| `SchemaEntity`, `RowMapping`, `NamedDatabase` | schema groups, row mappings, named databases | DB module |
| `Propagator` | context propagators | cloud invocation and event mesh |
| `RpcExport`, `MeshSubscription`, `CloudHealthContributor` | RPC exports, mesh subscriptions, readiness | cloud modules |
| `SymbolProvider`, `CoerceRule`, `KnownKeys` | config tiers, coercion rules, key vocabulary | config chain |

The entry type itself (e.g., `Route.class`) is the extension point identifier. Contributions are ordered via `add(id, value)` with `before/after`.

Missing ids in `before/after` are ignored with a WARN (the reference may be a typo, or the contributing module may not be installed). Cycles between resolved references still fail fast.

The three `add` variants are deliberately distinct, not an API gap waiting for a unifying default:

- `add(value)` — unnamed, insertion order. No id. Not in `asMap()`. Use when only iteration order matters (routing, filters) and id-based lookup is irrelevant.
- `add(id, value)` — named, with explicit id. Supports `before/after`. Included in `asMap()`. Use when the consumer needs to resolve a specific entry by name (drivers, runtime hooks).
- `addDeferred(Class)` — named, with auto-generated canonical id (`snake_name@package`). Supports `before/after`. Included in `asMap()`. Use when the class itself is the natural identifier.

`Extension.asMap()` returns only named contributions — this is by design, not a limitation. Unnamed entries serve iteration order; named entries serve identity. Forcing auto-generated ids onto unnamed entries would blur this distinction without solving a real problem.

Rules:
- Contributions are accepted only while modules bind: the container seals every extension store at the end of composition, so a held `Ordering` handle (or a direct `container.extension(X).add(...)`) after startup fails with a "sealed" error instead of silently mutating a frozen view.
- `add(value)` preserves insertion order.
- `add(id, value)` enables `before/after` constraints for topological ordering.
- `addDeferred(id, factory)` and `addDeferred(Class)` build the value in the construction phase — after every module has bound, so construction can resolve any module's services; ordering declared on the returned handle reaches the entry when it lands. `addDeferred(Class)` generates a canonical id of `snake_name@package` (e.g. `email_sender@com.example.flow`).
- Construction and publication finish before field injection or `@PostConstruct`. The config layer (`SymbolProvider`/`CoerceRule`) is constructed, published and initialized first so its providers and rules are final before any consumer is built. For the non-config layer, every contribution is constructed and published before any of them is field-injected or post-constructed, so a class contribution's `@PostConstruct` sees the complete set and never runs against a partial store. An eager `List`/`Map` constructor parameter that cannot be complete is rejected at startup; use `Supplier` or an `@Inject` field instead.
- Duplicate ids fail immediately. Generic `all()` ordering treats unknown order targets leniently — they are WARNed and ignored (a missing sibling is harmless for most extension points); strict consumers call `Extension.validateOrdering()`, which fails fast on any unknown reference (runtime-hook ordering in the boot layer does this, so a typo fails startup). Cycles fail at resolution time.
- Constructor parameters are auto-resolved; fields require `@Inject`. (Contribution consumption via `List`/`Map` follows the same rule: constructor parameters implicit, fields explicit — see above.)

### EventBus

In-process publish-subscribe built on the Extension mechanism. Events are plain objects; subscribers are contributed via `EventSubscriber` or registered at runtime.

```java
// Module-level subscribers (startup-time, supports ordering)
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(PostCreatedEvent.class, e -> index(e.post())))
    .add("notify", EventSubscriber.of(PostCreatedEvent.class, e -> sendEmail(e)))
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

**Lifecycle events:** Boot publishes `AppLifecycleEvent` with `AppState.RUNNING` after all hooks start, and with `AppState.STOPPING` before shutdown. Subscribe via EventBus instead of implementing `RuntimeHook` for non-critical work:

```java
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(AppLifecycleEvent.class, e -> { if (e.state() == AppState.RUNNING) cache.warmup(); }))
    .add(EventSubscriber.of(AppLifecycleEvent.class, e -> { if (e.state() == AppState.STOPPING) cache.flush(); }));
```

**Transaction-aware:** Events published inside a `db.transaction()` are automatically deferred and fire only after commit. No manual wiring needed — powered by the `Defer` mechanism (see [Defer](design/defer.md)).

**Planes are separate:** the bus never leaves the process — whether a fact
crosses a boundary is decided at the call site, not by which modules are
loaded. Cross-JVM broadcast is the cloud plane (see
[MeshEventBus](modules/cloud.md)); durable, per-key ordered
streams are the Kafka plane (see [Kafka](#kafka-freeway-mq-kafka)). A fact
that must live on two planes is published on both, deliberately.

### AOP (Advisors)

Advice attaches to a binding and wraps interface method calls:

```java
binder.bind(UserService.class).to(UserServiceImpl.class)
    .advise(advisor -> advisor.wrap(
        inv -> inv.method().getName().startsWith("get"),
        inv -> {
            long start = System.nanoTime();
            try {
                return inv.proceed();
            } finally {
                log.info("{} took {}ns", inv.method(), System.nanoTime() - start);
            }
        }));
```

| Type | Role |
|------|------|
| `Advisor` | `wrap(Predicate<MethodInvocation>, MethodAdvice)`, chained on the binding |
| `MethodInvocation` | `proceed()` continues the chain (or calls the target), plus `target()`, `method()`, `arguments()` |
| `MethodAdvice` | the advice body; return `proceed()`'s value or a replacement |

Each `advise(...)` call adds one advice; earlier entries are outermost. A
selector decides per method, and an advice may short-circuit by returning
without calling `proceed()` — later advice and the target never run:

```java
binder.bind(Greeter.class).to(GreeterImpl.class)
    .advise(a -> a.wrap(inv -> inv.method().getName().equals("greet"), inv -> {
        log.info("before");
        try { return inv.proceed(); } finally { log.info("after"); }
    }))
    .advise(a -> a.wrap(inv -> inv.method().getName().equals("greet"),
        inv -> "short-circuited"));
```

Rules and limits:

- **Interface bindings only.** Advice is applied through a JDK dynamic proxy;
  `bind(Concrete.class).advise(...)` fails at bind time naming the interface
  requirement. Static and private methods are not interface methods and are
  never intercepted; a `final` implementation class is fine, because the proxy
  implements the interface rather than subclassing the target.
- **One target per proxy.** A singleton proxy keeps its one target; a prototype
  proxy creates one target on its first call and keeps it across calls on that
  proxy (a failed construction is retried on the next call).
- **Boundary injection.** A singleton may inject a `Scope.BOUNDARY`-scoped
  interface: the injected object is a proxy that enters the caller's boundary on
  each call, and advice wraps that call. This is the supported way to give a
  singleton a boundary-scoped collaborator.
- **Not a lifecycle or argument hook.** Advice cannot substitute constructor
  arguments, and it cannot intercept `@PostConstruct` / `@PreDestroy` — there is
  no method call to wrap at those points.

### Type Coercion

Commons owns the reusable scalar coercion mechanics (`Coercer` interface, `CoerceRule<S,T>`, `CoercerDefault`). IoC owns container-aware coercion rules and `@Symbol` integration. The two layers are separate: commons does not import ioc types.

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

`freeway-commons` registers a JUL-backed SLF4J 2 provider **unconditionally** via `META-INF/services` — zero-dependency. Because SLF4J 2.x does not prefer any provider on its own, `LogBootstrap.ensureProvider()` runs at startup (`FreewayApp`/`Freeway` static initialization, before any `LoggerFactory.getLogger()` call) and resolves `freeway.log.system` to pin `slf4j.provider` deterministically:

- `freeway` (default) — Freeway's JUL backend wins even when Logback/Log4j is on the classpath; the startup report names the unselected provider and the switch;
- `auto` — external provider first (Logback > Log4j 2 > slf4j-simple), JUL as fallback (the pre-2.0 behaviour);
- `logback` / `log4j2` / `simple` / a provider class name — that backend explicitly; a missing one fails startup naming the dependency.

`freeway.log.system` is read at class-load time (`-D` or `FREEWAY_LOG_SYSTEM`), never from a config file. A user-supplied `-Dslf4j.provider` is always respected and wins over the Freeway key. Every startup prints one `[Freeway] logging: ...` report naming the backend, provider class and selection source.

With a non-JUL backend selected, Freeway pins the SLF4J binding and **leaves `java.util.logging` untouched**: it attaches no JUL handlers and does not forward JUL records. JDK-internal logging (for example the JDK's own HttpClient) therefore stays on JUL's configuration; if you want it in your backend, add the standard `jul-to-slf4j` bridge yourself (the dependency plus the usual `LogManager` reset). Freeway's JUL enhancement (formatters, files, rotation) is configured only for its own JUL backend.

Logging works **out of the box** with sensible defaults: ANSI-colored console output, rotating file logging at `logs/{app.name}.log`. Log keys have two file homes: the dedicated `freeway-logging.properties` on the classpath root (not bundled in the JAR), or the app's main config files — `application.properties` / `application.json` plus their active profile variants (the main cascade's classpath file baseline) contribute their `freeway.log.*` keys; the dedicated file outranks them. System properties (`-D`) and env vars override both file homes — the env prefix follows `freeway.env.prefix` (default `FREEWAY_`), same convention as the config cascade: `freeway.log.level` ↔ `FREEWAY_LOG_LEVEL`, or `APP_FREEWAY_LOG_LEVEL` under a custom prefix. Per-logger levels (`<logger>.level`) stay in `freeway-logging.properties` — inside the app config they are indistinguishable from ordinary dotted keys.

```properties
freeway.log.level=INFO
freeway.log.file=auto                    # logs/{app.name}.log, rotation + GZIP
freeway.log.file.max-size=104857600      # 100 MB before size-based rotation
freeway.log.file.max-history=30          # days kept
freeway.log.file.compress=true           # GZIP rotated archives
freeway.log.file.flush-interval=250      # ms; 0 = flush per record
# (the four values above are the auto defaults — omit to keep, override to change)
```

For multi-file logging, per-logger levels, env var support, and the full config reference, see [Commons Reference](modules/commons.md#logging).

---

## Boot

```java
AppRuntime runtime = FreewayApp.run(new AppModule());
// or with explicit module instances
AppRuntime runtime = FreewayApp.run(new AppModule(), new HttpModule());
```

`FreewayApp.run(Launch, …)` accepts a `Launch` (command-line args, discovery) plus module instances; `FreewayApp.run(modules)` uses `Launch.defaults()`. It loads config, discovers SPI modules, creates the container, starts hooks, logs startup time, and registers a JVM shutdown hook.

Adding the **same module class twice with two different instances** (e.g.
`add(new DbModule(), new DbModule())`, or an explicit module that SPI
auto-discovery also loads) fails fast at startup — silently dropping one
instance's configuration would be worse. The identical instance added twice
is deduplicated harmlessly, and SPI-discovered duplicates of an explicit
module are dropped in favor of the explicit one.

For more control, chain on `FreewayApp` — what `create(...)` returns is the application
before it runs, and `start()` is the only thing that runs it:

```java
AppRuntime app = FreewayApp.create(new MyModule())
    .name("order-service")                   // the root line in the startup log
    .add(new HttpModule(), new DbModule())   // additional modules
    .args("--freeway.profile=dev")           // config overrides
    .classLoader(customLoader)               // custom class loader for SPI/resources
    .autoDiscovery(false)                    // disable SPI module discovery
    .shutdownHook(false)                     // skip JVM shutdown hook
    .config(myConfig)                        // pre-built AppConfig
    .start();
```

| Type | Purpose |
|------|---------|
| `FreewayApp` | Application entry point and launcher: `run(...)` starts immediately; `create(...)` returns the chain (`name`, `add`, `args`, `autoDiscovery`, `shutdownHook`, `classLoader`, `config`) that ends in `start()` |
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

The symbol chain resolves every config lookup (`@Symbol` and
`SymbolSource.resolve` — the single read entry; `AppConfig` only carries
profiles, the sources it contributes and lifecycle). Lowest to highest priority —
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

Two keys configure the cascade itself and so cannot come from it —
`freeway.env.prefix` and `freeway.config.file`. Each has exactly two channels,
the JVM system property `-D<key>` and the fixed `FREEWAY_`-spelled environment
variable; a value in a config file or a CLI argument is ignored and named in a
startup WARN. `freeway.profile` is not one of them — it is read from the base
layers like any other key.

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

CLI keys are taken literally — no prefix is added, dotted or not. An
application key (`--mode=prod`) stays `mode=prod`, and a framework key is
written in full: activate profiles with `--freeway.profile=dev`.

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
`freeway.*` namespace; a key's dots become underscores and every other
character is carried through verbatim, so a key containing `-` needs that
hyphen in its variable name (`freeway.http.ssl.key-store-password` ↔
`FREEWAY_HTTP_SSL_KEY-STORE-PASSWORD`).

That is the entire rule — no folding, no aliases. Folding `-` into `.` would
let one variable feed two distinct keys, and no shell's inability to export a
name containing `-` justifies that: use `-D`, `env`, or a container's `-e`.
A variable whose spelling matches no declared key is simply inert, like any
other unknown key.

A single configurable prefix, `freeway.env.prefix` (default `FREEWAY_`), can
**replace** it entirely: set `-Dfreeway.env.prefix=APP_` and the app owns the
mapping — prefix stripped, `_` → `.`, no namespace inference:

```bash
-Dfreeway.env.prefix=APP_
APP_SERVER_PORT       → server.port
APP_FREEWAY_HTTP_SERVER_PORT → freeway.http.server.port
```

With a custom prefix, `FREEWAY_*` variables are no longer read by the config
cascade (logging's own `FREEWAY_LOG_*` env support is a separate mechanism and
is unaffected).

**Reading values — one entry point:** the symbol chain is the single way to
read configuration; `AppConfig` is not a reader (it owns profiles, the
contributed symbol sources and lifecycle only). Direct lookups resolve through the
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
fast, and the same chain backs `@Symbol` injection.

### Runtime inspection

`Container.describe()` returns `RuntimeDescription` — module metadata, binding
identity/scope/markers and contribution ids/order. `AppRuntime.describe()` adds
state, active profiles and configured key names. The snapshot never realizes a
lazy service and never carries a service instance or a config value:

```java
try (AppRuntime app = FreewayApp.create(MyModule.class).start()) {
    AppRuntimeDescription info = app.describe();
    List<RuntimeDescription.BindingDescription> bindings =
        info.container().bindings();
}
```

The startup log remains the human-readable rendering; the description is the
stable machine-readable surface for tools and coding agents.

---

## HTTP

Three-layer architecture: **engine layer** handles transport (socket I/O, protocol parsing), **orchestration layer** (`HttpServer`) wires filters and routing, **integration layer** (`HttpModule`) bridges to IoC.

| Category | Main Types |
|----------|------------|
| Core | `HttpEngine`, `HttpExchange`, `HttpExchangeSupport`, `HttpTransport`, `ResponseHead`, `HttpFilter`, `HttpModule`, `HttpServer`, `JsonCodec` |
| Engine (shared) | `FreewayHttpEngine`, `HttpTransportImpl`, `HttpExchangeSupport`, `HttpSession`, `HttpServerHandleImpl`, `SessionBufferedInputStream/OutputStream`, `FixedLengthInputStream`, `ChunkedInputStream` |
| Engine (HTTP/1.x) | `HttpConnection`, `Http1xSession`, `Http1xParser` in `engine/` |
| Engine (HTTP/2) | `Http2Connection`, `Http2Stream`, `FrameSerializer`, `HPackContext` etc in `engine/http2/` |
| Engine (WebSocket) | `WebSocketFrame`, `WebSocketConnection`, `WebSocketUtils` etc in `engine/ws/` |
| Routing | `Route`, `RouteGroup`, `RouteIndex`, `PathPattern` |
| Body | `BodyHandler`, `MultipartForm` |
| WebSocket | `WebSocketSession`, `WebSocketSessionSupport`, `WebSocketTransport`, `WebSocketListener`, `WebSocketRoute`, `WebSocketGroup`, `WebSocketIndex` |
| SSE | `SseEmitter`, `SseEvent` |
| Built-ins | `JsonCodecDefault`, `CorsFilter`, `HealthFilter`, `HealthCheck`, `StaticResourceMount`, `ErrorMapper` |

Engine internals (`engine/` and sub-packages) are implementation details — `FreewayHttpEngine` is the only public class. `JdkHttpEngine`/`JdkHttpContext` have been removed; the built-in engine is the sole default.

At runtime, `HttpServer.routeDescriptions()` and `HttpServer.webSocketPaths()` expose the
registered method/path identities for diagnostics and tools, without returning handlers.

### Routes

```java
// Module contribution
binder.contribute(Route.class)
    .add(Route.get("/", ctx -> ctx.sendText(200, "Hello")))
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

A group is a declaration convenience, not a nested router: its prefix is
normalized and joined onto each route, and `expand()` hands the resulting flat
routes to the same index as directly declared ones. Adding a direct route and a
group route with the same method and path shape therefore fails startup as a
duplicate — a group does not create a second routing layer.

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

        public void handle(HttpExchange ctx) throws Exception {
            int page = Integer.parseInt(ctx.queryParam("page").orElse("1"));
            ctx.sendJson(200, service.list(page));
        }
    }

    public static final class Get implements RouteHandler {
        private final UserService service;

        public Get(UserService service) {
            this.service = service;
        }

        public void handle(HttpExchange ctx) throws Exception {
            var user = service.findById(ctx.pathVar("id").orElseThrow());
            if (user.isEmpty()) {
                ctx.sendText(404, "Not found");
                return;
            }
            ctx.sendJson(200, user.orElseThrow());
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

Registration is strict: two routes with the same method and path fail startup
with `Duplicate route detected`, whether the collision comes from two explicit
routes, from a group and an explicit route, or from inside a group — nothing
overrides silently. WebSocket routes follow the same rule, because both
families share one `RouteIndex`: matching, path variables and specificity are
identical, and so is the duplicate check.

**Matching precedence** is a contract, not insertion order. When several routes
recognize a request, the first applicable rule wins:

| Order | Rule | Example |
|---|---|---|
| 1 | Literal segment | `/users/me` beats `/users/{id}` |
| 2 | Regex-constrained parameter | `{id:\d+}` beats `{name}` for `/items/42` |
| 3 | Plain path parameter | `{name}` serves values the regex rejects |
| 4 | Terminal wildcard | `{path:.*}` only when no more specific route matches |
| — | Method | The exact method wins; `HEAD` falls back to `GET`; `methods()` drives `Allow` for 405 |

Path variables are decoded per segment: `%2F` stays inside its original segment
and never creates a new path level. A terminal wildcard cannot share a node
with a literal child — registration refuses the ambiguous shape instead of
letting one silently shadow the other.

**Choosing lambda vs handler class:**

| Style | When to use |
|---|---|
| Lambda `ctx -> { ... }` | Stateless handler — no injected dependencies needed |
| Handler class `MyHandler.class` | Stateful handler — depends on services, needs constructor injection |

Handler classes also support `POST`/`PUT`/`PATCH` with request bodies.
Call `ctx.bodyAsJson(BodyType.class)` inside the handler to deserialize
the request body — the same deserialization as the `BodyHandler<T>`
convenience, with full access to constructor-injected services. One
difference to keep in mind: `BodyHandler` also runs `BeanValidator` on the
deserialized body and throws `ValidationException` on failure; a handler
that calls `bodyAsJson` itself must validate explicitly.

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
server and per request via `ctx.setMaxBodySize(...)`) is enforced on the body
stream itself, not just on buffered reads: a counting wrapper sits outside the
framing decision (chunked, fixed-length, and unknown-length), so every byte
delivered to any consumer — buffered `body()` reads or streaming reads during
parsing — is counted against the live limit, and an over-limit read throws
`BodyTooLargeException` → 413.

On a keep-alive connection the context is reused between requests, but all
per-request exchange state is reset: the security principal, request
attributes, and the correlation id are cleared and a fresh id is rolled for
request N+1 (an incoming `X-Request-Id` header is then applied on top).
`maxBodySize` is reset too, back to the server's configured value — so a filter
that raises the limit for an upload route governs that request only, and the
next request on the same connection is bounded by the configured limit again.

### Response

```java
ctx.setStatus(201);
ctx.setHeader("X-Custom", "value");
ctx.sendText(200, "plain text");
ctx.sendJson(200, object);
ctx.body("text".getBytes());
ctx.text("text");  // UTF-8 convenience
```

| Call | Result |
|------|--------|
| `setStatus(int)` / `status()` | status code |
| `setHeader(name, value)` | replace every existing value for the header |
| `addHeader(name, value)` | append a value, preserving repeated fields such as `Set-Cookie` |
| `addVary(token)` | merge a token into `Vary` without duplicating it |
| `body(byte[])` | binary body |
| `body(InputStream, long)` | stream body; `contentLength` is `-1` when unknown |
| `text(String)` / `json(Object)` | semantic body with the default `Content-Type` |
| `file(Path, offset, length)` / `file(FileChannel, offset, length)` | file range; the channel overload takes ownership and closes it |
| `sendText(int, String)` / `sendJson(int, Object)` | status + body shorthand |
| `sse()` | open an `SseEmitter` (set headers first) |
| `isCommitted()` / `allowsBody()` | whether the response is committed / may carry a body |

Response header values must be **ISO-8859-1 encodable** (the charset the
HTTP/1.1 writers serialize header values with) and must not contain CR/LF:
setting a header with a non-Latin-1 character (above U+00FF) or a line break
throws `IllegalArgumentException` instead of silently writing a corrupted or
injected header on the wire. Header names must be RFC 7230 tokens.

### Filters

```java
// Implement HttpFilter
public class AuthFilter implements HttpFilter {
    public void doFilter(HttpExchange ctx, RouteHandler next) throws Exception {
        if (ctx.header("Authorization") == null) {
            ctx.sendText(401, "Unauthorized");
            return;
        }
        next.handle(ctx);
    }
}

// Register in module
binder.contribute(HttpFilter.class).add(new AuthFilter());
```

Built-in filters: `CorsFilter` (configurable CORS via `freeway.http.cors.*` keys) and `HealthFilter` (health endpoint, see below). Request timing is measured by `HttpServer` and published as `HttpExchangeEvent`; the optional text access log (`freeway.http.access-log.enabled`) is an observer of that event, not a filter, so it also covers preflight and health probes.

#### Request-scoped services (`ScopeFilter`)

`ScopeFilter` is the opt-in HTTP boundary for `Scope.BOUNDARY` services and
`ScopedCache` values. Enable it with
`freeway.http.request-scope.enabled=true`, or contribute it explicitly:

```java
binder.contribute(HttpFilter.class).addDeferred(ScopeFilter.class);
```

```properties
# one container value scope per request
freeway.http.request-scope.enabled=true
```

It runs at order **-40** — after the built-in CORS (`-100`) and health (`-50`)
filters, before application filters (`0`). Each request therefore gets one
scope; values and `Scope.BOUNDARY` services are reused inside it and released when
the request finishes, on both the normal and exception paths.

**Use it when the request needs shared, request-lived state:**

| Scenario | Put in the scope |
|---|---|
| Tenant / authentication / principal context | tenant id, schema, user, roles, locale |
| Request-level permission or feature caches | permission set, tenant config, feature flags |
| Audit / business operation context | actor, operation, resource id, idempotency key |
| Request-level temporary resources | temp files/directories, parsers, request-scoped collectors |
| Batch/aggregation APIs | memoized lookups shared by several services in one request |
| Security policy context | data scope, authorization context, rate-limit context |

Typical composition:

```text
ScopeFilter (-40)
  ├── auth/tenant filter (-30)      fills RequestContext (Scope.BOUNDARY)
  ├── authorization filter (-20)    reads RequestContext + ScopedCache
  ├── route handler
  │     ├── singleton service through an interface proxy
  │     └── db.transaction(...)     // Defer owns commit/rollback
  └── request end: cleanup, @PreDestroy
```

`ScopeFilter` is not a transaction boundary: keep commit/rollback and deferred
events in `Defer`. It also does not propagate to child threads; use
`ContextExecutor` for async work, and do not hand request-scoped service
instances to work that outlives the request.

**Do not use it for:**

| Avoid | Why |
|---|---|
| Global caches or HTTP session state | the scope ends with the request |
| DB connections or transactions | use the pool and `Defer`; holding a connection per request can exhaust it |
| Stateless/high-QPS `/ping`, static or health routes | pure per-request allocation with no scoped state |
| SSE / WebSocket connections | use a per-message/callback boundary, not the whole connection |
| Background jobs | the scope is closed when the response completes; pass immutable data |
| Expensive resources | construction/`@PreDestroy` per request can cost more than it saves |

Two implementation notes:

- A class-based route handler is built at composition time, so it cannot take a
  `Scope.BOUNDARY` concrete type in its constructor; inject an **interface proxy**
  into a singleton and call it during the request.
- `ScopeFilter` is global to the server. For only a subset of routes, contribute
  a custom filter that decides whether to call `ScopeBoundary.withinChecked(...)`.

The filter is intentionally off by default: the per-request `ScopeSession`
(including its small maps) and any scoped service construction are costs paid by
every enabled request, so enable it when at least two consumers share
request-scoped context — not for a completely stateless API.

### Health Check

The health endpoint is powered by `HealthFilter` and `HealthCheck`. By default it responds `{"status":"ok"}` at `GET /healthz`. Customize the path via config or replace the check logic:

```java
// Custom health check — bind your own implementation
binder.bind(HealthCheck.class).to(MyDbHealthCheck.class);

public class MyDbHealthCheck implements HealthCheck {
    private final Database db;
    public MyDbHealthCheck(Database db) { this.db = db; }
    public HealthResult check() {
        return db.ping() ? HealthResult.ok() : HealthResult.unhealthy("database down");
    }
}
```

A healthy result answers 200 with `{"status":"ok"}`; an unhealthy result answers 503 with
`{"status":"unhealthy","detail":...}`. The same `HealthResult` is consumed by cloud readiness
contributors, so HTTP and cloud probes cannot disagree on the result shape.

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
    .withCacheMaxAge(Duration.ofHours(1))
    .withImmutable(true)            // sets Cache-Control: immutable
    .withFallthrough(true);         // pass to next handler on 404

// History-mode SPA (vue-router createWebHistory, React Router, ...):
// extensionless GET/HEAD paths that neither the mount nor the route chain
// serves fall back to <mount>/index.html.
StaticResourceMount.classpath("/", "/dist")
    .withSpaFallback(true);
```

`withSpaFallback(true)` implies fallthrough and adds the terminal hop nginx
spells as `try_files $uri $uri/ /index.html`. Precedence is: a real asset, the
route chain, a 405 for a path known under another method, then the SPA shell; a
missing `.js` never receives HTML. The fallback is owned by the most specific
matching mount (longest prefix, ties by contribution order), and a mount that
opts in fails server creation if its `index.html` is missing. `withFallthrough`
alone only hands the miss to the route chain — it does not serve the shell.

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
binder.contribute(ErrorMapper.class).add(ex -> {
    if (ex instanceof NotFoundException) {
        return Optional.of(ErrorResponse.of(404, "not_found", ex.getMessage()));
    }
    return Optional.empty();  // not handled, continues to next mapper
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
FreewayApp.run(new AppModule(), new HttpModule());

// Undertow — just add the module, container picks it via .primary()
FreewayApp.run(new AppModule(), new HttpModule(), new UndertowModule());
```

**How it works:**

1. `HttpModule` binds `HttpEngine` → `FreewayHttpEngine` **without** `.primary()`
2. `UndertowModule` binds `HttpEngine` → `UndertowEngine` **with** `.primary()`
3. With only `HttpModule`, `FreewayHttpEngine` is the sole binding and used automatically
4. With both modules, the container resolves `.primary()` → `UndertowEngine`
5. `HttpEngine` is the seam: under boot a test or an adapter binds its own engine
   with `.primary()`; with no container at all it passes one to
   `HttpServer.create(engine, config, pipeline)` — the single derivation
   `HttpModule` also calls, so there is no second assembly path to bypass

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

**HTTP/2 rapid-reset guard:** cancels arriving before the server committed a
response, past `freeway.http.h2.reset-burst-limit` (default 200, `0` disables)
inside `freeway.http.h2.reset-window` (default 10s), trip the connection with
`GOAWAY(ENHANCE_YOUR_CALM)` — the peer retries on a fresh connection.
Post-response cancels never count. `GOAWAY` reports the last *handled* stream,
so refused streams keep their explicit retry signal.

**TLS hot reload:** file watching triggers reloads promptly (debounced), with
the poll at `freeway.http.ssl.reload-interval` as fallback; either layer alone
drives the same snapshot comparison (`0` disables hot reload entirely).

### Testing with HTTP

When using `Container` directly (not `FreewayApp`), start the server explicitly:

```java
HttpServer server = container.get(HttpServer.class);
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
Database db = Database.create(config);
Orm orm = Orm.of(db);
```

### IoC Usage

```java
FreewayApp.run(new AppModule(), new DbModule());
// Database and Orm are now injectable
```

### Queries

```java
// Positional params
List<User> users = db.query("SELECT * FROM users WHERE active = ?", true)
    .list(User.class);

// Named params (:$name or :name)
User u = db.query("SELECT * FROM users WHERE id = :id")
    .bind("id", 42)
    .one(User.class)
    .orElseThrow();

// Stream (requires try-with-resources)
try (var stream = db.query("SELECT * FROM big_table").stream(Row.class)) {
    stream.forEach(row -> process(row));
}

// Execute (INSERT/UPDATE/DELETE)
ExecuteResult r = db.execute("INSERT INTO users (name) VALUES (?)", "Alice");
r.rows();   // affected rows
r.generatedKey();    // generated key (may be null)
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
r.asString("name");
r.asBigDecimal("amount");
r.asLocalDateTime("created_at");

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

The conflict clause takes **column names**, not raw assignments, so the dialect renders its own spelling:

```java
// PostgreSQL: ... ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name
// MySQL:      ... ON DUPLICATE KEY UPDATE name = VALUES(name)
Sql upsert = Sql.insert("users")
    .value("id", id).value("name", name)
    .onConflict("id").doUpdateSet("name");
db.execute(upsert);
```

`doUpdateSet` updates each named column to the row's excluded value; an expression that is not that
("take the incoming value") belongs in raw SQL. `doNothing()` emits `ON CONFLICT … DO NOTHING`,
which only dialects with `ON CONFLICT` accept — the dialect validation says so at render time.

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

Nested transactions are detected and rejected. Auto-commit is restored on exit. Queries inside the transaction automatically use the same connection. The transaction is **thread-bound** (backed by `ScopedValue`, which does not propagate to child threads): DB calls made on a different thread while a transaction is active, or consuming a `Query`/`BatchQuery` created inside a transaction on another thread (or after the transaction ends), throw a `SqlException` — the work would otherwise silently borrow an independent pooled connection and run outside the transaction, breaking atomicity. Cross-`Database` work (e.g. via `DatabaseRegistry`) commits independently and is not rolled back with the transaction.

**Transaction-aware side effects:** EventBus events published inside a transaction are automatically deferred and only fire after commit — no manual wiring needed. This is powered by the `Defer` mechanism (see [Defer](design/defer.md)):

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

`Pool` is the connection pool abstraction — `PoolDefault` (built-in) and `HikariPool` (`freeway-db-hikari`) both implement it. `borrow()` takes a connection out, `release(conn)` returns a healthy one for reuse, and `invalidate(conn)` destroys one that must not reach another borrower. The framework invalidates whenever it cannot restore a connection's state after a transaction or batch — closing `conn.connection()` is not a substitute, since a pool that hands out a proxy recycles the connection on close instead of destroying it. A pool adapter implements `invalidate` as a physical destroy.

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

`Database.create` accepts an optional pool override through `Wiring…withPool(...)`:

```java
// Default — create() builds a PoolDefault from PoolConfig
Database db = Database.create(config);

// Explicit pool — pass a pre-built instance
Pool pool = new PoolDefault(config);
Database db = Database.create(Database.Wiring.defaults(config).withPool(pool));
```

**IoC pool selection:** `DbModule` binds `PoolDefault` (id `"builtin"`, no `.primary()`). Extension modules like `HikariPoolModule` bind their pool with `.primary()`. The container automatically selects the primary pool when multiple bindings exist — same pattern as HTTP engine selection:

```java
// Default — PoolDefault
FreewayApp.run(new AppModule(), new DbModule());

// HikariCP — add the module, container selects it via .primary()
FreewayApp.run(new AppModule(), new DbModule(), new HikariPoolModule());
```

No config keys needed — just add or remove the extension module.

A borrowed connection must be released to the pool that borrowed it: a foreign
release fails fast with `SqlException` instead of closing another pool's
physical connection out from under it.

### HikariCP (`freeway-db-hikari`)

Third-party connection pool adapter for [HikariCP](https://github.com/brettwooldridge/HikariCP). Add the `freeway-db-hikari` dependency to your classpath.

**Standalone usage:**

```java
PoolConfig config = PoolConfig.defaults("jdbc:postgresql://localhost/db", "user", "pass");
HikariPool pool = new HikariPool(config);
Database db = Database.create(Database.Wiring.defaults(config).withPool(pool));
```

**IoC usage:** add `HikariPoolModule` to the launcher — it binds `HikariPool` as `id("hikari").primary()`, overriding the built-in `PoolDefault`. No config keys needed.

`HikariPool` maps `PoolConfig` fields to Hikari's configuration (pool size, timeouts, health check query) and adapts Hikari's `HikariPoolMXBean` stats to `PoolStats`.

### Batch

```java
List<ExecuteResult> results = db.batch("INSERT INTO t (a, b) VALUES (?, ?)")
    .rowsRaw(new Object[]{1, "a"}, new Object[]{2, "b"})
    .execute();
```

    ### Dialect

Database dialect controls DDL generation — identifier quoting, auto-increment clauses, and schema-introspection queries. Dialect selection is **by id**, because more than one dialect can be installed at once and a type-only lookup cannot say which database a connection belongs to.

**Resolution chain:**

```
freeway.db.dialect=company
    │
    └─ configured? ──yes──→ user binding Dialect@company
    │                            │
    │                            ├─ found? ──yes──→ use it
    │                            └─ not found ──→ Dialect.ofId("company")
    │                                              ├─ built-in? → use it
    │                                              └─ unknown  → fail fast
    │
    └─ not configured ──→ detect id from freeway.db.url
                              │
                              ├─ :postgresql:  → "postgresql"
                              ├─ :mysql:       → "mysql"
                              ├─ :mariadb:     → "mysql"
                              ├─ :h2:          → "h2" (MODE parsed into H2Dialect;
                              │                  never remapped to Postgres/MySQL)
                              ├─ :sqlite:      → "sqlite"
                              └─ unknown scheme → fail fast: "No SQL dialect for
                                    JDBC URL ..." — an unrecognized URL
                                    (e.g. jdbc:oracle:...) never silently
                                    falls back to PostgreSQL

                              then a user binding with the detected id wins;
                              otherwise the built-in instance is used
```

An explicit `freeway.db.dialect` always wins, and an unknown explicit id fails
fast. Auto-detection from the JDBC URL is shared between standalone
`Database.create` and `DbModule`; a `null`/blank URL (no database configured
yet) is the only case that defaults to `PostgresDialect`. `DbModule` also binds
the selected dialect as a `Dialect` service for direct injection.

**Built-in dialects:**

| id | Class | Target |
|----|-------|--------|
| `postgresql` / `postgres` | `PostgresDialect` | PostgreSQL — default |
| `mysql` / `mariadb` | `MySqlDialect` | MySQL, MariaDB |
| `h2` | `H2Dialect` | H2 native and every H2 `MODE` (mode-aware) |
| `sqlite` | `SqliteDialect` | SQLite |

The catalog is `Dialect.ofId(String)`. `DbModule` does not bind four dialect
instances: it binds one **selected** `Dialect` service and resolves a user
binding by requested id before consulting the catalog. Custom dialects can be
contributed by users or third-party modules under their own id.

**Dialect capabilities:** the `Dialect` interface declares capability flags
the framework consults at runtime. Notably `supportsTransactionalDdl()`
(PostgreSQL, SQLite: true; H2 and MySQL/MariaDB: false — their DDL commits
independently) governs whether DDL can run inside a transaction, and
`backslashEscapesStrings()` (MySQL/MariaDB: true) tells the SQL scanner that a
backslash escapes the next character inside ordinary single-quoted literals.
Other flags cover `CREATE INDEX IF NOT EXISTS`, `RETURNING`, `ON CONFLICT`,
`ALTER ... ADD COLUMN NOT NULL`, and lexer features (dollar quoting, bracket
quoting, `#` comments, `E'...'` literals).

**H2 modes:** H2's `MODE` is parsed from the JDBC URL and kept inside
`H2Dialect`; it is not a dialect switch. Recognized values are `REGULAR`,
`STRICT`, `LEGACY`, `PostgreSQL`, `MySQL`, `MariaDB`, `Oracle`,
`MSSQLServer`, `DB2`, `Derby` and `HSQLDB`. H2 keeps its own
`INFORMATION_SCHEMA`, DDL auto-commit semantics and types in every mode.
H2 does not support `RETURNING`, does not support parameterized `ON CONFLICT`
(so `supportsOnConflict()` is false), and uses `MERGE INTO ... KEY (...)` for
`Orm.save()`. Its pagination is standard `OFFSET/FETCH`, working in native,
PostgreSQL, MySQL and the other H2 modes. An explicit `freeway.db.dialect=h2`
selects native H2; URL auto-detection preserves the URL mode.

**Custom dialect — write once, select via config:**

```java
// 1. Implement Dialect
public class MySqlDialect implements Dialect {
    @Override public String quoteName(String name) { return "`" + name + "`"; }
    @Override public String generatedClause() { return "AUTO_INCREMENT"; }
    // ... remaining methods
}

// 2. Bind in your module (or a dedicated MySqlDialectModule)
binder.bind(Dialect.class).to(MySqlDialect.class).id("company");

// 3. Select via config
// freeway.db.dialect=company
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

`Schema.ensure()` reads `@Table` / `@Column` / `@Id` / `@Generated` / `@Index` annotations and generates the corresponding DDL. It never drops or modifies existing columns. It is **not transactional** — each DDL statement runs on its own connection. On databases without transactional DDL (H2, MySQL/MariaDB, see `Dialect.supportsTransactionalDdl()`), calling `ensure()` inside a user transaction is rejected with a `SqlException` (the DDL would implicitly commit and silently commit the transaction's pending work); on transactional-DDL databases (PostgreSQL, SQLite) wrapping `ensure()` in a transaction is safe and rolls the whole schema back on failure.

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
        // Register entities for auto-DDL on startup
        b.contribute(SchemaEntity.class)
            .add(SchemaEntity.of("app", User.class, Post.class, Comment.class));

        // Another schema group
        b.contribute(SchemaEntity.class)
            .add(SchemaEntity.of("profile", UserProfile.class));
    }
}
```

`DbModule` contributes a `RuntimeHook` (`"freeway.db.migration"`, before `"freeway.http.server"` when a server exists). On startup it calls `Schema.ensure()` for contributed entities in `auto` mode, then runs SQL migrations, then (in `validate` mode) compares the entities against the migrated database and fails startup on drift. The ordering is automatic — tables exist before migration SQL attempts to insert or alter them, and validation always runs after the migrations.

Config:

| Key | Default | Purpose |
|-----|---------|---------|
| `freeway.db.schema.mode` | `auto` | `auto` = annotation-driven DDL at startup; `validate` = compare after migrations, fail on drift; `off` = skip |
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
MigrationRunner runner = new MigrationRunner(database, MigrationRunner.Options.defaults());
int applied = runner.run();  // returns count of newly-applied files
```

**IoC usage** — `DbModule` binds `MigrationRunner` and runs it automatically on startup:

```java
MigrationRunner runner = container.get(MigrationRunner.class);
runner.run();  // idempotent — already-applied files are skipped
```

Each migration runs in its own database transaction. The tracking table is `_migrations` (configurable). Applied migrations are **immutable** — modifying a SQL file that has already been applied causes a checksum mismatch error at startup. Checksum validation is **dual-track for line-ending compatibility**: the stored SHA-256 of the raw file bytes is compared first, and if that mismatches, the file is compared a second time with CRLF line endings normalized to LF — a pure line-ending change (e.g. a Windows checkout of a file recorded from LF) is accepted, while any real content change still fails. The stored row is never rewritten.

On databases without transactional DDL (H2 and MySQL/MariaDB — DDL
statements commit there), a migration that **contains DDL is rejected** with a
`SqlException` before execution: the DDL would commit but the checksum row
would be lost, and the next startup would re-run the DDL and fail. Split such
DDL into separate migrations and make statements idempotent (`IF NOT EXISTS`),
or use a transactional-DDL database. Migrations are also locked against
concurrent runners (a `__LOCK__` row in the tracking table): a lock held past
`freeway.db.migration.lock-ttl` is taken over by conditional delete (zero rows
affected backs off instead of deleting a fresh lock), and every holder's
release removes only its own owner-token row — a slow owner can never delete
its successor's fresh lock. Already-applied migration files that disappear
from the classpath fail fast as a packaging error.

Config:

| Key | Default | Purpose |
|-----|---------|---------|
| `freeway.db.migration.enabled` | `true` | Set to `false` to skip SQL migrations |
| `freeway.db.migration.path` | `db/migration/` | Classpath resource directory for `.sql` files |
| `freeway.db.migration.table` | `_migrations` | Tracking table name |
| `freeway.db.migration.lock-ttl` | runner default (1h) | Stale-lock takeover budget; `0`/negative disables |

#### Dev vs Production Workflow

Schema and Migration serve different roles across environments:

```
Development                           Production
──────────                            ──────────
freeway.db.schema.mode=auto           freeway.db.schema.mode=validate
                                      freeway.db.migration.enabled=true

① Add @Column("phone") to User
   → restart → column auto-added     ② Write V004__add_phone.sql
                                         from Schema.define() output
                                     ③ Deploy → Migration applies V004,
                                        then validate compares entities
                                        (drift fails startup)
```

**Schema** shines in development — zero-friction iteration. Add a field, restart, the column appears. **Migration** shines in production — every schema change is versioned, auditable, reviewed, and can include data transformations and index tuning that annotations can't express.

The same entity classes and SQL files work in both environments. Switching modes is just one config key.

### DatabaseRegistry

```java
binder.contribute(NamedDatabase.class)
    .add(new NamedDatabase("audit", auditDb))
    .add(new NamedDatabase("primary", mainDb));

DatabaseRegistry registry = container.get(DatabaseRegistry.class);
Database primary = registry.primary();
Database audit = registry.find("audit").orElseThrow();
```

---

## Kafka (`freeway-mq-kafka`)

The durable event stream plane: explicit Kafka records with per-key ordering,
consumer groups, a poison policy and an optional dead-letter topic. Add
`KafkaModule` and enter the plane by name:

```java
FreewayApp.run(new AppModule(), new KafkaModule());
```

Config in `application.properties`:

```properties
freeway.kafka.bootstrap-servers=localhost:9092
freeway.kafka.group-id=my-app
freeway.kafka.topics=post.created,order.placed   # the subscriber's poll set
```

**Key types:**

| Type | Purpose |
|------|---------|
| `KafkaEvents` | The plane: `send(topic, payload[, key])`, `subscribe(prefix, type, handler)`, `stats()` |
| `KafkaConfig` | Bootstrap servers, group-id, poll set, poison/DLQ knobs |
| `KafkaModule` | Binds the plane + the `freeway.kafka.lifecycle` hook |

**Sending** writes to the topic you name — routing topic = wire topic (the old
bridge-topic override is gone):

```java
@Inject KafkaEvents events;

events.send("post.created", new PostCreatedEvent(1L, "Hello"));
events.send("post.created", new PostCreatedEvent(2L, "Ordered"), "post-2"); // partition key
```

**Receiving** delivers into the type each subscription declared — the
subscription table is the inbound allowlist: a record matching no subscription
is acknowledged unread (no class materializes that nobody asked for). A
throwing handler is isolated and counted; undecodable records follow the poison
policy (retry, then skip or DLQ). `X-Event-*` legacy headers are still read, so
records written by older builds consume fine.

This plane is separate from the local bus and from the cloud fabric: a `send`
is a Kafka record — not a local fact, not a mesh frame. Want both? Publish
twice, deliberately.

---

## Cloud (`freeway-cloud`)

**Placing the bundle in the tree is the integration.** `CloudModule` declares the
eight standard modules with `@SubModule` (context propagation, secrets,
discovery, remote invocation, observability, resilience, health, object
storage); each of them is an ordinary module, so a subset is placed directly —
and the event fabric is added explicitly (`EventMeshModule`) because it opens
a network listener of its own. Applications touch the cloud in three tiers:
**ambient** (install the module — self-registration, `/health/live`,
`/health/ready`, `/metrics`, metrics/tracing/resilience/propagation on every
outbound call), **declarative** (`RpcExport`, `MeshSubscription`,
`CloudHealthContributor` — what this service offers), and **consumption**
(`@Inject ServiceDiscovery` / `RemoteCaller` / `MeshEventBus` / `Metrics` /
`SecretStore` / `ObjectStorage` — what it uses).

**Service registration & discovery ships with three backends behind one seam:**
the in-process store (the default — a single node needs nothing), the
freeway-native fleet (`FleetRegistryModule` pointing at deployable registry
nodes — each an ordinary freeway application running `RegistryServerModule`;
writes fan out to every node, reads union the answers), and third-party ext
adapters (Consul). Switching is swapping the module and its keys; application
code never changes. The event mesh feeds itself from whichever backend is bound
(`freeway.cloud.event.discovery-feed-ms`).

**The full cloud development guide — backend deployment and semantics, remote
invocation with its error/idempotency model, the event mesh, health and
readiness, observability, secrets, storage, key-read timing and shutdown
ordering — lives in [cloud.md](modules/cloud.md).** Design rationale:
[cloud design](design/cloud.md). Every key:
[configuration.md](configuration.md).

---

## Flow (`freeway-flow`)

Lightweight graph-based workflow engine.

Parallel branches inherit the submitting thread's ambient work context through
the single `ContextCarrier` binding (the cloud context module supplies it):
trace, principal, baggage and MDC reach every branch worker, exactly as they
reach an `EventBus.publishAsync` task. A standalone engine (`FlowEngine.create()`
without a carrier) runs branches bare; the branch scope itself is still carried
by the flow engine's own `ScopedValue` frame. Graphs are defined by the canonical `GraphSpec` (`nodes`+`links` structure with explicit `entry`). Zero external dependencies beyond commons + ioc. The legacy solon-flow `layout` format was removed — only the canonical shape loads.

**7 node types:**

| Node | Purpose |
|------|---------|
| `START` | Graph entry point; advances along matched links |
| `END` | Marks the graph completed; a run that never reaches it fails |
| `ACTIVITY` | Writes its `data`, then executes a task, then and-fans out |
| `EXCLUSIVE` | Xor gateway — first matching `when`, else default link, else a dead end |
| `INCLUSIVE` | Inclusive gateway — joins on the **active** incoming flows (conditional branches that were not taken are dead paths and do not count), then runs, then fans |
| `PARALLEL` | Parallel fork/join, with declared write isolation (see *Branch isolation*) |
| `LOOP` | Sequential `$in` iteration over its body (no link back-edges) |

**Graph definition** (`version=3`; full field reference in `freeway-flow/docs/graph-v3.md`):

```java
// Programmatic
GraphSpec bp = GraphSpec.create("orderFlow", spec -> {
    spec.entry("start");
    spec.addStart("start").linkTo("approve");
    spec.addActivity("approve").task("@orderService").linkTo("end");
    spec.addEnd("end");
});
Graph graph = bp.create();

// JSON — canonical nodes+links format
Graph graph = Graph.fromText("""
{
    "id": "orderFlow", "version": 3, "entry": "start",
    "nodes": [
        {"id": "start", "type": "start"},
        {"id": "approve", "type": "activity", "task": "@orderService"},
        {"id": "end", "type": "end"}
    ],
    "links": [
        {"from": "start", "to": "approve"},
        {"from": "approve", "to": "end"}
    ]
}
""");
```

`Graph.fromText()` accepts **only** the canonical v3 format — a document must
carry `"version": 3` plus `nodes` and `links`; anything else (v1 `layout`, v2,
or a missing/other version) is rejected with an `IllegalArgumentException`.
`GraphSpec.create()` is the boot-time gate and fails on anything the run would
otherwise discover late: unknown node type, link references to undeclared
nodes, cycles (LOOP is driven by `$in`, never back-edges), duplicate
unconditional links, a missing/ambiguous entry, a `when` expression that does
not compile, a task descriptor outside the vocabulary (with a hint for the
removed v2 `$meta`/`!marker` forms), a misplaced or unknown `join`, or a blank
`data` key.

**Task vocabulary** — closed; a string the builder does not recognize is a
build error, never a runtime guess:

| Syntax | Resolves to |
|--------|-------------|
| `@name` | `TaskHandler` (or `ConditionHandler`, in a `when`) bound in the container with id `name` |
| `#graphId` | Another loaded graph, run as a sub-graph sharing the evaluation's join state; a child that never reaches its END fails at the calling node |
| (inline) | A `TaskHandler`/`ConditionHandler` supplied programmatically |
| `data` field | Static values written into the context before the task runs — the replacement for v2's `$meta` task |

Markers (`!marker`) are gone: contribute the handler with an id and reference
it as `@name`, which the container resolves like any other binding.

**Execution:**

```java
FlowEngine engine = container.get(FlowEngine.class);
engine.load(graph);
engine.run("orderFlow", FlowContext.of());
```

The walk is iterative: a chain of any length costs constant JVM stack, and
sub-graph/loop/parallel nesting is bounded only by how the graphs are written.
A top-level `eval` reuses a `FlowContext` safely: it resets the stop flag and
starts from a clean event bus. `ctx.stop()` ends the run early and is an
intentional, legal completion; `ctx.isStopped()` observes it.

**Gateway dead ends fail loudly:** a run that finishes without reaching the
END node throws a `FlowException` — e.g. an `EXCLUSIVE` node whose conditions
all evaluated false and that has no default link, or a join gateway that never
received all its incoming branches (including a `PARALLEL` fork where a branch
died). The exception names the dead-end node and its graph. Explicitly
stopped runs and interceptor vetoes (an `interceptFlow` that does not proceed)
are exempt.

**Condition expressions** (`when` on nodes/links) are compiled at build by
`ExprEvaluator`: `&&`/`||` (and `and`/`or`) **short-circuit** — the right
operand is only evaluated when it can affect the result — and unary `-` (plus
`+`, `!`/`not`) is supported with type-preserving negation. Mixed
number/string comparisons are numeric when the string parses (`"10" > 9` is
true; `"10" == 10` is true), otherwise lexicographic; `"true"`/`"1"` are
truthy and `"false"`/`"0"` falsy in boolean contexts. A `when` may also be a
`@name` handler reference.

**Branch isolation:** a `PARALLEL` fork defaults to `join: "merge"` — each
branch runs over a scoped write buffer (a `ScopedValue` frame, carried to the
worker) and merges into the context on clean completion; two branches that
write different values to the same key fail the run with a conflict error
naming the key (not a silent winner). Because the frame is carried, a fork
nested inside a branch writes into that enclosing branch and is discarded with
it on failure — nesting no longer escapes to the root. Declare
`{"type":"parallel","meta":{"join":"shared"}}` to opt the fork back into shared
writes (single-writer discipline is then the graph's job); its writes land in
the fork's enclosing branch scope, or the root at top level. The join counter
re-arms each LOOP iteration for a fork-join inside a loop body.

**Key types:**

| Type | Purpose |
|------|---------|
| `Graph` | Immutable runtime model — built from `GraphSpec` blueprints |
| `GraphSpec` | Canonical DAG authoring surface with explicit `entry`, separated `nodes`/`links`, and the boot-time validation gate |
| `FlowEngine` | Graph executor: `load`/`unload`/`graphs`, `find`/`require`, `run` (fresh top-level run, or continuation via an execution for sub-graphs) |
| `FlowDriver` | Pluggable task/condition executor + optional `PARALLEL` executor — contributed via `binder.contribute(FlowDriver.class)` |
| `FlowDriverDefault` | Built-in driver: resolves `@name` against the container, `#graphId` via sub-graph runs; standalone with inline handlers when container is null |
| `FlowInterceptor` | Contributed chain (flow-level `interceptFlow` + node `onNodeStart`/`onNodeEnd`); fixed at load, cannot change while running |
| `FlowEventBus` | Topic pub/sub scoped to one execution (cleared at run boundaries); a container-assembled engine also forwards each publish to the application `EventBus` under `"flow." + topic` |
| `ExprEvaluator` | Self-written recursive descent expression evaluator (~600 lines) |

**Driver & interceptor assembly:** graphs select a driver via `"driver"`
(null/""/`"default"` → the default). `FlowModule` binds `FlowEngine` as a
singleton: it puts a `FlowDriverDefault` (resolving `@name` against the
container) under `"default"`, then merges `Extension<FlowDriver>.asMap()` (a
contributed `"default"` overrides the built-in, with a warning), and freezes
`Extension<FlowInterceptor>.all()` as the chain. Register a custom driver by
contributing to the same extension point:

```java
binder.contribute(FlowDriver.class)
    .add("custom", new MyCustomDriver());

// Or use addDeferred(Class) for auto-instantiation via container.create()
binder.contribute(FlowDriver.class)
    .addDeferred(MyCustomDriver.class);
```

Graph definition: `{ "driver": "custom", ... }`

PlantUML export (`Graph.toPlantUml`), subgraph calls, and the interceptor chain
are supported. **Sub-graph calls share the one engine-level chain** — the same
interceptors wrap the child eval and fire exactly once per child node (there is
no per-eval interceptor list to propagate, so the v2 double-merge hazard is
structurally impossible).


---

## Commons Utilities

Commons contains the shared runtime primitives used across Freeway: JSON, coercion, validation, `Defer`, `ScopedCache`, and logging support.

- JSON parsing, building, and serialization live in `JsonUtils` and `JsonCodec`
- coercion lives in `Coercer` and `CoerceRule`
- validation lives in `BeanValidator`
- scoped primitives are described in [commons.md](modules/commons.md) and below

### Scope primitives (`Defer` / `ScopedCache` / `ScopeBoundary`)

`Defer` and `ScopedCache` share one `ScopeSession` (one session state, one close order: actions first, then values). The public session handle is read-only (`isActionScope()`, `isClosed()`); lifecycle control lives on the façade that opened it.

```java
Defer.within(() -> {
    Defer.defer(() -> cache.invalidate("posts"));
    if (bad) { Defer.rollback(); return; }   // discard deferred actions on normal exit
});
// no handle overloads: use Defer.within(Runnable|Supplier) + Defer.rollback()

ScopedCache.within(() -> {
    var conn = ScopedCache.computeIfAbsent("conn", () -> dataSource.getConnection());
    Defer.within(() -> Defer.defer(() -> conn.flush()));  // attaches to the action scope
});
```

Nesting is order-independent: a cache scope opened inside an action scope attaches to it, and an action scope opened inside a cache scope can still read the outer values during its drain. The value **lifetime** (until the owning scope closes) and the value **access window** (the cache block / action drain) are separate: outside the window `ScopedCache.isActive()` is false and `get` creates uncached values, but the attached values are still cleaned at the action scope's close. Thread-scoped services (`ScopeBoundary.within`) follow the same attach rule.

For HTTP request boundaries, see [Request-scoped services](#request-scoped-services-scopefilter).

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

- [docs/modules/commons.md](modules/commons.md)
- [Defer summary](design/defer.md)
- [DB usage guide](modules/db.md)

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
HttpServer server = container.get(HttpServer.class);
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
Database db = Database.create(config);
Schema.ensure(db, TestEntity.class);
```

For IoC tests, use `Freeway.create(...)`. For application integration tests, use `FreewayApp.run(...)` so runtime hooks and shutdown behavior are exercised.

---

## Architecture & Design

### Architecture Baseline

Framework internals — module boundaries, `RuntimeHook` ordering, the config
cascade mechanics, lifecycle rules — are in
[architecture.md](architecture.md). What follows is the developer-facing
summary.

- **`Container`** is the IoC boundary. Created via `Freeway.create()` for tests, received as a parameter in `RuntimeHook` callbacks and provider lambdas. Container is not injectable — use `@Inject` for service dependencies, not for the container itself.
- **`AppRuntime`** is the application boundary above `Container`. It owns config, profiles, runtime state, startup, shutdown, and runtime hooks. Access services through `app.get(Class)` rather than reaching for the Container.
- Service ids are **plain strings** and are normalized internally by `ServiceIds`. There is no public `ServiceId` type.
- Service lifecycle is declared only with `bind().scope(...)`: `SINGLETON`, `PROTOTYPE`, `BOUNDARY`.
- **`Defer` and `ScopedCache`** are the two façades over one `ScopeSession` in Commons. `Defer` buffers actions until the enclosing action scope commits; `ScopedCache` memoizes values for the lifetime of a scope and closes them on exit. Inside a `Defer` action scope, `ScopedCache` attaches its values to that scope; the value access window is the cache block plus the action drain, while values themselves are cleaned when the action scope closes.
- **Decision rule:** if the work should happen only after success, use `Defer`; if a value should be created once per scope and reused until cleanup, use `ScopedCache`.
- **`Defer` triggers:** DB transaction commit, HTTP request completion, batch/job success boundaries, ordered post-commit side effects such as invalidate → rebuild → notify.
- **`ScopedCache` triggers:** per-scope lookup tables, connections or handles held for the length of one `within()` block, repeated resolution of `Scope.BOUNDARY` services, values that need one cleanup action when the scope exits. The access window is the block (and the action drain); outside it `isActive()` is false and `get` does not cache, even if an enclosing action scope still holds the values.
- Thread-scoped services use **`ScopeBoundary.within()`** to enter an execution boundary. Backed by JDK 25 `ScopedValue` — no `ThreadLocal` overhead on virtual threads. When nested in a `Defer` action scope, the scope attaches and its beans live until that action scope closes.
- **`RuntimeHook`** provides start/stop extension points for modules. Ordered via `add(id, value).before()` / `.after()`. HTTP startup uses hook id `"freeway.http.server"` — no longer a side effect of resolving `HttpServer`.
- **`LoggerSource`** is the built-in logger service. Commons registers a JUL-backed SLF4J provider unconditionally via `META-INF/services`; at startup `LogBootstrap.ensureProvider()` resolves `freeway.log.system` (default `freeway`, i.e. the JUL backend) and pins `slf4j.provider` before SLF4J initializes. `auto` keeps the external-first behaviour (Logback, Log4j, slf4j-simple); a named backend or provider class selects it explicitly and fails startup when missing. `-Dslf4j.provider` overrides the Freeway key.

### Conventions

Naming (`XDefault`/`XImpl`, package visibility), design rules, testing and the
commit rules are canonical in [AGENTS.md](../AGENTS.md); this guide documents
usage, not conventions.
