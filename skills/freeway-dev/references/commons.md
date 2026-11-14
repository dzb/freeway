# Commons Reference

Examples below are minimal snippets. They omit imports and app-specific domain types.

## Stable API

- `JsonCodec`
- `JsonCodecDefault`
- `JsonUtils`
- `JsonObject`, `JsonArray`
- `Coercer`
- `CoerceRule`
- `Defer`, `DeferAction`
- `ScopedCache`
- `ScopeSession`
- `BeanValidator`
- `ValidationResult`, `ValidationError`
- `@NotNull`, `@NotBlank`, `@Size`, `@Min`, `@Max`, `@Valid`

## JSON

`JsonUtils` is the static node layer: parse text into `JsonObject`/`JsonArray`, serialize values, and pretty-print. `JsonCodec` is the injectable text/type layer; the container binds `JsonCodecDefault` with the container's `Coercer`.

```java
JsonObject obj = JsonUtils.parseObject("{\"name\":\"Alice\"}");
JsonArray arr = JsonUtils.parseArray("[1, 2, 3]");
String json = JsonUtils.toJson(obj);
String pretty = JsonUtils.toPrettyJson(obj);

@Inject JsonCodec codec;
String userJson = codec.toJson(user);
User user = codec.fromJson(userJson, User.class);
```

### Coercion

`Coercer.coerce(value, Target.class)` converts a value; `supports(sourceType, targetType)` and `conversions()` expose what it can do. Contributed `CoerceRule`s extend the container's `Coercer`, and therefore the config cascade too.

```java
Coercer coercer = container.get(Coercer.class);
int port = coercer.coerce("8080", int.class);

binder.contribute(CoerceRule.class).add(new CoerceRule<>(
    String.class, Endpoint.class,
    value -> {
        String[] parts = value.split(":", 2);
        return new Endpoint(parts[0], Integer.parseInt(parts[1]));
    }));
```

`JsonUtils.coerce(value, Target.class)` converts an already-parsed node with the built-in coercer; `JsonCodec.coerce(node, type)` does the same with the codec's coercer.

## Metrics and Failure Classification

`Metrics` is the framework-wide observability SPI: `counter(name)`, `timer(name)` and `gauge(name, supplier)`, plus labeled overloads. Names are dot-separated and static; labels are bounded dimensions (`method`, `route`, `service`), never raw paths, URLs, request ids or tenant input. A backend without label support records the name-only aggregate; do not branch on backend type.

- `timer` records count and total duration — only count/average.
- `histogram(name, labels)` records the framework's fixed latency buckets (`Metrics.DEFAULT_LATENCY_BUCKETS_NANOS`, 5ms…10s + `Inf`); HTTP and RPC durations use it, and the default registry renders `_bucket`/`_count`/`_sum`. Use it for latency you must alert on or compare; never pass arbitrary bucket lists.
- `/metrics` is Prometheus text; quantiles are approximate (`histogram_quantile`), and the default registry only holds one process. A backend without histogram support falls back to timer count/sum.

Failures: `RpcException`, `SqlException`, `FlowException`, `StorageException` and the four HTTP request rejections implement `com.jujin.freeway.commons.error.Failure` (`retryable()`, `outcomeUnknown()`). Defaults are conservative (no retry, outcome unknown); `RpcException` maps its `Kind` precisely, and HTTP rejections report a known, non-retryable outcome. Do not infer retry safety from a cause type, and do not retry SQL/flow failures blindly. Full table: `docs/reference/failures.md`.

## Defer

`Defer` buffers side effects until the enclosing action scope commits. Actions run after a normal return; an exception, or `Defer.rollback()`, discards them. `Defer.defer` outside a scope (or while the scope drains) runs the action immediately.

```java
Defer.within(() -> {
    db.execute("UPDATE ...");
    Defer.defer(() -> cache.invalidate("key"));  // runs after the scope commits
});

Defer.within(() -> {
    Defer.defer(() -> metrics.record());  // success only
    if (aborted) Defer.rollback();        // normal exit, but discard every action
});
```

- `Defer.within` takes a `Runnable` or a `Supplier<T>`; there is no scope-handle overload.
- Ordering: `Defer.defer(id, action)` and `Defer.supply(id, callable)` return a `DeferAction` with `.before(...)` / `.after(...)`. Constrained named actions drain in topological order, then unconstrained named actions, then unnamed actions, each band in registration order.
- Rollback: `Defer.rollback()` marks the current action scope to discard its actions on normal exit; it is a no-op outside a scope.
- A failing action is logged and the remaining actions still run.
- Deferred suppliers (`Defer.supply`) preserve `Error` identity (rethrown as-is, exactly once); other failures arrive wrapped.

Framework integrations already use it at these boundaries: EventBus publishes inside a DB transaction (deferred until commit), HTTP request completion, and Kafka record processing.

## ScopedCache

`ScopedCache` memoizes values for the lifetime of a scope and runs cleanup when the scope exits. `Defer` and `ScopedCache` are facades over one `ScopeSession`: one scope binding and one close order - actions first, then values.

```java
ScopedCache.within(() -> {
    Connection conn = ScopedCache.computeIfAbsent("db", () -> dataSource.getConnection());
    // the same key reuses the value for this scope
});
// exit cleans up; inside Defer.within the value attaches to the action scope
// and lives until that scope closes
```

- `computeIfAbsent(key, factory)` caches per scope; `computeIfAbsent(key, factory, onExit)` also runs `onExit` once when the owning scope exits. First creation wins; outside any scope the value is created uncached and the caller owns it.
- A `ScopedCache.within` opened inside `Defer.within` attaches its values to that action scope: values live until the action scope closes, and deferred actions see them.
- Value **lifetime** and value **access window** differ: values are visible only during the cache block (and the action drain); outside it `ScopedCache.isActive()` is false and `computeIfAbsent` creates uncached values, even though attached values are still stored.
- `ScopeSession` is a read-only handle (`isActionScope()`, `isClosed()`); it cannot roll back or close - rollback is `Defer.rollback()`.
- IoC's `Scope.BOUNDARY` is built on `ScopedCache`. `ScopeBoundary.within()` uses the same value scope: inside a `Defer` action scope, scoped beans attach and live until that action scope closes; outside a cache window they are not resolvable.

## BeanValidator

Annotation-driven validation for a bean and its `@Valid` graph.

```java
public class CreateUserRequest {
    @NotBlank @Size(max = 80) private String name;
    @NotNull @Min(1) @Max(150) private Integer age;
    @Valid private Address address;
}

ValidationResult result = BeanValidator.validate(request);
if (result.hasErrors()) { ... }
```

- `ValidationResult` exposes `hasErrors()` and `errors()`; each `ValidationError` is `(field, message, rejectedValue)`.
- Nested `@Valid` paths are dotted (`address.city`); array/collection elements use `items[0].name`.
- `@Size` applies to `CharSequence`, `Collection`, `Map`, and arrays; `@Min`/`@Max` apply to finite numbers. Both skip `null` - nullness is `@NotNull`'s job.
- `BeanValidator.validate(null)` yields a `(root)` error.

## When To Use

- Use `Defer` when the work should happen only after the current unit of work succeeds: DB transaction commit, HTTP response success, batch completion, or any ordered commit-time side effect.
- Use `Defer` when multiple side effects must stay ordered until commit time, such as cache invalidation before indexing before notifications.
- Use `Defer` when the agent sees a success boundary and the body is side effects, not state reuse.
- Do not use `Defer` for cleanup that must happen even on failure, or for cross-thread fire-and-forget work.
- Use `ScopedCache` when a value should be created once per scope and reused inside that scope, such as request state, scoped connections, scoped services, or per-transaction helpers.
- Use `ScopedCache` when the value must be closed or otherwise cleaned up as the scope exits.
- Use `ScopedCache` when the agent sees repeated resolution of a value inside the same boundary.
- Do not use `ScopedCache` for global caches or values that must outlive the current scope.

## Logging

Freeway bundles a JUL-backed SLF4J 2 provider and uses it by default, so logging works with no external backend dependency. A Logback/Log4j 2/slf4j-simple provider on the classpath does **not** take over automatically: `freeway.log.system` chooses the backend. Values are `freeway` (default), `auto` (external provider first, JUL fallback), `logback`/`log4j2`/`simple`, or a provider class name; a named backend that is absent from the classpath fails startup. The key is a class-load-time setting read from `-D` or the environment only (never a config file); `-Dslf4j.provider` is SLF4J's own escape hatch and takes precedence. Startup prints one `[Freeway] logging: ...` report naming the active provider and the selecting source.

With a non-JUL backend selected, Freeway pins the SLF4J binding and leaves `java.util.logging` untouched - no JUL handlers, formatters, files, or forwarding. JDK-internal JUL records stay on JUL; add the standard `jul-to-slf4j` bridge yourself if they should follow the selected backend. Freeway's JUL enhancement is configured only for the JUL backend.

**Configuration file:** `freeway-logging.properties` on classpath root (not bundled in JAR; the pre-1.5.2 name `freeway-log.properties` is never read - a leftover file only produces a startup notice to rename it). Every logging key also works as a `-D` flag or env var (prefix from `freeway.env.prefix`, default `FREEWAY_`; the bootstrap key `freeway.log.system` always reads the fixed `FREEWAY_LOG_SYSTEM` spelling). A key containing a hyphen keeps that hyphen in its env name (`FREEWAY_LOG_FILE_MAX-SIZE`), so most shells need `-D` for those.

**`-D`/env only (never from a file):** `freeway.log.system`, `freeway.log.color`, `freeway.log.mdc`, `freeway.log.mdc.priority`, and `freeway.log.caller-info` are read at class-load time, before any config file is parsed.

**Zero config:** console and file logging are on by default; `java -jar app.jar` writes `logs/{app.name}.log`.

```properties
freeway.log.level=INFO
freeway.log.console.enabled=true
freeway.log.file=auto                    # logs/{app.name}.log, dual rotation + GZIP
# freeway.log.file=off                   # disable file logging
freeway.log.file.max-size=104857600       # 100 MB
freeway.log.file.max-history=30           # days
freeway.log.file.compress=true            # GZIP
freeway.log.file.flush-interval=250       # ms; 0 = flush per record
# (the four values above are the auto defaults - omit to keep, override to change)
```

**Multi-file logging** - named files with independent paths, loggers, and levels:

```bash
-Dfreeway.log.files=audit
-Dfreeway.log.file.audit.path=logs/audit.log
-Dfreeway.log.file.audit.logger=com.myapp.audit
```

Each named file gets its own `JULFileHandler` (time + size rotation, GZIP) routed to the configured logger. Configure it in `freeway-logging.properties` or override with `-D`.

**Per-logger levels** - any key ending with `.level`. Supports SLF4J names (TRACE/DEBUG/INFO/WARN/ERROR) and JUL names (FINEST/FINE/INFO/WARNING/SEVERE), case-insensitive.

**Late re-attach:** `LogBootstrap.applyNamedFileLoggers()` after `FreewayApp.run()` if named file handlers are missing.

**Priority:** `-D` > env var (prefix per `freeway.env.prefix`) > `freeway-logging.properties` > code default.
