# Gotchas

- Unknown `before()` / `after()` targets throw `IllegalArgumentException` — they are not silently ignored.
- `StaticResourceMount` is the static-file API; use its `classpath(...)` / `directory(...)` mount factories.
- `HealthCheck` lives in `com.jujin.freeway.http.filter`.
- `AppRuntime` is `AutoCloseable`; examples should close it.
- If a snippet uses CLI args, pass them through `Launch.of("--freeway.profile=dev")` to `FreewayApp.run(...)`, or use `FreewayApp.create(...).args(...)`; there is no argument-array parameter on the launcher.
- `FreewayApp.run(...)` already starts the runtime; `HttpModule` already contributes the server hook.
- A `BOUNDARY` scoped concrete class should not be injected directly into a singleton; inject the interface so the container can hand out a scoped proxy.
- `Binding.primary()` and the `@Primary` marker are equivalent — the DSL call maps to the marker internally, so both forms resolve through the same marker index.
- `Schema.ensure(db, ...)` / `Schema.drop(db, ...)` use the database's dialect automatically — no `Dialect` argument.
- `HttpExchange.pathVar()` returns `Optional<String>`, not a bare `String`.
- Tests that start a `HttpServer` should use `port=0` to avoid conflicts with running services.
- `HttpServer` is `com.jujin.freeway.http.HttpServer`; JDK's `com.sun.net.httpserver.HttpServer` has the same simple name. Import Freeway's by name and keep the JDK one fully qualified in the few files that drive both — a bare `HttpServer.class` can silently resolve to the JDK type (single-type import beats a wildcard) and then fail as a missing container binding at runtime.
- `Container` and `Extension<V>` are not injectable. Use `@Inject List<V>` or `@Inject Map<String, V>` to consume contributions. Access `Container` only via `RuntimeHook`, `Freeway.create()`, or provider lambdas.
- Prefer handler classes over lambdas when a route handler needs injected services. If you find yourself calling a static `Xxx.get()` inside a lambda, switch to `Route.get("/path", Handler.class)` with constructor injection.
- Return a borrowed `PooledConnection` to its own pool — cross-pool release throws `SqlException` by design.
- **A class the container constructs needs exactly one constructor, annotated `@Inject`.** Selection
  (`commons.bean.BeanIntrospector.selectConstructor`) has three tiers: a single `@Inject`-annotated
  constructor wins; otherwise a no-arg constructor wins **even when a larger one exists**; otherwise
  the most-parameterized one wins. Two annotated constructors throw, which is the only loud failure.
  Everything else is silent: leave a no-arg next to your intended constructor without `@Inject` and
  parameter injection is skipped with no warning, and two constructors of equal arity are chosen by
  `getDeclaredConstructors()` order, which the JVM does not specify — the winner can change with the
  compiler or a source reorder. Only tier 1 is a decision the source states; tiers 2 and 3 are the
  container inferring. So give the class one constructor and mark it `@Inject`, and when construction
  really does branch (settings, plain values, runtime config), bind a provider instead of leaving a
  second constructor to be inferred.

## Observability & Failures

- Metric names are static and dot-separated; **never** put a raw path, URL, request id or tenant id in a label. Use `routePattern()` for the declared template; misses are `unmatched`.
- `MetricsDefault` admits 10,000 distinct name+label sets and throws at registration beyond that; bounded labels are a correctness requirement, not only a style rule.
- `timer` only gives count/average; use `histogram` for p95/p99. Buckets are fixed by the framework — do not invent arbitrary bucket lists.
- `histogram_quantile` is approximate and single-process in `MetricsDefault`; cross-instance aggregation is the backend's job.
- HTTP duration histograms carry `method` + `route`; `status` stays on the 4xx/5xx counters, so do not expect it on the duration series.
- Cancelling an RPC future is meaningful (`cancel(true)` interrupts the retry loop); do not rely on abandoning the future alone.
- `Failure.retryable()` / `outcomeUnknown()` defaults are conservative; do not retry `SqlException`/`FlowException` blindly or infer from a `SocketTimeoutException` cause.
- A `Metrics` backend without labels/histograms records the name-only aggregate (the default overloads delegate); do not branch on backend type at instrumentation sites.

## Testing Patterns

### IoC tests

```java
Container c = Freeway.create(binder -> {
    binder.bind(MyService.class).to(MyServiceImpl.class);
});
MyService svc = c.get(MyService.class);
c.close();
```

### HTTP tests

`c` is a container that placed `HttpModule`. Set `freeway.http.server.port=0`
through the test's config so parallel runs cannot collide, and read the bound
port from `server.port()`:

```java
HttpServer server = c.get(HttpServer.class);
server.start();
try {
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder().uri(URI.create("http://localhost:" + server.port() + "/test"))
            .GET().build(), BodyHandlers.ofString());
    assertEquals(200, resp.statusCode());
} finally { server.stop(); }
```

### DB tests (H2 in memory)

```java
PoolConfig config = PoolConfig.defaults("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "");
Database db = Database.create(config);
Schema.ensure(db, TestEntity.class);
```

Close the database when the test ends. `DB_CLOSE_DELAY=-1` keeps the in-memory
database alive after the last connection closes, so the schema a test created is
still there for its later connections.

## Design Conventions

- **Public interfaces use the bare domain name**: `Container`, `JsonCodec`, `HttpExchange`.
- **Default implementations use the `XDefault` suffix**: `AppRuntimeDefault`, `JsonCodecDefault`, `CoercerDefault`.
- **`Impl` is reserved for non-strategic concrete implementations**: container-internal assembly (`ContainerImpl`), engine internals (`HttpTransportImpl`), and per-owner types that coexist with siblings (`PooledConnectionImpl`).
- **Library types do not import IoC types** - the `Library -> DbModule` pattern: the library itself has zero IoC dependencies.
- **Do not start work in `bind()`** - modules only declare; `RuntimeHook.start()` is the activation point.

