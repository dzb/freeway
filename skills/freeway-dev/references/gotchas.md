# Gotchas

- Unknown `before()` / `after()` targets are not silently ignored.
- `StaticResourceMount` is the static-file API, not `StaticResources`.
- `HealthCheck` lives in `com.jujin.freeway.http.filter`.
- `AppRuntime` is `AutoCloseable`; examples should close it.
- If a snippet uses CLI args, define them in the snippet or use `new String[0]`.
- `FreewayApp.run(...)` already starts the runtime.
- A `THREAD` scoped concrete class should not be injected directly into a singleton.
- `Binding.primary()` is the API for primary resolution, not an annotation.

