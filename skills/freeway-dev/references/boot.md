# Boot Reference

Examples below are minimal snippets. They omit imports and app-specific domain types.

## Project Module Structure

| Module | Purpose |
|---|---|
| `freeway-commons` | JSON, type coercion, `Defer`, `ScopedCache`, bean introspection, validation, logging |
| `freeway-ioc` | IoC container: bindings, injection, scopes, AOP, event bus, extensions |
| `freeway-boot` | Launcher, config cascade, profiles, runtime lifecycle |
| `freeway-flow` | Graph workflow engine: 7 node types, v3 DAG format |
| `freeway-http` | HTTP/WebSocket: routing, filters, static files, multipart, SSE; built-in `FreewayHttpEngine` (HTTP/1.1, HTTP/2, WebSocket, HTTPS); Undertow/Jetty engines in `freeway-ext` |
| `freeway-db` | JDBC: ORM, connection pool, transactions, SQL builder, migrations; HikariCP pool in `freeway-ext` |
| `freeway-cloud` | Cloud-native: service registry and discovery, RPC, event mesh, health, observability, resilience, secrets, object storage |

Inside `freeway-cloud`, `RegistryServerModule` is a deployable registry node - a
normal application assembled from `HttpModule` + `DiscoveryModule` + it.
`FleetRegistryModule` binds a fleet of those nodes as the registry/discovery
backend: writes fan out, reads union the nodes.

Third-party adapters (`freeway-http-undertow`, `freeway-http-jetty`,
`freeway-db-hikari`, `freeway-mq-kafka`) live in
[freeway-ext](https://github.com/dzb/freeway-ext). Core modules have no
external dependency beyond SLF4J.

## Entrypoints

- `FreewayApp.run(ModuleEx...)` - start now with the default `Launch` (no arguments, discovery on); accepts module instances
- `FreewayApp.run(Launch, ModuleEx...)` - start now with launch settings: `Launch.of("--freeway.profile=dev")`
- `FreewayApp.create(ModuleEx...)` / `create(Class...)` - the application before it runs; chain on it:
- `FreewayApp.add(...)` - append modules (the way to mix instances and class declarations)
- `FreewayApp.name(...)` - the application's root line in the startup log (default `application`)
- `FreewayApp.args(...)`
- `FreewayApp.config(...)`
- `FreewayApp.autoDiscovery(...)`
- `FreewayApp.classLoader(...)`
- `FreewayApp.shutdownHook(...)`
- `FreewayApp.start()` - the only effect: returns the running `AppRuntime`; a launcher is single-use

### Start Now

```java
// Minimal
AppRuntime runtime = FreewayApp.run(new AppModule());

// More modules
AppRuntime withInfrastructure = FreewayApp.run(
    new AppModule(), new HttpModule(), new DbModule());

// Launch settings (command-line args, SPI discovery)
AppRuntime configured = FreewayApp.run(
    Launch.of("--freeway.profile=dev"), new AppModule());
```

### Configure, Then Start

```java
AppRuntime app = FreewayApp.create(new MyModule())
    .add(new HttpModule(), new DbModule())
    .args("--freeway.profile=dev")
    .classLoader(customLoader)
    .autoDiscovery(false)        // disable SPI module discovery
    .shutdownHook(false)         // skip the JVM shutdown hook
    .config(myConfig)            // a pre-built AppConfig
    .start();
```

`create(...)` returns a single-use launcher: `start()` builds the container,
starts the hooks and returns the running `AppRuntime`; a second `start()`
throws. `config(...)` substitutes the whole cascade, so `.args(...)` no longer
feeds config in that form.

## Writing a Module

Every module implements the `@FunctionalInterface ModuleEx`:

```java
public class AppModule implements ModuleEx {
    public void bind(Binder b) {
        // Bind services
        b.bind(UserService.class).to(UserServiceImpl.class);

        // Contribute extensions (routes, hooks, event subscribers, ...)
        b.contribute(Route.class)
            .add(Route.get("/", ctx -> ctx.sendText(200, "Hello")));

        b.contribute(RuntimeHook.class)
            .add("cache.warmup", new RuntimeHook() {
                public void start(Container c) { c.get(Cache.class).warmup(); }
                public void stop(Container c) { c.get(Cache.class).close(); }
            }).before("freeway.http.server");
    }
}
```

Rules:

- **Do not start work in `bind()`** - only declare; `RuntimeHook.start()` is the activation point.
- **Bind once per runtime** - every module's `bind()` runs in order during one container initialization.
- **Library types do not import IoC types** - a library stays usable without a container (the `Library -> DbModule` pattern).

## Runtime Contract

- `AppRuntime` extends `AutoCloseable`
- `AppRuntime.config()`
- `AppRuntime.state()`
- `AppRuntime.get(Class)`
- `AppRuntime.get(Class, String)`
- `AppRuntime.start()`
- `AppRuntime.close()`

## Lifecycle Facts

```
CREATED -> STARTING -> RUNNING -> STOPPING -> STOPPED
              \-> FAILED
```

- `run(...)` already starts the runtime.
- `start()` starts from `CREATED` and is a no-op while `RUNNING`; from `STARTING`, `STOPPING`, `STOPPED`, or `FAILED` it throws.
- `close()` is idempotent and at-most-once. The first call publishes an `AppLifecycleEvent` for `AppState.STOPPING` when the runtime actually ran, stops the hooks that started in reverse order, releases the config, then closes the container; later calls are no-ops.
- `AppLifecycleEvent` with `AppState.RUNNING` is published after successful startup (best-effort).
- `AppLifecycleEvent` with `AppState.STOPPING` is published before shutdown (only for a runtime that actually ran, not a startup-failed one) and is a reliability point.
- `FAILED` is terminal for `start()`, not for `close()`: a failed runtime must still be released, which normally reaches `STOPPED`. A shutdown failure also leaves `FAILED`.
- Shutdown hook registration is enabled by default.
- Auto-discovery is enabled by default.

### AppRuntime Usage

```java
AppRuntime runtime = FreewayApp.run(new AppModule());
AppConfig cfg = runtime.config();               // profiles, symbol sources, lifecycle
Greeter greeter = runtime.get(Greeter.class);   // service by type
AppState state = runtime.state();               // current state
runtime.start();                                // manual start; run() already started it
runtime.close();                                // stop the application
```

## Configuration Cascade

Lowest to highest priority:

| Precedence | Tier | Declared order |
|---|---|---|
| 1 (lowest) | Config files: classpath baseline, working-directory same-name overrides, `freeway.config.file` extras | `TIER_FILES` (20) |
| 2 | Module-contributed sources, e.g. the cloud secret store | the module's own `order()` (15 for the secret store) |
| 3 | Environment variables, default prefix `FREEWAY_` mapped into `freeway.*` | `TIER_ENV` (10) |
| 4 | JVM system properties: `-Dkey=value` set before main, keys verbatim | `TIER_SYS_PROPS` (5) |
| 5 (highest) | CLI arguments: `--key=value`, `--key value`, `--key`, `-Dkey=value` | `TIER_CLI` (0) |

Every tier is a `SymbolProvider` with a declared `order()`; the chain consults
providers in ascending order and the first non-null value wins, so a module
source slots in by declaring its order and never depends on module install
order.

Within the file tier: `application.properties` -> `application.json` ->
`application-{profile}.properties` -> `application-{profile}.json`, with the
classpath baseline first, then the same names in the working directory, then
files named by `freeway.config.file` (comma-separated). Inside the profile band
the format outranks the profile order: `application-a.json` beats
`application-b.properties`. The file tier is hot-reloaded; profile activation is
startup-static.

CLI keys are literal, with no prefix: `--app.name=foo` -> `app.name`,
`--mode=prod` -> `mode`. Framework keys are spelled in full: activate a profile
with `--freeway.profile=dev`.

## Canonical Snippet

```java
try (AppRuntime runtime = FreewayApp.run(new AppModule())) {
    Greeter greeter = runtime.get(Greeter.class);
    System.out.println(greeter.greet("World"));
}
```
