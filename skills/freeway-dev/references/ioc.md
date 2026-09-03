# IoC Reference

Examples below are minimal snippets. They omit imports and app-specific domain types.

## Stable API

- `Container` - `get(Class)`, `get(Class, String)`, `get(Class, Annotation...)`, `extension(Class)`, `create(Class)`, `close()`
- `Binder` - `bind(Class)`, `contribute(Class)`, `install(ModuleEx)`; `ModuleEx` is the module entry-point type
- `Binding<T>` - `to(Class)`, `to(c -> ...)` (provider), `id(String)`, `primary()`, `marker(Annotation...)`, `scope(Scope)`, `advise(...)`; there is no `to(instance)`
- `ModuleEx` - module entry-point type: `bind(Binder)`
- `Scope` - `SINGLETON`, `THREAD`, `PROTOTYPE`
- `Scoping` - `within(...)`
- `Extension<V>` - framework-internal aggregation handle. Access via `container.extension(Class)`. Application code injects `List<V>` or `Map<String, V>`, not `Extension<V>` directly.
- `Contribution` - `before(String...)`, `after(String...)`
- `EventBus` - `publish`, `publishAsync`, `subscribe`, `unsubscribe`
- `RuntimeHook` - `start(Container)`, `stop(Container)`
- `LoggerSource` - owner-aware logger lookup

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
- the object graph aggregates `List`/`Map` contributions or extension data (`RouteIndex`, `WebServer`);
- the instance already exists and must not be rebuilt (`AppConfig`, a shared `PeerHub`);
- realization must be deferred until after all modules are composed (lazy builtins).

The provider owns only *how the object is built*. Scope and lifecycle still apply: a singleton
provider runs once per container, and the produced object still receives field injection,
`@PostConstruct`, and `@PreDestroy`.

```java
binder.contribute(Route.class)
    .add(Route.get("/", ctx -> ctx.send(200, "Hello")));

binder.contribute(RuntimeHook.class)
    .add("cache.warmup", new RuntimeHook() {
        public void start(Container c) { c.get(Cache.class).warmup(); }
        public void stop(Container c) { c.get(Cache.class).close(); }
    }).before("freeway.http.server");
```

## Config Injection: @Symbol vs @Value

Freeway uses two annotations with distinct semantics:

- **`@Symbol("key")`** — strict, for **required** config. Missing key = startup failure.
  Use for mandatory settings: database URLs, server ports, credentials.

- **`@Value("${key:default}")`** — relaxed, for **optional** config with a default.
  Use for settings with sensible fallbacks: timeouts, feature flags, cosmetic names.

```java
@Symbol("db.url") String dbUrl;                    // required
@Value("${app.timeout:30}") int timeout;            // optional, default 30
@Value("${app.name:freeway}") String appName;       // optional, default "freeway"
```

## Important Behavior

- `@Inject`, `@Symbol`, `@Value` are the main injection annotations.
- `List<Foo>` and `Map<String, Foo>` are injectable for contribution consumption. `Extension<Foo>` is not injectable — use `List` or `Map` instead.
- `Container` is not injectable — access it via `RuntimeHook.start(Container)` or `Freeway.create()`.
- Singleton services should not directly inject thread-scoped concrete classes.
- AOP only applies to interface-to-class bindings.
- Blank ids are rejected.
- `Binding.primary()` is the DSL for primary resolution.

## Contribution Ordering

- `add(value)` — unnamed, insertion order.
- `add(id, value)` — named, supports `before()` / `after()` topological ordering.
- `add(Class)` — auto-instantiates from the container, generates a canonical id as `snake_name@package`. Returns `Contribution` for `before`/`after` chaining.
- Unknown ids are not ignored. They fail resolution when ordering is evaluated.
- Cycles fail resolution.
