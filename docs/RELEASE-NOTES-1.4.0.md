# Freeway 1.4.0 Release Notes

> Version: 1.4.0 ｜ Highlight: **one config read path — the symbol chain is the single entry point, and every seam is declared**

1.4.0 is a convergence release for the configuration architecture. The
config system previously exposed parallel read paths with subtly different
precedence (`AppConfig.get` vs `SymbolSource.resolve`), five type-parsing
idioms, and a hidden provider tier that let JVM flags lose to config files.
This release collapses all of it into one explicit pipeline: formats are
normalized by a single parser, the symbol chain is the only read entry, and
typing is an explicit post-processing step over the resolved raw value.
~1,825 tests green across all core modules.

## Breaking

- **`AppConfig` is no longer a reader** (`freeway-boot`) — `get(String)`,
  `get(SymbolSpec)` and the `DefaultCoercer` nested class are removed.
  `AppConfig` owns profiles, the cascade snapshot (`asMap()`, never
  secrets) and the hot-reload lifecycle. Application reads go through
  `SymbolSource`; migration: `config.get(key)` →
  `symbols.resolve(key, null)`.
- **`SymbolSource` is a raw-string resolver again** (`freeway-ioc`) — the
  spec-aware `get(SymbolSpec)` / `get(SymbolSpec, Coercer)` accessors and
  `ConfigValues` are removed. Typed reading is two explicit steps:
  `SPEC.parse(symbols.resolve(SPEC.key(), null)[, coercer])`. The key and
  default live in the `SymbolSpec` once; ioc no longer depends on
  `commons.config`.
- **Four declared framework tiers** (`freeway-ioc`) — `TIER_CLI(0)` /
  `TIER_SYS_PROPS(5)` / `TIER_ENV(10)` / `TIER_FILES(20)` in one ordered
  list; module sources slot between by declared order (cloud secrets at
  15). JVM `-D` flags now rank above config files (the process-level ops
  override, matching Spring Boot convention). The raw-env fallback tier is
  gone — env vars enter only through the declared prefix mapping, so an
  unknown symbol fails fast instead of silently matching an unrelated
  variable. Bare containers (no boot) resolve from system properties only.
- **`EventBus.hasSubscribers(String/Class)` removed** (`freeway-ioc`) —
  zero-consumer query API; `DeadEvent` diagnostics and
  `CallBus.handles(topic)` cover the real needs.
- **`EventBus.publishOrdered(Object key, Object event)` removed**
  (`freeway-ioc`) — the `key` was ignored by the implementation
  (placeholder API). Use `publishOrdered(event)`; per-key ordering will
  return with real semantics when implemented.
- **`@RoundRobin` removed** (`freeway-cloud`) — a strategy marker with a
  single implementation and no logic consumers. Load-balancer extension is
  `LoadBalancer` (@FunctionalInterface) + a primary binding;
  `LoadBalancerDefault` is now marked `@Local` like every other built-in
  default.

## Changed

- **`ConfigFileReader`** (`freeway-boot`) — one parser for config files:
  `.json` (case-insensitive) parses as JSON with dotted-key flattening,
  everything else as properties, all UTF-8 with BOM tolerance. Shared by
  the classpath cascade and the hot-reload file tier, so a file parses
  identically at startup and on every reload.
- **Typed declarations in freeway-cloud/db/http** — `HttpConfig`'s
  reflective value helper, `DbModule`'s key/default double declarations,
  and the ad-hoc `Boolean.parseBoolean(symbols.resolve(...))` calls in
  cloud modules all converged to declarative `SymbolSpec`s. Cloud's
  `*_DEFAULT` constants are now typed values shared by the config layer
  and the in-library fallbacks.
- **`PeerHub.Wiring`** (`freeway-cloud`) — the eight positional arguments
  of `PeerHub.wire` became one named record; the mesh's cross-module
  inputs can no longer drift apart at a call site.

## Fixed

- **JSON override files parsed as properties** (`freeway-boot`) — the
  hot-reload file tier previously `Properties.load()`-ed every override
  regardless of extension, so `application.json` in the working directory
  or listed in `freeway.config.file` produced mangled keys
  (`{"app.name"="..."}`) and its real keys resolved to null. The shared
  reader dispatches by extension and JSON overrides now work at startup
  and across hot reloads.
