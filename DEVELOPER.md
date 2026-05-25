# Freeway 2 Developer Guide

## Module Dependency Graph

```
freeway-commons
      ↑
freeway-ioc
      ↑               ↑               ↑
freeway-boot    freeway-web    freeway-db
      ↑               ↑
      └─── engine adapters ───┘
    freeway-web-engine-robaho
    freeway-web-engine-undertow
    freeway-web-engine-jetty
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

// With explicit configuration
App app = Launcher.run(MyAppModule.class, new LauncherConfig()
    .withDiscoveredModules(true));
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

## Web Layer (`freeway-web`)

### Routing

Routes are registered via `RouteRegistry` (available in the container):

```java
RouteRegistry routes = container.get(RouteRegistry.class);
routes.get("/hello", ctx -> ctx.sendText("Hello!"));
routes.post("/api/users", ctx -> {
    User user = ctx.bodyJson(User.class);
    ctx.sendJson(201, user);
});
routes.staticResources("/static", "/public");
```

### WebSocket

```java
routes.webSocket("/chat", session -> new WebSocketListener() {
    @Override
    public void onOpen(WebSocketSession session) {
        System.out.println("Connected: " + session.path());
    }

    @Override
    public void onText(String text) {
        session.sendText("Echo: " + text);
    }
});
```

### Filters & Exception Mappers

```java
// Global filter
binder.contribute(HttpFilter.class).add((ctx, chain) -> {
    long start = System.nanoTime();
    chain.doFilter(ctx);
    long elapsed = System.nanoTime() - start;
    ctx.header("X-Response-Time", String.valueOf(elapsed / 1_000_000));
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

### Engine Selection

Set `web.engine` in config:

| Value | Engine | Dependencies |
|-------|--------|-------------|
| `robaho` (default) | Robaho HTTP Server | Zero external deps |
| `undertow` | Undertow 2.3 | `undertow-core` |
| `jetty` | Jetty 12.1 | `jetty-server` + `jetty-websocket-server` |

The engine adapter implements the `WebServer` SPI. Each adapter module registers itself via `ServiceLoader`.

---

## DB Layer (`freeway-db`)

### Database Interface

```java
Database db = container.get(Database.class);

// Query
List<User> users = db.sql("SELECT * FROM users WHERE active = ?")
    .param(true)
    .query(User.class);

// Single result
User user = db.sql("SELECT * FROM users WHERE id = ?")
    .param(42)
    .queryOne(User.class);

// Update
int rows = db.sql("UPDATE users SET name = ? WHERE id = ?")
    .param("Alice", 42)
    .update();

// Batch
int[] counts = db.sql("INSERT INTO log (msg) VALUES (?)")
    .batch()
    .param("msg1")
    .param("msg2")
    .execute();
```

### Named Parameters

SQL uses `:name` or `#name` syntax for named placeholders, with values supplied via `.param(name, value)`:

```java
// Named parameters — use :name or #name in SQL
List<User> users = db.sql("SELECT * FROM users WHERE name = :name AND active = #active")
    .param("name", "Alice")
    .param("active", true)
    .list(User.class);

// Mixed with Collection expansion (IN clause)
List<User> users = db.sql("SELECT * FROM users WHERE id IN (:ids)")
    .param("ids", List.of(1, 2, 3))
    .list(User.class);
```

> **Note**: Named and positional parameters cannot be mixed. Once you use `.param(name, value)`, all placeholders must be named.

Batch queries also support named parameters:

```java
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
    tx.sql("UPDATE accounts SET balance = balance - 100 WHERE id = ?").param(1).update();
    tx.sql("UPDATE accounts SET balance = balance + 100 WHERE id = ?").param(2).update();
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
Database analytics = hub.database("analytics");
analytics.sql("SELECT COUNT(*) FROM events").queryOne(Long.class);
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
mvn -pl freeway-web -am test
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

## Adding a New Web Engine Adapter

1. Create module `freeway-web-engine-{name}`
2. Depend on `freeway-web`
3. Implement `WebServer` interface
4. Register implementation in `META-INF/services/com.jujin.freeway.ioc.Module`
5. Engine picks up automatically by setting `web.engine={name}`

---

## Code Style

- Java 25 with preview features (`--enable-preview`)
- No external dependencies for core modules (commons, ioc, boot, web, db)
- Explicit over implicit — no annotation scanning, no bytecode manipulation
- `compose-first` — wire everything in `Module.bind(Binder)`
