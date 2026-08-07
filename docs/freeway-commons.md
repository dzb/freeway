# Commons Reference

Commons provides the small, shared runtime utilities used across Freeway.

## Stable API

- `JsonCodec` / `JsonCodecDefault` / `JsonUtils`
- `Defer` / `ScopedCache`
- `BeanValidator`
- `Coercer` / `CoerceRule`
- `JULFileHandler`
- `LogBootstrap`

## Scoped Primitives

`Defer` buffers side effects until the enclosing scope commits. `ScopedCache` memoizes values for the lifetime of a scope and runs cleanup on exit.

### Use `Defer` When

- work should happen only after the current unit of work succeeds
- side effects must stay ordered until commit time
- the agent sees a success boundary and the body is side effects, not state reuse

### Use `ScopedCache` When

- a value should be created once per scope and reused inside that scope
- the value must be cleaned up when the scope exits
- the agent sees repeated resolution of the same value in one boundary

### Do Not Use Them When

- cleanup must happen even on failure
- work must cross threads without losing context
- the value should outlive the current scope

For more details, see:

- [Defer summary](../freeway-defer-summary.md)
- [DB usage guide](../freeway-db-how-to-use.md)

## JSON

Use `JsonUtils` for direct parse/serialize helpers and `JsonCodec` when you want an injectable codec.

## Coercion

Use `Coercer` for string-to-type conversion. Add `CoerceRule` when you need a custom target type.

## Validation

Use `BeanValidator` for annotation-driven validation. Keep validation close to request or config boundaries.

## Logging

Freeway bundles a JUL-backed SLF4J 2 provider. Adding Logback to the classpath switches automatically.

### Configuration

Logging is configured through `freeway-log.properties` at the classpath root. The file is **not bundled in the JAR** — create it in your project's `src/main/resources/` only when needed. All defaults are built into code.

**Config cascade:** `-D` flag > env var > `freeway-log.properties` > code default. The env prefix follows `freeway.env.prefix` (default `FREEWAY_`), same convention as the config cascade: `freeway.log.level` ↔ `FREEWAY_LOG_LEVEL`, or `APP_FREEWAY_LOG_LEVEL` under a custom prefix.

```properties
# ── Global ──
freeway.log.level=INFO

# ── Console ──
freeway.log.console.enabled=true
freeway.log.console.level=INFO

# ── Default file log ──
freeway.log.file=auto                      # logs/{app.name}.log
# freeway.log.file=off                     # disable file logging
freeway.log.file.max-size=104857600         # 100 MB
freeway.log.file.max-history=30             # days
freeway.log.file.compress=true              # GZIP
freeway.log.file.flush-interval=250         # 批量刷盘间隔（毫秒）；0 = 每条日志立即刷盘
# （以上四项为 auto 默认值——不写即用这些值，覆盖即调整）
```

### Multi-File Logging

Declare named files with independent rotation and logger binding:

```bash
-Dfreeway.log.files=audit
-Dfreeway.log.file.audit.path=logs/audit.log
-Dfreeway.log.file.audit.logger=com.myapp.audit
-Dfreeway.log.file.audit.level=FINE
```

Each file creates a `JULFileHandler` (time+size dual rotation, GZIP) attached to its target logger. `useParentHandlers=false` prevents double-delivery.

### Per-Logger Levels

Any key ending with `.level` sets the corresponding JUL logger. Accepts SLF4J names (TRACE/DEBUG/INFO/WARN/ERROR) and JUL names (FINEST/FINE/INFO/WARNING/SEVERE), case-insensitive.

```bash
-Dcom.myapp.audit.level=FINE
-Dorg.hibernate.level=WARNING
```

### Env Var Support

All `freeway.log.*` keys support env vars via the configurable prefix (`freeway.env.prefix`, default `FREEWAY_`): `FREEWAY_LOG_LEVEL=DEBUG` equals `-Dfreeway.log.level=DEBUG`; under a custom prefix `APP_`, use `APP_FREEWAY_LOG_LEVEL=DEBUG`.

### Late Re-attachment

If JUL's lazy `LogManager` initialization clears named file handlers:

```java
AppRuntime runtime = FreewayApp.run(args, new AppModule());
LogBootstrap.applyNamedFileLoggers();  // re-attach if needed
```

### Formatter Control

Console colors auto-detected from TTY. Force on/off with `-Dfreeway.log.color=always|never` or `NO_COLOR=1`. Opt out of Freeway's formatter with `-Dfreeway.log.format=simple`.

### Reference Template

See [`docs/freeway-log.properties.reference`](../freeway-log.properties.reference) for annotated examples with best practices.
