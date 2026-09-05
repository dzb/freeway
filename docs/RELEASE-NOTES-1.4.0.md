# Freeway 1.4.0 Release Notes

> Version: 1.4.0 ｜ Highlight: **one config read path — the symbol chain is the single entry point, and every seam is declared**

## Headline: the config system converges

Freeway's configuration previously exposed parallel read paths with subtly
different precedence (`AppConfig.get` vs `SymbolSource.resolve`), five
type-parsing idioms, and a hidden provider tier that let JVM flags lose to
config files. 1.4.0 collapses all of it into one explicit pipeline:

- **One parser for every format** — `ConfigFileReader` normalizes `.json`
  (dotted-key flattening) and `.properties` identically, so a file parses
  the same at startup and on every hot reload.
- **One read entry** — `SymbolSource` is the only resolver and it returns
  raw strings. Typing is an explicit second step: declare a `SymbolSpec`
  once (key + default + parser), then `SPEC.parse(symbols.resolve(...))`.
  `AppConfig` is no longer a reader: it owns profiles, the cascade snapshot
  (never secrets) and the hot-reload lifecycle.
- **Four declared tiers, deterministic** — CLI (0) / JVM system properties
  (5) / declared env mapping (10) / config files (20), in one ordered list;
  cloud secrets slot in at 15 by declaring their order. JVM `-D` now
  overrides files (the process-level override, matching Spring Boot
  convention). The raw-env fallback tier is gone: unknown symbols fail fast
  instead of silently matching an unrelated environment variable.

## What it means

The precedence story is now readable from one rule instead of emergent
behavior: each tier answers one ownership question, module order never
sneaks in, and nothing is parsed twice by two different code paths. Config
declarations across cloud/db/http moved onto the same `SymbolSpec` shape,
with the library fallback values sharing the config layer's single source
(`*_DEFAULT` constants).

## Cleanup in the same spirit

Dead or placeholder surface was removed rather than documented:
`EventBus.hasSubscribers(...)`, the no-op `publishOrdered(key, event)`, the
`@RoundRobin` strategy marker (single implementation, no consumers), and the
spec-aware `SymbolSource.get(SymbolSpec)` accessors. `PeerHub.wire`'s eight
positional arguments became one named `Wiring` record so the mesh's
cross-module inputs cannot drift at a call site.

## Modules

- **freeway-ioc** — `SymbolSource` raw-string resolver + declared tiers;
  `commons.config` decoupled.
- **freeway-boot** — `AppConfig` owns profiles/snapshot/hot reload; shared
  `ConfigFileReader`.
- **freeway-cloud / freeway-db / freeway-http** — typed `SymbolSpec`
  declarations and single-source defaults; `PeerHub.Wiring`.
