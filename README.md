# Freeway 2

**A brand-new, modern, lightweight Java application framework built on JDK 25+.**

Zero classpath scanning. Compose-first API. No magic.

```
freeway2-commons        — minimal shared utilities, JSON, SLF4J-over-JUL provider
freeway2-ioc            — lightweight IoC container (bind, inject, coerce, advise)
freeway2-boot           — application launcher, config, profiles, lifecycle
freeway2-web            — thin HTTP/WebSocket layer (routing, filters, static, multipart)
  freeway2-web-engine-robaho   — zero-dep HTTP engine (default)
  freeway2-web-engine-undertow — Undertow transport adapter
  freeway2-web-engine-jetty    — Jetty transport adapter
freeway2-db             — JDBC data access (pooling, transactions, migrations)
```

### Philosophy

Freeway 2 is a **compose-first** framework. Instead of scanning the classpath, you explicitly wire modules together:

```java
Freeway2.create(
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
Container container = Freeway2.create(
    binder -> binder.bind(Greeter.class).to(GreeterImpl.class)
);
```

---

## Build

Requires **JDK 25** (preview features enabled).

```bash
# full build + tests
mvn test

# focused module
mvn -pl freeway2-ioc test
mvn -pl freeway2-web -am test
mvn -pl freeway2-db -am test
```

---

## Modules at a Glance

### IoC (`freeway2-ioc`)

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

### Boot (`freeway2-boot`)

Application lifecycle management:

- **`Launcher.run()`** — parses CLI args, loads config, discovers modules via SPI, starts the app
- **`App`** — owns config, profiles, and lifecycle; exposes the `Container`
- **Profiles** — `--freeway.profile=dev` activates profile-specific config
- **Config providers** — properties files, JSON, environment, system properties, CLI args

### Web (`freeway2-web`)

A thin HTTP layer with:

- **Routing** — explicit route registration via `RouteRegistry`
- **Filters** — `HttpFilter` chain for request/response interception
- **Exception mapping** — map exceptions to HTTP responses
- **Static resources** — serve files from classpath or filesystem
- **Multipart upload** — file upload handling
- **WebSocket** — `WebSocketListener` with open/text/binary/close/error callbacks
- **JSON** — built-in codec, no Jackson/Gson required
- **CORS** — configurable cross-origin support
- **Pluggable engines** — swap between Robaho (default, zero-dep), Undertow, or Jetty

Switch engines with a single config:

```properties
web.engine=undertow
# or
web.engine=jetty
```

### DB (`freeway2-db`)

Minimal JDBC data access layer:

- **`Database`** — core interface for SQL execution
- **Connection pooling** — built-in pool, no HikariCP required
- **Transactions** — programmatic transaction control
- **Row mappers** — map result sets to records/POJOs
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
