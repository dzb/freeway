# HTTP Reference

Examples below are minimal snippets. They omit imports and app-specific domain types.

## Stable API

- `HttpModule`
- `HttpEngine`
- `FreewayHttpEngine`
- `WebServerBuilder`
- `WebServer`
- `HttpContext`
- `Route`, `RouteGroup`, `WebSocketRoute`, `WebSocketGroup`
- `HttpFilter`, `ExceptionMapper`, `HealthFilter`, `HealthCheck`
- `StaticResourceMount`

## Configuration Keys

- `freeway.http.server.host`
- `freeway.http.server.port`
- `freeway.http.server.backlog`
- `freeway.http.server.shutdown-grace`
- `freeway.http.cors.*`
- `freeway.http.health.enabled`
- `freeway.http.health.path`

## Canonical Snippets

```java
binder.contribute(Route.class)
    .add(Route.get("/", ctx -> ctx.send(200, "Hello")))
    .add(Route.get("/users/:id", ctx -> ctx.sendJson(200, ctx.pathVar("id"))));
```

```java
binder.contribute(StaticResourceMount.class)
    .add(StaticResourceMount.classpath("/", "/public"))
    .add(StaticResourceMount.directory("/uploads", Path.of("/var/uploads")));
```

```java
binder.contribute(RuntimeHook.class)
    .add("freeway.http.server", new RuntimeHook() {
        public void start(Container c) { c.get(WebServer.class).start(); }
        public void stop(Container c) { c.get(WebServer.class).stop(); }
    });
```

## Notes

- `HealthCheck` is in `com.jujin.freeway.http.filter`.
- Standalone HTTP uses `WebServerBuilder.builder()`.
- Built-in engine selection is driven by normal binding resolution; `HttpModule` provides the default engine binding.
