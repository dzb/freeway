# Freeway Framework

<div align="left">
  <img width="96" src=".github/assets/logo.png" alt="Freeway Framework Logo">
  <p>Compose-First · Full-Featured · Zero-Dependency · High-Performance · Agentic-Friendly</p>
</div>

[![Maven Central](https://img.shields.io/maven-central/v/com.jujin8.freeway/freeway-parent?label=Maven%20Central&color=blue)](https://central.sonatype.com/artifact/com.jujin8.freeway/freeway-parent)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-25%2B-orange)](https://jdk.java.net/25/)
[![Dependencies](https://img.shields.io/badge/deps-0-brightgreen)]()

**A concise, compose-first Java application framework for JDK 25+.** IoC, HTTP,
DB, Flow and Cloud — one coherent design, zero classpath scanning, and no
dependencies beyond the SLF4J API.

Freeway is an exercise in engineering taste: every API should state its intent,
every concept should pull its weight, and a framework should be **complete
without being bloated**.

> **Latest release: 1.5.6** · **Main branch: 1.5.7-SNAPSHOT**
> The examples below follow `main`; see [CHANGELOG.md](CHANGELOG.md) for the
> 1.5.7 changes.

## Why Freeway

- **Compose-first.** Modules are placed explicitly; there is no classpath scan,
  no bytecode weaving, and no hidden wiring.
- **One way to say it.** One container, one module concept, one config cascade,
  one lifecycle — small vocabulary, sharp boundaries.
- **Zero dependencies.** The core is built on the JDK plus the SLF4J API.
- **Whole-stack coherence.** IoC, HTTP, DB, Flow and Cloud are designed
  together and share the same conventions.
- **Agent-friendly.** A small, explicit API surface is easier to read, generate
  and review — by humans and by coding agents.

## Quick Start

Add the modules you need. The examples use the current `main`
(`1.5.7-SNAPSHOT`); the latest published release is `1.5.6`.

```xml
<dependency>
    <groupId>com.jujin8.freeway</groupId>
    <artifactId>freeway-boot</artifactId>
    <version>${freeway.version}</version>
</dependency>
```

```java
public final class AppModule implements ModuleEx {
    @Override
    public void bind(Binder binder) {
        binder.bind(Greeter.class).to(GreeterImpl.class);
    }
}

try (AppRuntime runtime = FreewayApp.run(new AppModule())) {
    Greeter greeter = runtime.get(Greeter.class);
    System.out.println(greeter.greet("World"));
}
```

Command-line arguments and discovery are one value object:

```java
FreewayApp.run(Launch.of("--freeway.profile=dev"), new AppModule());
```

Without boot, build just the container:

```java
try (Container container = Freeway.create(
        binder -> binder.bind(Greeter.class).to(GreeterImpl.class))) {
    Greeter greeter = container.get(Greeter.class);
}
```

### A Web App

Add `freeway-http` and contribute routes; `FreewayApp` starts and stops the
server through the runtime lifecycle.

```java
public final class WebApp implements ModuleEx {
    @Override
    public void bind(Binder binder) {
        binder.contribute(Route.class)
            .add(Route.get("/", ctx -> ctx.send(200, "Hello Freeway")))
            .add(Route.get("/users/{id}", ctx -> ctx.sendJson(200, Map.of(
                "id", ctx.pathVar("id").orElse(""),
                "name", "Alice"
            ))));
    }

    public static void main(String[] args) {
        FreewayApp.run(Launch.of(args), new HttpModule(), new WebApp());
    }
}
```

```bash
curl http://localhost:8080/           # Hello Freeway
curl http://localhost:8080/users/42   # {"id":"42","name":"Alice"}
```

A runnable REST + DB sample with validation, ORM, transactions and a fat-jar
smoke path lives in [`demo/rest-db`](demo/rest-db/README.md).

## Modules

| Module | Purpose |
|---|---|
| **`freeway-commons`** | JSON, coercion, validation, scoped primitives, logging |
| **`freeway-ioc`** | Container, binding DSL, injection, scopes, contributions, AOP, EventBus |
| **`freeway-boot`** | Launcher, config cascade, profiles, runtime lifecycle |
| **`freeway-http`** | Routing, filters, static resources, multipart, SSE, WebSocket |
| **`freeway-db`** | JDBC, ORM, pooling, transactions, schema, migrations |
| **`freeway-flow`** | Graph workflow engine — 7 node types, v3 DAG format, `@name` tasks |
| **`freeway-cloud`** | Discovery, RPC, event mesh, observability, resilience, health, secrets, storage |

Third-party adapters live in **[freeway-ext](https://github.com/dzb/freeway-ext)**:

| Module | Purpose |
|---|---|
| **`freeway-http-undertow`** | Undertow engine adapter |
| **`freeway-http-jetty`** | Jetty 12 engine adapter |
| **`freeway-db-hikari`** | HikariCP pool adapter |
| **`freeway-cloud-consul`** | Consul registry/discovery adapter |
| **`freeway-mq-kafka`** | Kafka durable event plane (`KafkaEvents`) |

## What You Get

### Commons
`JsonCodec`/`JsonUtils` for JSON, `Coercer` with contributed rules,
`BeanIntrospector`/`BeanPlan`, `@NotNull`-style validation, and logging.
The scoped primitives are `Defer` (commit-time actions) and `ScopedCache`
(scope-lifetime values); both share one `ScopeSession`, and manual rollback is
`Defer.rollback()`.

### IoC
Explicit `bind`/`to`, named and primary bindings, `SINGLETON`/`PROTOTYPE`/`THREAD`
scopes, constructor and field injection, `${...}` expansion, contributions with
ordering, method advisors, and an in-process EventBus. `Scoping.within(...)`
enters a thread scope on JDK 25 `ScopedValue`.

### Boot
`FreewayApp` composes modules, loads the config cascade, runs `RuntimeHook`s,
publishes `AppLifecycleEvent`, and owns shutdown. ServiceLoader discovery is the
one automatic step — and it happens only in `FreewayApp` (disable with
`autoDiscovery(false)`); bindings are never scanned.

### HTTP
Trie-based routing with path parameters, regex constraints and wildcards; typed
body binding with validation; filters and error handlers; static resources;
multipart; SSE; WebSocket. The built-in engine speaks HTTP/1.1, HTTP/2 and
WebSocket; Undertow and Jetty swap in through a module.

### DB
`Database` SQL execution, `Orm` CRUD/upsert, schema-less `Row`, a programmatic
`Sql` builder, pooling, `db.transaction(...)` with thread-bound isolation and
transaction-aware events, dialect detection, `Schema.ensure()` and versioned
migrations with checksum validation.

### Flow
JSON-defined DAGs with `START`, `END`, `ACTIVITY`, `EXCLUSIVE`, `INCLUSIVE`,
`PARALLEL` and `LOOP`; `@name` container tasks and `#graphId` subflows; static
validation at build/load; iterative execution; branch isolation and PlantUML
export.

### Cloud
In-process registry, a deployable registry node and a fleet client; discovery
with caching and readiness; RPC over HTTP with load balancing, retries, circuit
breaking and rate limiting; an at-most-once event mesh; health/readiness,
metrics/tracing, secrets, object storage and TLS rotation.

## Configuration

The cascade runs lowest to highest:

1. `application.properties`
2. `application.json`
3. `application-{profile}.properties`
4. `application-{profile}.json`
5. environment variables — `FREEWAY_DB_URL` → `freeway.db.url`
6. CLI arguments — `--key=value`, `-Dkey=value`

Activate a profile with `--freeway.profile=dev`. The environment prefix is
replaceable (`-Dfreeway.env.prefix=APP_`), and every key is declared in full in
its module's `ConfigKeys` and documented in
[docs/freeway-config.md](docs/freeway-config.md).

## Logging

Freeway uses the SLF4J 2 API and ships a JUL-backed provider as the fallback.
At startup it detects an external provider (Logback, Log4j, slf4j-simple) and
lets it win; an explicit `-Dslf4j.provider` is always respected. Configure the
built-in provider with `freeway-logging.properties` on the classpath root or
with `-D` flags:

```properties
freeway.log.level=INFO
freeway.log.console.enabled=true
freeway.log.file=auto
freeway.log.file.max-size=104857600
freeway.log.file.max-history=30
freeway.log.file.compress=true
```

Route named files and per-logger levels with `freeway.log.files` and any
`*.level` key. Full reference:
[docs/freeway-logging.properties.reference](docs/freeway-logging.properties.reference).

## Build

Requires JDK 25+.

```bash
mvn test                 # all core modules
mvn -pl freeway-ioc test # one module
mvn -pl freeway-http -am test
mvn -Pdemos test         # core + demos
```

## Documentation

- [Developer Guide](docs/DEVELOPER-GUIDE.md) — usage for every module
- [Architecture](docs/ARCHITECTURE.md) — boundaries, lifecycle, config internals
- [Configuration keys](docs/freeway-config.md)
- [Module model](docs/freeway-module.md) · [Commons](docs/freeway-commons.md) · [DB](docs/freeway-db.md) · [Cloud](docs/freeway-cloud.md) · [Flow](docs/freeway-flow-design-decisions.md)
- [Defer & ScopedCache](docs/freeway-defer-summary.md)
- [Changelog](CHANGELOG.md) · [Release notes](docs/RELEASE-NOTES-1.5.6.md)

## Extensions

Adapters and add-ons are released in
**[freeway-ext](https://github.com/dzb/freeway-ext)** at the same version as the
core. Add the modules you need:

```xml
<dependency>
    <groupId>com.jujin8.freeway</groupId>
    <artifactId>freeway-mq-kafka</artifactId>
    <version>${freeway.version}</version>
</dependency>
```

> The Maven group id keeps its original `com.jujin8` spelling while Java
> packages are `com.jujin.freeway`. Renaming either would break every consumer's
> build for no design gain, so the inconsistency is stated here rather than left
> for the first `import` to puzzle over.

## License

[Apache 2.0](LICENSE)
