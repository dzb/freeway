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

## Route Handlers

The lambda form fits dependency-free handlers. A handler that needs injected services is a `RouteHandler` class: the container creates it at startup and injects its constructor arguments.

```java
public static final class GetUser implements RouteHandler {
    private final UserService users;

    public GetUser(UserService users) { this.users = users; }

    @Override
    public void handle(HttpExchange ctx) throws Exception {
        var user = users.findById(ctx.pathVar("id").orElseThrow());
        if (user.isPresent()) {
            ctx.sendJson(200, user.get());
        } else {
            ctx.sendText(404, "Not found");
        }
    }
}

binder.contribute(Route.class)
    .add(Route.get("/api/users/:id", GetUser.class));
```

Body-carrying routes have two forms. The lambda form takes the body type and gets JSON deserialization plus bean validation for free; the class form reads the body itself.

```java
// Lambda with a body type: parse JSON, validate with BeanValidator, then handle
binder.contribute(Route.class)
    .add(Route.post("/users", User.class, (ctx, body) -> ctx.sendJson(201, body)))
    .add(Route.put("/users/:id", User.class, (ctx, body) -> ctx.sendJson(200, body)));

// Class handler: read the body in handle() via ctx.bodyAsJson(User.class)
binder.contribute(Route.class)
    .add(Route.post("/api/users", CreateUser.class));
```

`Route.post`, `put` and `patch` accept `(path, bodyType, handler)`; a body that fails `BeanValidator` raises `ValidationException`, which the built-in error mapper reports as `400 validation_failed`. A class handler for one of those routes reads the body itself with `ctx.bodyAsJson(User.class)`. Handler classes are resolved once at startup, so a missing dependency fails startup rather than the first request.

**Selection rule:** a handler that only forwards or returns a static response stays a lambda; a handler that needs dependencies injected is a class. A lambda that reaches for a static service locator such as `Users.get()` is a sign it should be a handler class.

## RouteGroup

`RouteGroup.of(prefix, Route...)` declares routes under one prefix:

```java
binder.contribute(RouteGroup.class)
    .add(RouteGroup.of("/api/v1",
        Route.get("/users", ctx -> ctx.sendJson(200, users())),
        Route.get("/users/:id", ctx -> ctx.sendJson(200, user(ctx.pathVar("id"))))
    ));
```

At startup the module expands every group route to its full path once and indexes the expanded list (`/api/v1` + `/users` becomes `/api/v1/users`; a `/` prefix adds no segment). Groups are flat declarations, not nested ones, and registration is strict: a duplicate method plus matching path shape, including a group route colliding with an explicitly declared route, fails startup instead of one winning silently.

## HttpExchange Core Methods

`HttpExchange` is the single object a route handler receives. It composes the request face (`HttpRequest`), the response face (`HttpResponse`) and exchange metadata (`ExchangeMeta`). The common methods:

| Method | Purpose |
|--------|---------|
| `ctx.method()` | HTTP method |
| `ctx.path()` | Request path |
| `ctx.pathVar("id")` | Path variable (`Optional<String>`) |
| `ctx.queryParam("q")` | Query parameter (`Optional<String>`) |
| `ctx.header("Accept")` | Request header (`Optional<String>`, case-insensitive) |
| `ctx.bodyAsJson(User.class)` | Deserialize the JSON request body |
| `ctx.param("name")` | Query parameter first, then path variable |
| `ctx.correlationId()` | Unique request id |
| `ctx.setStatus(201)` | Set the response status |
| `ctx.setHeader("X-Custom", "value")` | Set a response header, replacing existing values |
| `ctx.sendText(200, "plain text")` | Send a text response (`text/plain` by default) |
| `ctx.sendJson(200, object)` | Send a JSON response (`application/json` by default) |
| `ctx.sse()` | Open an SSE emitter (the response must not be committed yet) |

Request accessors for single values return `Optional`; response writers return the `HttpResponse` face, so calls chain. `setPathVars` and `setMaxBodySize` are framework and filter seams rather than handler conveniences.

## Filters

A filter wraps dispatch and may short-circuit by not calling `next`:

```java
public final class AuthFilter implements HttpFilter {
    @Override
    public void doFilter(HttpExchange ctx, RouteHandler next) throws Exception {
        if (ctx.header("Authorization").isEmpty()) {
            ctx.sendText(401, "Unauthorized");
            return;
        }
        next.handle(ctx);
    }
}

binder.contribute(HttpFilter.class).add(new AuthFilter());
```

Filters run in `order()` order: lower values run earlier (closer to the request entry, farther from the route handler), and filters with equal order keep their registration order.

| Order | Filter |
|-------|--------|
| `-100` | `CorsFilter` |
| `-50` | `HealthFilter` |
| `-40` | `ScopeFilter` (contributed, or added once when `freeway.http.request-scope.enabled=true`) |
| `0` | Application filters (`HttpFilter.order()` default) |

`HttpFilter` is a functional interface, so a simple filter can also be an inline lambda.

## Static Files

```java
binder.contribute(StaticResourceMount.class)
    .add(StaticResourceMount.classpath("/", "/public"))
    .add(StaticResourceMount.directory("/uploads", Path.of("/var/uploads")));
```

`classpath` mounts a resource tree and `directory` mounts a filesystem root. Both serve `GET`/`HEAD` only, and a directory request resolves to its `index.html`. Mounts are tried in contribution order before route dispatch; a miss on a matching mount is a terminal 404 unless at least one matching mount enables fallthrough. Each mount carries its own policy:

| Wither | Effect |
|--------|--------|
| `withCacheMaxAge(Duration)` | `Cache-Control: public, max-age=...` (default one day) |
| `withImmutable(boolean)` | Adds `immutable` to `Cache-Control` |
| `withFallthrough(boolean)` | On a miss, continue to the route chain instead of returning 404 |
| `withSpaFallback(boolean)` | Serve the mount's `index.html` after route dispatch misses on an extensionless path; implies fallthrough and fails startup if `index.html` is absent |

Responses carry an `ETag` (and `Last-Modified` when the source reports one), honor `If-None-Match`/`If-Modified-Since` with 304, support single byte ranges (206/416), and a HEAD reports the known length without reading the file. Resolved paths must remain inside the mount root; a symlink escape is rejected.

## WebSocket

```java
binder.contribute(WebSocketRoute.class)
    .add(WebSocketRoute.of("/ws/chat", session -> new WebSocketListener() {
        @Override
        public void onText(String text) throws Exception {
            session.sendText("Echo: " + text);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) { }
    }));
```

`WebSocketEndpoint.open(WebSocketSession)` runs once per connection and returns the listener that receives its frames and lifecycle events. Every `WebSocketListener` callback is a default method: `onOpen(WebSocketSession)`, `onText(String)`, `onBinary(byte[])`, `onClose(int, String, boolean)`, `onPong(byte[])` and `onError(Throwable)`. The session sends frames with `sendText`, `sendBinary`, `ping`, `close(int, String)` and `flush`, and `sendTextBatch` flushes a batch of text frames. It also exposes the request view (`pathVar`, `queryParam`, `header`) and `correlationId()`. Path templates use the same syntax as HTTP routes; register an endpoint class instead of a lambda when it needs injected dependencies.

## SSE

```java
Route.get("/events", ctx -> {
    SseEmitter sse = ctx.sse();
    sse.send("connected");
    sse.send(new SseEvent("data", "msg-1", "update", null));
    sse.complete();
});
```

`ctx.sse()` opens an `SseEmitter` and sets the event-stream headers; it requires an uncommitted response. `SseEvent` is `(data, id, event, retry)`, and `send(String)` is shorthand for a data-only event. The emitter writes a heartbeat comment every 15 seconds by default and is `AutoCloseable` (`complete()` and `close()` both close it). For long-lived streams, `pipe(Flow.Publisher)` and `pipe(publisher, mapper)` pump a JDK Flow source with one-in-flight backpressure; the call blocks the handler until the source ends or the emitter is closed, and one emitter carries at most one attached stream.

## Error Mapping

`ErrorMapper` turns an exception into an `ErrorResponse` without writing it, so the server remains the only writer. Contribute mappers; the first one that returns a non-empty `Optional` wins:

```java
binder.contribute(ErrorMapper.class).add(ex ->
    ex instanceof NotFoundException
        ? Optional.of(ErrorResponse.of(HttpStatus.NOT_FOUND, "not_found",
            ex.getMessage()))
        : Optional.empty());
```

The built-in mapper (`ErrorMapper.defaults()`) is appended after application mappers, so an application can re-map any case it handles and keep the default mappings for the rest:

| Exception | Status | Code |
|-----------|--------|------|
| `BodyTooLargeException` | `413` | `payload_too_large` |
| `MultipartException` | `400` | `invalid_multipart` |
| `UnsupportedMediaTypeException` | `415` | `unsupported_media_type` |
| `ValidationException` | `400` | `validation_failed` |
| anything unclaimed | `500` | `internal_error` |

`ErrorResponse` is `(status, code, message, details, headers)`. The wire body is `{"error": message, "code": code, "details": {...}}`, with `details` present only when non-empty; `withDetails` and `withHeader` return a new response. Route misses answer 404 `not_found`, and a known path with the wrong method answers 405 `method_not_allowed` with an `Allow` header and the same value in `details.allow`.

## Health Checks

The built-in `/healthz` endpoint is served by `HealthFilter` before routing and is enabled by default:

| Key | Default | Meaning |
|-----|---------|---------|
| `freeway.http.health.enabled` | `true` | Enable or disable the endpoint |
| `freeway.http.health.path` | `/healthz` | Endpoint path |

`HealthCheck` is a bound service; `HealthCheck.ALWAYS_OK` is the default. A healthy result answers `200 {"status":"ok"}`; an unhealthy one answers `503 {"status":"unhealthy","detail":"..."}`. The check returns a `HealthResult`, the same value type cloud readiness contributors use:

```java
binder.bind(HealthCheck.class).to(c -> () -> {
    Database db = c.get(Database.class);
    return db.ping()
        ? HealthResult.ok()
        : HealthResult.unhealthy("database unreachable");
}).primary();
```

`HealthResult` is `(boolean healthy, String detail)` with `ok()` and `unhealthy(detail)` factories.

## Writing an engine adapter

An engine plugs into the seam by **composition** — no framework class is
subclassed:

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
