## Freeway 2

[![Version](https://img.shields.io/badge/version-2.0.0-blue)](docs/releases/release-notes-2.0.0.md) [![JDK](https://img.shields.io/badge/JDK-25%2B-orange)](https://jdk.java.net/25/) [![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE) ![Runtime dependencies](https://img.shields.io/badge/runtime%20deps-0-brightgreen)

A modern, concise, compose-first Java application framework for JDK 25+.
IoC, HTTP, DB, Flow and Cloud are one coherent stack: no classpath scanning, no
bytecode weaving, and no runtime dependencies beyond the SLF4J API.

Freeway is built around one rule: **every shared fact has one owner**. APIs state
their intent, concepts pull their weight, and the framework stays predictable for
both humans and coding agents.

**Latest release: 2.0.0** — [release notes](docs/releases/release-notes-2.0.0.md) · [changelog](CHANGELOG.md)

[Documentation](docs/README.md) · [Quick Start](#quick-start) · [Modules](#modules) · [Changelog](CHANGELOG.md) · [License](#license)

---

### Quick Start

Add only the modules you need. The snippets use `${freeway.version}`; use `2.0.0`
for the current release.

```xml
<dependency>
    <groupId>com.jujin8.freeway</groupId>
    <artifactId>freeway-boot</artifactId>
    <version>${freeway.version}</version>
</dependency>
```

A minimal application:

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

A web application:

```java
public final class WebApp implements ModuleEx {
    @Override
    public void bind(Binder binder) {
        binder.contribute(Route.class)
            .add(Route.get("/", ctx -> ctx.sendText(200, "Hello Freeway")))
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

### Modules

<table>
  <thead>
    <tr>
      <th align="left" width="210" nowrap>Module</th>
      <th align="left" nowrap>What it provides</th>
    </tr>
  </thead>
  <tbody>
    <tr><td nowrap>freeway-commons</td><td>JSON, coercion, validation, scoped primitives and logging</td></tr>
    <tr><td nowrap>freeway-ioc</td><td>Container and binding DSL, injection, scopes, contributions, AOP, EventBus</td></tr>
    <tr><td nowrap>freeway-boot</td><td>Launcher, config cascade, profiles and runtime lifecycle</td></tr>
    <tr><td nowrap>freeway-http</td><td>Routing, filters, static resources, multipart, SSE and WebSocket</td></tr>
    <tr><td nowrap>freeway-db</td><td>JDBC, ORM, pooling, transactions, schema and migrations</td></tr>
    <tr><td nowrap>freeway-flow</td><td>Graph workflow engine: 7 node types, v3 DAG format, @name tasks</td></tr>
    <tr><td nowrap>freeway-cloud</td><td>Registration lifecycle, RPC, event mesh, observability, resilience, health, secrets, storage and static discovery</td></tr>
  </tbody>
</table>

Third-party adapters live in [freeway-ext](https://github.com/dzb/freeway-ext):

<table>
  <thead>
    <tr>
      <th align="left" width="230" nowrap>Module</th>
      <th align="left" nowrap>Purpose</th>
    </tr>
  </thead>
  <tbody>
    <tr><td nowrap>freeway-http-undertow</td><td>Undertow engine adapter</td></tr>
    <tr><td nowrap>freeway-http-jetty</td><td>Jetty 12 engine adapter</td></tr>
    <tr><td nowrap>freeway-db-hikari</td><td>HikariCP pool adapter</td></tr>
    <tr><td nowrap>freeway-cloud-consul</td><td>Consul registry/discovery adapter</td></tr>
    <tr><td nowrap>freeway-mq-kafka</td><td>Kafka durable event plane (KafkaEvents)</td></tr>
  </tbody>
</table>

### Why Freeway

- **Modern** — JDK 25 is the baseline. Freeway uses records, sealed types, pattern switches and `ScopedValue` directly instead of emulating older Java.
- **Concise** — one accurate verb per action. `find` returns `Optional`, `require` throws, `withX` returns a new value, and aliases that do different things under the same name do not exist.
- **Clean** — every fact has one owner. `ResponsePlan` + `HttpTransport` own response execution; `RequestHead` + `RequestViewSupport` own request reads.
- **Composition-only** — extension seams such as `HttpTransport`, `FlowDriver`, `ServiceRegistry` and `ServiceDiscovery` are interfaces; implementations are final and selected by the container.
- **Consistent** — one `find` / `require` contract, one `metadata()` / `putMetadata()` family, one `withX` convention, one `ConfigKeys` per module and one lifecycle.
- **Solid** — safety comes from types and tests. Sealed pattern switches force exhaustive dispatch, and `ErrorMapper` returns data while `HttpServer` owns the only write path.
- **Full-featured** — IoC, boot/config, HTTP, DB/ORM/migrations, Flow and Cloud cover the application lifecycle without a second stack.
- **Agentic-friendly** — `Container.describe()`, `AppRuntime.describe()`, `RouteIndex.descriptions()` and `HttpServer.routeDescriptions()` expose stable metadata; every config key is declared in `ConfigKeys`.

#### Composition, not inheritance

Freeway's object model is interface contracts plus final implementations. To
customize behavior you implement an SPI or contribute an object; you never
subclass a framework class to reach protected state.

The few `extends` clauses in `src/main` are standard-library boundary adapters:
exceptions, stream and collection adapters, TLS managers, logging handlers and
`SubmissionPublisher`. They exist because the standard library requires those
base types.

### Runtime

#### Configuration

The cascade runs lowest to highest:

1. `application.properties`
2. `application.json`
3. `application-{profile}.properties`
4. `application-{profile}.json`
5. environment variables — `FREEWAY_DB_URL` → `freeway.db.url`
6. CLI arguments — `--key=value`, `-Dkey=value`

Activate a profile with `--freeway.profile=dev`. The environment prefix is
replaceable (`-Dfreeway.env.prefix=APP_`). Every key is declared in full in its
module's `ConfigKeys` and documented in [docs/configuration.md](docs/configuration.md).

#### Logging

Freeway uses the SLF4J 2 API and ships a JUL-backed provider as the fallback. At
startup it detects an external provider (Logback, Log4j, slf4j-simple) and lets
it win; an explicit `-Dslf4j.provider` is always respected. Configure the
built-in provider with `freeway-logging.properties` on the classpath root or with
`-D` flags:

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
[docs/reference/logging.properties.reference](docs/reference/logging.properties.reference).

#### Build

Requires JDK 25+.

```bash
mvn clean test                     # all core tests
mvn -pl freeway-ioc test           # one module
mvn -pl freeway-http -am test      # module plus its dependencies
mvn -Pdemos -DskipTests compile    # compile the in-repo consumers
```

### Documentation

The documentation index is [`docs/README.md`](docs/README.md). Highlights:

- [Developer guide](docs/developer-guide.md) — usage for every module
- [Architecture](docs/architecture.md) — boundaries, lifecycle, config internals
- [Configuration keys](docs/configuration.md)
- [Module model](docs/module-system.md)
- [Commons](docs/modules/commons.md) · [DB](docs/modules/db.md) · [Cloud](docs/modules/cloud.md)
- [Flow design](docs/design/flow.md) · [Defer & ScopedCache](docs/design/defer.md) · [Cloud design](docs/design/cloud.md)
- [Design taste](TASTE.md) — the judgment rules behind these APIs
- [Changelog](CHANGELOG.md) · [Release notes](docs/releases/)

Framework comparisons and engineering evaluations are reference-only and live
under [`demo/reference/`](demo/reference/).

### Extensions

Adapters and add-ons are released in [freeway-ext](https://github.com/dzb/freeway-ext)
at the same version as the core:

```xml
<dependency>
    <groupId>com.jujin8.freeway</groupId>
    <artifactId>freeway-mq-kafka</artifactId>
    <version>${freeway.version}</version>
</dependency>
```

The Maven group id keeps its original `com.jujin8` spelling while Java packages
are `com.jujin.freeway`. Renaming either would break every consumer's build for
no design gain.

### 2.0 Refactor

`2.0.0` is an architecture and vocabulary cleanup, not a feature pile-on. The
theme is fewer owners, fewer defaults and fewer ways to express the same fact.
Existing code migrates through compiler errors; no compatibility alias was added.

Key changes:

- `ModuleDeclaration` is the single module-composition value; `ConfigFilePlan` owns configuration file precedence and snapshots.
- `HttpTransport` is the adapter SPI; `ResponsePlan` and sealed `ResponseBody` own response execution and framing.
- `Dialect` owns SQL capabilities; `H2Dialect` no longer inherits `PostgresDialect`.
- `Extension` is read-only, `Scope.BOUNDARY` replaces `Scope.THREAD`, and `JsonCodec` is an IoC capability.
- RPC and event mesh use `Rpc*` / `Mesh*` vocabulary; Fleet lives in freeway-ext.
- `find(...): Optional<T>` is optional lookup, `require(...)` throws, and `withX(...)` returns a new value.

Verification:

```text
mvn clean test
  commons 447 · ioc 341 · boot 139 · http 550
  db 550 · flow 141 · cloud 375
  total 2,543 — 0 failures, 0 errors, 0 skipped
```

`mvn -Pdemos -DskipTests compile` also compiles the in-repo consumers against the
reactor.

### License

[Apache 2.0](LICENSE)
