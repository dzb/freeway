# Freeway 1.5.3 Release Notes

> Version: 1.5.3 | Highlight: **Module tree becomes data, flow rewritten as freeway's own graph interpreter, "compatibility is not a goal" becomes an explicit design rule.**

1.5.3 is a structural + semantic release: module composition becomes pure data
(`ModuleNode` is now an immutable tree with `@SubModule` bundle declarations),
flow gets a full redesign as freeway's own graph interpreter (schema v2 to v3),
the symbol chain collapses to a single implementation, entry-point factories
unify to `create`, parameter matrices become records with withers, and a full
audit pass closes every confirmed defect across all seven modules. ~243 tests
green.

This is a **breaking release**. The design rule is explicit: **compatibility is
not a goal** -- compile errors are the migration path. See the Migration section
in [CHANGELOG.md](../CHANGELOG.md#unreleased) for the full replacement table.

## Highlights

### Module tree becomes a value type (freeway-ioc)

`ModuleNode` is now an immutable tree with two node kinds: application root and
module node. Class declarations are resolved lazily at container-creation time
(not eagerly at `of(Class)`), so the same class-only tree can be loaded into
multiple containers. Structural validation (duplicate declarations, `@SubModule`
cycles) runs before any user constructor. `ModuleNode.group(...)`,
`ModuleNode.of(module, children...)`, and `CloudModules.standard()` are all
deleted -- bundles use `@SubModule` on the module class.

### Flow v3: from "ported engine" to "freeway's graph interpreter" (freeway-flow)

The module was originally a port of solon-flow 4.0.2; this release removes every
mechanism that belonged to a standalone framework (own DI seam `FlowContainer`,
own marker table `FlowMarkerIndex`, own interceptor chain `FlowOptions`, own
replay-skip "persistence" `FlowTrace`) and replaces them with freeway's existing
answers. Execution changes from recursive descent to iterative frontier walking
(tested with 20,000-node chains). Schema upgrades from v2 to v3: task vocabulary
closes to `@name`/`#graphId`/inline components + node `data` field; `when`
expressions compile at `create()` time; `PARALLEL` gains a `join` declaration
(`merge` default: branch isolation + conflict error).

### SymbolSource: one chain, one implementation (freeway-ioc)

The system-property tier had two implementations with different semantics
(container chain vs. `SymbolSource.systemProperties()` flat source). Now there
is one: `SymbolSource.of(Coercer, SymbolProvider...)`. The independent source
is `SymbolProvider.systemProperties()`. Extensions (Jetty, Undertow, HikariCP)
each lose one line of duplicated assembly code.

### Entry-point factory unification (freeway-boot)

`FreewayApp.of(...)` becomes `FreewayApp.create(...)` (69 call sites),
`FlowEngine.newInstance(...)` becomes `FlowEngine.create(...)` (24 call sites).
The rule: `of` builds a value, `create` is a framework entry point, `.builder()`
is fluent assembly.

### Parameter matrices become records (freeway-db / freeway-http / freeway-cloud)

Seven-position constructors with adjacent same-type parameters (two `Duration`,
two `long`) that could be swapped without a compile error are replaced by
`Wiring` records with `defaults()` and per-field withers. Affected:
`PeerConnector`, `PoolConfig`, `MigrationRunner`, `FreewayHttpEngine`,
`Orm.findAll` (`FindOptions`).

### "Compatibility is not a goal"

The design rule that "compatibility is not a goal" replaces the previous
"preserve old arity constructors for adapters." New components change the
canonical constructor shape directly -- the compile error is the migration.
`freeway-ext` adapts in the same batch.

### Documentation: 17 corrections, rules rewritten

The audit found 17 places where documentation contradicted implementation
(HTTP response javadoc, cloud event hook ordering, DB view semantics, IoC
lock striping claims, config cascade layer list). All corrected. Design rules
in `AGENTS.md` are rewritten: naming convention (`XDefault` vs `XImpl`),
optional-input pattern (overload ladder vs. parameter record), config
ownership by layer, "file size is not a reason to split."

## Bug Fixes

- **Coercer: Number to boolean** -- `0.5` and `4294967296L` no longer silently
  become `false` via `intValue()` truncation; evaluated by exact decimal
  magnitude.
- **Config file read failures** -- all paths now report clearly: file exists but
  unreadable throws `IllegalStateException`; declared-but-missing path logs a
  WARN; optional working-directory files remain silent.
- **Profile variant no longer overrides activation key** -- `overrideFiles` now
  carries a role (`BASE`/`PROFILE_VARIANT`/`DECLARED`).
- **AppBuilder.start() composition phase now in try** -- module class declared
  twice or bad SPI provider no longer leaks a hot-reload watcher.
- **Migration lock races** -- stale owner token removal is conditional on
  observed `executed_at`.
- **Foreign pool release** -- `PooledConnection` from another pool fails with
  actionable `SqlException`, not `ClassCastException`.
- **JSON write method rename + type mismatch** -- `JsonObject.object(key)` /
  `JsonArray.object()` become `newObject` / `newArray`; type mismatch throws
  `IllegalArgumentException` instead of returning null.
- **BeanIntrospector cache no longer pins classloaders** -- `WeakHashMap` replaced
  by per-class `ClassValue`.
- **Log config keys centralized** -- 23 string literals across five classes
  converge to `LogKeys`; invalid values log-and-fallback instead of silently
  replacing or throwing.
