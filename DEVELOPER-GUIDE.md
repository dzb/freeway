# Freeway 2 Developer Guide

Freeway is a lightweight, modern Java application framework for JDK 25+. Compose-first, zero classpath scanning, zero bytecode weaving, minimal dependencies.

## Quick Start

```java
// A minimal HTTP application
public class App {
    public static void main(String[] args) {
        AppRuntime runtime = Launcher.run(args, new AppModule());
    }

    public static final class AppModule implements Module2 {
        public void bind(Binder b) {
            b.contribute(Route.class).add(Route.get("/", ctx ->
                ctx.send(200, "Hello Freeway")));
        }
    }
}
```

```bash
mvn test                          # all modules
mvn -pl freeway-ioc -am test      # single module
```

## Module Map

```
freeway-commons          shared utilities: JSON, coercion, Defer, ScopedCache, beans, validation
freeway-ioc              IoC container: bind, inject, scope, advise, event-bus, extensions
freeway-boot             launcher, config cascade, profiles, runtime lifecycle
freeway-http             HTTP/WebSocket: routing, filters, static, multipart, SSE
  ├ freeway-http-robaho  zero-dep engine with WebSocket (default)
  ├ freeway-http-undertow Undertow adapter
  └ freeway-http-jetty   Jetty adapter
freeway-db               JDBC: ORM, pooling, transactions, SQL builder, migrations
  └ freeway-db-hikari    HikariCP connection pool adapter
freeway-mq-kafka         Kafka adapter for EventBus
```

Dependencies: `commons` ← `ioc` ← `boot`, `http`, `db`, `mq-kafka`. Core modules have zero external dependencies.

---

## Module — The Fundamental Building Block

`Module2` is the central organizing concept in Freeway. Every application and library expresses its composition through modules. A module is a single unit that declares: *what services I provide, what I need from others, and what I contribute to the system.* The name `Module2` avoids a naming conflict with `java.lang.Module` (JDK 9+); conceptually it is simply Freeway's Module.

```java
@FunctionalInterface
public interface Module2 {
    void bind(Binder binder);
}
```

A module does three things in its `bind()` method:

1. **Bind services** — register implementations, set scopes, configure injection
2. **Contribute extensions** — add routes, event subscribers, runtime hooks, coercion rules
3. **Compose with other modules** — passed together to the launcher, sharing a unified binding space

### The Module Contract

Modules are **self-contained** and **declarative**. They don't start work during `bind()` — they only declare what should exist. Actual initialization happens when the container resolves services or when `RuntimeHook.start()` fires. This separation is what makes Freeway testable: you can create a container with a subset of modules and verify bindings without starting servers or opening connections.

### Application Module

Every application starts with a primary module:

```java
public class AppModule implements Module2 {
    public void bind(Binder b) {
        // Bind application services
        b.bind(UserService.class).to(UserServiceImpl.class);
        b.bind(OrderService.class).to(OrderServiceImpl.class);

        // Install framework modules (chainable on Binder)
        b.install(new HttpModule())
         .install(new DbModule());

        // Contribute routes
        b.contribute(Route.class)
            .add(Route.get("/users", ctx -> ctx.sendJson(200, users())))
            .add(Route.post("/orders", (ctx, OrderRequest body) -> {
                orderService.place(body);
                ctx.sendJson(201, Map.of("status", "ok"));
            }, OrderRequest.class));

        // Contribute lifecycle hooks
        b.contribute(RuntimeHook.class)
            .add("cache.warmup", new RuntimeHook() {
                public void start(Container c) { c.get(Cache.class).warmup(); }
                public void stop(Container c) { c.get(Cache.class).close(); }
            }).before("freeway.http.server");
    }
}

// Bootstrap
AppRuntime runtime = Launcher.run(args, new AppModule());
```

### Module Composition

Modules compose by passing all of them to the launcher. The container loads them all into a shared space — bindings and extensions merge across module boundaries:

```java
// Compose framework + application modules
Launcher.run(args, new AppModule(), new HttpModule(), new DbModule());
// or via Freeway.create()
Freeway.create(new AppModule(), new HttpModule(), new DbModule());
```

Extensions from every module merge into shared contribution lists. `before()`/`after()` ordering works across module boundaries — a security filter contributed by one module can declare it runs before all routes regardless of which module contributed them. This is the key to modular, composable applications: each module contributes its piece, and the container resolves the whole.

### Library Module — The DbModule Pattern

Freeway modules follow a unique design: **the module integrates the library into the IoC container, but the library itself has zero dependency on IoC.** This is the pattern used by `freeway-db`, `freeway-mq-kafka`, and `freeway-db-hikari`:

```
freeway-db (the library)
  ├─ Database, Orm, Pool, SQL, Row, Schema — all work standalone
  └─ DbModule — the IoC integration point
```

**Standalone usage (no IoC, no Container):**

```java
PoolConfig config = PoolConfig.defaults("jdbc:h2:mem:test", "sa", "");
Database db = new DatabaseBuilder().config(config).build();
Orm orm = Orm.of(db);
orm.insert(new Post("Hello", "World"));
```

**IoC usage (with Container):**

```java
Launcher.run(args, new AppModule(), new DbModule());
// Database, Orm, Coercer are now injectable
@Inject Database db;
```

`DbModule.bind()` does the integration work: it reads `PoolConfig` from the container's config cascade, creates a `Database`, binds it as a singleton, registers it in `DatabaseHub`, and wires the `RuntimeHook` for connection pool lifecycle. But the library types (`Database`, `Orm`, `Pool`) never import anything from `freeway-ioc`. This means:

- The library can be used in non-Freeway projects.
- The library can be tested without a container.
- The library can be versioned independently.
- The integration surface is a single, auditable class (`DbModule`).

### Writing a Library Module

Follow this pattern for your own libraries:

```
my-library/
├── src/main/java/com/example/mylib/
│   ├── MyService.java           # public API — no IoC imports
│   ├── MyServiceConfig.java     # configuration record
│   └── MyLibModule.java         # IoC integration — implements Module2
```

```java
// The library types — zero IoC dependency
public class MyService {
    public MyService(MyServiceConfig config) { ... }
    public void doWork() { ... }
}

public record MyServiceConfig(String url, int timeout) {
    public static MyServiceConfig from(Container c) {
        return new MyServiceConfig(
            c.get(Coercer.class).coerce(
                c.get(AppConfig.class).get("myservice.url"), String.class),
            c.get(Coercer.class).coerce(
                c.get(AppConfig.class).get("myservice.timeout"), int.class)
        );
    }
}

// The Module — the only file that imports freeway-ioc
public class MyLibModule implements Module2 {
    public void bind(Binder b) {
        b.bind(MyServiceConfig.class).to(c -> MyServiceConfig.from(c));
        b.bind(MyService.class).to(c -> new MyService(c.get(MyServiceConfig.class)));
    }
}
```

### Config-Driven Module Selection

Modules can read config to decide what to bind. The HTTP engine selection and DB pool selection both use this pattern:

```java
// freeway.db.pool=builtin  → binds PoolDefault
// freeway.db.pool=hikari   → binds HikariPool (if HikariPoolModule is installed)
b.bind(Pool.class).to(PoolDefault.class).id("builtin");
// HikariPoolModule binds: b.bind(Pool.class).to(HikariPool.class).id("hikari").primary();
```

User sets `freeway.db.pool=hikari` in config; the primary binding resolves to `HikariPool`. No code change needed.

### Built-in Framework Modules

| Module | Purpose |
|--------|---------|
| `HttpModule` | Registers `WebServer`, `RouteIndex`, `WebSocketIndex`, `CorsFilter`, `RequestTimingFilter`. Contributes `RuntimeHook` with id `"freeway.http.server"`. |
| `DbModule` | Reads `PoolConfig` from config, creates `Database` and `Orm`, binds `Pool` (selectable via `freeway.db.pool`), registers `DatabaseHub`. |
| `HikariPoolModule` | Binds `HikariPool` as a `Pool` implementation with `id("hikari").primary()`. |
| `KafkaModule` | Creates `KafkaConfig`, binds `KafkaEventBridge` and `KafkaSubscriber`, registers `RuntimeHook` for Kafka lifecycle. |

### Module Best Practices

- **One module per library.** Don't split a library's IoC integration across multiple modules.
- **Don't start work in `bind()`.** The `bind()` method declares; `RuntimeHook.start()` activates.
- **Read config at bind time, not at class load time.** Use `@Value` / `@Symbol` or read `AppConfig` through the container.
- **Use stable ids for RuntimeHooks.** Other modules may need to order relative to yours (e.g., `"freeway.http.server"`).
- **Library types should not import IoC types.** Keep the library usable without a container.

---

## IoC Container

### Creating a Container

```java
// Direct container (tests, standalone)
Container c = Freeway.create(binder -> {
    binder.bind(MyService.class).to(MyServiceImpl.class);
});
MyService svc = c.get(MyService.class);
c.close();

// Full application (config, profiles, hooks, lifecycle)
AppRuntime runtime = Launcher.run(args, new AppModule());
Container c = runtime.container();
```

### Binding Services

```java
Freeway.create(binder -> {
    // interface → implementation
    binder.bind(Greeter.class).to(GreeterImpl.class);

    // named bindings
    binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe");
    binder.bind(PaymentGateway.class).to(PayPalGateway.class).id("paypal");

    // primary binding (injected by default when multiple exist)
    binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe").primary();

    // instance binding (must be SINGLETON)
    binder.bind(Config.class).to(new Config(...));

    // provider binding
    binder.bind(Cache.class).to(c -> new Cache(c.get(Config.class)));

    // scope
    binder.bind(RequestState.class).to(RequestState.class).scope(Scope.THREAD);

    // advice (requires interface binding)
    binder.bind(UserService.class).to(UserServiceImpl.class).advise(advisor ->
        advisor.wrap(inv -> inv.method().getName().startsWith("get"),
                     inv -> { /* before/after logic */ return inv.proceed(); }));
});
```

### Resolution

```java
// by type (requires unique or primary binding)
Greeter g = container.get(Greeter.class);

// by type + id
PaymentGateway pg = container.get(PaymentGateway.class, "stripe");

// Container also available as a service
Container c = container.get(Container.class);
```

### Scopes

| Scope | Behavior |
|-------|----------|
| `SINGLETON` | Default. One instance per container. Destroyed on close. |
| `PROTOTYPE` | New instance every resolution. Not retained. |
| `THREAD` | One instance per `Scoping.within()` boundary. Auto-destroyed on exit. |

```java
binder.bind(RequestState.class).to(RequestState.class).scope(Scope.THREAD);

Scoping scoping = container.get(Scoping.class);
scoping.within(() -> {
    RequestState state = container.get(RequestState.class);
    // same instance reused within this block
});
// state destroyed — @PreDestroy + AutoCloseable invoked
```

`Scoping.within()` uses JDK 25 `ScopedValue`, so there is no `ThreadLocal` overhead on virtual threads. Nesting is supported — inner scopes shadow outer scopes.

**Scope compatibility rule:** A singleton cannot directly inject a thread-scoped concrete class. Use an interface with proxy support instead:

```java
// OK — singleton injects interface, gets a lazy proxy
binder.bind(ScopedApi.class).to(ScopedCounter.class).scope(Scope.THREAD);
binder.bind(ScopedSingletonService.class).to(ScopedSingletonService.class);

// WRONG — singleton injects concrete thread-scoped class
binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD);
binder.bind(ScopedSingleton.class).to(ScopedSingleton.class); // fails
```

### Injection

Supported annotations in `com.jujin.freeway.ioc.annotation`:

| Annotation | Purpose |
|------------|---------|
| `@Inject` | Field, constructor, or parameter injection |
| `@Named("id")` | Qualify by binding id |
| `@Symbol("key")` | Strict config lookup — missing key fails |
| `@Value("${key:default}")` | Config expression with optional default |

```java
public class UserService {
    private final UserRepository repo;

    // constructor injection (preferred for framework internals)
    public UserService(UserRepository repo) {
        this.repo = repo;
    }

    // field injection (acceptable for app code)
    @Inject
    private Logger log;

    @Inject("audit")
    private Logger audit;

    @Value("${app.timeout:30}")
    private int timeout;
}
```

`Logger` injection is owner-aware: without an explicit id, the logger name is the declaring class. `@Inject("name")` uses the explicit name.

### Extensions

Extensions are ordered contribution lists keyed by entry type:

```java
// Module: contribute
binder.contribute(Route.class).add(Route.get("/hello", ctx -> ctx.send(200, "hi")));

// named contributions with ordering
binder.contribute(RuntimeHook.class)
    .add("cache", hook).after("freeway.http.server");

binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(OrderCreated.class, e -> notify(e)))
    .after("audit");

// Injection
@Inject Extension<Route> routes;
routes.all().forEach(r -> ...);
```

Rules: `add(value)` preserves insertion order. `add(id, value)` enables `before/after` ordering. Duplicate ids fail. Missing order targets are ignored. Cycles fail at resolution time.

### EventBus

In-process pub-sub built on the Extension mechanism.

```java
// Module-level subscribers (startup-time, supports ordering)
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(PostCreatedEvent.class, e -> index(e.post())))
    .add(EventSubscriber.of("notify", PostCreatedEvent.class, e -> sendEmail(e)))
    .after("index");

// String-topic subscribers (no event class needed)
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of("order.placed", payload -> process(payload)));

// Runtime subscribers
@Inject EventBus bus;
Subscription<PostCreatedEvent> sub = bus.subscribe(PostCreatedEvent.class, e -> { ... });
bus.unsubscribe(sub);

// Publish
bus.publish(new PostCreatedEvent(post));    // class-based
bus.publish("order.placed", payload);       // string-topic
bus.publishAsync(new PostCreatedEvent(post)); // fire-and-forget (virtual threads)
```

**Transaction-aware:** Events published inside a `db.transaction()` are automatically deferred and fire only after commit. No manual wiring needed.

**Dead events:** When zero subscribers exist for an event, a `DeadEvent` is published — subscribe to it for diagnostics.

**Lifecycle events:** Boot publishes `AppStartedEvent` (after start) and `AppStoppingEvent` (before shutdown). Subscribe instead of implementing `RuntimeHook` for non-critical work.

**Short-circuit:** Events implementing `EventBus.Stoppable` can `stop()` the subscriber chain — later subscribers are skipped.

**Cross-JVM:** Add `@Topic("kafka.topic")` on an event class + `KafkaModule` for distributed pub/sub via the `EventBridge` mechanism.

---

## Boot

```java
AppRuntime runtime = Launcher.run(args, new AppModule());
// or with explicit module instance
AppRuntime runtime = Launcher.run(args, new AppModule());
```

`AppRuntime` provides:
- `container()` — the IoC container
- `config()` — merged configuration
- `state()` — `CREATED → STARTING → RUNNING → STOPPING → STOPPED` (or `FAILED`)
- `start()` / `close()` — lifecycle control
- `get(Class)` / `get(Class, String)` — convenience shortcuts

### Runtime Hooks

Modules that own runtime resources contribute `RuntimeHook`:

```java
binder.contribute(RuntimeHook.class)
    .add("my.cache", new RuntimeHook() {
        public void start(Container c) { c.get(Cache.class).warmup(); }
        public void stop(Container c) { c.get(Cache.class).close(); }
    }).before("freeway.http.server");
```

Startup invokes hooks in contribution order. Shutdown invokes only started hooks in reverse order, then closes the container.

### Config Cascade

Priority high → low:

1. CLI args (`--key=value`, `-Dkey=value`)
2. Environment variables (`FREEWAY_` prefix)
3. `application-{profile}.json`
4. `application-{profile}.properties`
5. `application.json`
6. `application.properties`

Activate profiles: `--freeway.profile=dev` or `-Dfreeway.profile=dev`.

---

## HTTP

### Routes

```java
// Module contribution
binder.contribute(Route.class)
    .add(Route.get("/", ctx -> ctx.send(200, "Hello")))
    .add(Route.get("/users/:id", ctx -> {
        String id = ctx.pathVar("id");
        ctx.sendJson(200, userService.findById(id));
    }))
    .add(Route.post("/users", (ctx, body) -> {
        // auto-deserialized from JSON
        User created = userService.create(body);
        ctx.sendJson(201, created);
    }, User.class));

// Route groups
binder.contribute(RouteGroup.class)
    .add(RouteGroup.of("/api/v1",
        Route.get("/users", ctx -> ctx.sendJson(200, users())),
        Route.get("/users/:id", ctx -> ctx.sendJson(200, user(ctx.pathVar("id"))))
    ));
```

### Request Context

```java
ctx.method()            // "GET", "POST", etc.
ctx.path()              // "/users/42"
ctx.pathVar("id")       // "42"
ctx.queryParam("q")     // query string parameter
ctx.header("Accept")    // request header
ctx.body()              // raw bytes
ctx.bodyText()          // UTF-8 string
ctx.bodyAsJson(User.class)  // deserialize JSON to object
ctx.param("name")       // pathVar → queryParam → body field (convenience)
ctx.requestContext().correlationId()  // unique request id
```

### Response

```java
ctx.status(201);
ctx.headerSet("X-Custom", "value");
ctx.send(200, "plain text");
ctx.sendJson(200, object);
ctx.output("text".getBytes());
ctx.output("text");  // UTF-8 convenience
```

### Filters

```java
// Implement HttpFilter
public class AuthFilter implements HttpFilter {
    public void doFilter(HttpContext ctx, RouteHandler next) throws Exception {
        if (ctx.header("Authorization") == null) {
            ctx.send(401, "Unauthorized");
            return;
        }
        next.handle(ctx);
    }
}

// Register in module
binder.contribute(HttpFilter.class).add(new AuthFilter());
```

Built-in filters: `RequestTimingFilter` (logs request duration), `CorsFilter` (configurable CORS via `freeway.http.cors.*` keys).

### Static Resources

```java
binder.contribute(StaticResourceMount.class)
    .add(StaticResources.classpath("/", "/public"))     // from classpath
    .add(StaticResources.directory("/uploads", Path.of("/var/uploads")));  // from filesystem

// Options
StaticResources.classpath("/assets", "/static")
    .cacheMaxAgeSeconds(3600)
    .immutable(true)            // sets Cache-Control: immutable
    .fallthrough(true);         // pass to next handler on 404
```

### Multipart

```java
if (ctx.isMultipart()) {
    MultipartForm form = ctx.multipart();
    String title = form.value("title");
    Part file = form.file("attachment").orElseThrow();
    file.saveTo(Path.of("/uploads", file.filename()));
}
```

### SSE (Server-Sent Events)

```java
Route.get("/events", ctx -> {
    SseEmitter sse = ctx.sse();
    sse.send("connected");
    sse.send(new SseEvent("data", "msg-1", "update", null));
    sse.complete();
});
```

### WebSocket

```java
// Route
binder.contribute(WebSocketRoute.class)
    .add(WebSocketRoute.of("/ws/chat", session -> new WebSocketListener() {
        public void onText(String text) {
            session.sendText("Echo: " + text);
        }
        public void onClose(int code, String reason, boolean remote) {
            log.info("Closed: {}", reason);
        }
    }));

// Group
binder.contribute(WebSocketGroup.class)
    .add(WebSocketGroup.of("/ws", WebSocketRoute.of("/chat", listener)));
```

### Exception Mapping

```java
binder.contribute(ExceptionMapper.class).add((ctx, ex) -> {
    if (ex instanceof NotFoundException) {
        ctx.sendJson(404, Map.of("error", ex.getMessage()));
        return true;  // handled
    }
    return false;     // not handled, continues to next mapper
});
```

### Engine Selection

Set `web.engine` config property:

| Value | Engine |
|-------|--------|
| `robaho` (default) | Zero-dep, WebSocket |
| `jdk` | Built-in JDK, HTTP only |
| `undertow` | Undertow adapter |
| `jetty` | Jetty adapter |

### Testing with HTTP

When using `Container` directly (not `Launcher`), start the server explicitly:

```java
WebServer server = container.get(WebServer.class);
server.start();
try {
    // HTTP calls
} finally {
    server.stop();
}
```

---

## Database

### Standalone Usage

`freeway-db` works without IoC:

```java
PoolConfig config = PoolConfig.defaults("jdbc:h2:mem:test", "sa", "");
Database db = new DatabaseBuilder().config(config).build();
Orm orm = Orm.of(db);
```

### IoC Usage

```java
Launcher.run(args, new AppModule(), new DbModule());
// Database and Orm are now injectable
```

### Queries

```java
// Positional params
List<User> users = db.query("SELECT * FROM users WHERE active = ?", true)
    .list(User.class);

// Named params (:$name or :name)
User u = db.query("SELECT * FROM users WHERE id = :id")
    .param("id", 42)
    .one(User.class)
    .orElseThrow();

// Stream (requires try-with-resources)
try (var stream = db.query("SELECT * FROM big_table").stream(Row.class)) {
    stream.forEach(row -> process(row));
}

// Execute (INSERT/UPDATE/DELETE)
ExecuteResult r = db.execute("INSERT INTO users (name) VALUES (?)", "Alice");
r.rows();   // affected rows
r.key();    // generated key (may be null)
```

### Row Mapping

Auto-mapping for records, beans, and basic types (`String`, `Long`, `Integer`, `UUID`, `BigDecimal`, `LocalDate`, `LocalDateTime`, etc.).

```java
// Raw Row access
List<Row> rows = db.query("SELECT * FROM t").list(Row.class);
Row r = rows.get(0);
r.string("name");
r.decimal("amount");
r.dateTime("created_at");

// Custom mapper
binder.contribute(RowMapping.class)
    .add(new RowMapping(MyType.class, (rs, rowNum) -> new MyType(...)));
```

### SQL Builder

```java
SQL sql = SQL.select("u.name, o.total")
    .from("users u")
    .join("orders o").on("u.id = o.user_id")
    .where("u.active = ?", true)
    .orderBy("o.total DESC")
    .limit(10);

db.query(sql).list(UserOrderView.class);
```

Supports: `SELECT`, `INSERT`, `UPDATE`, `DELETE`, CTEs (`WITH`), `JOIN`/`LEFT JOIN`/`INNER JOIN`, `ON`, `WHERE`/`OR WHERE`/`WHERE NOT`, grouped conditions, `GROUP BY`/`HAVING`, `ORDER BY`, `LIMIT`/`OFFSET`, `UNION`/`UNION ALL`, `RETURNING`, `ON CONFLICT DO UPDATE/NOTHING`.

### Transactions

```java
// Basic
db.transaction(() -> {
    db.execute("UPDATE ledger SET amount = amount + ? WHERE id = ?", 100L, 1L);
    db.execute("INSERT INTO audit_log (msg) VALUES (?)", "transfer");
});

// Isolation level
db.transaction(IsolationLevel.SERIALIZABLE, () -> {
    db.query("SELECT ...").list(User.class);
});
```

`ScopedValue`-based — queries inside the transaction automatically use the same connection. Nested transactions are rejected. Auto-commit is restored on exit. `bus.publish()` inside a transaction is automatically deferred until commit.

### ORM

```java
Orm orm = Orm.of(db);

// Entity
@Table("posts")
public class Post {
    @Id @Generated
    private Long id;
    private String title;
    @Column("created_at")
    private LocalDateTime createdAt;
}

// CRUD
orm.insert(post);                     // auto-increment id written back
orm.findById(Post.class, 1L);         // Optional<Post>
orm.findAll(Post.class);              // List<Post>
orm.findAll(Post.class, "created_at DESC", 20, 0);  // with pagination
orm.save(post);                       // upsert
orm.update(post);
orm.delete(post);
orm.deleteById(Post.class, 1L);
```

Annotations: `@Table`, `@Column`, `@Id`, `@Generated`, `@Transient`, `@Index`.

### Connection Pool

```java
// Built-in pool (PoolDefault)
PoolConfig config = PoolConfig.defaults(url, user, pass);

// Or with custom sizing
new PoolConfig(url, user, pass,
    20,     // maxSize
    5,      // minIdle
    Duration.ofSeconds(30),   // connectionTimeout
    Duration.ofMinutes(30),   // maxLifetime
    Duration.ofMinutes(10),   // maxIdleTime
    Duration.ofMinutes(2),    // cleanInterval
    null,                     // healthCheckQuery (uses JDBC isValid)
    Duration.ofSeconds(5),    // healthCheckTimeout
    Duration.ofSeconds(15)    // queryTimeout
);

// HikariCP
Pool pool = new HikariPool(config);
Database db = new DatabaseBuilder().config(config).pool(pool).build();

// IoC: set freeway.db.pool=hikari + add HikariPoolModule
Launcher.run(args, new AppModule(), new DbModule(), new HikariPoolModule());
```

### Batch

```java
List<ExecuteResult> results = db.batch("INSERT INTO t (a, b) VALUES (?, ?)")
    .rows(new Object[]{1, "a"}, new Object[]{2, "b"})
    .execute();
```

### Schema & Migrations

```java
// AutoMigrate from entity classes
Schema.ensure(database, User.class, Post.class);

// SQL-based migrations (files in db/migration/)
// V001__create_users.sql, V002__add_email.sql, ...
MigrationRunner runner = new MigrationRunner(database, true, "db/migration", "schema_version");
runner.run();
```

### DatabaseHub

```java
binder.contribute(DatabaseNamed.class)
    .add(new DatabaseNamed("audit", auditDb))
    .add(new DatabaseNamed("primary", mainDb));

DatabaseHub hub = container.get(DatabaseHub.class);
Database primary = hub.primary();
Database audit = hub.get("audit");
```

---

## Kafka (`freeway-mq-kafka`)

```java
Launcher.run(args, new AppModule(), new KafkaModule());
```

Config in `application.properties`:

```properties
freeway.kafka.bootstrap-servers=localhost:9092
freeway.kafka.group-id=my-app
freeway.kafka.topics=post.created,order.placed
```

**Sending:** EventBus automatically bridges to Kafka when an `EventBridge` is configured:

```java
@Topic("post.created")
public record PostCreatedEvent(Long postId, String title) {}

bus.publish(new PostCreatedEvent(1L, "Hello"));
// → local subscribers + Kafka broker
```

**Receiving:** `KafkaSubscriber` polls Kafka and publishes to local EventBus. Messages carry an `X-Event-Type` header for automatic type deserialization.

---

## Commons Utilities

### JSON

```java
// Parse
JsonObject obj = JsonUtils.parseObject("{\"name\":\"Alice\"}");
JsonArray arr = JsonUtils.parseArray("[1, 2, 3]");

// Build
JsonObject o = JsonUtils.object()
    .put("name", "Alice")
    .object("address").put("city", "NYC");

// Serialize
String json = JsonUtils.stringify(obj);
String pretty = JsonUtils.stringifyPretty(obj);

// Codec (injectable)
@Inject JsonCodec codec;
String json = codec.toJson(user);
User u = codec.fromJson(json, User.class);
```

### Coercion

```java
// Built-in: String → primitives, enums, UUID, dates, collections, maps
Coercer coercer = container.get(Coercer.class);
int port = coercer.coerce("8080", int.class);

// Custom rule
binder.contribute(CoerceRule.class).add(new CoerceRule<>(
    String.class, MyType.class, value -> MyType.parse(value)
));
```

### Validation

```java
public class CreateUserRequest {
    @NotBlank
    private String name;

    @NotNull @Size(min = 1, max = 150)
    private Integer age;

    @Valid
    private Address address;
}

ValidationResult result = BeanValidator.validate(request);
if (result.hasErrors()) {
    result.getErrors().forEach(e ->
        log.warn("{}: {}", e.field(), e.message()));
}
```

Annotations: `@NotNull`, `@NotBlank`, `@Size(min, max)`, `@Min`, `@Max`, `@Valid` (recursive).

### Defer — Scope-bound Deferred Execution

"Run this side effect only after the current boundary commits. If it rolls back, forget it."

```java
// Inside a Defer scope
Defer.within(() -> {
    db.execute("UPDATE ...");
    Defer.defer(() -> cache.invalidate("key"));   // runs after commit
    Defer.defer("index", () -> rebuildIndex())    // ordered
        .after("cache");
});
// cache invalidated → index rebuilt

// Outside scope — runs immediately
Defer.defer(() -> log.info("done"));

// Deferred value (computed at commit time)
Supplier<Snapshot> snap = Defer.supply(() -> buildSnapshot());
```

Built-in framework scopes (no setup needed):

| Boundary | Commit = | Rollback = |
|----------|----------|------------|
| `db.transaction()` | SQL commit succeeds | Work throws |
| HTTP request (`WebServer`) | Request completes | Filter chain throws |
| Kafka record (`KafkaSubscriber`) | Record publishes successfully | Deserialization fails |

### ScopedCache — Scope-bound Value Cache

"Cached within a scope, cleaned up on exit."

```java
ScopedCache.within(() -> {
    Connection conn = ScopedCache.get("db", () -> dataSource.getConnection());
    // same key → reused within scope
});
// conn closed on exit

// Register cleanup handlers
ScopedCache.onClose(v -> { if (v instanceof AutoCloseable c) c.close(); });
```

IoC uses `ScopedCache` internally for thread-scoped service caching. `@PreDestroy` and `AutoCloseable` lifecycle are registered as global cleanup handlers.

---

## Testing

```java
// IoC tests
Container c = Freeway.create(binder -> {
    binder.bind(MyService.class).to(MyServiceImpl.class);
});
MyService svc = c.get(MyService.class);
// ... test ...
c.close();

// HTTP tests (in-process)
WebServer server = container.get(WebServer.class);
server.start();
try {
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + server.port() + "/api/test"))
            .GET().build(),
        BodyHandlers.ofString());
    assertEquals(200, resp.statusCode());
} finally {
    server.stop();
}

// DB tests (H2 in-memory)
PoolConfig config = PoolConfig.defaults("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "");
Database db = new DatabaseBuilder().config(config).build();
Schema.ensure(db, TestEntity.class);
```

---

## Key Design Rules

- **No classpath scanning, no bytecode weaving.**
- **Constructor injection** for framework internals; **field injection** acceptable for app code and config values.
- **`XDefault` naming** for framework default implementations (`JsonCodecDefault`, `CoercerDefault`).
- **`Impl` suffix** only for uninteresting concrete implementations.
- **Core modules have zero external dependencies.** Adapter modules are the exception.
- **Keep concepts few:** Module, Service, Extension, Scope, Runtime.
- **`Scoping.within()`** uses JDK 25 `ScopedValue` — no `ThreadLocal` overhead on virtual threads.
