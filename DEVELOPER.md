# Freeway 2 Developer Guide

## Module Dependency Graph

```
freeway-commons
      ↑
freeway-ioc
      ↑               ↑               ↑
freeway-boot    freeway-http    freeway-db
      ↑               ↑
      └─── engine adapters ───┘
    freeway-http-jdk       (built-in, always available)
    freeway-http-robaho    (default)
    freeway-http-undertow
    freeway-http-jetty
```

Dependencies flow **downward** — core modules never depend on higher-level modules.

---

## IoC Container (`freeway-ioc`)

### Core Interfaces

| Interface | Purpose |
|-----------|---------|
| `Container` | Service locator: `get(Class)`, `get(Class, ServiceId)` |
| `Module` | Entry point for binding: `bind(Binder)` |
| `Binder` | Binding DSL: `bind(X).to(Y.class\|instance)` |
| `Freeway` | Bootstrap: `Freeway.create(Module...)` |
| `ServiceId` | Named qualifier for multi-binding scenarios |

### Binding DSL

```java
Container container = Freeway.create(binder -> {

    // Simple binding
    binder.bind(Greeter.class).to(GreeterImpl.class);

    // Named binding — resolve by ServiceId
    binder.bind(PaymentGateway.class)
        .to(StripeGateway.class)
        .id(ServiceId.of("stripe"))
        .primary();           // default when no id specified

    binder.bind(PaymentGateway.class)
        .to(PaypalGateway.class)
        .id(ServiceId.of("paypal"));

    // Prototype scope — new instance per get()
    binder.bind(Greeter.class)
        .to(GreeterImpl.class)
        .scope(Scope.PROTOTYPE);

    // Advisors (AOP) — intercept method calls
    binder.bind(Greeter.class)
        .to(GreeterImpl.class)
        .advise(advisor -> advisor.wrap(
            inv -> inv.method().getName().equals("greet"),
            inv -> {
                System.out.println("before");
                Object result = inv.proceed();
                System.out.println("after");
                return result;
            }
        ));
});
```

> **Note**: Advisors only work with interface-to-class bindings. The container creates a JDK proxy. Bindings of concrete classes to themselves cannot be advised.

### Symbol Injection (`@Symbol` / `@Value`)

Inject configuration values from system properties, environment, or config files:

```java
public record ConfiguredService(
    @Symbol("server.port") int port,
    @Value("${app.name}") String name
) {}
```

| Annotation | Resolution |
|------------|-----------|
| `@Symbol("key")` | Strict: fails if key is missing |
| `@Value("${key}")` | Lenient: expression syntax, supports defaults via `\${key:default}` |

Both support automatic type coercion (String → int, boolean, enum, Duration, etc.).

### Extension Points

A plugin-style contribution model for collecting items from multiple modules:

```java
// Contributor side
binder.contribute(AppFeature.class).add(new AppFeature("core"));
binder.contribute(AppFeature.class).add(new AppFeature("web"));

// Consumer side — receives a Collection of all contributions
public class AppConfig {
    public AppConfig(@ExtensionPoint(AppFeature.class) Collection<AppFeature> features) {
        ...
    }
}

// Mapped variant — keyed contributions
binder.contributeMapped(AppFlag.class).put("debug", new AppFlag("debug", true));
// Consumer receives Map<String, AppFlag>
```

### Type Coercion

Built-in coercions: `String → int, long, double, boolean, char, enum, Duration, ...`

Add custom coercions:

```java
binder.contribute(CoercionRule.class).add(new CoercionRule<>(
    String.class, Endpoint.class,
    value -> {
        String[] parts = value.split(":", 2);
        return new Endpoint(parts[0], Integer.parseInt(parts[1]));
    }
));
```

### SPI Discovery

Modules can be auto-discovered via the standard Java `ServiceLoader` mechanism:

```
META-INF/services/com.jujin.freeway.ioc.Module
-- content: fully qualified class name implementing Module
```

This is used by engine adapters and the DB module to auto-register themselves.

---

## Boot (`freeway-boot`)

### Launcher API

```java
// From a class
App app = Launcher.run(MyAppModule.class, args);

// From a Module instance
App app = Launcher.run(module);
```

### Shutdown Hook

`Launcher.run()` automatically registers a JVM shutdown hook (`freeway-shutdown-hook` thread) that calls `app.close()` on JVM termination, ensuring graceful cleanup of containers, connection pools, and engine resources.

### Startup Timing

On every launch, the elapsed startup time is logged to the console:

```
INFO  - Started freeway application in 284 ms
```

### Config Cascade

Priority (high → low):

1. CLI arguments (`--key=value`)
2. System properties (`-Dkey=value`)
3. Environment variables (`KEY=value`)
4. `application-{profile}.json` (profile-specific)
5. `application-{profile}.properties`
6. `application.json`
7. `application.properties`

### Profiles

Activate with `--freeway.profile=dev` (or `-Dfreeway.profile=dev`). Multiple profiles can be comma-separated.

---

## HTTP Layer (`freeway-http`)

The `com.jujin.freeway.http` package uses a flat structure — all classes in one package, no sub-packages. This keeps imports simple and discoverable:

| Category | Classes |
|----------|---------|
| Core API | `HttpEngine`, `HttpContext`, `HttpFilter`, `HttpModule`, `HttpServerConfig`, `HttpServerHandle`, `HttpRequestHandler`, `JsonCodec`, `WebServer`, `ExceptionMapper`, `RouteHandler`, `RequestContext`, `BodyHandler` |
| WebSocket API | `WebSocketSession`, `WebSocketListener`, `WebSocketEndpoint`, `WebSocketRoute`, `WebSocketGroup`, `WebSocketMatch`, `WebSocketIndex` |
| SSE API | `SseEmitter`, `SseEvent` |
| Routing | `Route`, `RouteGroup`, `RouteIndex`, `PathGroupSupport`, `PathPattern` |
| Built-ins | `DefaultJsonCodec`, `DefaultRequestContext`, `CorsFilter`, `RequestTimingFilter`, `StaticResourceMount`, `StaticResources`, `MultipartForm`, `RequestBodyTooLargeException`, `ValidationException` |
| JDK engine | `JdkHttpEngine`, `JdkHttpContext` (package-private, always available fallback) |

The public API surface a typical application uses is 5–10 classes. Implementation details like `JdkHttpEngine` are package-private, isolating them from the public contract without needing directory-level separation.

### Routing

Routes are registered via the extension point mechanism — contribute `Route` or `RouteGroup` instances in your module:

```java
binder.contribute(Route.class).add(Route.get("/hello", ctx -> ctx.send(200, "hello")));
binder.contribute(Route.class).add(Route.post("/api/users", ctx -> {
    ctx.sendJson(201, ctx.bodyAsJson(Map.class));
}));
binder.contribute(RouteGroup.class).add(RouteGroup.of("/api",
    Route.get("/group", ctx -> ctx.send(200, "group")),
    Route.get("/items/{id}", ctx -> ctx.send(200, ctx.pathVar("id")))
));
```

Route paths support path parameters with optional regex constraints:

| Pattern | Matches | Example |
|---------|---------|--------|
| `{id}` | any single segment | `/users/42` |
| `{id:\\d+}` | digits only | `/users/42` (not `/users/abc`) |
| `{path:.*}` | remaining path (wildcard) | `/files/a/b/c` |

The route index uses a **trie** (prefix tree) internally, delivering O(L) lookup where L is the number of path segments — independent of total route count.

### Request Body Binding with Validation

`Route.post`, `Route.put`, and `Route.patch` accept a body type class for automatic JSON deserialization and bean validation:

```java
binder.contribute(Route.class).add(Route.post("/api/users", CreateUserRequest.class, (ctx, body) -> {
    // body is already validated — type-safe, no manual deserialization needed
    ctx.sendJson(201, Map.of("id", 1, "name", body.name()));
}));

binder.contribute(Route.class).add(Route.put("/api/users/{id}", UpdateUserRequest.class, (ctx, body) -> {
    ctx.sendJson(200, Map.of("updated", true));
}));
```

If validation fails, a `ValidationException` is thrown and automatically mapped to a `400 Bad Request` response with field-level error details.

### Server-Sent Events (SSE)

Call `HttpContext.sse()` to switch a response to SSE mode. Returns a `SseEmitter` for writing events:

```java
binder.contribute(Route.class).add(Route.get("/events", ctx -> {
    try (var emitter = ctx.sse()) {
        emitter.send("hello");                            // plain data event
        emitter.send(new SseEvent("data", "evt1"));      // typed event
        emitter.send(new SseEvent("payload", "msg-001", "update", 3000L)); // full event
    }
}));
```

`SseEvent` supports:
| Field | Description |
|-------|-------------|
| `data` | Event data (required) |
| `id` | Event ID for `Last-Event-ID` tracking |
| `event` | Event type name (`event:` field) |
| `retry` | Reconnection time in milliseconds |

The emitter implements `AutoCloseable` — use try-with-resources for safe cleanup. SSE is supported on all HTTP engines (JDK, Robaho, Undertow, Jetty).

### WebSocket

WebSocket endpoints are registered via `WebSocketRoute` and `WebSocketGroup` contributions:

```java
binder.contribute(WebSocketRoute.class).add(WebSocketRoute.of("/chat", session -> new WebSocketListener() {
    @Override
    public void onText(String text) throws Exception {
        session.sendText("Echo: " + text);
    }
}));
```

### Filters & Exception Mappers

```java
// Global filter
binder.contribute(HttpFilter.class).add((ctx, next) -> {
    long start = System.nanoTime();
    next.handle(ctx);
    long elapsed = System.nanoTime() - start;
    ctx.headerSet("X-Response-Time", String.valueOf(elapsed / 1_000_000));
});

// Exception mapper
binder.contribute(ExceptionMapper.class).add((ctx, ex) -> {
    if (ex instanceof RequestBodyTooLargeException) {
        ctx.sendJson(413, Map.of("error", "Payload Too Large"));
        return true;
    }
    return false;
});
```

`ValidationException` is automatically mapped by `HttpModule` to a `400 Bad Request` with `{error, details}` payload — no manual exception mapper needed.

### Engine Selection

Set `web.engine` in config:

| Value | Engine | Dependencies |
|-------|--------|-------------|
| `robaho` (default) | Robaho HTTP Server + WebSocket | Zero external deps |
| `jdk` | JDK built-in HttpServer | Always available (HTTP only) |
| `undertow` | Undertow 2.3 | `undertow-core` |
| `jetty` | Jetty 12.1 | `jetty-server` + `jetty-websocket-server` |

The engine adapter implements the `HttpEngine` interface. Each adapter module registers itself via `ServiceLoader`. The JDK engine is built into `freeway-http` and serves as automatic fallback when `robaho` is not on the classpath.

---

## DB Layer (`freeway-db`)

### Database Interface

```java
Database db = container.get(Database.class);

// Query — returns a list
List<User> users = db.sql("SELECT * FROM users WHERE active = ?", true)
    .list(User.class);

// Single result — returns Optional
User user = db.sql("SELECT * FROM users WHERE id = ?", 42)
    .one(User.class)
    .orElseThrow();

// Named parameters — :name or $name syntax
List<User> filtered = db.sql("SELECT * FROM users WHERE name = :name AND active = $active")
    .param("name", "Alice")
    .param("active", true)
    .list(User.class);

// IN clause with Collection expansion
List<User> byIds = db.sql("SELECT * FROM users WHERE id IN (:ids)")
    .param("ids", List.of(1, 2, 3))
    .list(User.class);

// Write operations
int rows = db.sql("UPDATE users SET name = ? WHERE id = ?", "Alice", 42)
    .execute();
```

> **Note**: Named (`.param(name, value)`) and positional (`.sql(sql, val1, val2)`) parameters cannot be mixed.

### Streaming Queries

For large result sets, use `Query.stream()` — returns a lazy `Stream<T>` that fetches rows on demand. The underlying database connection is held open until the stream is closed:

```java
// Always use try-with-resources to ensure the connection is returned to the pool
try (var stream = db.sql("SELECT * FROM users WHERE active = ?", true)
                       .stream(User.class)) {
    stream.forEach(user -> process(user));
}
```

The stream uses a fetch size of 100 rows per network round-trip. Connection is returned to the pool automatically when the stream is closed (either via `close()` or a terminal operation like `collect()` inside try-with-resources).

### Batch Queries

```java
// Positional batch
int[] results = db.batch("INSERT INTO log (msg) VALUES (?)")
    .rows(new Object[]{"msg1"}, new Object[]{"msg2"})
    .execute();

// Named batch
db.batch("INSERT INTO users(name, active) VALUES (:name, :active)")
    .named(List.of(
        Map.of("name", "Alice", "active", true),
        Map.of("name", "Bob", "active", false)
    ))
    .execute();
```

### Transactions

```java
db.transaction(tx -> {
    tx.sql("UPDATE accounts SET balance = balance - 100 WHERE id = ?", 1).execute();
    tx.sql("UPDATE accounts SET balance = balance + 100 WHERE id = ?", 2).execute();
    return true; // commit
});
```

### Connection Pooling

Built-in pool, configured via config properties:

```properties
db.url=jdbc:h2:mem:test
db.username=sa
db.password=
db.maxSize=10
db.minIdle=2
db.connectionTimeout=5s
db.maxLifetime=30m
```

### Leak Detection

The pool tracks active (borrowed) connections. `DatabaseStats.longLeased()` reports connections held longer than 30 seconds, helping detect connection leaks in application code. Query via:

```java
DatabaseStats stats = db.stats();
int leaked = stats.longLeased(); // connections borrowed > 30s
```

### RowMapper Caching

Row mappers for record and bean types cache column index lookups after the first row, avoiding repeated `ResultSetMetaData` calls across large result sets. The cache auto-invalidates when the result set's column count changes (e.g., across different queries on the same `PreparedStatement`).

### Migrations

SQL files in `db/migration/` with `V{version}__{description}.sql` naming:

```sql
-- db/migration/V001__create_users.sql
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    active BOOLEAN DEFAULT TRUE
);
```

Migrations run automatically when the `Database` is first accessed. Each migration runs exactly once.

### DatabaseHub (Multi-Datasource)

```java
DatabaseHub hub = container.get(DatabaseHub.class);
Database analytics = hub.get("analytics");
analytics.sql("SELECT COUNT(*) FROM events").one(Long.class);
```

---

## Testing Guidelines

### Running Tests

```bash
# All tests
mvn test

# Single module
mvn -pl freeway-ioc test

# Module with dependencies
mvn -pl freeway-http -am test
```

### Writing Tests

The IoC container makes unit testing straightforward:

```java
@Test
void resolvesPrimaryBinding() {
    Container container = Freeway.create(
        binder -> binder.bind(Greeter.class).to(GreeterImpl.class)
    );
    Greeter greeter = container.get(Greeter.class);
    assertEquals("hello", greeter.greet());
}
```

For module integration tests, use `Launcher`:

```java
@Test
void bootsWithFullStack() {
    App app = Launcher.run(TestApp.class);
    try {
        MyService svc = app.container().get(MyService.class);
        assertNotNull(svc);
    } finally {
        app.close();
    }
}
```

---

## Adding a New HTTP Engine Adapter

1. Create module `freeway-http-{name}`
2. Depend on `freeway-http`
3. Implement `HttpEngine` interface
4. Register a `Module` in `META-INF/services/com.jujin.freeway.ioc.Module`
5. Bind the engine class with an id: `binder.bind(MyEngine.class).to(MyEngine.class).id(ServiceId.of("my-engine"))`
6. Users select it with `web.engine=my-engine`

---

## Code Style

- Java 25 with preview features (`--enable-preview`)
- No external dependencies for core modules (commons, ioc, boot, http, db)
- Explicit over implicit — no annotation scanning, no bytecode manipulation
- `compose-first` — wire everything in `Module.bind(Binder)`
