# HTTP Reference

Examples below are minimal snippets. They omit imports and app-specific domain types.

## Stable API

- `HttpModule`
- `HttpEngine`
- `FreewayHttpEngine`
- `HttpServer`
- `HttpContext`
- `Route`, `RouteGroup`, `WebSocketRoute`, `WebSocketGroup`
- `HttpFilter`, `ErrorHandler`, `HealthFilter`, `HealthCheck`
- `StaticResourceMount`

## Configuration Keys

- `freeway.http.server.host`
- `freeway.http.server.port`
- `freeway.http.server.backlog`
- `freeway.http.server.shutdown-grace`
- `freeway.http.cors.*`
- `freeway.http.health.enabled`
- `freeway.http.health.path`
- `freeway.http.h2.reset-burst-limit` (default 200, `0` disables)
- `freeway.http.h2.reset-window` (default 10s)
- `freeway.http.ssl.reload-interval` (`0` disables hot reload)

## Path Variables

Freeway supports two path variable syntaxes. They match identically at runtime — the difference is in expressive power.

| 语法 | 约束 | 场景 |
|------|------|------|
| `:name` | 匹配任意非空段 | 简洁熟悉（Express/Flask/Sinatra 风格），快速原型、公开 API |
| `{name}` | 同 `:name`，匹配任意非空段 | 与 `{name:regex}` 保持视觉一致，强调"这是参数" |
| `{name:regex}` | 正则约束段值 | 需要验证参数格式时（如 `{id:\d+}` 只允许数字） |
| `{name:.*}` | 通配剩余段（仅限路径末尾） | 静态文件代理（`/files/{path:.*}` 匹配 `/files/a/b/c.txt`） |

**建议：** 日常用 `:name`，需要正则约束时用 `{name:regex}`。两者可混用，如 `/users/:userId/posts/{postId:\d+}`。

```java
// Lambda — stateless handlers, no injected dependencies
binder.contribute(Route.class)
    .add(Route.get("/", ctx -> ctx.send(200, "Hello")))
    .add(Route.get("/users/:id", ctx -> ctx.sendJson(200, ctx.pathVar("id"))))
    .add(Route.get("/posts/{id}", ctx -> ctx.sendJson(200, ctx.pathVar("id"))))
    .add(Route.get("/items/{id:\\d+}", ctx -> ctx.sendJson(200, ctx.pathVar("id"))));

// Handler class — constructor injection for handlers with dependencies
binder.contribute(Route.class)
    .add(Route.get("/api/users/:id", GetUserHandler.class));
// GetUserHandler implements RouteHandler, receives UserService via constructor
```

```java
binder.contribute(StaticResourceMount.class)
    .add(StaticResourceMount.classpath("/", "/public"))
    .add(StaticResourceMount.directory("/uploads", Path.of("/var/uploads")));
```

## Notes

- `HttpModule` automatically contributes a `RuntimeHook` that starts and stops the server — no manual hook registration needed.
- `HealthCheck` is in `com.jujin.freeway.http.filter`.
- `HttpServer.create(engine, config, pipeline[, eventSink])` 是唯一的派生点：独立构建零容器（引擎自己 `new FreewayHttpEngine(Wiring.defaults(json, coercer))`，部件用 `HttpPipeline.of(routes).withFilter(...)/.withCors(...)/.withStaticFiles(...)` 累加）。
- 容器路径由 `HttpModule` 走同一个 `create`：键由值类型自己读（`HttpServerConfig.from` / `CorsFilter.from` / `HealthFilter.from` / `SslSettings.from`），路由/过滤器/静态资源/异常映射用 `contribute(...)`；要换引擎或引擎契约用 `bind(HttpEngine.class)` / `bind(HttpServerConfig.class)` 的 `.primary()`。
- Engine selection: `HttpModule` binds `FreewayHttpEngine` (default); add an extension module (Undertow/Jetty) to override via `.primary()`.
- TLS hot reload is event-driven (WatchService, debounced) with the poll at `ssl.reload-interval` as fallback; either layer alone drives the same snapshot comparison.
- HTTP/2 rapid-reset guard: cancels arriving before the server responded, past `h2.reset-burst-limit` inside `h2.reset-window`, trip the connection with `GOAWAY(ENHANCE_YOUR_CALM)`. Post-response cancels never count.
