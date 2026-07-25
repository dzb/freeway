# Gotchas

- Unknown `before()` / `after()` targets throw `IllegalArgumentException` — they are not silently ignored.
- `StaticResourceMount` is the static-file API, not `StaticResources`.
- `HealthCheck` lives in `com.jujin.freeway.http.filter`.
- `AppRuntime` is `AutoCloseable`; examples should close it.
- If a snippet uses CLI args, define them in the snippet or use `new String[0]`.
- `FreewayApp.run(...)` already starts the runtime; `HttpModule` already contributes the server hook.
- A `THREAD` scoped concrete class should not be injected directly into a singleton.
- `Binding.primary()` is the API for primary resolution, not an annotation.
- `Schema.ensure()` requires an explicit `Dialect` parameter — there is no no-dialect overload.
- `HttpContext.pathVar()` returns `Optional<String>`, not a bare `String`.
- Tests that start a `WebServer` should use `port=0` to avoid conflicts with running services.
- `Container` and `Extension<V>` are not injectable. Use `@Inject List<V>` or `@Inject Map<String, V>` to consume contributions. Access `Container` only via `RuntimeHook`, `Freeway.create()`, or provider lambdas.
- Prefer handler classes over lambdas when a route handler needs injected services. If you find yourself calling a static `Xxx.get()` inside a lambda, switch to `Route.get("/path", Handler.class)` with constructor injection.

