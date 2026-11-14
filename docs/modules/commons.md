# Commons Reference

Commons provides the small, shared runtime utilities used across Freeway.

## Stable API

> This list covers only **commons' own** types. `SymbolSpec` (`ioc.symbol`) and `AppConfig`
> (`freeway-boot`) belong to their own modules; see `DEVELOPER-GUIDE` and `configuration.md`.

- `JsonCodec` / `JsonCodecDefault` / `JsonUtils`
- `Defer` / `ScopedCache`
- `BeanValidator`
- `Coercer` / `CoerceRule`
- `ReflectiveHandles` — cached `MethodHandle`/`VarHandle`/constructor handles for reflective invocation hot paths (`methodHandle`, `defaultMethodHandle`, `invokeOn`)
- `ContextExecutor` — explicit cross-thread propagation of `ScopedValue`-based context
- `JULFileHandler`
- `LogBootstrap`

## Scoped Primitives

`Defer` buffers side effects until the enclosing action scope commits. `ScopedCache` memoizes values for the lifetime of a scope and runs cleanup on exit. Both are façades over one `ScopeSession`: one binding for the scope, one close algorithm (actions first, then values). A cache scope opened inside `Defer.within(...)` attaches to that action scope, so values live until the action scope closes.

The value **lifetime** and the value **access window** are separate: inside a `ScopedCache.within` block (and while the action scope drains) values are visible; outside that window `ScopedCache.isActive()` is false and `get` creates uncached values, while the attached values remain stored until the action scope closes.

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

- [Scope primitives — Defer & ScopedCache](../design/defer.md)
- [DB usage guide](db.md)

## JSON

Use `JsonUtils` for direct parse/serialize helpers and `JsonCodec` when you want an injectable codec.

## Coercion

Use `Coercer` for string-to-type conversion. Add `CoerceRule` when you need a custom target type.

## Validation

Use `BeanValidator` for annotation-driven validation. Keep validation close to request or config boundaries.

## Logging

Freeway bundles a JUL-backed SLF4J 2 provider. Adding Logback to the classpath switches automatically.

### Configuration

Logging is configured through `freeway-logging.properties` at the classpath root. The file is **not bundled in the JAR** — create it in your project's `src/main/resources/` only when needed. All defaults are built into code.

**Config cascade:** `-D` flag > env var > `freeway-logging.properties` > code default. The env prefix follows `freeway.env.prefix` (default `FREEWAY_`), same convention as the config cascade: `freeway.log.level` ↔ `FREEWAY_LOG_LEVEL`, or `APP_FREEWAY_LOG_LEVEL` under a custom prefix.

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
freeway.log.file.flush-interval=250         # batch flush interval (ms); 0 = flush each log record immediately
# (the four above are the defaults for auto — omit them to use these values, override to adjust)
```

### Backend Selection

Freeway ships a JUL-backed SLF4J provider and selects it by default. Use `freeway.log.system` (a class-load-time key: `-D` or `FREEWAY_LOG_SYSTEM` only) to choose another backend:

| Value | Meaning |
|---|---|
| `freeway` (default) | Freeway's JUL backend wins even when Logback/Log4j is on the classpath (startup names the unselected provider and the switch) |
| `auto` | External provider wins (Logback > Log4j 2 > slf4j-simple), JUL as fallback |
| `logback` / `log4j2` / `simple` | That backend explicitly; startup fails naming the missing dependency if absent |
| `<provider class>` | Any `SLF4JServiceProvider` implementation |

`-Dslf4j.provider=<class>` is SLF4J's own escape hatch and takes precedence. The startup report (`[Freeway] logging: ... | provider=... | selected by ...`) names what actually bound.

With a non-JUL backend selected, Freeway only pins the SLF4J binding and leaves `java.util.logging` untouched — no JUL handlers, no forwarding. Add the standard `jul-to-slf4j` bridge yourself if JDK-internal JUL records should follow your backend. Freeway's own JUL enhancement is configured only for the JUL backend.

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

Any key ending with `.level` sets the corresponding JUL logger. Accepts SLF4J names (TRACE/DEBUG/INFO/WARN/ERROR) and JUL names (FINEST/FINE/INFO/WARNING/SEVERE), case-insensitive. Output always displays the SLF4J name (a JUL `WARNING` record reads `WARN`; `CONFIG` keeps its JUL name because SLF4J has no counterpart).

```bash
-Dcom.myapp.audit.level=FINE
-Dorg.hibernate.level=WARNING
```

### Env Var Support

All `freeway.log.*` keys support env vars via the configurable prefix (`freeway.env.prefix`, default `FREEWAY_`): `FREEWAY_LOG_LEVEL=DEBUG` equals `-Dfreeway.log.level=DEBUG`; under a custom prefix `APP_`, use `APP_FREEWAY_LOG_LEVEL=DEBUG`.

### Late Re-attachment

If JUL's lazy `LogManager` initialization clears named file handlers:

```java
AppRuntime runtime = FreewayApp.run(Launch.of(args), new AppModule());
LogBootstrap.applyNamedFileLoggers();  // re-attach if needed
```

### Formatter Control

Console colors auto-detected from TTY. Force on/off with `-Dfreeway.log.color=always|never` or `NO_COLOR=1`. Opt out of Freeway's formatter with `-Dfreeway.log.format=simple`.

### Reference Template

See [`docs/reference/logging.properties.reference`](../reference/logging.properties.reference) for annotated examples with best practices.
