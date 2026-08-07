# Commons Reference

Examples below are minimal snippets. They omit imports and app-specific domain types.

## Stable API

- `JsonCodec`
- `JsonCodecDefault`
- `JsonUtils`
- `Defer`
- `ScopedCache`
- `BeanValidator`
- `@NotNull`, `@NotBlank`, `@Size`, `@Min`, `@Max`, `@Valid`
- `Coercer`
- `CoerceRule`

`Defer` buffers side effects until the enclosing scope commits. `ScopedCache` memoizes values for the lifetime of a scope and runs cleanup on exit.

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

Freeway bundles a JUL-backed SLF4J 2 provider. Adding Logback to the classpath switches automatically — no code changes.

**Configuration file:** `freeway-log.properties` on classpath root (not bundled in JAR). All logging keys also work as `-D` flags or env vars (prefix from `freeway.env.prefix`, default `FREEWAY_`).

```properties
freeway.log.level=INFO
freeway.log.console.enabled=true
freeway.log.file=auto                    # logs/{app.name}.log, dual rotation + GZIP
# freeway.log.file=off                   # disable file logging
freeway.log.file.max-size=104857600       # 100 MB
freeway.log.file.max-history=30           # days
freeway.log.file.compress=true            # GZIP
freeway.log.file.flush-interval=250       # ms; 0 = flush per record
# (the four values above are the auto defaults — omit to keep, override to change)
```

**Multi-file logging** — named files with independent paths, loggers, and levels:
```bash
-Dfreeway.log.files=audit
-Dfreeway.log.file.audit.path=logs/audit.log
-Dfreeway.log.file.audit.logger=com.myapp.audit
```

**Per-logger levels** — any key ending with `.level`. Supports SLF4J (TRACE/DEBUG/INFO/WARN/ERROR) and JUL names, case-insensitive.

**Late re-attach:** `LogBootstrap.applyNamedFileLoggers()` after `FreewayApp.run()` if named file handlers are missing.

**Priority:** `-D` > env var (prefix per `freeway.env.prefix`) > `freeway-log.properties` > code default.

## Canonical Snippets

```java
String json = JsonUtils.stringify(Map.of("name", "Alice"));

Defer.within(() -> {
    db.execute("UPDATE ...");
    Defer.defer(() -> cache.invalidate("key"));
});

ScopedCache.within(() -> {
    Connection conn = ScopedCache.get("tx", () -> pool.borrow().connection());
});
```
