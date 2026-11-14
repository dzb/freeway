# Freeway 1.3.10 Release Notes

> Version: 1.3.10 ｜ Highlight: **the hardening pass — structured failures, one observability SPI, an honest naming audit**

1.3.10 is a consolidation release: no new modules, no headline feature — instead the release
sweeps every module for residual roughness and lands three structural unifications. 159 files
touched (+7,252 / −5,157), all module test suites green (≈1,040 tests).

## Added

- **Structured binding exceptions** (`freeway-ioc`) — `AmbiguousBindingException` and
  `UnknownSymbolException` now cover multi-hit/dual-primary and symbol misses; catch by type
  instead of matching exception message text. Both extend `IllegalArgumentException`, so
  existing catch sites keep working.
- **`Defer.within` result shape** (`freeway-commons`) — `within(Supplier<T>)` and
  `within(Function<DeferScope, T>)` yield a scope return value; commit semantics unchanged
  (drain on normal return, discard on rollback/exception). One extra overload beyond
  `ScopedCache.within`: a `Consumer<DeferScope>` form for value-less transaction shells.
- **Migration lock TTL** (`freeway-db`) — `freeway.db.migration.lock-ttl` (default PT1H):
  a lock row stranded by a crashed process is taken over automatically after expiry; set to 0
  to disable takeover.
- **Connection dial login timeout** (`freeway-db`) — `PoolDefault` sets
  `DriverManager.setLoginTimeout` before connecting, so a hung dial no longer holds a pool
  permit forever and blocks subsequent borrowers.
- **Expression `*` `/` `%`** (`freeway-flow`) — multiplicative operators in condition
  expressions; division/modulo by zero fail explicitly instead of producing Infinity/NaN.
- **EventBus subscriber queries** (`freeway-ioc`) — `hasSubscribers(String)` /
  `hasSubscribers(Class)` complete the query surface alongside `CallBus.handles`.

## Changed

- **One observability SPI** (`freeway-commons`) — cloud's parallel `MeterRegistry` is gone;
  commons `Metrics` is now *the* metrics SPI for the whole framework (nanos as canonical unit,
  integer counter semantics). Installing `CloudObserveModule` routes every framework counter —
  EventBus, HTTP engine, pool, tracer spans — into `/metrics`. Custom backends implement
  `com.jujin.freeway.commons.metrics.Metrics`.
- **CallBus is a container builtin** (`freeway-ioc`) — works out of the box, closed only after
  every `@PreDestroy`; hand-written `bind(CallBus.class)` bindings become ambiguous and must go.
- **Circuit breakers / rate limiters are sharded per serviceId** (`freeway-cloud`) — an injected
  instance acts as a policy template, each service gets its own state. One service tripping its
  breaker can no longer refuse calls for healthy services.
- **Tracer span durations feed metrics** (`freeway-cloud`) — closing a span records into the
  `tracer.span.duration` timer; visible in `/metrics`.
- **Cross-thread transactions rejected** (`freeway-db`) — DB work inside a transaction from
  another thread throws with a diagnosis naming the two remedies; previously it silently ran on
  a separate connection and committed past the parent's rollback.
- **Parallel-transaction guard fixes & migration-lock correctness** (`freeway-db`) — duplicate-key
  detection narrowed to exact SQL states (23505/23000/40001); lock-expiry comparisons read the
  database clock in-query so JVM/DB timezone drift cannot preempt live locks.
- **Parallel fan-out hardening** (`freeway-flow`) — a PARALLEL branch rejection no longer leaks
  join latches (previously an executor-shutdown race could hang awaiting tasks forever).
- **Protocol hardening continues** (`freeway-http`) — response-side header validation aligned
  with the parser (rejects CTL/DEL beyond HTAB), HPACK locale-independent decoding.

## Renamed (audit-driven)

A whole-repo naming audit walked ioc/http/db/commons; renames are mechanical but break source:

| Old | New | Why |
|---|---|---|
| `InjectResolver` (ioc internal) | `InjectionResolver` | concept-noun consistency |
| `AnnotationLookup` + `Reader` (ioc internal) | single record + static `find()` | −3 duplicated adapters |
| `SettingsMap` (http internal) | `Settings` | drop type-suffix echo |
| `goawayReceived` flag (http internal) | `goawaySentOrReceived` | name matched neither semantics |
| `ExecuteResult.key` / `hasKey()` (db) | `generatedKey()` / `hasGeneratedKey()` | "key" collided with config-key vocabulary; **`longKey()` kept** |
| `Row.bool(col)` (db) | `booleanValue(col)` | accessor family spells out types |
| `BeanValidator.compareToMinMax` | `toBigDecimal` | it converts; comparison lives at call sites |
| `SymbolSpec.validate(...)` (commons) | `normalizedKey` | returned value was invisible |

Also: window fields on the http2 engine are package-private now (engine internals off the API
surface), and `HttpContext.headerSet()` was already renamed `setHeader()` in 1.3.9's cycle —
double-check call sites when upgrading across both versions.

## Fixed

- **commons** — SLF4J provider probe now warns honestly when an external provider binds first
  (`freeway-log.properties` ownership contract clarified and documented); `SymbolSpec.parse`
  no longer NPEs on missing parser+coercer combos; coercer re-registration no longer leaves
  stale index entries; JSON BOM handling uses an escaped literal.
- **ioc** — EventBus stream bridge survives the close-vs-subscribe race without hanging or
  leaking ISE; AOP hot-path caches rebuilt lock-free (`ClassValue` layered, zero reads blocked).
- **http** — keep-alive state reset between requests (auth context can no longer leak to the
  next request on the same connection); SSE pump cancel races made deterministic; route sort
  precomputed at registration.
- **db** — BatchQuery destroys connections whose autocommit restore fails instead of returning
  dirty ones; DDL migrations on non-transactional-DDL dialects (MySQL) fail fast up front with
  remediation guidance.
- **cloud** — breaker/limiter shard injection fixed for container-proxied beans; HALF_OPEN probe
  races consolidated under a single invariant.

## Tests & tooling

- The two monolith test classes (`FreewayTest` ~2900 lines, `EventBusTest` ~1000) are split by
  domain into focused suites with shared fixtures.
- Cloud performance smoke moved from absolute numbers to floor thresholds tunable via
  `-Dcloud.bench.floor` — slow CI no longer false-fails while still printing measured values.
- Deterministic regression tests added for the stream-close race, SSE cancel race, shard
  isolation, and lock-takeover paths.

## Upgrade notes

1. If you bind `CallBus.class` manually, delete the binding (it's built-in now).
2. Custom metrics backends implement `commons...Metrics` (the cloud SPI is gone).
3. Catch `AmbiguousBindingException`/`UnknownSymbolException` by type instead of parsing messages.
4. DDL migrations against MySQL/MariaDB need idempotent statements or splitting — enforced.
5. Rename follow-ups listed in the table above (all internal except `ExecuteResult.hasKey`/
   `Row.bool`, which have automated-safe replacements).

**Coordinates:** `com.jujin8.freeway:freeway-parent:1.3.10` — adapters in
[freeway-ext](https://github.com/dzb/freeway-ext) v1.3.10 build against this release.
