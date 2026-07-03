# Commons Reference

Examples below are minimal snippets. They omit imports and app-specific domain types.

## Stable API

- `JsonCodec`
- `JsonCodecDefault`
- `JsonUtils`
- `Defer`
- `ScopedCache`
- `BeanValidator`
- `Coercer`
- `CoerceRule`
- `LoggerSource`

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

- Console colors are enabled only when the JVM has an attached console.
- `NO_COLOR` disables colors.
- `freeway.log.color=always|never` overrides detection.
- `freeway.log.format=simple` keeps JUL's default formatter.

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
