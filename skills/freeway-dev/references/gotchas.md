# Gotchas

- Unknown `before()` / `after()` targets throw `IllegalArgumentException` — they are not silently ignored.
- `StaticResourceMount` is the static-file API, not `StaticResources`.
- `HealthCheck` lives in `com.jujin.freeway.http.filter`.
- `AppRuntime` is `AutoCloseable`; examples should close it.
- If a snippet uses CLI args, define them in the snippet or use `new String[0]`.
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

