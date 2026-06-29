# IoC Reference

Examples below are minimal snippets. They omit imports and app-specific domain types.

## Stable API

- `Container` - `get(Class)`, `get(Class, String)`, `extension(Class)`, `close()`
- `Binder` - `bind(Class)`, `contribute(Class)`, `install(Module2)`; `Module2` is the module entry-point type
- `Binding<T>` - `to(Class)`, `to(instance)`, `to(provider)`, `id(String)`, `primary()`, `scope(Scope)`, `advise(...)`
- `Module2` - module entry-point type: `bind(Binder)`
- `Scope` - `SINGLETON`, `THREAD`, `PROTOTYPE`
- `Scoping` - `within(...)`
- `Extension<V>` - ordered contributions for a given entry type
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

- `@Inject`, `@Named`, `@Symbol`, `@Value` are the main injection annotations.
- `List<Foo>` and `Extension<Foo>` can be resolved through injection.
- Singleton services should not directly inject thread-scoped concrete classes.
- AOP only applies to interface-to-class bindings.
- Blank ids are rejected.
- `Binding.primary()` is the DSL for primary resolution.

## Contribution Ordering

- Named contributions can be ordered with `before()` / `after()`.
- Unknown ids are not ignored. They fail resolution when ordering is evaluated.
- Cycles fail resolution.
