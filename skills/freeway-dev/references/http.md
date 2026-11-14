# HTTP Reference

Examples below are minimal snippets. They omit imports and app-specific domain types.

## Stable API

- `HttpModule`
- `HttpEngine`
- `FreewayHttpEngine`
- `HttpServer`
- `HttpExchange`
- `HttpTransport`, `ResponseHead`, `HttpExchangeSupport` (engine SPI — see below)
- `WebSocketTransport`, `WebSocketSessionSupport`
- `Route`, `RouteGroup`, `WebSocketRoute`, `WebSocketGroup`
- `HttpFilter`, `ScopeFilter`, `ErrorMapper`, `HealthFilter`, `HealthCheck`
- `StaticResourceMount`

## Configuration Keys

- `freeway.http.server.host`
- `freeway.http.server.port`
- `freeway.http.server.backlog`
- `freeway.http.server.shutdown-grace`
- `freeway.http.cors.*`
- `freeway.http.request-scope.enabled` (opt-in per-request value scope; `Scope.BOUNDARY` services/caches live for one request)
  Use for tenant/auth context, request-level permission caches, audit context and request-lived resources;
  not for DB connections/transactions, global state, SSE/WebSocket sessions or background jobs. It does not propagate to child threads.
- `freeway.http.health.enabled`
- `freeway.http.health.path`
- `freeway.http.h2.reset-burst-limit` (default 200, `0` disables; built-in engine only — `HttpModule` wires it into `FreewayHttpEngine.Wiring`)
- `freeway.http.h2.reset-window` (default 10s)
- `freeway.http.ssl.reload-interval` (`0` disables hot reload)

## Path Variables

Freeway supports two path variable syntaxes. They match identically at runtime — the difference is in expressive power.

| Syntax | Constraint | Use case |
|------|------|------|
| `:name` | Matches any non-empty segment | Familiar and concise (Express/Flask/Sinatra style); fast prototypes and public APIs |
| `{name}` | Same as `:name`, matches any non-empty segment | Visually consistent with `{name:regex}` and emphasizes "this is a parameter" |
| `{name:regex}` | Constrains the segment value by regex | When the parameter format must be validated (for example `{id:\d+}` allows digits only) |
| `{name:.*}` | Wildcards the remaining segments (path end only) | Static-file proxying (`/files/{path:.*}` matches `/files/a/b/c.txt`) |

**Recommendation:** use `:name` for everyday routes and `{name:regex}` when a regex constraint is needed. Both may be mixed, for example `/users/:userId/posts/{postId:\d+}`.

```java
// Lambda — stateless handlers, no injected dependencies
binder.contribute(Route.class)
    .add(Route.get("/", ctx -> ctx.sendText(200, "Hello")))
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

## Writing an engine adapter

An engine plugs into the seam by **composition** — no framework class is
subclassed (`AbstractHttpContext`/`AbstractWebSocketSession` are gone):

1. Implement `HttpTransport` — the request bytes in (`method`, `path`,
   `queryParams`, `headers`, `framedBodyStream`, TLS/remote) and the response bytes
   out (`writeResponse(ResponsePlan)`, `openSse`). Contract edges: the request snapshots are
   immutable with lower-cased names, `framedBodyStream()` returns the
   transport-framed stream and translates native limits (e.g. Undertow's
   `RequestTooBigException`) into `BodyTooLargeException`, **no body bytes
   are written when `head.sendsBodyBytes()` is false** — a HEAD keeps the
   declared `Content-Length` without reading the source — and `isCommitted()`
   flips immediately before the first head byte. A buffering transport may
   finalize the length of the body it materialized (including HEAD); a streaming
   transport leaves a missing length to its framing (`Transfer-Encoding:
   chunked` for HTTP/1.1, never for HTTP/2).
2. Build one transport + one `HttpExchangeSupport` **per request**:
   `new HttpExchangeSupport(transport, jsonCodec, coercer, config.maxBodySize(),
   config.compression(), correlationId)` and pass *that* to
   `ExchangeHandler.handle`. There is no `reset()` and no pool — per-request
   state cannot leak, and the support owns status/header validation, `Date`,
   `Content-Length`, the gzip decision, `Vary`, body limiting and coercion.
3. Serialize the committed `ResponseHead` (status, `headRequest`, headers in
   wire order). A declared `Content-Length` is already final; a buffering
   transport may add the length it materialized, while a streaming transport
   leaves it to framing (chunked on HTTP/1.1, absent on HTTP/2). Apply headers by
   replace-per-name so the head's list is the wire's final state.
4. WebSocket: implement `WebSocketTransport` (frame operations; `close` gets a
   reason already truncated to 123 bytes) and compose
   `WebSocketSessionSupport` for the endpoint.

`HttpServer.create(engine, config, pipeline)` is the entry point for the new
engine; the testkit contracts (`ContextContract`, `CompressionContract`,
`HeaderBudgetContract`, `RemoteRpcContract`, `WebSocketUpgradeContract`) are
what it must pass.

## Notes

- `HttpModule` automatically contributes a `RuntimeHook` that starts and stops the server — no manual hook registration needed.
- `HealthCheck` is in `com.jujin.freeway.http.filter`.
- `HttpServer.create(engine, config, pipeline[, eventSink])` is the single derivation point: standalone construction needs no container (the engine itself is `new FreewayHttpEngine(Wiring.defaults(json, coercer))`, and parts accumulate through `HttpPipeline.of(routes).withFilter(...)/.withCors(...)/.withStaticFiles(...)`).
- The container path goes through the same `create` from `HttpModule`: keys are read by the value types themselves (`HttpServerConfig.from` / `CorsFilter.from` / `HealthFilter.from` / `SslConfig.from`), while routes/filters/static resources/error mappings use `contribute(...)`. To swap the engine or its contract, use `.primary()` on `bind(HttpEngine.class)` / `bind(HttpServerConfig.class)`.
- Engine selection: `HttpModule` binds `FreewayHttpEngine` (default); add an extension module (Undertow/Jetty) to override via `.primary()`.
- TLS hot reload is event-driven (WatchService, debounced) with the poll at `ssl.reload-interval` as fallback; either layer alone drives the same snapshot comparison.
- HTTP/2 rapid-reset guard: cancels arriving before the server committed a response, past `h2.reset-burst-limit` inside `h2.reset-window`, trip the connection with `GOAWAY(ENHANCE_YOUR_CALM)`. Post-response cancels never count.
