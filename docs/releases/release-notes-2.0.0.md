# Freeway 2.0.0 Release Notes

> Version: 2.0.0 | Highlight: **One owner per fact: composition, HTTP, persistence, events, and cloud capabilities converge on explicit contracts with no compatibility aliases.**

2.0.0 is a deliberate breaking release. It is not a feature pile-on; it is an
architecture and vocabulary cleanup. The migration path is the compiler: old
names are removed rather than aliased, every replacement is documented in
[CHANGELOG.md](../../CHANGELOG.md), and `freeway-ext` ships on the same 2.0.0
train.

## Highlights

### Composition and lifecycle have one path

- `ModuleDeclaration` makes class declarations and configured instances one
  composition value. `FreewayApp`, `Freeway.create(...)` and module discovery no
  longer keep parallel module-instance paths.
- `OrderingSupport` and `TopologicalOrder` own contribution ordering; domain
  strategies still choose their edges.
- `ConfigFilePlan` owns standard file names, precedence, startup reads and
  snapshots. CLI keys are literal: `--mode=prod` is an application key named
  `mode`, not a rewritten framework key.
- Contribution construction has one lifecycle: construct/publish, seal, then
  field injection and `@PostConstruct`.

### HTTP is one seam

- `RequestHead` + `RequestViewSupport` own request reads for `HttpTransport` and
  WebSocket sessions.
- `HttpTransport` is the adapter SPI; `HttpExchangeSupport` is final composition.
  Commit state and framed body streaming belong to the transport.
- `ResponsePlan` + sealed `ResponseBody` replace parallel byte/stream/file
  response methods. gzip, `Content-Length`, and HEAD/204/205/304 behavior have
  one owner.
- `ErrorResponse` + pure `ErrorMapper` separate error data from writing;
  `HealthResult` is shared by HTTP health and cloud readiness.
- The HTTP/2 frame family is a sealed `Http2Frame`; a new frame cannot compile
  without its handling branch.
- The engine implementation now has an explicit public-surface budget in
  `api/http-engine-surface.txt`; its public types are only cross-package
  assembly seams.

### DB owns its SQL

- `DatabaseImpl.assemble(...)` is the single standalone/module assembly path.
  `DbModule` binds one selected `Dialect`; `Dialect.ofId(String)` is the catalog
  entry.
- `H2Dialect` no longer inherits `PostgresDialect`; `H2Mode` describes H2 syntax
  modes without pretending H2 is PostgreSQL or MySQL.
- `Orm` query construction goes through `Sql`; pagination, returning, upsert
  and transactional-DDL capabilities are stated by the dialect.

### IoC and public surfaces state their permissions

- `Extension` is read-only; writes remain in the internal contribution store and
  enter only through `Binder.contribute(...)`.
- `Container.describe()` and `AppRuntime.describe()` expose metadata-only
  composition snapshots without instantiating lazy services.
- `Scope.THREAD` became `Scope.BOUNDARY`, with `ScopeBoundary.withinChecked(...)`
  for checked-exception callers.
- `JsonCodec` is an IoC capability; replacing the container's codec/coercer
  governs HTTP and Cloud RPC.
- Public types under `internal` packages are an explicit budget in
  `api/internal-surface.txt`.

### Cloud packages and names describe their plane

- Implementation types moved to `cloud.<domain>.internal`; top-level
  `cloud.internal` contains only `BackendTypeGuard`.
- RPC and event mesh use `Rpc*` / `Mesh*` vocabulary. The event mesh is
  explicitly `MeshEventBus`, not a second `EventBus`; Fleet lives in
  `freeway-ext`.
- Domain modules no longer repeat the `Cloud` prefix already supplied by the
  package.
- `ReadinessAwareRegistry` makes readiness-aware registration and flipping an
  explicit capability. `ServiceRegistry.readinessCapability()` returns that
  capability, or empty when the backend has no readiness concept; the base
  registry never advertises a boolean with inert default methods.
- `ServiceHealth` enforces `ready ⇒ live` and requires `lastSeen`.

### Event channels are named, not inferred

The local `EventBus` now states its channel in every method name:

| Old shape | 2.0 shape |
|---|---|
| `publish(event)` | `publishEvent(event)` |
| `publish(topic, payload)` | `publishTopic(topic, payload)` |
| `publishAsync(...)` | `publishEventAsync(...)` / `publishTopicAsync(...)` |
| `publishOrdered(...)` | `publishEventOrdered(...)` / `publishTopicOrdered(...)` |
| `subscribe(...)` | `subscribeEvent(...)` / `subscribeTopic(...)` |
| `stream(...)` | `streamEvents(...)` / `streamTopic(...)` |
| `EventSubscriber.of(...)` | `EventSubscriber.ofEvent(...)` / `ofTopic(...)` |

The one-argument `String` can no longer be mistaken for a topic by overload
resolution. `MeshEventBus.publish` and `FlowEventBus.publish` remain topic-only;
they are separate planes with separate vocabulary.

### Public vocabulary is consistent

- `find(...): Optional<T>` for optional lookup; `require(...)` when absence is
  exceptional; `get(...)` remains the throwing container lookup.
- The multi-database lookup is `DatabaseCatalog` (an immutable catalog, not a
  registry with a register operation); the HTTP engine's live-connection set is
  `ConnectionTracker`, not `ConnectionRegistry`.
- Class-based HTTP wrappers are `PendingValue` / `PendingHandler` /
  `PendingEndpoint`; `StaticResourceMount` names its route-chain hop
  `withRouteFallback` / `routeFallback`; `BeanPlan.hasConstructor()` and
  `KnownKeys.namespaceOf(...)` state their facts directly.
- Optional inputs are split by role: `FreewayHttpEngine` and `PeerHub` each
  expose `Deps` for collaborators and `Options` for caller-selected values;
  `RpcHttpClientDefault.Deps` holds collaborators while its `Options` holds
  timing policy; `PeerConnector.Options` and Consul's `ConsulOptions` name
  caller-selected values rather than object wiring.
- `withX(...)` always returns a new value; bare nouns remain reads.
- `body/text/json/file/sendText` replace ambiguous `output`/`send` families.
- `Row.asString/asInt/asLong`, `metadata()/putMetadata(...)`, `linkTo(...)`,
  `addDeferred(...)`, `EventBus.Stats` and `MeshEventBus.Stats` name their facts.
- `FlowExecution` and `FlowEngine.run/find/require` separate workflow execution
  from expression evaluation; `FlowContext.stop/isStopped` is the one stop
  contract.

## Breaking changes at a glance

- `RpcHttpClient` retry/deadline overloads are abstract contract methods;
  implementations must honor the caller's attempt cap and deadline.
- `RemoteProxyFactory.withServiceId/withMapping/withTimeout` return new
  factories instead of mutating the receiver.
- `ObjectStorage.get` became `find`; `SecretStore.get/getBytes` became
  `find/findBytes`.
- `RpcResponse.headers` is deeply immutable; framework leaf exceptions are
  `final`.
- No compatibility aliases were added for any renamed or removed shape.

## Verification

```text
mvn clean test
  commons 447 · ioc 341 · boot 139 · http 550
  db 550 · flow 141 · cloud 375
  total 2,543 — 0 failures, 0 errors, 0 skipped
```

`mvn -Pdemos -DskipTests compile` compiles the in-repo consumers against the
reactor, and `git diff --check` is clean.

## Compatibility

- Core artifacts: `2.0.0`.
- `freeway-ext`: `2.0.0`, built against this core release.
- Java: 25+.
- No new runtime dependencies were added to the core.
