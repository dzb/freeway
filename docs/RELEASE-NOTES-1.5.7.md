# Freeway 1.5.7 Release Notes

> Version: 1.5.7 | Highlight: **Scoped primitives converge into one `ScopeSession`; cloud mesh and registry lifecycles are bounded and observable; HTTP, Flow, DB and IoC get correctness fixes.**

1.5.7 is a convergence release. The deferred-action and scoped-value primitives
now share one session state and one close order, so the old "cache outside
Defer" nesting rule is gone. The cloud mesh no longer writes sockets on the
publishing thread, registry shutdown cannot be resurrected by a heartbeat, and
an external registry no longer makes readiness report the wrong story. The HTTP
layer gets one header budget, bounded request-body drains and case-correct
tracing; Flow, DB and IoC close the remaining correctness gaps found by their
probes. The public surface shrinks where the old shape was an obligation rather
than a capability.

This is a **breaking release**: compatibility is not a goal — compile errors are
the migration path, and every renamed or deleted shape has a replacement row in
[CHANGELOG.md](../CHANGELOG.md). Consumers move together with the framework;
`freeway-ext` ships on the same 1.5.7 train.

## Highlights

### Scoped primitives: one `ScopeSession` (freeway-commons / freeway-ioc)

`Defer` and `ScopedCache` are now two façades over one session state. A value
scope opened inside a `Defer` action scope attaches to it: values live until the
action scope closes, and the close order is fixed — deferred actions first, then
values. The action façade alone controls rollback (`Defer.rollback()`); a cache
handle cannot roll back the action scope it attached to.

The value **lifetime** and the value **access window** are separate:
`ScopedCache.isActive()`/`get` only see values during the cache block and the
action drain, while attached values remain stored until the owner closes. This
removes the previous leak where one cache block made the rest of the action
scope cache-capable. `Scopes`, `DeferScope` and `ScopedCache.Session` are gone;
`Defer.within` keeps only the `Runnable`/`Supplier` forms.

`Scoping.within` uses the same value scope. When nested inside a `Defer` action
scope, its thread beans attach and live until that action scope closes; outside
a value window they are not resolvable.

### Cloud: no unbounded waits or silent readiness

- **Mesh publishing never blocks on a peer.** `PeerConnection` has a bounded
  per-connection queue (256 frames) and its own sender thread; a slow or stopped
  peer is disconnected instead of blocking the publisher.
- **Registry shutdown is final.** `RegistryLifecycleHook.stop()` sets `stopped`
  before stopping the heartbeat, so a late `renew` cannot re-register the node
  after drain. Startup registration failure compensates what was already
  registered.
- **External registries keep readiness truthful.** Registration/renewal/drain
  are core state and are reported regardless of the bound backend; the
  contributor no longer deactivates itself when discovery/registry are not
  `@Local`.
- **Keepalive has a real budget.** The probe interval and the pong budget are
  separate: `timeout = max(3 × interval, 1 s)`, with one probe in flight. A JVM
  pause no longer closes a healthy link; a half-open peer is still disconnected.
- **TLS and registry credentials rotate online**, discovery results are cached
  with an implementation-internal TTL, the mesh can be fed by discovery
  (`event.discovery-feed-ms`), and `/metrics` exposes mesh counters. See the
  cloud sections of the CHANGELOG for the protocol and key details.

### HTTP: one budget, bounded drains, case-correct tracing

- `freeway.http.server.max-header-bytes` is the single inbound header budget
  (default **16 KiB**), applied by both HTTP/1.1 and HTTP/2 and by the ext
  engines; the old HTTP/1.1 8 KiB constant and the HTTP/2 table limit were two
  answers to one question.
- HTTP/2 no longer loses W3C trace headers to header-name case: `Headers.copyOf`
  is the one normalization point, and `RequestView.headers()` declares lowercase
  keys.
- A response that leaves an unread HTTP/2 request body behind drains it with a
  bounded linger instead of waiting forever.
- No-body responses carry the `Content-Length` rule from the writer, error
  responses are JSON, and response framing has one place to be right.
- The access log is an `HttpExchangeEvent` observer rather than a filter:
  preflights and health probes each produce a line, and a custom destination is
  a subscriber, not a filter replacement. `AccessLogFilter` is gone.
- `setPathVars` now *replaces* path variables instead of merging them; engine
  `reset()` is no longer the only thing standing between requests.

### Flow, DB, IoC and Boot correctness

- **Flow:** failed branches discard their write layer instead of leaving it on
  the execution thread; `PARALLEL` out-edge `when` conditions are rejected at
  build time; graph validation is declaration-order independent (seeded random
  graph probe); join-domain depth is memoized, so deep graphs no longer blow the
  stack; `FlowContext.Branch` is the commit/discard boundary.
- **DB:** `Sql` clause fields have one render order (`GROUP BY` / `HAVING` /
  `ORDER BY` / `LIMIT` / `OFFSET`), `ON CONFLICT` actions must follow their
  target, and `ON` accepts parameters like every other condition. `OwnedTransaction`
  gives the transaction-owning paths one lifecycle.
- **IoC:** `bind(Concrete)` no longer drops class-level `@Primary`/`@NotThreadSafe`/
  `@Marker` metadata; `EventBus` no longer holds a `Container`; `SymbolSource.resolve(SymbolSpec)`
  is a seam method, not a default that silently loses the coercer.
- **Boot:** hot reload reacts to `WatchService` `OVERFLOW` instead of silently
  missing a batch; cascade overlay order is derived from `SymbolProvider.TIER_*`;
  `freeway.log.*` keys that cannot take effect (CLI/working-dir log keys,
  class-load-time keys in files) are named at startup.

### APIs and documentation

One-shot startup with `Launch` is now `FreewayApp.run(Launch.of(args), modules)`;
lifecycle events are one `AppLifecycleEvent(Container, AppState)`; class
declarations use `create(Class…).start()` / `.add(Class…)`. Flow's PlantUML
display callback is the sealed `PlantUmlSubject`; `NamedTaskHandler`,
`PlantUmlDisplayContext`, `PlantUmlDisplayResult` and `PathJoiner` are gone;
`hpack` is merged into `engine.http2` with package-private internals. README,
the developer guide, config docs and skills are aligned with the current surface,
and an API-documentation consistency test covers the backticked names they cite.

## Migration

The full replacement table is in [CHANGELOG.md](../CHANGELOG.md). The changes
that need knowing rather than grepping:

1. **`FreewayApp.run(String[] args, modules)` is gone.** Use
   `FreewayApp.run(Launch.of(args), modules)`; class declarations go through
   `FreewayApp.create(Class…).start()` or `.add(Class…)`.
2. **`AppStartedEvent` / `AppStoppingEvent` are gone.** Subscribe to
   `AppLifecycleEvent` and filter on `state()` (`RUNNING` / `STOPPING`).
3. **The scoped primitives changed shape.** `Scopes`, `DeferScope` and
   `ScopedCache.Session` are deleted; `Defer.within` has only `Runnable` and
   `Supplier` forms; manual rollback is `Defer.rollback()`; a cache handle is a
   read-only `ScopeSession` token, and `ScopedCache.isActive()` is the access
   window, not "the action scope holds values".
4. **HTTP access logging moved to `HttpExchangeEvent`.** Delete custom
   `AccessLogFilter` subclasses; disable the built-in line and subscribe to the
   event if you need a custom destination.
5. **`setPathVars` replaces, it does not merge.** Adapters/tests that relied on
   `reset()` to clear leftover variables must pass the full map.
6. **Several internal/public shapes were deleted** (`Node` meta/prev accessors,
   `FlowEvaluation.runTask`, `Graph.nodeOrThrow`, `NamedTaskHandler`,
   `PlantUmlDisplay*`, `PathJoiner`, `hpack` package). The compile errors name
   their replacements in the CHANGELOG.

## Numbers

- Core: **2 374 tests across 7 modules**, plus 1 runnable demo test
  (418 commons / 320 ioc / 125 boot / 493 http / 531 db / 128 flow / 359 cloud).
- Ext: **212 tests (7 environment-gated skips)** across 8 modules, including
  Jetty, Undertow, HikariCP, Kafka, Consul, the adapter testkit and the
  benchmark.

## Thanks

Freeway 1.5.7 is the result of a long convergence pass: every duplicate entry
point, every second answer to the same lifecycle question, and every test that
only documented the implementation was challenged. The framework is smaller in
surface and stricter in the places that matter.
