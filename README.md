# Freeway 2

**A brand-new, modern, lightweight Java application framework built on JDK 25+.**

Zero classpath scanning. Compose-first API. No magic.

```
freeway-commons        — minimal shared utilities, JSON, SLF4J-over-JUL provider
freeway-ioc            — lightweight IoC container (bind, inject, coerce, advise)
freeway-boot           — application launcher, config, profiles, lifecycle
freeway-http           — thin HTTP/WebSocket layer (routing, filters, static, multipart)
  freeway-http-jdk          — JDK built-in HttpServer engine
  freeway-http-robaho       — zero-dep HTTP engine with WebSocket (default)
  freeway-http-undertow     — Undertow transport adapter
  freeway-http-jetty        — Jetty transport adapter
freeway-db             — JDBC data access (pooling, transactions, migrations)
freeway-starter        — all-in-one dependency bundle
```

### Philosophy

Freeway 2 is a **compose-first** framework. Instead of scanning the classpath, you explicitly wire modules together:

```java
Freeway.create(
    binder -> binder.bind(Greeter.class).to(GreeterImpl.class),
    binder -> binder.bind(Store.class).to(Store.class)
);
```

This gives you:
- **Fast startup** — no bytecode scanning, no reflection-heavy discovery
- **Total control** — every binding is explicit, every dependency is visible
- **Small footprint** — core modules have **zero external dependencies**

---

## Quick Start

```java
// 1. Define an application module
public class AppModule implements Module {
    @Override
    public void bind(Binder binder) {
        binder.bind(Greeter.class).to(GreeterImpl.class);
    }
}

// 2. Boot it
App app = Launcher.run(AppModule.class, args);
Greeter greeter = app.container().get(Greeter.class);
System.out.println(greeter.greet("World"));
app.close();
```

Or compose inline without a class:

```java
Container container = Freeway.create(
    binder -> binder.bind(Greeter.class).to(GreeterImpl.class)
);
```

---

## Build

Requires **JDK 25**.

```bash
# full build + tests
mvn test

# focused module
mvn -pl freeway-ioc test
mvn -pl freeway-http -am test
mvn -pl freeway-db -am test
```

---

## Modules at a Glance

### IoC (`freeway-ioc`)

The heart of the framework. A container that binds interfaces to implementations with:

- **Service binding** — `binder.bind(X.class).to(Y.class)`
- **Named services** — `.id(ServiceId.of("primary"))`
- **Primary resolution** — `.primary()` for default-choice bindings
- **Scopes** — `Scope.SINGLETON` (default), `Scope.PROTOTYPE`
- **Symbol injection** — `@Symbol("app.name") int port` for config-driven values
- **Value expansion** — `@Value("${server.port}")` with `${...}` placeholder syntax
- **Type coercion** — `String → int/boolean/enum/Duration/Endpoint/...`
- **Extension points** — `binder.contribute(X.class).add(...)` for plugin-style aggregation
- **Advisors (AOP)** — `binder.bind(X.class).to(Y.class).advise(...)` for method interception

### Boot (`freeway-boot`)

Application lifecycle management:

- **`Launcher.run()`** — parses CLI args, loads config, discovers modules via SPI, starts the app
- **Shutdown hook** — JVM shutdown hook auto-registered to close the `App` gracefully
- **Startup timing** — logs elapsed startup time on console
- **`App`** — owns config, profiles, and lifecycle; exposes the `Container`
- **Profiles** — `--freeway.profile=dev` activates profile-specific config
- **Config providers** — properties files, JSON, environment, system properties, CLI args

### HTTP (`freeway-http`)

A thin HTTP layer with:

- **Routing** — explicit route registration via `Route` and `RouteGroup` extensions
  - Trie-based route index for O(L) matching (L = path segment count)
  - Path parameters with optional regex constraints (`{id:\\d+}`) and wildcards (`{path:.*}`)
- **Request body binding** — `Route.post(path, BodyType.class, handler)` auto-deserializes and validates
- **Server-Sent Events (SSE)** — `HttpContext.sse()` returns a `SseEmitter` for streaming events
- **Filters** — `HttpFilter` chain for request/response interception
- **Exception mapping** — map exceptions to HTTP responses (including `ValidationException`)
- **Static resources** — serve files from classpath or filesystem
- **Multipart upload** — file upload handling
- **WebSocket** — `WebSocketListener` with open/text/binary/close/error callbacks
- **JSON** — built-in codec, no Jackson/Gson required
- **CORS** — configurable cross-origin support
- **Pluggable engines** — JDK (built-in), Robaho (default, zero-dep + WebSocket), Undertow, or Jetty

Switch engines with a single config:

```properties
web.engine=robaho   # default (WebSocket support)
web.engine=jdk      # JDK built-in (zero-dep, HTTP only)
web.engine=undertow
web.engine=jetty
```

### DB (`freeway-db`)

Minimal JDBC data access layer:

- **`Database`** — core interface for SQL execution
- **Streaming queries** — `Query.stream()` returns a lazy `Stream<T>`, hold connection until closed
- **Connection pooling** — built-in pool, no HikariCP required, with leak detection
- **Transactions** — programmatic transaction control
- **Row mappers** — map result sets to records/POJOs with cached column lookup
- **Named parameters** — `:name` or `$name` syntax with Collection auto-expansion
- **Migrations** — file-based schema migration (SQL files in `db/migration/`)
- **`DatabaseHub`** — multi-datasource routing

---

## Configuration

Configuration flows in a layered cascade (later overrides earlier):

1. Environment variables (`SERVER_PORT=9090`)
2. System properties (`-Dserver.port=9090`)
3. Config files (`application.properties`, `application-{profile}.json`)
4. CLI arguments (`--server.port=9090`)

---

## License

[Apache 2.0](LICENSE)
