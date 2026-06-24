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
    Connection conn = ScopedCache.get("db", () -> dataSource.getConnection());
});
```
