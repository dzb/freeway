# Freeway 1.5.6 Release Notes

> Version: 1.5.6 | Highlight: **Configuration keys get one declared home and a startup check for unknown ones. The composition vocabulary collapses to modules plus an entry list. Lifecycle ownership is stated where it can be enforced.**

1.5.6 is an explicitness release. Three things that used to hold only by
convention became checkable facts. A module's configuration keys are declared in
one table, and a `freeway.*` key that no module reads is named at startup instead
of being ignored. The composition surface is "modules + an entry list", with the
validated tree moved behind `ioc.internal` where it belongs. And `@PostConstruct`
is pinned to the managed half of the lifecycle, so `Container.create` is `new`
plus dependency resolution and nothing else. Alongside them, a correctness round
fixed a concurrency defect that reported a dead end on a graph which had already
run, a close/`get()` race that handed out a proxy into a sealed container, and a
drain-lock window that could deadlock. Reflection — the one machinery this
framework cannot delete — got an inventory: every reflective site, its cache
shape, and the rules for adding one. ~2255 core tests, ~199 ext tests green.

This is a **breaking release**: **compatibility is not a goal** — compile errors
are the migration path, and the full replacement tables live in
[CHANGELOG.md](../CHANGELOG.md). Consumers must move together with it: **ext
ships on the same train** (this release adds the Consul service-discovery
adapter, and `freeway-http` no longer produces its `tests` classifier artifact).

## Highlights

### Configuration has one declared home, and unknown keys are named (freeway-ioc / freeway-boot)

`KnownKeys` turns "which keys does this module read" from something you find by
reading code into a declared, checkable value. Each module owns a `ConfigKeys`
table spelled as full literals, and contributes it as one vocabulary; a table key
outside the declared namespaces fails the bind rather than sitting outside the
check. `UnknownKeysHook` then reports, once at startup, a `freeway.*` key nobody
reads — with a spelling suggestion when it is within edit distance 2 of a
declared key, and a direct "declared but unread" when it falls inside a
namespace a module owns. Identity strings (hook ids, WS paths, contribution ids)
stay out of the tables by type, which is what keeps them out of the vocabulary.
The database surface follows the same discipline: `freeway.db.schema.mode`
replaces the boolean `freeway.db.schema.auto`, adding a `validate` tier for
production (migrate, then compare entities against the database and fail on
drift); setting only the retired key now **fails startup** and names the
replacement, because the old key was the only way production could say "no
automatic DDL" and silently landing on the default `auto` would have switched it
on.

### Composition is modules plus an entry list (freeway-boot)

The tree type `ModuleNode` moves into `ioc.internal` and the entry points that
exposed it are deleted (`Freeway.create(ModuleNode)`,
`FreewayApp.run`/`create(ModuleNode…)`, `AppBuilder.add(ModuleNode…)`,
`Container.moduleTree()`). `AppBuilder` is dissolved into `FreewayApp`, which is
now both the entry point and the application-to-be (`create(...)` returns it,
the chain returns it, `start()` returns `AppRuntime`); `FreewayApp.run(String[])`
— the one entry point that let the classpath decide the application, against the
framework's first rule — is gone. The validated structure stays, with its
invariants (duplicate classes, `@SubModule` cycles, pre-order) and its startup
log, just no longer assembled from outside. The application root can now be
named — `FreewayApp.create(...).name("order-service")` — a display-layer fact
that reaches the startup log and composition errors and nothing else.

### Lifecycle ownership: `@PostConstruct` is the managed half (freeway-ioc)

`@PostConstruct` fires on the managed path and only there. `Container.create` is
`new` plus dependency resolution: constructor arguments and `@Inject` fields, no
lifecycle — which also removes a silent double-run of the callback under
`to(c -> c.create(X))`, the most natural provider shape. All four realize paths
run it once, in the binding's own materialize step, and because the instance is
bound, `@PreDestroy` pairs with it at `close()`. Cleanup preference is now
stated: implement `AutoCloseable` (the container sees it by type), and reach for
`@PreDestroy` only for cleanup that must still publish on the `EventBus`, which
outlives that phase.

### The reflection inventory (every module)

`docs/freeway-reflection.md` is the single page for the framework's reflective
sites: the mechanism, the cache shape, the path, and the rules (ClassValue for
anything keyed by a class, failures never cached, spread invocation as the
standard form, pre-adaptation licensed only by profiling). It exists for
hygiene, not for GraalVM — the point is that the one machinery that cannot be
deleted has one home and one cache discipline. The inventory pass also fixed a
real cost: `BeanIntrospector.selectConstructor` rescanned
`getDeclaredConstructors` on every instantiation and now caches the selection per
(type, annotation).

### Cloud: one frame classifier, jitter on both backoff paths (freeway-cloud)

`PeerHub` and `PeerConnector` each carried a five-branch frame dispatch kept in
sync by a comment; the security rule — an event frame must never reach the
broadcast plane before a peer is admitted — was stated twice and enforced twice.
It is now `MeshFrame.classify(text, handshaken)`, a sealed interface, with each
leg keeping only what genuinely differs. The two backoff paths share
`resilience.Backoff` and both carry jitter (the mesh leg had none, and it is the
one caller that re-dials an entire fleet after a rolling restart); the first-hop
semantics and the config keys stay per-caller. `AsyncCarrier` lets the async bus
channels carry the submitting thread's ambient context, so a `publishAsync`
handler observes the same trace a synchronous one does.

### A registry server for development, and a Consul adapter in ext (freeway-cloud / freeway-ext)

`RegistryServerModule` exposes the in-process registry over HTTP
(register/renew/unregister/query), so a single freeway process can stand in as
the registry during development — the paths and the store are the same ones the
in-process defaults wrap. For production, `freeway-ext` gains
`freeway-cloud-consul`: `ServiceRegistry` and `ServiceDiscovery` bound
`.primary()`, implemented against Consul's agent API with the JDK `HttpClient`
alone (no third-party client), with `scheme`/`basePath` carried in service
`Meta` so a discovered endpoint is called exactly as it was registered. The
adapter was verified against a live Consul agent; the four behaviours a stub
could not catch (Meta keys cannot contain dots, a TTL check starts critical, the
check id must be explicit, errors must carry the agent's response body) are
recorded in its design note.

### Correctness round (freeway-flow / freeway-ioc / freeway-http / freeway-db)

- **Parallel join bookkeeping is atomic** (flow). The arrival count and the
  provisional dead-end marker were two structures updated in two steps, so a
  branch could mark a gateway dead *after* the arrival that completed it had
  already cleared the marker: a graph that had run would still report
  `dead end at node 'gw'`, and the message described a lost branch where nothing
  was lost. The whole transition now runs under the node's monitor.
- **Close vs. `get()`** (ioc): the cache miss path could publish a proxy into the
  already-sealed `proxyCache`, handing the caller an object whose every method
  throws; "still open?" and the publish are now one step under the lock, and the
  PROTOTYPE branch gained the same check. The final drain no longer holds the
  realize lock — the one window in which a user callback joining a resolving
  thread could deadlock.
- **Body limits** (http): `readBody` (the adapter seam) delegates to the one
  limiter the built-in engine runs, so adapter and built-in 413 behaviour are the
  same code; a filter's `setMaxBodySize` adjustment no longer outlives its
  request on a keep-alive connection; and the HTTP/2 frame serializers
  (`DataFrame`, `HeadersFrame`, `GoawayFrame`, `PushPromiseFrame`,
  `NotImplementedFrame`) write the bytes they declare.
- **`Sql.setColumn`** (db): a column name is validated as a name (and was only
  null-checked before), and a nested `Sql` value is inlined as a parenthesized
  scalar subquery instead of being bound as a JDBC parameter.

## Bug Fixes

- **A `join` gateway whose branches mix iteration domains is rejected at
  `load()`** — a LOOP upstream makes the arrival count meaningless, and the old
  failure surfaced at the end of a run as a misleading "branch lost" message.
- **Method-parameter injection is rejected at startup** instead of being
  silently dropped: `@Inject`/`@Symbol` compile on a method parameter (the
  target must allow `PARAMETER` for constructor parameters), but the container
  has no injection point there, so the parameter arrived as the caller passed it.
- **`/health/live` answers `application/json`**, and every JSON response is
  produced by `sendJson` — two sibling probes used to disagree on `Content-Type`.
- **`TracingFilter` skips the *configured* health path**, not a copy of the
  default; `PlantUML` display functions that throw are reported instead of being
  swallowed; `Backoff` bounds its exponential shift by the base so it cannot wrap
  to zero; `PeerConnector.Wiring` rejects a cap below its base; the
  `CloudHttpClient` in-flight settle moved into `finally`; `ScopedCache` cleanup
  follows entries, and the container dropped its JVM-global side table.
- **`require*` refuses, `ensure*` repairs** — three private `ensureOpen()` /
  `checkOpen()` variants became `requireOpen()`, and `Orm.ensureInsertable` (which
  only threw) became `requireInsertable`; the distinction is now written down in
  `AGENTS.md`.

## Migration

Every renamed or deleted shape has a replacement row in the CHANGELOG tables —
compile errors point the way. Three that need knowing rather than grepping:

1. **`Container.create` no longer runs `@PostConstruct`.** A type that needs
   lifecycle must be bound (`binder.bind(X.class).to(X.class)` +
   `container.get(X.class)`), where both halves are paired.
2. **A retired configuration key now fails startup.** `freeway.db.schema.auto`
   is deleted and its value is not honoured; the failure names
   `freeway.db.schema.mode` and the `true`→`auto` / `false`→`off` mapping.
3. **`freeway-http` no longer produces `freeway-http-<version>-tests.jar`.**
   Nothing in this repository or in `freeway-ext` consumed it; an external
   consumer of that classifier will fail to resolve, which is the intended
   signal.

## Numbers

Core: 2255 tests, 7 modules — including a new runnable `demo/registry-server`.
Ext: 199 tests (7 skipped — 5 broker-gated, 2 Consul-agent-gated), including the
new `freeway-cloud-consul` module. The correctness audit behind this release —
its criteria, evidence grading, and every withdrawn judgement — is
[docs/audit-correctness-and-redundancy-1.5.6.md](audit-correctness-and-redundancy-1.5.6.md).
