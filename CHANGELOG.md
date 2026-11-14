# Changelog

All notable changes to Freeway will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Renamed / API vocabulary

- **`AsyncCarrier` → `ContextCarrier`** (`freeway-commons`): the ambient
  work-context carrier moved out of `ioc.event` into
  `com.jujin.freeway.commons.util`, beside `ContextExecutor`. It is the one
  contract every offload point uses (event bus, flow parallel branches,
  asynchronous RPC); the cloud context module's binding now captures MDC in
  addition to the invocation context, so an asynchronous task logs the same
  `traceId`/`spanId` as inline code and restores the worker's previous MDC
  afterwards.

- **HTTP**: `HttpContext` → `HttpExchange`, `HttpContextSupport` →
  `HttpExchangeSupport`; `ResolvableSupport` → `ResolvableValue`, `SslSettings` →
  `SslConfig`; `HttpServer.secure` / `HttpEngine.secure` → `isSecure`;
  `ExchangeHandler.websocket` → `matchWebSocket`; `websocketIndex` →
  `webSocketIndex` on `HttpPipeline` / `HttpServer`. `HttpResponse`'s body-writing
  family is now `body(byte[])`, `body(InputStream,long)`, `text(String)`,
  `json(Object)`, `file(Path/FileChannel,long,long)`, `sendText(int,String)`,
  `sendJson(int,Object)`;
  `StaticResourceMount.withCacheMaxAgeSeconds(long)` → `withCacheMaxAge(Duration)`;
  `SseEmitter.from(...)` → `pipe(...)`; `ErrorResponse.send(HttpResponse)` →
  `write(HttpResponse)`.
- **DB**: `DatabaseStats` → `PoolStats`; `DatabaseRegistry.get(String)` →
  `find(String): Optional<Database>`; `DatabaseRegistry.all()` → `databases()`;
  `Query.param(...)` → `bind(...)`; `BatchQuery.rows(...)` → `rowsRaw(...)`,
  `BatchQuery.named(...)` → `rowsNamed(...)`; `Row`'s convenience accessors are now
  `asString/asInt/asLong/asBoolean/asBigDecimal/asBigInteger/asLocalDate/...`;
  `Sql.sql()` → `toSql()`, `Sql.setColumn(...)` → `value(...)`.
- **Flow**: `FlowEvaluation` → `FlowExecution`; `FlowEngine.eval(...)` →
  `run(...)`, `evalAndGet(...)` → `runAndGet(...)`; `graph(String)` →
  `find(String): Optional<Graph>`, `graphOrThrow(...)` → `require(...)`;
  `FlowContext.stopped(boolean)` moved into internal
  `FlowContextState.setStopped(...)`; the Graph/Node/Link metadata family is now
  `metadata()`/`putMetadata(...)`; `NodeSpec.linkAdd(...)` → `linkTo(...)`.
- **IoC**: `Container.isActiveBinding(...)` → `isActiveBindingMarked(...)`;
  `EventBus.EventBusStats` → `EventBus.Stats`; `Contribution.add(Class)` and
  `add(id, factory)` → `addDeferred(...)`.
- **Commons**: `ScopedCache.get(key, factory)` → `computeIfAbsent(...)`;
  `JsonUtils.stringify(...)` → `toJson(...)`, `stringifyPretty(...)` →
  `toPrettyJson(...)`; `JsonCodec.convert(...)` → `coerce(...)`.
- **Cloud RPC**: `CloudException` → `RpcException`; `CloudRequest` /
  `CloudResponse` / `CloudHttpClient` / `CloudHttpClientDefault` → `RpcRequest` /
  `RpcResponse` / `RpcHttpClient` / `RpcHttpClientDefault`; `idempotentWith` →
  `withIdempotent`, `idempotentVerb` → `isIdempotentMethod`, `is2xx` →
  `isSuccess`, `propagateMessages` → `withPropagatedMessages`;
  `RemoteProxyFactory`'s `serviceId/mapping/timeout` →
  `withServiceId/withMapping/withTimeout`.
- **Cloud event mesh**: `CloudEventEnvelope` / `CloudEventBus` /
  `CloudEventSubscription` / `CloudEventInterceptor` / `CloudEventLifecycleHook`
  → `MeshEnvelope` / `MeshEventBus` / `MeshSubscription` / `MeshInterceptor` /
  `MeshEventLifecycleHook`; `CloudEventBus.CloudEventStats` →
  `MeshEventBus.Stats`; `CloudEventModule` → `EventMeshModule`.
- **Cloud modules / discovery**: `CloudRpcModule` / `CloudSecretModule` /
  `CloudStorageModule` / `CloudHealthModule` / `CloudResilienceModule` /
  `CloudDiscoveryModule` / `CloudObserveModule` → `RpcModule` / `SecretModule` /
  `StorageModule` / `HealthModule` / `ResilienceModule` / `DiscoveryModule` /
  `ObserveModule`; `CloudHealthContributor` keeps its name; `Health` / `Endpoint` →
  `ServiceHealth` / `ServiceEndpoint`; `cloud.registry` →
  `cloud.discovery.registry`, and `RegistryNodeClient` /
  `RegistryNodeServiceRegistry` / `RegistryNodeServiceDiscovery` /
  `RegistryFleetModule` → `FleetRegistryClient` / `FleetServiceRegistry` /
  `FleetServiceDiscovery` / `FleetRegistryModule`.

  - `ErrorHandler` → `ErrorMapper`: error mapping no longer writes the response.
    A mapper returns `Optional<ErrorResponse>` (the server is the only writer),
    and a throwing mapper is skipped for the next one. Port a
    `contribute(ErrorHandler.class)` lambda to `contribute(ErrorMapper.class)`
    returning `Optional.of(ErrorResponse.of(...))`.

### Added


- **`freeway.log.system`** (freeway-commons): class-load-time backend selection for SLF4J —
  `freeway` (default), `auto`, `logback`/`log4j2`/`simple`, or a provider class name. The default
  now pins Freeway's JUL provider even when a transitive Logback/Log4j is on the classpath; the
  startup report names the active backend, the provider class, the selecting source, and any
  unselected provider with the exact switch. `-Dslf4j.provider` remains the SLF4J-native escape
  hatch and wins. Log output displays SLF4J level names (`WARN`/`ERROR`/`DEBUG`/`TRACE`) even for
  JUL-originated records; configuration keeps accepting JUL names (`WARNING`, `SEVERE`, `FINE`).

- **`ScopeSession.propagate(Executor, ScopedValue...)`** (freeway-commons): wraps an executor so each
  task runs with the submitting thread's `Defer`/`ScopedCache` scope, plus caller-supplied context
  keys. This is the mechanism `ScopeFilter`'s async guidance referred to but could not name, because
  the scope keys were package-private. Deferred actions registered on the worker join the submitting
  scope; a task submitted outside any scope inherits nothing, and `addAction` is synchronized so the
  propagated scope's id set and pending list stay one structure.

- **Runtime description API** (freeway-ioc / freeway-boot): `Container.describe()`
  returns a read-only `RuntimeDescription` (module tree, each binding's
  type/id/scope/markers, contribution ids and order), and `AppRuntime.describe()`
  returns an `AppRuntimeDescription` (state, profiles, config key names, container
  description). A description never instantiates a lazy service and holds no
  service instance or config value, so tools and coding agents can read the graph
  instead of parsing startup logs.
- **`ModuleDeclaration` and mixed composition** (freeway-ioc / freeway-boot): a
  placed module is now an explicit value — either a class declaration or a
  configured instance. It alone owns no-arg construction and `@SubModule` static
  metadata; `Freeway.create(ModuleDeclaration...)` and `FreewayApp.add(...)`
  express mixed lists. boot no longer keeps or instantiates module suppliers of its
  own, and SPI discovery reads the same declaration metadata.
- **Route registration description** (freeway-http): `RouteIndex.descriptions()`
  and `HttpServer.routeDescriptions()` give one route's method/path identity
  without exposing the handler; `WebSocketIndex.paths()` /
  `HttpServer.webSocketPaths()` give the WebSocket paths.
- **`Dialect.ofId(String)`** (freeway-db): the single id→implementation entry point
  into the built-in dialect catalog; an unknown id fails with a hint about binding
  an extension.

- **JsonCodec as a container builtin** (freeway-ioc / freeway-http): JsonCodec is
  no longer bound by HttpModule but is an IoC base capability; the codec reads the
  container's Coercer, so replacing the Coercer still takes effect, and replacing
  JsonCodec is still possible with `.primary()`. This fixes the Cloud RPC client
  subset without an HttpModule being unable to resolve `RemoteCaller`. A custom
  JsonCodec binding that coexists with the built-in default must be marked
  `.primary()`; one rule for "base capability plus replaceable default" applies,
  and HttpModule no longer supplies a second implicit default.

- **`ScopeFilter` (`freeway-http`)**: an optional request-level container value
  scope. With `freeway.http.request-scope.enabled=true`, `HttpModule` inserts a
  `ScopeFilter` after CORS/health and before application filters; within one
  request `Scope.BOUNDARY` services and scoped-cache values are reused and cleaned
  up when the request ends. A caller may also add it explicitly with
  `contribute(HttpFilter.class).add(ScopeFilter.class)`; an explicit contribution
  is not added twice. It does not propagate to child threads (use
  `ContextExecutor` for async work) and it is not a transaction boundary — `Defer`
  still owns commit/rollback.
- **`ScopeBoundary.withinChecked` (`freeway-ioc`)**: the checked-exception entry
  point for adapters; the scope closes on both the normal and the exception path,
  and the original `Exception` is rethrown unchanged.
- **`StaticResourceMount.withSpaFallback(boolean)` (`freeway-http`)**: the terminal
  index fallback for history-mode SPAs. With it enabled, an extensionless GET/HEAD
  path that misses both the static resource and the route chain falls back to the
  mount's `index.html`; a miss with an extension stays a 404. The fallback selects
  the most specific matching mount, so a dedicated `/assets` mount's missing file
  is not swallowed into HTML by a root SPA mount; a route hit wins, and a 405 is
  never rewritten into the SPA page; a mount that opts in but lacks `index.html`
  fails when `HttpServer` is constructed.

### Changed

- **HTTP server spans use the declared route template** (`freeway-cloud` +
  `freeway-http`): the tracing filter names a span `GET /users/{id}` instead of
  `GET /users/42`; requests with no matching route are `GET unmatched`, and
  static-mount traffic is `GET static`. The server pre-matches the route before
  the filter chain for this preview (dispatch still owns the authoritative
  match), so identifier-bearing paths no longer create one span name per
  request.
- **Metrics carry bounded dimensions** (`freeway-commons` / `freeway-cloud` /
  `freeway-http`): `Metrics` gained labeled `counter`/`timer`/`gauge` overloads
  (the default delegates to the name-only methods, so existing backends keep
  recording aggregates). `MetricsDefault` renders `name{label="value"}`;
  HTTP request metrics carry `method`, `route` (the declared template, never
  the raw path; misses are `unmatched`, plus `static`/`spa`/`health`/
  `cors-preflight`) and `status` (bounded classes); RPC metrics carry
  `service`. The unlabeled series for these metrics are no longer emitted —
  aggregate them in the query.
- **Failure classification is a code contract** (`freeway-commons`):
  `RpcException`, `SqlException`, `FlowException` and `StorageException`
  implement the shared `Failure` view (`retryable()`, `outcomeUnknown()`).
  The defaults are conservative; RPC overrides them from its existing `Kind`
  mapping, so no second classification enum was introduced. Application code
  can classify a framework failure without type-switching on the module that
  threw it.
- **Property baselines cover HPACK, JSON and SQL parameters**: `HPackPropertyTest`
  round-trips random header blocks through the frame split and decoder (plus
  Huffman), `JsonPropertyTest` round-trips random trees and bounds malformed
  input to `IllegalArgumentException`, and `SqlParameterPropertyTest` pins
  positional/named/repeated/comment/string/cast binding. All use fixed seeds,
  so a failure is reproducible.
- **Hot reload has an explicit contract** (`freeway-boot` / docs): the file tier
  is swapped live and `SymbolSource` sees new values, but module binding and
  already-injected singletons are startup-static. `docs/configuration.md` now
  carries the full matrix (including the two explicit TLS-material reload
  exceptions), and `ConfigReloadContractTest` pins the distinction between "the
  source changed" and "the component changed".
- **RPC shutdown settles before it interrupts** (`freeway-cloud`): `close()`
  now fails the calls the grace could not drain with the documented
  "RpcHttpClient is closed" failure *before* stopping the executor. Interrupting
  first let an interrupted worker complete the future with "request interrupted"
  and mask the shutdown cause; the worker's later completion is now a no-op.
- **Metric API names use one dot grammar** (`freeway-commons` / `freeway-ioc` /
  `freeway-cloud`): names such as `cloud.event.frames_queued` became
  `cloud.event.frames.queued`, and the `eventbus.*` names follow the same rule.
  The Prometheus exposition is unchanged — dots and underscores both render as
  `_` — so dashboards keep working; the Java-side API names now match the
  documented catalog (`docs/reference/metrics.md`), pinned by
  `MetricCatalogTest`.
- **Failure taxonomy is a documented contract** (`docs/reference/failures.md`):
  every public exception, its retry/outcome classification, and its HTTP boundary
  in one table; `FailureCatalogTest` keeps the table and the source in sync.
- **HTTP/1 parser has a deterministic robustness baseline** (`freeway-http`):
  arbitrary and mutated byte streams must parse or fail with `IOException`, never
  hang or throw unchecked; `Http1xParserRobustnessTest` runs 2 400 seeded cases.
- **Async RPC cancellation is end-to-end** (`freeway-cloud`): cancelling the
  future returned by `RpcHttpClient.callAsync` now interrupts the retry loop
  (backoff included) and asks the in-flight send to cancel; previously only the
  returned future settled and the worker kept retrying. Cancellation is distinct
  from a deadline (a timeout failure), and the in-flight registration is settled
  the same way.
- **Public types in `internal` are budgeted** (`freeway-boot`): `api/internal-surface.txt`
  plus `InternalSurfaceBudgetTest` make every public type under an `internal`
  package an explicit decision instead of an accidental API addition.
- **Flow parallel branches inherit ambient context** (`freeway-flow`): a
  `PARALLEL` fork now wraps each branch with the bound `ContextCarrier`, so
  trace/principal/baggage/MDC cross into the branch worker. Previously only the
  branch write frame crossed, and a branch that logged or called out ran bare.
  The carrier is optional: a standalone engine runs branches bare, as before.

- **Logging backend selection defaults to Freeway's JUL provider** (freeway-commons): previously an
  external SLF4J provider on the classpath (Logback, Log4j 2, slf4j-simple) was pinned automatically
  and won over the bundled JUL backend. Now `freeway.log.system` decides, defaulting to `freeway`;
  `auto` preserves the old external-first behaviour. The change is loud: startup reports the chosen
  backend and offers the switch when another provider is present.
- **External logging backends leave JUL alone** (freeway-commons): selecting Logback/Log4j 2/
  slf4j-simple pins the SLF4J binding and does not attach JUL handlers or forward JUL records.
  JDK-internal JUL logging stays on JUL; an application that wants it in its backend adds the
  standard `jul-to-slf4j` bridge itself. The former `freeway.log.jul-bridge` key is gone.

- **INCLUSIVE joins wait for active flows, not the static in-degree** (freeway-flow): a conditional
  fork that does not take a branch now propagates a dead path to the inclusive joins that branch
  would have fed (dead-path elimination, the BPMN semantics). A join with one taken and one skipped
  incoming flow runs; a join whose every incoming flow is dead never runs and the run fails loudly
  (absence is not a token) instead of reporting a misleading dead end. PARALLEL joins keep the
  static in-degree: a conditional split feeding one is a modelling error and still fails. Dead-path
  reachability is cached per skipped-link target; an over-counted path (a skipped branch reaching a
  join outside its scope) fails with a named error rather than silently miscounting.

- **Flow branch isolation is a `ScopedValue` frame, not a `ThreadLocal` stack** (freeway-flow):
  `FlowContext.beginBranch()` now creates an immutable frame, `Branch.run(body)` binds it for the
  branch body, and the PARALLEL dispatcher carries the submitting thread's frame into its workers
  (`ContextExecutor`-style propagation). A fork nested inside a branch therefore writes into that
  enclosing branch and is discarded with it on failure; previously the worker's empty thread-local
  stack sent those writes straight to the root, so an outer branch could not roll them back.
  `join: "shared"` now writes into the fork's enclosing branch (or the root at top level) instead
  of always the root. Branch-sibling conflicts are still detected; commits/closes refuse a parent
  that folds while a child scope is open. `FlowContext.Branch` gained `run(Runnable)`.

- **Module composition converges into one resolution** (freeway-ioc /
  freeway-boot): `ModuleDeclaration` unifies class/instance, no-arg construction
  and `@SubModule`; `ModuleNode` now only owns binding order, validation and render
  for the normalized tree, and boot's `FreewayApp` and `ModuleDiscovery` no longer
  each instantiate modules or expand bundles. Mixed composition is expressed
  explicitly, and a class declaration is still instantiated when the container
  loads.
- **Ordering rules and topological sort each get one home** (freeway-ioc /
  freeway-commons): `OrderingSupport` unifies the four kinds of ordering edge for
  contributions and deferred actions; `TopologicalOrder` unifies the Kahn sort,
  the ready-node tie-break and the cycle remainder for both extension
  contributions and `DeferAction`. The two ordering domains keep their own policy
  with their callers.
- **DB assembly, dialect and ORM SQL converge** (freeway-db):
  `DatabaseImpl.assemble(...)` is the single assembly/defaulting point shared by
  the standalone path and `DbModule`, and `DatabaseImpl` keeps one full-argument
  private constructor. `DbModule` binds one selected `Dialect` service and a custom
  dialect is still bound by id; built-in ids resolve through the `Dialect.ofId`
  catalog, so four dialect instances are no longer hand-bound. `Orm`'s
  select/insert/update/delete all go through the `Sql` builder (H2 `MERGE` still
  takes the dialect's full-statement rendering); `Sql.offset(n)` now supports
  offset-only and lets the dialect render the spelling —
  `LIMIT -1 OFFSET` / `OFFSET n ROWS` and so on.
- **Startup config file layer gets one owner** (freeway-boot): the new
  `ConfigFilePlan` owns the standard file names, their role, precedence, reading
  and snapshotting. `ConfigCascade` first selects a profile from a base snapshot
  and then hands the same base values to the full plan; the working directory's
  `application.properties/json` are parsed once at startup, while hot reload still
  re-reads everything.
- **cloud internal is split by domain** (freeway-cloud): the context, discovery,
  health and observe implementations move into `cloud.<domain>.internal`, and the
  top-level `cloud.internal` keeps only cross-domain support (the shared HTTP
  client, constant-time comparison, backend guard, service identity, HTTP service
  declaration). `BackendTypeGuard.warnHook(...)` unifies the secret and storage
  guard hooks; `OptionalBinding.get(...)` unifies two optional lookups.
- **Small cross-module convergences** (commons / ioc / http / cloud):
  `Proxies.objectMethod(...)` unifies the Object-method semantics of the
  container's advising proxy and cloud's remote proxy; `RequestViewSupport`'s
  case-insensitive header fallback reuses `HttpHeaders`;
  `HttpPipeline.filtersInOrder()` becomes the single ordering point for built-in
  and application filters.

- **HTTP assembly and response framing converge** (freeway-http): `RouteIndex` and
  `WebSocketIndex` now accept only the final, already-expanded and already-resolved
  route list; `HttpModule` is the single assembly point, so a contribution's direct
  route, `RouteGroup`/`WebSocketGroup` expansion and class handler/endpoint
  resolution each happen once. `ResolvableHandler`/`ResolvableEndpoint` share
  `ResolvableValue`'s two-phase first-assignment state machine;
  `WebSocketSessionSupport` and the built-in frame writer share
  `WebSocketCloseReason`'s 123-byte UTF-8 truncation rule. `HttpExchangeSupport`'s
  four response paths — byte[], stream, file, channel — merge into one `write(...)`
  policy entry point, and the transport resolves a file length that only an opened
  channel can report exactly once, before committing the head; this fixes a framing
  defect where a large file of unknown length took the sendfile path with no
  `Content-Length` in the head, so the writer chose chunked framing while raw bytes
  were actually written. `Compression.gzip` reuses `gzipStream`'s writer
  construction. Breaking: `new RouteIndex(routes, groups)` /
  `new WebSocketIndex(routes, groups)` are gone — callers expand groups first and
  then construct the index. Flow's public value and implementation types `Node`,
  `Link`, `Graph`, `GraphSpec`, `FlowEventBus`, `FlowModule`, `Stepper`, commons'
  `ValidationResult` and http's `SseEmitter` are now `final`; the framework leaves
  no implicit implementation type for users to extend.
- **Request face de-duplicated, extension points read-only, scope renamed**
  (freeway-http / freeway-ioc):
  - New `RequestHead`, which both `RequestView` and `HttpTransport` extend; the
    four request facts — method, path, query, headers — are declared once. New
    `RequestViewSupport` composes one query/header/path-var implementation for both
    HTTP exchanges and WebSocket sessions, with header keys uniformly lowercase.
  - `HttpRequest`'s TLS/remote/bodyStream default implementations are removed:
    these are transport facts, and a missing one must be answered explicitly by the
    implementation rather than masked by `false`/`null`/an empty string or by
    buffering everything.
  - `Container.extension()` now returns a read-only `Extension<T>`; `add`/`seal`
    exist only on the internal `ExtensionStore`, and writes still go only through
    `Contribution`. A read interface no longer exposes write capability.
  - `Scope.THREAD` is renamed `Scope.BOUNDARY`: it was never a ThreadLocal or a
    thread lifetime but the lifetime of a `ScopeBoundary.within(...)` boundary, and
    the old name misleads under virtual threads.
  - `FlowDriverDefault.Builder` is deleted for having no caller: it carried two
    optional fields, conflicting with "a builder holds no defaults of its own";
    construct `FlowDriverDefault` directly when it needs customizing.
- **HTTP response-body execution converges into `ResponsePlan`** (freeway-http):
  `HttpTransport` no longer has four body methods (byte[]/stream/file/channel) or
  their default implementations, and instead accepts one
  `ResponsePlan(head, body)` where the body is a sealed
  `Bytes`/`Stream`/`File`/`Channel`/`Empty`. gzip is stated once, by the head's
  `Content-Encoding`, and `Content-Length`/HEAD are no longer computed three times
  — by the support object, a transport default and a transport override.
  HEAD/204/205/304 do not read the body source but do close a channel the
  transport took ownership of.
- **HTTP error and health contracts are pinned** (freeway-http / freeway-cloud):
  - New `ErrorResponse(status, code, message, details, headers)` plus a pure
    `ErrorMapper`; the mapper only returns data, `HttpServer` is the only writer,
    and 404/405/413/415/422/500 share `{error, code, details?}`, with 405
    additionally carrying `Allow`.
  - `HealthCheck` returns `HealthResult` instead of a bare `Object`; healthy is
    200 and unhealthy is 503, cloud's `CloudHealthContributor` reuses the same
    result type, and cloud's internal duplicate `HealthResult` is deleted.
- **HTTP/WebSocket extension points become composition** (`freeway-http`):
  implementation inheritance between framework types is deleted
  (`AbstractHttpContext`, `AbstractWebSocketSession` and their subclasses). New
  `HttpTransport` / `WebSocketTransport` SPIs plus the final
  `HttpExchangeSupport` / `WebSocketSessionSupport`; the built-in engine type
  `HttpContextImpl` is renamed `HttpTransportImpl`, and `WebSocketSessionImpl`
  splits into `WebSocketConnection` (frames and connection state) plus support.
  Status-code and header validation, `Date`, `Content-Length`, the gzip decision,
  `Vary`, the body ceiling and JSON/coercion now have one implementation each, so
  all three engines behave identically. Behaviour changes:
  - `Content-Length` is framework-authoritative: `body(byte[])` overrides a
    handler-set value with the byte count actually committed (the compressed
    length when gzip is used); `body(InputStream, len)` uses `len` and removes it
    when unknown; 204/205/304 always remove it; HEAD keeps the same length
    semantics as GET — a known length is written as `Content-Length`, an
    unknown/gzipped one is materialized by a buffering transport and added as the
    real length, and a streaming transport keeps declining to declare it.
  - **One exchange per request**: there is no `reset`/`resetMaxBodySize`, no
    `ThreadLocal` pool, and the transport and support are created per request
    (about 64 ns/op more in JMH end-to-end), in exchange for which the reset
    obligation and the cross-request state-leak failure mode disappear.
  - SSE no longer writes `Connection: keep-alive`: the HTTP/1 writer writes
    `Connection: close`, HTTP/2 filters the header, and container adapters leave it
    to their container.
  - WebSocket close reasons are uniformly truncated to 123 bytes (on UTF-8 code
    point boundaries), the built-in engine no longer throws
    `IllegalArgumentException`, and adapters' internal error closes also go through
    the support object rather than bypassing the truncation.
  - Adapter migration path: `UndertowHttpExchange`/`JettyHttpExchange` implement
    `HttpTransport`, and sessions compose `WebSocketSessionSupport`;
    `HttpTransport` gains `isCommitted()`, and the compile error is the migration.
    `HttpResponse.isCommitted()` becomes abstract (no longer defaulting to false),
    `addVary` returns `HttpResponse` for consistent chaining, and the internal
    lifecycle names are unified as `committed`/`resetIfUncommitted`; the HTTP/2
    stream's `headersSent`/`isResponseCommitted()` are likewise unified as
    `committed`/`isCommitted()`, with the H2 transport's `isCommitted()` reading
    that stream fact directly instead of keeping a second verdict.
    `HttpResponse.body(InputStream, long)` and both `file` methods no longer offer
    default fallbacks that would silently break length/gzip semantics — the
    implementation or `HttpExchangeSupport` must provide them;
    `HttpTransport.bodyStream()` becomes `framedBodyStream()`, distinguishing it
    from the already-limited `HttpRequest.bodyStream()` on the handler side;
    `allowsResponseBody()` is unified as `allowsBody()`, and the body rules for
    204/205/304 are centralized in `HttpStatus.allowsBody(int)`.
  - **The commit fact belongs to the transport**: `HttpTransport.isCommitted()` is
    the only commit state, and the support object no longer marks a response
    committed before the transport writes the head. A missing file, a failed file
    open or a failed stream read before commit rolls back the prepared head and is
    written as a 500 by error handling; a failure after commit is a transport
    failure.
  - **Framing belongs to the transport**: a buffering transport adds
    `Content-Length` from the byte count it actually materialized (HEAD likewise),
    while a streaming transport keeps the chunked semantics of no
    `Content-Length`. The HTTP/1.1 adapter adds `Transfer-Encoding: chunked` only
    when no `Content-Length` exists, HTTP/2 does not, and connection-level fields
    (Connection/Keep-Alive/Upgrade/TE and friends) are filtered under HTTP/2. A
    handler-set TE is filtered by the support object; for a file range with
    length<0 the built-in transport uses `channel.size()` and no longer reports
    500.
  - **Jetty `Date`**: Jetty's automatic Date generation is switched off, making
    `HttpExchangeSupport` the only source, which fixes every response carrying two
    `Date` headers.
  - **Final adapter response headers**: Undertow's and Jetty's `applyHead`
    validate, then clear and replay the `ResponseHead`, so a retried failure
    cannot leave the previous response's headers behind; HTTP/2 additionally
    filters connection-level fields.
  - **`setStatus` after commit** is a no-op (consistent with headers); duplicate
    output is still ignored.
  - **HTTP/2 frame family becomes composition**: the `BaseFrame` abstract class is
    deleted in favour of a package-private sealed `Http2Frame` interface; each
    frame composes `FrameHeader`, dispatch uses a sealed pattern switch, and the
    production path no longer casts frame types. Adding a frame type makes the
    compiler demand its dispatch arm. This change targets type safety and
    exhaustiveness, not throughput; performance needs its own JMH/end-to-end
    benchmark.
  - Internal naming: `Headers` → `HeaderStore`; the duplicate
    `HttpResponse.suppressBodyBytes`/`ResponseFraming.suppressBodyBytes` are
    deleted, along with the uncalled `HeaderStore.setValueIfPresent`/`getAll` and
    `ExchangeMetaImpl.setCorrelationId`.
- **DB dialects drop inheritance; H2 becomes mode-aware** (`freeway-db`):
  `H2Dialect` no longer extends `PostgresDialect` but implements `Dialect`
  directly and carries an `H2Mode`; `PostgresDialect` becomes final. H2's `MODE`
  only affects H2's own syntax surface and no longer maps PG/MySQL modes onto a
  target dialect: H2 always uses its own `INFORMATION_SCHEMA`, DDL semantics and
  type system. Behaviour changes:
  - An H2 URL (including `MODE=PostgreSQL`/`MODE=MySQL`) always returns the `h2`
    dialect, and `DbModule` auto-detects and preserves the mode; an explicit
    `freeway.db.dialect=h2` means native.
  - H2 `supportsTransactionalDdl=false` and `supportsReturning=false`; H2 does not
    accept a parameterized `ON CONFLICT`, so `supportsOnConflict=false`, and
    upsert goes through H2 `MERGE INTO ... KEY`.
  - H2's MySQL/MariaDB modes still allow `ON DUPLICATE KEY`; H2's OFFSET/LIMIT use
    the standard `OFFSET/FETCH`, fixing paging under Oracle/MSSQLServer/Derby/
    STRICT modes.
  - H2 introspection uniformly uses `INFORMATION_SCHEMA.INDEXES` instead of
    `pg_indexes` or `INFORMATION_SCHEMA.STATISTICS`.
  - The migration/DDL guard treats H2 as non-transactional DDL; a migration
    containing DDL is rejected on H2, matching H2's real auto-commit behaviour.
- **CLI no longer auto-prefixes dotless keys with `freeway.`** (`freeway-boot`):
  key names resolve as literals, so `--mode=prod` is now the application key
  `mode` and is no longer rewritten to `freeway.mode` — the old behaviour
  silently pulled application config into the framework namespace, and the
  synthesized keys such as `freeway.port` were read by no module and could not even
  be named by the unknown-key check because of the root prefix. Framework keys are
  written in full (`--freeway.profile=dev`, `--freeway.http.server.port=8080`);
  `--profile=dev` no longer activates a profile and is just the ordinary
  application key `profile` with no special handling.
- **`Scoping` is renamed `ScopeBoundary` (`freeway-ioc`)**: the public type, the
  built-in service id and the error text change together; `Scoping.within` →
  `ScopeBoundary.within`. The name moves from "the scoping mechanism" to "the
  boundary entry point", layering consistently with `Defer` (an action capability)
  and `ScopedCache` (a value primitive); behaviour is unchanged.

### Fixed

- **Orm.findAll paging goes through the dialect** (freeway-db): findAll's
  limit/offset is no longer hard-coded LIMIT/OFFSET but rendered by
  `Dialect.limitClause`/`limitOffsetClause`/`offsetOnlyClause`; this fixes find
  failing under H2's Oracle/MSSQLServer/Derby/STRICT modes, which reject LIMIT.
- **history-mode SPA deep links lacked the index fallback** (`freeway-http`):
  `withFallthrough(true)` only handed a miss back to the route chain, without
  nginx `try_files ... /index.html`'s final hop, so a deep link such as `/chat`
  got a 404 when no route matched. The terminal `withSpaFallback` fallback is
  added, `withFallthrough`'s javadoc no longer claims it supports SPAs on its own,
  and `serve()` splits out a shared `serveResolved` so the fallback reuses the same
  cache/ETag/Range/HEAD/bytes pipeline.
- **Contribution lifecycle becomes "construct/publish → seal → initialize"**
  (`freeway-ioc`): previously `add(Class)` did, per drained entry, `create`
  (construct plus field injection) → `@PostConstruct` → `ext.add`; a service
  realized midway through a field-injection chain would freeze the then-partial
  extension view into a singleton, silently losing cross-module contributions (the
  `List<Propagator>` constructor of `PropagationFilter` was exactly that shape).
  Now the config layer is constructed-only, published and initialized first; the
  remaining deferred entries are constructed-only and published, and after `seal`
  field injection and `@PostConstruct` run uniformly; a field-injection chain sees
  the complete store, and `@PostConstruct` belongs to the final build rather than
  to early composition. Behaviour changes:
  - `Extension.all()`/`asMap()`/`validateOrdering()` throw
    `IllegalStateException` while a container-created store has not finished
    publishing, instead of returning a prefix; the config chain
    (`SymbolProvider`/`CoerceRule`) is exempt under the existing live-pull
    semantics.
  - Injecting `Supplier<List<E>>` / `Supplier<Map<String, E>>` is now the formal
    way for a construction-time consumer to read the complete view after startup;
    an eager `List`/`Map` injection in a constructor fails startup while pending
    entries remain.
  - `PropagationFilter`'s constructor takes `Supplier<List<Propagator>>`, so a
    `Propagator` from a late module cannot be silently missed.
  - `@PostConstruct` no longer runs while the store is publishing: the config
    layer initializes after its own entries are constructed/published, and the
    remaining entries initialize uniformly after the global seal; no extension can
    be added after seal. Code depending on the old interleaved order will see a
    startup failure or a behaviour change.

## [1.5.7] - 2026-11-02

### Fixed

- **Mesh broadcast no longer writes sockets on the publishing thread**
  (`freeway-cloud`): `CloudEventBus` used to send synchronously per connection, so a
  peer that stopped reading its socket blocked the publishing thread (an HTTP worker
  or a `Defer` submission thread) forever — while the design document claimed "slow
  peers do not block the publisher", the opposite of what the code did.
  `PeerConnection` now has a bounded queue per connection (256 frames) plus a
  dedicated sender thread: a full queue or a failed write closes that connection
  (the established at-most-once failure mode), and the publishing thread never
  waits on a peer.
- **A registry no longer "resurrects" during shutdown** (`freeway-cloud`):
  `RegistryLifecycleHook.stop()` sets `stopped` first, then stops the heartbeat and
  `awaitTermination`s; previously a heartbeat that had already been through
  `renew` would re-`register` after deregistration, leaving entries to outlive the
  backend TTL. A failed registration at startup now compensates by deregistering
  what was already registered.
- **An external registry no longer makes `/health/ready` drop registration state**
  (`freeway-cloud`): the contributor's `active` used to require both discovery and
  registry to still be `@Local`, so switching to Consul deactivated the whole
  report, and draining and heartbeat failures went unreported. Register/renew/drain
  are the core's own state (`RegistryRenewal` is updated by the hook, regardless of
  backend), so they are reported no matter which registry is bound; `instances=` is
  given only with the built-in store, and an external backend reports
  `backend=external`.
- **`event.enabled` is back to a Boolean vocabulary** (`freeway-cloud`): it was a
  hand-written string routed through `SymbolSpec.activated` (which only accepts
  true/false), so `off` — the most natural value among the documented
  yes/no/on/off/1/0 — failed startup. It is now a `SymbolSpec<Boolean>` parsed by
  the container Coercer.
- **Documentation contradicting the code is corrected to the facts**: the LB does
  read weight (§5.1/§8.2); Consul is delivered in ext (§8.1/guide/config);
  `PeerHub`'s `wire()` runs **after** the HTTP server; secret files rotate on
  size/mtime (not "cached until restart"); mesh broadcast goes through per-
  connection queues; `EVENT_PATH_DEFAULT` moved out of `ConfigKeys` (a route path
  is an identity string, not a config key).
- **A failure branch no longer leaves a write layer on the executing thread**
  (`freeway-flow`): `runBranch` tied "commit" to `walk` returning normally, so an
  exception branch neither committed nor discarded — the `Layer` stayed on
  `FlowContextImpl`'s `ThreadLocal` stack. When the same `FlowContext` was reused
  across retries, the next round of writes merged into the discarded layer instead
  of the root, and the parent could not read them (silent write loss) — while the
  javadoc said "discarded with it". Commit/discard is now decided by the
  `FlowContext.Branch` scope (see Changed). `stop()`'s branches always returned
  normally and committed, contradicting the other half of the documented
  "stopped or contributed nothing" — the document is corrected to the
  implementation.
- **cloud's W3C trace is no longer lost over HTTP/2** (`freeway-http`): the h1
  parser lower-cases inbound header names while h2 kept them capitalized
  (`Traceparent`), and cloud's `PropagationFilter` flattened `ctx.headers()` into a
  case-sensitive map before looking up `traceparent` — so the whole chain broke
  silently over HTTP/2. `Headers.copyOf` is now the single normalization point
  (see Changed).
- **`bind(Concrete)` no longer drops class-level annotations** (`freeway-ioc`):
  when `bind(X)` was written without `.to(...)`, `@Primary` / `@NotThreadSafe` /
  `@Marker` were silently discarded, while `.to(X)` extracted them — one binding,
  two spellings, two marker sets, which meant the singleton rejection for
  `@NotThreadSafe` could be bypassed by a one-line rewrite.
- **A `when` on a `PARALLEL` outgoing edge is rejected at build time**
  (`freeway-flow`): a fork never evaluates edge conditions, yet the graph compiled
  and passed `GraphSpec`'s expression validation, and the condition was silently
  ignored at runtime.
- **flow's build result no longer depends on node declaration order**
  (`freeway-flow`): a seeded random-graph probe (`FlowDeclarationOrderTest`,
  100 graphs × two declaration orders) pins "the same logical graph declared in a
  different order still gets the same verdict" as an invariant; every third graph
  carries a deliberate defect (duplicate edge, cycle, unresolved target), so the
  rejection paths are covered too. The probe surfaced and fixed one more case: the
  cycle-detection root was chosen by declaration order, so the same graph could
  report its cycle from a different starting node — roots are now chosen by sorted
  node id, making the error independent of declaration order.
- **Config hot reload no longer misses OVERFLOW batches** (`freeway-boot`): the
  `WatchService` reports `OVERFLOW` when the platform drops events, and its
  `context()` is null, so the old "which files did this batch touch" check answered
  no forever — no reload, no log, at exactly the moment (bulk writes, IDE save
  storms) hot reload matters most. OVERFLOW now triggers a reload outright: the
  cost of re-reading a few small files is far below silently stale config.
- **The cascade merge order derives from `SymbolProvider.TIER_*`** (`freeway-boot`):
  the overlay list used for profile selection was the hand-written
  `List.of(properties, json, filesystemBase, environment, cli)` — a second source
  of the tier order the symbol chain already declares. It is now sorted by the tier
  constants (lower value wins; `overlay` lets the later entry win, hence
  descending), and within one tier the declared properties → json → working
  directory order still applies (the stable sort preserves it).
- **`boot.internal.ConfigLoaderImpl` renamed `ConfigCascade`** (`freeway-boot`,
  in-package): it is a pure static utility — no instance, no interface, no
  binding, no substitution — and in this repo the `Impl` suffix is licensed only to
  "container-internal assembly" or "per-owner implementations coexisting for the
  same role", neither of which applies. The compile error is the migration
  (in-package class, no public surface). The README's dependency snippet now notes
  why `groupId` stays `com.jujin8` while packages are `com.jujin`: changing either
  is a break, and stating it beats leaving readers to guess.
- **`freeway.log.*` keys that are set but have no effect are named at startup**
  (`freeway-boot`): logging configures itself before a container exists and reads
  only `-D`, environment variables and classpath config files; CLI arguments
  (`--freeway.log.level=DEBUG`) and log keys in working-directory override files
  still enter the symbol chain, so config appears to apply while loggers stay at
  their defaults, with no hint. Startup now WARNs per key: the key, the value, the
  source, and the three channels that do work. `freeway.log.color` /
  `freeway.log.mdc` / `freeway.log.mdc.priority` / `freeway.log.caller-info` are
  read at class-load time and are ineffective in any file — file sources are named
  too. Keys newly appearing via hot reload are reported once.
- **Access logging moves to an event observer; `AccessLogFilter` is deleted**
  (`freeway-http`): the filter timed requests itself and never saw a request that
  CORS (-100) or health (-50) short-circuited, so "one line per request" was not
  true; the server already timed outside the chain and published
  `HttpExchangeEvent`. `freeway.http.access-log.enabled` now makes `HttpModule`
  write lines at the event sink (preflights and probes get lines too), and
  `HttpExchangeEvent` gains `remoteAddress`/`userAgent`. Custom destinations no
  longer replace a filter: turn the key off and contribute your own
  `EventSubscriber.of(HttpExchangeEvent.class, …)`. **Public type deleted**:
  `AccessLogFilter` no longer exists, and the compile error is the migration.
- **`setPathVars` is now "replace" rather than "merge"** (`freeway-http`): the
  method only `putAll`ed and relied on the engine's `reset()` to clear — an adapter
  or test double that reused a context and skipped clearing kept the previous
  request's tenant/id in the next match. The name says set, so the behaviour is
  set.
- **`freeway.log.mdc=false` also turns MDC off in file lines** (`freeway-commons`):
  the switch was read only by the console formatter while `JULFileFormatter`
  hard-coded `showMDC` to true — the console was clean but traceId still landed in
  the file. Both formatters now read one place (`JULLogFormatterSupport.showMdc`),
  and the file formatter's `FormatConfig` is constructed per instance (a static
  snapshot would freeze the first answer).
- **HTTP/2 no longer drains an unread request body without bound** (`freeway-http`):
  when the response was already sent and the body unread (401/404/413),
  `DataIn.close()` looped to EOF on the stream thread — no limit, no deadline, so a
  slow upload could hold the stream and its virtual thread indefinitely. It is now
  a linger close: at most 64 KiB and at most 500 ms, beyond which
  `RST_STREAM(NO_ERROR)` discards the remaining upload; a fully read request keeps
  reusing the connection as before.
- **flow's deep graphs no longer overflow the stack** (`freeway-flow`): the join
  domain's nesting depth was computed by memoized recursion, and a 50 000-node
  chain whose declaration order was not topological hit `StackOverflowError` in
  `GraphSpec.create()`. It is now one pass in Kahn order; `FlowDeepGraphTest` pins
  it with 50 000 nodes.
- **Several docs-vs-implementation contradictions are corrected to the facts**: the
  db manual's default table/column names are snake_case (not "lower-cased class/
  field names"); `Sql`'s error messages name the existing methods
  (`setColumn`/`setExpression` — the old `set(...)` was deleted long ago) and no
  longer claim `$1` is rewritten to `?`; `bodyAsJson` and `BodyHandler` are no
  longer described as equivalent (the latter runs `BeanValidator`);
  `Binding.primary()`'s `@throws` moved to the resolution entry point that actually
  throws; `AppState.STOPPING` no longer claims the registry already answered 503;
  the access log says "requests that reached the filter chain"; the
  working-directory-logs contradiction inside one section of
  `docs/configuration.md` is resolved to the implementation; the task vocabulary
  in `docs/architecture.md` changes `#graph` to `#graphId` (what the same file
  already used seven lines earlier), and the skill's `#graphId` and
  `AppRuntime.container()` (a deleted method) are fixed along with it;
  `BeanPlan.of` no longer claims its result is cached.

### Added

- **`Sql`'s trailing clauses become fields; render order is decided by statement
  structure** (`freeway-db`): `ORDER BY`/`GROUP BY`/`HAVING`/`LIMIT`/`OFFSET`/
  `RETURNING`/`ON CONFLICT` were previously concatenated into one `tail` string —
  order equalled call order, and capability checks read `tail.contains(...)`. Each
  clause now has its own field and renders in SQL order, so:
  `limit(5).orderBy("id")` no longer errors (rendering `ORDER BY id LIMIT 5`);
  duplicate clauses (two `LIMIT`s, two `RETURNING`s, two `HAVING`s) are rejected at
  construction instead of producing two stretches of illegal SQL; `HAVING`'s bound
  values are stored separately and bind in WHERE→HAVING order even when declared
  before `WHERE` (the old call order silently misaligned them). `OFFSET` still
  requires `LIMIT` (MySQL has no standalone OFFSET syntax). With the canonical call
  order the SQL text is unchanged character for character.
- **An `ON CONFLICT` action must follow its target** (`freeway-db`):
  `doNothing()`/`doUpdateSet(...)` only checked "is an INSERT", so calling one
  alone rendered `INSERT … VALUES (…) DO NOTHING` — illegal syntax the database
  reports far from the call site. Without `onConflict(...)` they are now rejected
  at construction with a message naming what to call first; a second
  `ON CONFLICT` is rejected too.
- **`OwnedTransaction`** (`freeway-db`, in-package): `DatabaseImpl.transaction` and
  `BatchQueryImpl.execute` each hand-wrote "borrow connection → autoCommit off →
  commit/rollback → restore → release", including the "destroy the connection when
  restore fails" guard. Both now share one lifecycle object; a batch bound to an
  outer transaction still only borrows the connection and does not commit.
- **flow's event exit** (`freeway-flow`):
  `FlowEngine.create(drivers, interceptors, BiConsumer<String,Object>)` gives the
  core an optional sink; `FlowModule` installs the bridge, forwarding every
  `context.eventBus().publish(topic, payload)` to the application `EventBus` as
  `"flow." + topic`. flow's execution-domain bus was previously unbridgeable — an
  application wanting to observe flow had to attach an interceptor per context,
  while http in the same repo had already solved the same problem with
  `Consumer<Object> eventSink`. Local subscribers still receive events first (the
  execution domain is its own audience; the sink is the application's), and a sink
  exception is isolated exactly like a local subscriber's.
  `FlowEngine.create()` (no container) stays local-only.
- **`Sql.on(String, Object...)`** (`freeway-db`): `ON` was the only condition slot
  without a parameter channel, so dynamic JOIN predicates could only be
  concatenated into strings (an injection surface); the `?` never entered
  `args()`, and `db.query(...)` then failed with "too few parameters". ON text
  precedes WHERE, so its bound values also precede the WHERE values, regardless of
  the order of the two calls.
- **API doc consistency check** (`freeway-boot` test): a `Type.member(` written in
  backticks in README, `AGENTS.md` and `skills/` must really exist in the
  corresponding module's `src/main`. The config tables have had such a check for a
  while (`ConfigDocsConsistencyTest`); the prose side did not —
  `AppRuntime.container()` survived several releases in the skill after the method
  was deleted. Historical documents (RELEASE-NOTES, past audits) are records of
  their time and are not scanned.
- **CI** (repo root): two jobs — core `mvn verify`, and in-repo consumers
  `mvn -Pdemos verify`. The latter gives "the compile error is the migration path"
  its first executor.

- **Discovery result caching (implementation-internal, 5 s default)**
  (`freeway-cloud`): the fleet's discovery implementations of
  `ServiceRegistry`/`ServiceDiscovery` wrap themselves in the framework-provided
  `CachingServiceDiscovery` — TTL + single-flight refresh + stale-on-failure +
  per-service overrides. The cache is a **freshness policy of the discovery
  implementation**, so it lives in the implementation, not the caller: RPC, the
  mesh feeder and an application's own `@Inject ServiceDiscovery` all benefit, and
  the seam gains no method. Default `freeway.cloud.registry.cache-ttl=5s` (half a
  heartbeat, well inside the 30 s eviction window), `…cache-ttl.<serviceId>`
  overrides it, and `0` disables caching for that service; on failure, the oldest
  answer keeps serving for grace = max(10×ttl, 30s) before throwing. Previously
  every RPC attempt fanned out to the registry nodes (1000 rps × 3 nodes =
  3000 QPS) with one extra RTT on the call path.

- **Outbound TLS material rotates online** (`freeway-cloud`): setting
  `freeway.cloud.rpc.tls.reload-interval` lets the next handshake pick up a fresh
  keystore/truststore — the `SSLContext` is built once, and its `KeyManager`/
  `TrustManager` delegate to rotatable material (size/mtime + throttle; a failed
  read keeps the old material). This is the only way to avoid rebuilding the
  client: JDK `HttpClient` captures the context at build time, and rebuilding means
  re-opening the connection pool and in-flight settlements (RPC and mesh share one
  `TransportSecurity`, so both would need the change). The inbound side already had
  hot rotation — the asymmetry was in this direction.

- **Mesh half-open connection probing** (`freeway-cloud` + `freeway-http`):
  `WebSocketListener` gains `onPong` (engines treated `Pong` as `/* no-op */`, so
  applications never saw acknowledgements), and the mesh gains `MeshKeepalive`:
  set `freeway.cloud.event.keepalive-ms` to enable it; every tick pings and requires
  the pong before the next tick, otherwise the connection closes and hands over to
  the existing reconnect/deregistration path. **No protocol change** — it reuses
  WS ping/pong, with the mechanism in the transport and the policy in the
  connection's owner; that is the difference from "add a mesh heartbeat frame",
  which would need a protocol upgrade and duplicate what WS already provides.
- **Readiness actually enters discovery results** (`freeway-cloud`):
  `ServiceRegistry.markReady` (a no-op default, so adapters like Consul with their
  own health checks need no change) + a ready bit on `RegistryStore` + a `…/ready`
  route and heartbeat payload on `RegistryApi`. A `RegistryLifecycleHook` whose
  process has a `CloudHealthContributor` first registers with `Health.starting()`
  and flips only after the readiness gate passes — a slow-starting instance no
  longer receives traffic before it can serve; without a contributor,
  registration is readiness (old behaviour unchanged).

- **Registry surface protocol version and credential rotation online**
  (`freeway-cloud`): the client sends `X-Registry-Proto` per request, and an
  incompatible server answers `426` (with its supported versions) instead of 404;
  `GET /registry` self-describes protocol/routes/auth with no credentials. Node
  tokens are now **resolved per request** and support a `token-previous` two-value
  window — rotation no longer requires stopping the node, an online operation
  compared to the mesh token's rolling restart.
- **Mesh metrics are scrapeable** (`freeway-cloud`): `/metrics` gains eight gauges —
  `cloud_event.published / frames_queued / frames_dropped / send_failures /
  inbound_delivered / dropped_legacy / dropped_no_subscriber / peers` (read from
  `CloudEventBus.stats()`) — present even when the mesh is not wired, because
  "no peers" is a fact that must be visible, not a missing series.

- **freeway's own registry backend takes shape: node + `RegistryFleetModule`
  client** (`freeway-cloud`): `RegistryServerModule`'s HTTP surface was plaintext,
  unauthenticated and written by no freeway client — "standalone registry node"
  was half a feature. Both ends now exist, inside freeway-cloud: the node can set
  a shared secret (when non-empty, writers must carry `X-Registry-Token`; both
  reads and writes check it — a readiness table filled in by strangers is an
  attack surface too); `RegistryFleetModule` binds a **set** of registry nodes as
  `ServiceRegistry`/`ServiceDiscovery` (keys `freeway.cloud.registry.*`): writes
  fan out to the node list, discovery takes the union of the nodes' answers, a 404
  heartbeat re-registers in place (re-registering only what this process
  registered, never guessed endpoints), and any surviving node keeps the fleet
  usable. A node is an ordinary freeway application of
  `HttpModule + CloudDiscoveryModule + RegistryServerModule` (about ten lines to
  assemble; see the module's javadoc). End-to-end test: one node + two services
  that have never met register and discover over the wire, and
  `discovery-feed-ms` feeds them into a mesh; registering with a wrong token fails
  loudly at startup. Third-party backends (Consul and friends) remain in ext —
  the same seam, swap the binding.
- **`CloudEventBus.replaceDiscoveredPeers(List<String>)`** (`freeway-cloud`): turns
  the documented "discovery-fed peers" into a reachable entry point — a registry
  adapter hands in the discovered addresses **as a whole** each round, and the
  mesh aligns its dial list accordingly: new addresses get dialed, and **addresses
  absent from the snapshot are interrupted, re-dialed, and gracefully closed**.
  The seam did not exist before (`PeerConnector` was package-private with no
  caller). Static `peers` and discovered addresses are two facts, and the dial set
  is their union: an empty snapshot revokes only the discovered part and never the
  static list.
- **Discovery-fed mesh: `freeway.cloud.event.discovery-feed-ms`**
  (`freeway-cloud`): setting the key enables it — the event module queries the
  bound `ServiceDiscovery` for this service's instances on an interval and hands
  the whole list to `CloudEventBus.replaceDiscoveredPeers` (excluding this node):
  new instances get dialed, ones that left the directory get disconnected and stop
  being re-dialed, and one failed query keeps the previous set. The
  `replaceDiscoveredPeers` seam fixed earlier had no caller — "discovery-fed mesh"
  was a sentence in a document; now any discovery backend (including ext's Consul
  adapter) wired in yields a self-configuring mesh. Static `peers` and the
  discovered set remain a union, and an empty poll never cancels the static list.
  The key set with no discovery to ask = startup failure naming the problem (a
  silent no-op would leave a mesh that looks self-configuring but never dials);
  an interval below 1 also fails startup.
- **The dial list converges into one `Map<PeerAddress, Dialer>`** (`freeway-cloud`):
  "is this peer still wanted", "how many failures", and "which thread to interrupt"
  used to live in two collections while a third (`Set<Thread>`) had no key, so
  removing a peer had no structural way in — append-only growth meant a departed
  node was **re-dialed forever** (capped at once per 30 s, until process exit) and
  `backoffByPeer` grew without bound. The entry is now the list itself, and
  removal is one `remove`; the reconnect path keys off the entry, so a removed
  peer's `handleDisconnect` cannot dial it back.

### Changed

- **Scopes converge into `ScopeSession`** (`freeway-commons`): `Defer` and
  `ScopedCache` share one session state and one close algorithm — delayed actions
  run first, then values clean up; a rollback discards only actions, and values
  still clean up. Opening a value scope inside an action scope attaches the values
  to that action scope (alive until it closes), so the "cache must be outer"
  nesting-direction rule and its WARN are gone. `Scopes`, `DeferScope` and
  `ScopedCache.Session` are deleted; the handle is uniformly `ScopeSession`, and
  read-only (`isActionScope()` / `isClosed()`): rollback belongs to
  `Defer.rollback()`, and a cache handle can no longer roll back an outer action;
  `Defer.within` consequently narrows to two overloads, `Runnable`/`Supplier`. A
  value's **lifetime** and **access window** separate: `CURRENT` scopes the
  lifetime, while the internal `CACHE` view covers only the cache block and the
  action drain — outside the block `ScopedCache.isActive()` is false and `get`
  does not cache, but values stay alive until the action closes, and delayed
  actions can still see them during the drain. EventBus's transactional deferral is
  unchanged; when `Scoping` attaches inside an action scope, a bean lives until the
  action closes (resolvable within the access window).
- **Half-open detection separates the wait budget from the probe interval**
  (`freeway-cloud`): `keepalive-ms` sets only the probe cadence, and the pong
  budget derives as `max(3 × interval, 1 s)`; one probe stays in flight at a time,
  and only an exhausted budget takes the existing close/reconnect path. Previously
  "the pong was still absent at the next tick" meant dead, so the budget equalled
  the interval — 50 ms in tests, where one JVM pause or scheduling delay closed a
  healthy loop connection and re-dialed it (the real cause of
  `MeshKeepaliveEndToEndTest`'s flakiness). `MeshKeepaliveTest` gains a
  "late but answered" case that is necessarily red on the old implementation.
- **Redundancy convergence (second review round)**:
  - Registry-surface token comparison goes constant-time (`ConstantTime`, the same
    rule as the mesh token); `ObjectStorage`'s etag reuses `Digests.sha256Hex`,
    deleting the inline SHA-256; cloud responses uniformly use `HttpStatus`
    constants (including the new 426).
  - `FreewayApp`'s run converges to `run(ModuleEx...)` + `run(Launch, ModuleEx...)`:
    the `run(String[] args, …)` and by-class run variants are deleted; declaring by
    class still goes through `create(Class…).start()` or `.add(Class…)`.
  - `AppStartedEvent`/`AppStoppingEvent` merge into
    `AppLifecycleEvent(Container, AppState)`; subscribers filter on `state()`, and
    the delivery policy (best-effort while RUNNING, the reliability point at
    STOPPING) is unchanged.
  - `Node` drops `hasMeta/metaAsBool/metaAsNumber/prevNodes` (duplicates of
    `metaAs`/`prevLinks` with zero callers); `FlowEvaluation.runTask` and
    `Graph.nodeOrThrow` are deleted; `PoolConfig`'s six zero-caller withers are
    reinstated after review, with an `everyFieldHasAWither` test — deleting half a
    field's withers would leave this config value without its uniform per-field
    entry.
  - `MediaTypes.JSON` absorbs four hard-coded occurrences in cloud;
    `Compression.gzipStream` absorbs streaming gzip; `CloudHttpClients` unifies
    the common construction of the RPC/registry/mesh outbound HttpClients;
    `ConfigCascade.bootstrapLayers` caches the startup classpath resolution (the
    log side no longer parses it separately from the main cascade);
    `TaskDesc`/`ConditionDesc` share an in-package `Ref` (public API unchanged);
    `Scopes` provides one entry that opens cache/defer in the correct order.
  - **Withdrawn**: R2 (four `within` overloads on `Defer`) — a scope handle must
    bind `ScopedValue` inside the callback and cannot be expressed with
    try-with-resources; the result/handle axes each stand on their own, so both
    stay.
- **`TlsStores`** (`freeway-commons`): a public key/trust store loading utility —
  PKCS#12/JKS probing and the empty-password rule (`null`/empty → `null`) exist in
  exactly one place, shared by the server-side `SslContexts` and the RPC client,
  replacing two independent private loaders.
- **`PathPattern.join`**, **`PlantUmlSubject`** (`freeway-http`/`freeway-flow`):
  the corresponding entry points after the deleted types.

- **`CloudHttpClient extends AutoCloseable`** (`freeway-cloud`): the lifecycle
  enters the role interface (consistent with `Pool`/`Database`/`HttpServerHandle`);
  previously only a default `close()` existed, and an external `.primary()`
  replacement silently lost in-flight settlements.
- **The mesh sender thread stops polling and writes in batches** (`freeway-cloud`):
  `PeerConnection.close()` now reads `senderThread` under the same lock that
  publishes it, so the `poll(200ms)` fallback timeout is deletable — a blocking
  `take()` costs no extra thread and idle connections stop waking five times a
  second (200 peers = 1000 wakes a second). The drain becomes `take()` +
  `drainTo` handing a batch to `Sender.sendBatch`: the inbound leg uses the
  engine's existing `sendTextBatch` (one writeLock, one flush) instead of a
  write+flush per frame — that interface previously had **no production caller at
  all**.
- **`CloudHealthContributor.isActive()` is deleted** (`freeway-cloud`): keeping
  registration state for an external backend is exactly the bug this round fixed,
  after which the only implementation always returned true and the filter branch
  in `ReadyHandler` was dead code. Keeping it costs not a few lines but an
  interface javadoc still teaching the next adapter to "deactivate itself" — the
  very move that blinded readiness (no implementation exists in ext, and
  `ConsulModule`'s "deactivates itself" wording is corrected to the facts too).
- **The mesh counts dropped frames, and `framesSent` becomes `framesQueued`**
  (`freeway-cloud`): the old name promised "written to the peer" while it counted
  "successfully enqueued" — after batching, one failed write lost the whole batch
  yet all of it was recorded as sent. Now every frame a connection accepts counts
  exactly once: enqueued is `framesQueued`, and one that never left this node is
  **`framesDropped`** (the batch the transport rejected plus the residue still
  queued at close, reported by the connection itself, never double-counted);
  `queued − dropped` is the upper bound of "still in flight or already written".
  Along with this, **a drain-side write failure now gets a `LOG.warn`** — the
  publish side logged its rejections while the drain side was silent, so the same
  failure had one leg that rang and one that did not. `CloudEventStats`'s field
  shape changed: code still depending on `framesSent()` errors at compile time,
  and the rename is the migration.
- **`FlowContext.beginBranch()` returns `FlowContext.Branch`** (`freeway-flow`):
  the old `Runnable` could only commit, never discard, leaving failure paths
  nothing to clean up. Now `commit()` merges and closing the scope discards, and
  `try (var branch = ctx.beginBranch()) { … branch.commit(); }` is the only
  spelling — both paths decided by the shape. `beginBranch()` is `@hidden` engine
  internals; the compiler is the migration path.
- **Join iteration-domain validation moves to graph build time** (`freeway-flow`):
  `rejectUnsatisfiableJoin` was called only from `FlowEngine.load()`, so
  `eval(GraphSpec)` and `eval(Graph, …)` bypassed it — a graph `load` would reject
  could run under `eval`, reporting only a `dead end` at the end and dressing a
  configuration error up as a dropped branch. The validation now follows `Graph`
  construction (`JoinDomains`), and an uncountable graph **cannot be built**;
  `load` only registers, and `JoinMarkerAtomicityTest` moves its assertions from
  load to build accordingly.
- **`SymbolSource.resolve(SymbolSpec)` is no longer an interface default method**
  (`freeway-ioc`): the default implementation had no `Coercer`, and
  `SymbolSpec.parse(raw)` throws outright without a per-key parser — a replacement
  written the way `AGENTS.md` sanctioned would crash on the first
  `Duration`/`Integer` spec. Typing is now part of the seam: `SymbolSource.of(...)`
  already carries the coercer, and a hand-written replacement takes
  `c.get(Coercer.class)` in its factory; `AGENTS.md` and
  `SymbolSourceReplacementTest` are updated (the latter gains an assertion for a
  parser-less spec).
- **Inbound request header names are uniformly lowercase** (`freeway-http`):
  `RequestView.headers()` now declares lowercase keys, and `Headers.copyOf`
  normalizes once at the context boundary (h1 already did; h2 stops keeping
  capitals). Code reading by lowercase key behaves the same on both protocols;
  code reading by the original case finds nothing — grep `headers().get(` to
  migrate.
- **`ExchangeMetaDefault` renamed `ExchangeMetaImpl`** (`freeway-http`): it was
  `new`ed directly by `AbstractHttpContext`/`AbstractWebSocketSession`, had no
  resolution point, and `.primary()` replacement had no effect — by the naming
  rules it is an engine-internal component. `AGENTS.md`'s `XDefault` example list
  drops it in step.
- **SPI-discovered modules log one INFO line** (`freeway-boot`): the composition
  source is "declared modules + `META-INF/services` on the classpath", and both
  appear in the startup tree; `ModuleDiscovery` now names the classes it found.
  `ARCHITECTURE.md` and `AGENTS.md` state the two sources, the order (declared
  first, deduplicated by class), the default and the opt-out.
- **demos follow the core version** (`demo/`): dependencies switch to
  `${freeway.version}` = `1.5.7`, and the root POM gains a `-Pdemos` profile so
  these three in-repo consumers compile with the reactor — "the compile error is
  the migration path" needs someone to run into it.
- **The bodyless-response `Content-Length` rule moves up** (`freeway-http`): the
  rule lived only in the HTTP/1.1 writer, so a handler-set `Content-Length` on a
  204 went out on h2 unchanged. `ResponseFraming.suppressesHeader` is now the only
  verdict, and both writers ask it (RFC 9110 §8.6).
- **The registry surface self-describes and accepts versions** (`freeway-cloud`):
  `/registry`'s route list derives from the same `SURFACE` table `bind` uses —
  previously it hand-copied four entries and missed `ready` and itself;
  `X-Registry-Proto` changes from "equal" to "accept older" (what the document
  promised), and a non-numeric value still answers 426.
- **`GraphSpec.normalize()`'s cache becomes real** (`freeway-flow`): `bfsOrder` and
  each setter's `invalidate()` never hit (the `toMap()` recomputed in full every
  time) while the javadoc claimed "subsequent calls are no-ops". An already-
  normalized spec now returns directly, making `touch()`'s invalidation
  load-bearing.
- **Error responses are uniformly JSON** (`freeway-http`): 404/405/500 were written
  by `ErrorResponses` as `text/plain` while 400/413/415 were written by
  `ErrorHandler` as JSON — one server, two error-body shapes, and clients could not
  parse uniformly (404 did not even have a structured reason). Both now go through
  `sendJson` (`{"error": …}`, with 405 still carrying `Allow`). **Client-facing
  body change**: code parsing 404/405/500 as plain text needs updating.
- **JSON scalar getters follow the same rules as the `Coercer`** (`freeway-commons`):
  `JsonObject.getBoolean` accepted only `true`/`false` while `coerce` accepted
  `yes/on/1`; `getInt("1.5")` threw while `coerce` produced `1` — the same JSON,
  two readers, two answers. The getters now delegate to the same built-in
  `Coercer`, and custom `CoerceRule`s still apply only on the `JsonCodec` path
  (tree nodes have no container). **Semantics widen**: code relying on getters
  throwing to catch dirty data will start accepting it.
- **`EventBus` no longer holds a `Container`** (`freeway-ioc`): the public
  constructor takes three capabilities (`Metrics`, a sealed `EventSubscriber`
  list, an optional `AsyncCarrier`), and the assembly point uses
  `EventBus.create(container)` once and lets go. **Constructor signature change**:
  code calling `new EventBus(container)` switches to `EventBus.create(container)`
  (the container builtin and the tests are updated).
- **The inbound request header ceiling is one number** (`freeway-http`): the
  HTTP/1.1 header block was fixed at 8 KiB and HTTP/2's block/field table at
  64 KiB, so the same request had different limits by protocol. The new
  `freeway.http.server.max-header-bytes` (default 16 KiB) is shared by the h1
  parser and h2's block limit, decode limit and SETTINGS announcement.
- **cloud readiness is one write** (`freeway-cloud`): `ServiceRegistry` gains
  `register(instance, Health)` (defaulting to the old method); the lifecycle hook
  registers with `Health.starting()` directly when it knows the readiness gate is
  installed, instead of registering ready and then marking not-ready. The fleet
  client expresses the same thing with `POST …/instances?ready=false`, and the node
  recognizes the query parameter. `ReadinessGate` issues one extra
  `markReady(true)` when everything is healthy at startup (because the instance
  registered as starting).
- **Lost-lease self-healing exists in one place** (`freeway-cloud`): the fleet
  client no longer secretly re-registers inside `renew` and returns `true`; it
  only answers "still there?" (any node 404 → false), and the lifecycle hook
  re-registers — a write that fans out to all nodes anyway, and one that makes the
  failure metric and WARN visible again. Re-registration carries the current
  readiness (an instance not yet flipped ready does not come back as ready).
- **Two `internal` roles get their visibility settled**
  (`freeway-flow`/`freeway-http`): `ExecState` (execution bookkeeping whose only
  reader is the package-private field of same-package `FlowEvaluation`);
  `SslContexts` was briefly narrowed along with it, but freeway-ext's Undertow
  adapter builds its `SSLContext` with it — the clean build failed and it rolled
  back from public to package-private. `AGENTS.md`'s criterion is "another package
  must assemble or substitute it" — neither of these qualifies.

- **`Sql`'s conflict clauses become semantic** (`freeway-db`):
  `onConflict(String...)` and `doUpdateSet(String...)` take **column names**, and
  the dialect renders — PostgreSQL
  `ON CONFLICT … DO UPDATE SET col = EXCLUDED.col`, MySQL
  `ON DUPLICATE KEY UPDATE col = VALUES(col)`; `Orm.save` assembles a `Sql`, so
  upsert has one path. `sql()` without a dialect renders reference (PostgreSQL)
  syntax; `doNothing()` still only supports dialects with `ON CONFLICT`, and its
  error message points to `doUpdateSet`/raw SQL. **API break**: `doUpdateSet` no
  longer accepts raw `"col = excluded.col"` assignment expressions (that need goes
  through raw SQL).
- **`PathJoiner` deleted; `PathPattern.normalizePath` renamed `normalize`**
  (`freeway-http`): `join` merges into `PathPattern`, and `"/"` is the only
  normalization of the root path, with `RouteGroup`/`WebSocketGroup` updated
  (prefixes are no longer the empty string). Rename rationale: the class name
  already says "Path", and the qualifier in `normalizePath` existed only to
  distinguish it from the deleted `PathJoiner.normalize`; now
  `PathPattern.normalize` is consistent with the adjacent `PathPattern.join`.
- **The PlantUML display callback becomes `PlantUmlSubject`** (`freeway-flow`):
  node/link are the two arms of a sealed type instead of a nullable union; the
  callback returns `String` (`null` keeps the default, empty hides, other text
  replaces), and `PlantUmlDisplayContext`/`PlantUmlDisplayResult` are deleted.
  **API break**: the callback type and return value change.
- **`NamedTaskHandler` deleted** (`freeway-flow`):
  `addActivity(id).title(...).task(handler)` already expresses it; interface
  inheritance added no polymorphism.
- **http2 internal types settle** (`freeway-http`): the `hpack` subpackage merges
  into `engine.http2`, and `HPackContext`/`HeaderFields`/`Http2HeaderField`/
  `ContinuationFrame`/`Http2Exception` become package-private.
- **Docs**: `Route`, `RouteGroup`, `WebSocketRoute`, `WebSocketGroup`,
  `WebSocketMatch`, `CorsFilter`, `StaticResourceMount`, `Binder`, `Query`,
  `MigrationRunner`, `JsonArray`, `CloudModule` gain type-level javadoc; the
  DEVELOPER-GUIDE's SQL Builder section states that conflict clauses take column
  names.

## [1.5.6] - 2026-10-08

### Removed

- **`freeway-http` no longer produces a `tests`-classifier test jar**: it existed
  since 1.0.3 for the single reason that freeway-ext's two engine adapters reused
  contract tests from the core's test tree; ext has since gathered those contracts
  into its own `freeway-http-adapter-testkit` (`746e5b9`), the dependency was left
  behind, and its deletion has been verified (61 top-level classes, zero references
  from ext's sources). **Release-facing shape change**: external code still
  depending on that classifier will fail to resolve — the resolution error is the
  migration hint.

### Changed

- **Module composition's public vocabulary converges to "modules + entry list"**:
  the tree type `ModuleNode` moves into `ioc.internal` (package-private), deleting
  `Freeway.create(ModuleNode)`, `FreewayApp.run/create(ModuleNode…)`,
  `AppBuilder.add(ModuleNode…)` and `Container.moduleTree()`; `AppBuilder` is
  absorbed into `FreewayApp` (`create(...)` returns it, its chain methods return
  it, `start()` returns `AppRuntime`); `FreewayApp.run(String[])` is deleted — the
  only entry point where "the application is decided entirely by the classpath",
  contradicting "No classpath scanning". boot's public vocabulary narrows from
  three types to two, and `ContainerImpl` becomes the only "declaration →
  structure" assembly point. Migrating means writing the tree form as
  `new OrderModule(), CloudModule.class`.
- **`@PostConstruct` belongs only to the managed path**: `Container.create` is a
  bare `new` plus dependency resolution and no longer runs `@PostConstruct`
  (previously it ran twice, silently, under `to(c -> c.create(X))`). The four
  realize paths all run it once, in the binding's materialize step; the
  `@PostConstruct` promised by `contribute(X.class)` is issued explicitly by the
  contribution path, whose not pairing with `@PreDestroy` is the existing
  convention. See Migration.
- **Prefer `AutoCloseable` for teardown; `@PreDestroy` narrows to the class of
  things that "must publish while the `EventBus` is still alive"** (see the
  Service Lifecycle section of the DEVELOPER-GUIDE). When both are marked, both run,
  once each (deduplication holds only within a phase); `AutoCloseable` deliberately
  goes last, with `@PreDestroy` before it. `@PostConstruct` has no substitute:
  `advise` intercepts method calls, while it runs when the object is not yet
  callable.
- **`/health/ready` and RPC responses change `Content-Type` from
  `application/json` to `application/json; charset=utf-8`** (a behaviour change
  with no compile-error protection): both go through `sendJson` now, matching
  `/health/live`, `/healthz` and the framework's other JSON responses.
- **`ReadyHandler`'s constructor drops the `JsonCodec` parameter**
  (`cloud.internal`, not public surface): with `sendJson` it no longer needs to
  carry its own codec.
- **`LazyHandler`/`LazyEndpoint` renamed `ResolvableHandler`/`ResolvableEndpoint`**
  (`freeway-http`, not public surface): the old names promised a laziness the
  implementation never had — the class route resolves everything at startup.
  `Resolvable` states the two-phase fact and promises nothing about timing.
- **`RouteIndex` restores fail-fast for unresolved class routes**: symmetric with
  `WebSocketIndex`, reporting at assembly time which route, which class, who is
  responsible for resolving, and the way out; building the index by hand is still
  supported.
- **The guard messages of unresolved wrappers align**: `ResolvableHandler.handle`
  and `ResolvableEndpoint`'s `open`/`subprotocols` now say the same things (the
  class's full name, `invoked directly, outside an index`, who resolves, the way
  out).
- **`AppRuntime`'s shutdown semantics tell the truth**: same-thread unwind,
  cross-thread wait (the javadoc previously claimed it always "unwinds the
  startup"). The code did not change.
- **A startup failure no longer overwrites a completed shutdown state**: when a
  hook `close()`es first and then throws, the state `close()` settled is kept, and
  `state()` no longer contradicts `close()`'s "already closed".
- `Sql.Group` and `Sql` share group construction (extracting `Sql.buildGroup`). The
  similar shape of their six public condition methods is inherent to the interface;
  `addCondition` is same-name, different-semantics (`Sql` is immutable, `Group`
  appends in place), so they are not merged.
- `GraphSpec.create()` no longer normalizes twice: `drainNodeLinks()` +
  `normalize()` fold into the `Graph` constructor (the only reader of a normalized
  blueprint).
- `Schema.ensure`'s `@return` tells the truth: index DDL executes but does not
  count toward the return value.
- Two **comment-only** corrections: `PeerConnector.MAX_INBOUND_MESSAGE`'s comment
  pointed at a constant that exists nowhere in the repo and masked a unit
  difference (server by bytes, mesh by characters); `HttpModule`'s WS block notes
  that it and `WebSocketIndex` each expand `WebSocketGroup` once (safe because
  `expand()` reuses the endpoint instance).
- `MultipartForm`'s per-part ceiling becomes the named constant
  `DEFAULT_MAX_PART_SIZE` (readability only; it equals `DEFAULT_MAX_BODY_SIZE` in
  value but not in meaning — per part vs whole — deliberately fixed).

### Added

- **The application root can be named**: `FreewayApp.create(...).name("order-service")`,
  or `Freeway.create("order-service", …)` without boot. The name goes only into the
  startup log and composition errors — never into bindings, ordering or identity;
  a blank name throws `IllegalArgumentException` in `start()`.
- **`AsyncCarrier`** (`ioc.event`, `@FunctionalInterface`): the submitting thread's
  ambient context is captured at the `publishAsync`/`publishOrdered` entry points
  and restored on the executing thread; with no binding it is identity, and events
  published without trace context run bare.
- **`KnownKeys`** (`ioc.symbol`): one `ConfigKeys` table and one vocabulary per
  module, with keys spelled as full literals (no `PREFIX + "…"` composition);
  `of(...)` harvests reflectively and declares the namespaces, `admit(...)` serves
  namespaces whose names are partly dynamic, and `suggest(...)` offers spelling
  suggestions; a table constant outside the declared namespaces fails at bind time.
  `UnknownKeysHook` WARNs once at startup, naming unknown `freeway.*` keys that
  nobody reads.
- **The reflection inventory `docs/reflection.md`**: a one-page ledger of
  every reflective site in the framework (site, cache shape, path and rules). It
  also fixed a real defect: `BeanIntrospector.selectConstructor` used to re-scan
  `getDeclaredConstructors` on every instantiation and now caches per
  (type, annotation) (behaviour unchanged).

### Fixed

- **The parallel-branch join bookkeeping becomes atomic** (`freeway-flow`): the
  arrival count and the temporary dead-end marker lived in two structures updated
  non-atomically, occasionally reporting "dead end" after the graph had actually
  finished (the message even described the bookkeeping-order problem as a dropped
  branch). The whole join transform folds into `ExecState.join` (completed inside
  the node's monitor), `resetLoopBodyJoins` switches to `joinReset`, and
  `ExecState.deadEndClear` is deleted — it was precisely that unsafe operation.
- **`@Inject`/`@Symbol` on method parameters fails at startup** (a behaviour change
  with no compile-error protection): the container resolves constructor parameters
  and fields only; method parameters compiled and were silently ignored, receiving
  whatever the caller passed. The message names the declaring class, the method,
  the parameter index, the annotation and the way out.
- **`/health/live` changes `Content-Type` from `text/plain` to
  `application/json`**: it switches to `sendJson`, with the body unchanged byte for
  byte.
- **`TracingFilter` no longer copies the probe paths itself**: it takes them from
  the path's owner — `HealthFilter.healthPath()` (configurable) first, falling back
  to its default; cloud's two paths become the constants
  `CloudHealthModule.LIVE_PATH`/`READY_PATH` and `CloudObserveModule.METRICS_PATH`.
- **JSON responses are produced in one place, `sendJson`**: `ReadyHandler`/
  `RpcEndpoint`/`RegistryApi` previously hand-wrote the `Content-Type` header,
  leaving the sibling probes `/health/ready` and `/health/live` inconsistent.
- **Concurrent close and `get()` no longer publish a proxy into a sealed container**
  (`freeway-ioc`): "still open?" and "publish into proxyCache" merge into one step
  under the lock; the PROTOTYPE branch gains the same closed check.
- **The final drain no longer holds the realize lock** (`freeway-ioc`):
  `ServiceRuntime.seal` becomes set `closed` under the lock → drain outside the
  lock → clear caches under the lock, removing one direction of the deadlock window
  (a user callback joining a resolver waiting for the lock).
- **The body limit converges to a single implementation** (`freeway-http`):
  `AbstractHttpContext#readBody` delegates to `LimitedInputStream` (the one the
  engines already ran), making AGENTS.md's Adapters clause true.
- **A `maxBodySize` adjustment no longer survives across requests**
  (`freeway-http`): see Migration.
- **HTTP/2 frames' `writeTo` is fixed** (`freeway-http`): `DataFrame` writes its
  frame header, `HeadersFrame` writes pad-length/PRIORITY/padding, and
  `GoawayFrame`/`PushPromiseFrame`/`NotImplementedFrame` write their payloads.
- **A join whose branches span iteration domains is rejected at `load()`**
  (`freeway-flow`): see Migration.
- **A PlantUML display function throwing is no longer swallowed silently**
  (`freeway-flow`): it now propagates, naming the three real outcomes
  (`of`/`ofDefault`/`HIDDEN`).
- **mesh reconnection gains jitter**: the new `resilience.Backoff` serves RPC and
  mesh alike (independent config keys; each keeps its own first-hop semantics).
  Same batch: **`Backoff`'s exponential shift is bounded by the base and no longer
  wraps to zero**; **`PeerConnector.Wiring` rejects
  `backoffMaxMs < backoffBaseMs`**.
- **`CloudHttpClient.close()`'s in-flight fallback moves into a `finally`**.
- **mesh frame classification converges into `MeshFrame.classify`** (a sealed
  interface: Hello/DuplicateHello/Event/EventBeforeHello/Unrecognized/Malformed),
  leaving each leg only its own distinct handling; classification recognizes the
  reserved field `specversion` first (`proto` is only this framework's hello
  field, and a third party may use it as an extension attribute).
- `BeanIntrospector.selectConstructor` returns the map's winner on a concurrent
  first computation (selection semantics unchanged); `UnknownKeysHook` deduplicates
  suggestions across vocabularies; `KnownKeys.of` fails at bind time on an empty
  harvest; an all-empty `InvocationContext` no longer propagates across executors
  (also fixing `TracerDefault` substituting `Baggage.empty()` for null);
  `ModuleDiscovery` skips anonymous/synthetic module classes, and `HttpModule`'s
  retirement probe switches to a dot boundary; `Ordering` gains
  `beforeIfPresent`/`afterIfPresent` (an optionally absent companion is
  configuration, not an error); `ScopedCache.get(key, factory, onExit)` gains an
  overload, and the container drops its JVM-global side table and class-load-time
  hooks.
- Three private `ensureOpen()`/`checkOpen()` methods unify as `requireOpen()`
  (stem only; exception types unchanged); `Orm.ensureInsertable` →
  `requireInsertable` (it only refuses, never repairs). **`require*` always
  refuses, `ensure*` always idempotently repairs** — now written into AGENTS.md.
- Two shape fixes in `Sql`: `where("id in ?", sub)` splicing a nested `Sql` raw is
  rejected at build time (the error names writing `"(?)"`); a chained `union` no
  longer re-wraps its left side (the right side is kept, and grouping is not lost).

### Migration

Behaviour changes (no compile-error protection — check each one):

| Old behaviour | New behaviour | Impact |
|---|---|---|
| `Container.create(X)` ran `@PostConstruct` | It does not; only constructor injection + field injection | The signature is unchanged, but code relying on `create` to run `@PostConstruct` silently loses the callback. **Types needing the full lifecycle become bindings** (`binder.bind(X.class).to(X.class)` + `container.get(X.class)`), where the managed path has both halves |
| A filter's `ctx.setMaxBodySize` adjustment survived into the next request on the same keep-alive connection | Reset per request to the server-configured value | Filters relying on "raised once, effective indefinitely" will see 413 — exactly the "for this request only" the javadoc already declared. Change the ceiling via `freeway.http.max-body-size`; an adapter reusing a context calls `resetMaxBodySize()` in its own `reset()` |
| `PeerConnector.Wiring` accepted `backoffMaxMs < backoffBaseMs` | The constructor rejects it | A ceiling below the base clamps the curve flat and makes the dial loop nearly idle. `withBackoff(60_000, 1_000)` now throws `IllegalArgumentException` (`Wiring` is a record, so the compiler notices nothing) |
| A join whose branches landed in different iteration domains could `load()`, reporting a dead end at the end of the run | `load()` rejects immediately, naming the node and each branch's domain | One more validation at load time; a failure leaves no registered graph. Spellings where all branches share a domain (no loop upstream, or all inside one loop body, including fed directly by the loop node), or where the join sits downstream of all loops, are unaffected; a LOOP without `$for` is not a loop |
| `Sql.setColumn` only null-checked the column name and spliced it raw into the statement | Validated by name (qualified names segment by segment, bare/quoted combinable, premature closure rejected) | Expressions that used to work **will start throwing `IllegalArgumentException`**; the acceptance surface is the union of the three dialects' quoting conventions |
| `Sql.setColumn` rendered a nested `Sql` of `UNION`/`INSERT`/`UPDATE`/`DELETE` shape into a column slot | Rejected; only a pure `SELECT` is accepted (`WITH` still allowed) | It used to render a syntactically meaningless statement that failed only at the database, far from the call site; now the construction point throws |
| `LimitedInputStream.read(b,off,len)` returned 0 or -1 for invalid arguments | Throws `IndexOutOfBoundsException` / `NullPointerException` | Invalid arguments used to be reported as "body ended". The type is `public` and adapters construct it directly per the docs |
| `GoawayFrame.parse` kept the reserved bits of last-stream-id verbatim | Masked (RFC 9113 §6.8 requires receivers to ignore them), and `writeTo` writes back the masked value | Wire bytes change; `Http2Connection` reads only `errorCode`, so no live path is harmed today |
| Injection annotations on method parameters were rejected only after the constructor ran | Rejected before it (including via `Container.create`) | A lazily realized singleton goes from "run the constructor's side effects, then fail" to "fail on the first `get()`" |
| When a hook `close()`ed first and then threw, `AppRuntime#state()` reported `FAILED` | The state `close()` settled is kept | A completed shutdown no longer reports a startup failure, observably |
| `MeshFrame.classify` checked `proto` before `specversion` | The reserved field `specversion` is checked first | A legitimate third-party event carrying `proto` extension attributes is no longer mistaken for a hello; this framework's own frames are unaffected |

Release-facing shape changes (the compile error is the migration path):

| Old | New | Impact |
|---|---|---|
| `FreewayApp.run(String[])` and the tree entry points (`Freeway.create(ModuleNode)`, `FreewayApp.run/create(ModuleNode…)`, `AppBuilder`, `Container.moduleTree()`) | Deleted | Migrate: `run(new String[0])` → `create().start()`; the tree spelling → `new OrderModule(), CloudModule.class`; an explicit `AppBuilder` becomes `FreewayApp launcher = …`. A single `String[]` matched two varargs at once, so the old spelling failed with "reference is ambiguous" rather than naming the fix — hence this migration note |
| `AbstractHttpContext#setMaxBodySize(long)` was overridable | `final` | Adapters that overrode it fail to compile; "reset per request" thereby has one unbypassable implementation point |
| `AbstractHttpContext` constructors `(codec, coercer)` / `(codec, coercer, correlationId)` | Each gains a `maxBodySize` parameter | The adapters' contexts fail to compile — the configured limit is only known once the server is up, so the pool moves into `start()`; both engines call `resetMaxBodySize()` in their `reset()` |
| `FrameHeader`'s three entry points silently truncated over-limit lengths | Uniformly throw `IllegalArgumentException` | The type is `public`; an adapter constructing a header from its own computed length goes from "write a broken frame" to "throw" |
| `http.engine.http2.hpack.Huffman` / `StaticHeaderTable` were `public` | Package-private | HPACK implementation details, with no reference from outside the package |
| `commons.bean.MethodHandleUtils` | `commons.bean.ReflectiveHandles` | It also owns `VarHandle` field accessors and constructor handles, so the old name understated its scope. Pure rename |
| `http.internal.HttpUtils`'s six header methods | `http.internal.HttpHeaders` | Header wire-format rules separate from query-string parsing; `HttpUtils` keeps the query string. All package-private |
| `http.engine.http2.BinUtils` | `http.engine.http2.Bytes` | Two groups — big-endian integer reads/writes and byte concatenation — and `Utils` hid that it had a shape. Pure rename |
| `http.engine.ws.WebSocketUtils` | Methods move into `WebSocketUpgrade.acceptKey` | One method, one caller, and deriving `Sec-WebSocket-Accept` belongs to the handshake |
| `AppConfigDefaultTest` / `BootConfigTierTest` in `com.jujin.freeway.boot` | Move to `boot.internal`, same package as the classes under test | Pure test relocation |

Schema belongs to development, migration to production; `freeway.db.schema.mode`'s
three values replace the boolean key:

| Old API / behaviour | New API / behaviour |
|---|---|
| `freeway.db.schema.auto=true/false` | `freeway.db.schema.mode=auto/off`, with `validate` added for production (after migration, compares entities against the database and fails startup on drift). The old key is deleted and its value has no effect: setting only the old key fails startup, naming the new key and the true→auto/false→off mapping |
| Schema and migration both ran at startup (Schema first) | `auto` keeps its order; `validate` runs migration first, then compares |
| `Schema.ensure` checked and applied inline | The checking half is extracted as `inspect`: `ensure` executes, `validate` reports |

Two **visibility narrowings were overturned by facts** and are now written into
AGENTS.md: `boot.internal.ConfigSources` must stay `public` (`AppConfigDefault`'s
public constructor takes it as a parameter, and freeway-cloud's tests construct
one), and `boot.internal.AppLogSource` must stay `public` (a `META-INF/services`
provider constructed reflectively by `ServiceLoader`). The criterion is "whoever a
public signature reaches must be able to see it", not "whether the repo itself has
a caller".

## [1.5.5] - 2026-09-24

### Migration

`WebServer` is renamed `HttpServer` (see the last three rows of the table), and
assembly converges to **one derivation point**:
`HttpServer.create(engine, config, pipeline[, eventSink])`.
Two assembly paths used to coexist: `HttpModule` (the container) and
`WebServerBuilder` ("standalone use without IoC") — the builder was not a second
entry point but a second assembly root: it carried its own copy of the defaults,
appended `ErrorHandler.defaults()` itself and judged `secure` itself, so one
question had two answers (the built-in error mapping sorted last in the builder
but first by contribution order in the container; class routes resolvable in the
container but a hard error in the builder). This round moves the derivation rules
into `create`, so both paths are just "fill the parts and call", and **standalone
construction is preserved as-is and needs no container**.

At the same time `internal.HttpModuleConfig` is deleted: it was a second owner of
the keys — the defaults of 13 server knobs restated one by one (by luck they
referenced `HttpServerConfig.DEFAULT_*`), the CORS/health defaults stated twice
beside `CorsFilter.defaults()` / `HealthFilter.defaults()`, and it gave
`HttpServerConfig` two acquisition paths (`container.get(...)` and `cfg.server()`).
The shape becomes the existing precedent `SslSettings.from(symbols)`:
**value types read their own keys**.

`DatabaseBuilder` is deleted in the same round, for reasons isomorphic to
`WebServerBuilder`: the standalone face's assembly knowledge (JDBC coercion
assembly, `DatabaseImpl` construction, URL typing) all hung off a builder, while
the container face `DbModule` borrowed the builder's static
`DatabaseBuilder.dialectForUrl` even when detecting its dialect — the path the
container must take stood under the standalone face's sign. Standalone assembly
moves into the interface itself: `Database.create(PoolConfig)` in one step; deltas
go through `Database.create(Database.Wiring.defaults(config)
.withCoercer/withPool/withDialect/withRowMapper(…))` (a parameter record +
`defaults()` + per-field withers, the same template as
`CloudHttpClientDefault.Wiring`; the builder's old assembly words were bare nouns
`coercer(…)`/`pool(…)`, indistinguishable as assignment or read at the call site).
URL typing belongs to `Dialect.of(url)` — the dialect answers "who is this URL"
itself, and `Database.create` and `DbModule.detectDialect` share one place from
now on. The two assembly faces are exactly http's shape: `Database.create`
(standalone) + `DbModule` (container).

| Old API / behaviour | New API / behaviour |
|---|---|
| `WebServerBuilder.builder().config(c).route(r).build()` | `HttpServer.create(engine, c, HttpPipeline.of(r))` |
| `WebServerBuilder.engine(e)` / `.sslContext(ctx, h2)` / `.metrics(m)` | These are the engine's business: hand `new FreewayHttpEngine(Wiring.defaults(json, coercer).withSsl(…).withMetrics(…))` straight to `create` |
| `.route(…)` / `.webSocketRoute(…)` / `.filter(…)` / `.staticFile(…)` / `.errorHandler(…)` / `.cors(…)` / `.health(…)` | `HttpPipeline.of(…).withRoutes/withWebSockets/withFilters/withStaticFiles/withErrorHandlers/withCors/withHealth(…)` (withers return new instances) |
| `.accessLog(out)` | `HttpPipeline.of(…).withFilter(new AccessLogFilter(out))`; in the container it is the key `freeway.http.access-log.enabled` |
| `.eventSink(sink)` | `create`'s fourth parameter (`Consumer<Object>`); the three-argument form keeps publishing "no events when nobody observes" |
| `.jsonCodec(…)` / `.coercer(…)` / `.routeGroup(…)` | Deleted (zero call sites in core+ext). The builder's two private `new JsonCodecDefault()` / `new CoercerDefault()` were exactly why "the container's registered `CoerceRule`s never reached HTTP" |
| `internal.HttpModuleConfig` (a snapshot record + 26 SymbolSpecs) | Deleted: `HttpServerConfig.from(symbols)` / `CompressionConfig.from` / `CorsFilter.from` / `HealthFilter.from` / `SslSettings.from` (already existing), one `withX(resolve(SPEC.orDefault(current)))` line per key; the single boolean `access-log` key is read by `HttpModule` itself |
| `SslSettings` obtainable only via `HttpModuleConfig.ssl()` | It is itself a bound service: `container.get(SslSettings.class)` (one resolution shared by the engine and the hot-reload hook) |
| `HttpServerConfig` decided solely by config keys, no override seam | `HttpModule` binds it with `.id("builtin")`; overrides go through `.primary()` (the same vocabulary as `HttpEngine`) |
| `HttpEngine` implementors did not declare transport | `HttpEngine.secure()` is an implementation method: whoever holds the keys answers (the built-in engine reads `SSLContext`; Undertow/Jetty read their own listener) |
| `WebServer` package-private constructor (5 args, including `secure` and `ReadinessProbe`) | `HttpServer.create(...)` with two overloads; the constructor stays package-private, and the probe is an in-package test seam only |
| An application's custom `ErrorHandler` in the container sorted **after** the built-in mapping | The built-in mapping is appended last by `create`, so both paths agree: contribution order equals placement order, and the application is always placed after `HttpModule` — so the 413/415/400 cases an application wants to remap would be claimed by the framework first. `HttpModuleErrorHandlerOrderTest` + `HttpServerStandaloneTest` each pin one half |
| `CorsFilter.builder().allowedOrigins("a,b").allowCredentials(true).build()` | `CorsFilter.defaults().withAllowedOrigins(List.of("a","b")).withAllowCredentials(true)` |
| `new CorsFilter(false, null, null, null, null, null, false)` / `new HealthFilter(false, …, null)` | `CorsFilter.defaults().withEnabled(false)` / `HealthFilter.defaults().withEnabled(false)` |
| `CorsFilter.DEFAULT` / `HealthFilter.DEFAULT` | `defaults()` (the same verb as `HttpServerConfig.defaults()` and 1.5.3's `PlantUmlOptions.defaults()`; a constant and a factory do not keep two names) |
| `StaticResourceMount.cacheMaxAgeSeconds(n)` / `.immutable(b)` / `.fallthrough(b)` (writes) | `withCacheMaxAgeSeconds` / `withImmutable` / `withFallthrough`; bare nouns remain reads only (`fallthrough()`), so a call site can always tell assignment from lookup |
| `.builder()` as a legitimate example of "fluent assembly of a configured object" | The convention tightens: a builder **must not hold defaults** (`FlowDriverDefault.Builder` is legal: two required parts, no stated default); delta construction is uniformly `defaults()` + `withX` |
| `WebServer` (the type name) | `HttpServer`: one family with `HttpServerConfig` / `HttpServerHandle` / `HttpServerStartedEvent` (the type was the odd one out in its own family); `create`'s signature and semantics unchanged |
| `RequestComponents` (the type name) | `HttpPipeline`: one of the server's four anchors (`HttpEngine` capability / `HttpServerConfig` transport declaration / `HttpPipeline` handling declaration / `HttpServer` derivation) — an engine-independent declaration compiled by `create` into an `ExchangeHandler` before `engine.start`; third-party engines see only the seam |
| `pipeline.withErrorMapper(…)` | `withErrorHandlers(…)`: the type had already changed with the `ExceptionMapper`→`ErrorHandler` rename; the wither catches up — of two live names, one remains |
| `HttpServerConfig.h2ResetBurstLimit` / `h2ResetWindow` (field and wither) | `FreewayHttpEngine.Wiring.withH2Reset(burst, window)`: engine-private knobs belong to the capability side; the keys stay (`freeway.http.h2.*`), the container path is wired automatically by `HttpModule`, and the standalone path passes `Wiring` directly |
| `com.jujin.freeway.http.internal.SslReloader` | `com.jujin.freeway.http.engine.SslReloader`: hot reload's driver changes from an `HttpModule` lifecycle hook to being self-driven inside `FreewayHttpEngine.start` (inputs pushed down through `Wiring.SslReload`); the class moves back to the engine package and becomes package-private |
| `DatabaseBuilder.from(cfg).coercer(…).pool(…).dialect(…).rowMapper(…).build()` | `Database.create(cfg)` (all parts at their standard defaults) or `Database.create(Database.Wiring.defaults(cfg).withCoercer(…)…)` (a `Wiring` record delta, `withX` writes) |
| `DatabaseBuilder.dialectForUrl(url)` | `Dialect.of(url)`: URL typing belongs to the dialect itself, and the standalone `Database.create` and the container `DbModule` go through one place |
| `import com.jujin.freeway.db.DatabaseBuilder` (including direct `new DatabaseBuilder()`) | The class is deleted — `Database`'s `create` and `DbModule` are the only two assembly faces |

Behaviour changes (no call sites need changing, but worth knowing):

- `HttpServer.secure()` is the engine's verdict and no longer re-reads
  `freeway.http.ssl.*`.
- Event publication follows `create`'s overloads: three arguments (standalone)
  publishes nothing, four arguments publishes; `HttpModule` uses the four-argument
  form and hands events to the `EventBus`.
- `HttpPipeline`'s canonical constructor no longer tolerates `null` parts: a
  container binding returning `null` throws an NPE **at startup** instead of
  silently falling to default CORS/health policy; `of(...)` states its own defaults
  explicitly, and the rule lives in exactly one place.
- The javadoc of `withRoutes(...)` / `withWebSockets(...)`'s varargs overloads said
  "append" while the implementation replaced — the document now states replace
  semantics (`RouteIndex` is a frozen trie and withers can only replace; no
  append call site exists in the repo).
- The engine contract is written down: `HttpEngine.start`'s honor contract has two
  tiers (must-honor / per-exchange policy, the latter split into the state-shaped
  maxBodySize and the wire-adjacent compression), and `ExchangeHandler.websocket`'s
  "must be consulted for every upgrade candidate" — this is the boundary that lets
  `HttpServerConfig` and `HttpPipeline` apply equally to the Undertow/Jetty
  third-party engines; after `h2Reset*` folds into `Wiring`, the "engine-private"
  tier disappears entirely and every config field applies to every engine.
- `HttpServerConfig` no longer carries `freeway.http.h2.*`: the config a
  third-party engine receives now has every field applicable to it; a tuned value
  paired with a non-built-in engine WARNs at startup naming the owner, and
  `from(...)` only points at those two keys rather than carrying them.
- TLS hot reload is self-driven: `Wiring.SslReload` carries the watched
  certificate material and the rebuilder, and the engine starts the reloader in
  `start()` before opening the listener port (unreadable material fails startup
  without taking the port), and the returned handle stops the watcher before
  draining on `close()`; `HttpModule` no longer assembles the reloader and only
  logs a skip notice when a non-built-in engine is active with reload configured.
  On the seam, `FreewayHttpEngine` references drop from 2 to 1 (only `HttpModule`'s
  builtin binding).
- `HealthFilter`'s `healthCheck` no longer accepts `null` (`requireNonNull` at
  construction): a disabled probe used to leave a field that could NPE on every
  request. The default path becomes `HealthFilter.DEFAULT_PATH`, and `normalize`
  and `defaults()` no longer each carry their own `"/healthz"`.

Two EventBus shape changes (record canonical constructors/component types migrate
with the content; the compiler is the migration path):

| Old API / behaviour | New API / behaviour |
|---|---|
| `new EventBus.EventBusStats(published, delivered, subscriberFailures, deadEvents)` | The canonical constructor gains one trailing argument `…, deadEvents, streamDrops`; readers (`stats().delivered()` etc.) are unaffected. |
| `DeadEvent` recorded `source` as the internal `EventDispatcher`, component type `Object` | `source` is the publishing, diagnostics-facing `EventBus` itself, and the component type narrows to `EventBus` (record shape unchanged; zero call sites in the repo affected) |
| `EventSink`, the bridging seam (local publish fanning out to contributed transport sinks) | `EventSink` is deleted wholesale: the local bus returns to being an in-process concept that knows of no transport (`EventBusInbound`/`publishInbound` deleted with it; inbound no longer enters the local bus). The fan-out tax (full serialization, a package-name deny list) and the drift of "installing a module changes publish semantics" are cut with it — "where does the fact go" is visible at the call site again |
| A `Binding` handle could still `id/marker/to/scope/primary/advise` after its module finished binding (a late `id` once orphaned a realized instance, a late `marker` once missed the index, each patched over by its own migration/sync machinery) | A binding seals when its module's flush registers it; afterwards all six DSL methods throw `IllegalStateException` ("is sealed — …accepted only while its module is binding"). `BindingIndex.updateId/contains`, `ContainerImpl.syncMarkers/updateId`, `ServiceRuntime.rekey` are deleted; `lateIdChangeMigratesRealizedInstance` / `markerDeclaredAfterFlushStillResolves` flip to rejection assertions |
| `@IntermediateType` (two-step coercion: first to an intermediate type, then to the target) | Deleted: custom shapes become one `CoerceRule<String, T>` parsing straight from the raw string (the `Endpoint` test was already in that shape, and the `Timeout` test is rewritten to match). `InjectionResolver.coerceConfiguredValue` drops the `lookup` parameter and `resolveMarkers` skips one table |
| `@ThreadSafe` (a marker whose only effects were "an error when coexisting with `@NotThreadSafe`" and resolution by marker) | Deleted: `@NotThreadSafe` standing alone is a complete contract (unannotated = no contract, no check); the conflict check and `MarkerIndex`'s `ThreadSafe` branch are deleted, `ScopeProxyAdvisorTest`'s conflict test and by-marker resolution test are deleted, `ThreadSafeGreeterImpl` becomes `SafeGreeterImpl` without the annotation; `@NotThreadSafe`'s javadoc drops the dual-annotation paragraph, and `InjectionResolver`'s fix hint becomes "drop the marker / use a THREAD scope / use a holder" |
| `EventBus` facade managed bridging directly (the sink registry, eventId minting, the dedup window and the fan-out loop all lived on the bus/dispatcher) | The "out of the JVM" half is dismantled with the bridge: `EventBridge`/`EventBridgePolicy` are deleted, and the dedup window retires with them — the "one event, many copies across transports" problem they served does not exist in the split-plane model (the mesh is an at-most-once fabric with no redelivery; Kafka's idempotency belongs to the business key per the existing contract). `EventBus.Keyed` and `@Topic` (a routing annotation whose javadoc self-identifies as "for MQ bridging", violating cloud-design §7's "no routing annotations on business types") are deleted with the bridge — the partition key becomes a Kafka call-site argument, and the cloud topic is an argument of the facade's publish |
| Transports installed via a sealed contribution (`contribute(EventSink.class)`) + `publishInbound` as the single funnel | Cross-JVM broadcast goes through the explicit facade `CloudEventBus` (see Added); inbound delivers into the facade's subscription table. **A subscription is the gate**: the same `contribute(CloudEventSubscription.class)` declaration derives the hello fetch prefixes, the inbound allow list (undeclared topics are dropped without triggering reflective loading — structural security replacing the two keys `allowed-types`/`allowed-topics`) and the delivery routing; the five keys `freeway.cloud.event.subscriptions/allowed-types/allowed-topics/dedup.enabled/dedup.capacity` retire (`token`/`peers`/`enabled` survive), and `warnWhenInboundIsUngated` narrows to the token warning. CLASS frames in flight on the old bridge are still parsed, dropped per channel and counted (in-flight compatible; cross-version routing is not mutually guaranteed — a fabric upgrade requires a same-version fleet) |
| Scope validation guessed the owner by type (`findOwnerBinding`: exact type → interface recursion → superclass chain) | The realize path threads the owning `BindingImpl` down and reads its exact scope, and `create()` passes null to fall back to the heuristic. Two behaviour fixes (no signature change, so the compiler stays silent): with multiple bindings of one type and no primary, a singleton's owner previously skipped validation entirely (`uniqueOrNull` swallowed the ambiguity → "owner unknown" → allowed), and it is now judged by its own binding's scope; a prototype's owner was once wrongly blocked by being "claimed" by a realized singleton interface, and it is no longer blocked. `Container.create/create/constructInstance/initialize` and the resolver gain `owner` parameters end to end; `multiBoundSingletonOwnerDoesNotEscapeScopeValidation` / `multiBoundPrototypeOwnerIsNotJudgedByItsSingletonInterface` each pin one case |

### Added

- **`CloudEventBus` — the facade of the cloud-born broadcast plane**:
  `publish(topic, payload[, subject])` is the only exit, coupled to Defer
  submission (buffered inside the transaction, leaves after commit, discarded on
  rollback — contract tests pin "rollback: no frames / commit: frames" as a pair);
  filter-then-translate (no interested peer means no serialization, ending the
  bridge era's full-payload tax); its own counters
  (published/framesSent/sendFailures/inboundDelivered/droppedLegacy/droppedNoSubscriber
  — each plane keeps its own books, with no merged view into the local stats).
  `CloudEventSubscription(prefix, Class<T>, handler)` is the declaration form and
  **only** the declaration form: runtime registration cannot change the peer-side
  hello filter, so opening a runtime hole would create the half-dead plane of "a
  handler exists but receives nothing" — the subscription table is therefore also
  the hello prefix source, the inbound gate and the delivery routing. The javadoc's
  first sentence pins the compound misreading (publish is outbound only; local
  facts still go through the local EventBus). Remote facts flowing back local is
  the application's explicit line (calling `bus.publish` inside a subscription
  handler), and `demo/cloud-event-mesh` is rewritten as a live demonstration of
  "facade + mirror + local/cloud-born contrast". The CE 1.0 envelope, `EventTrace`,
  PeerHub/connector/origin self-loop — the wire is untouched, and the bridge era's
  envelope machinery moves into the new plane intact.
- Events crossing a JVM no longer lose trace: when the sending thread holds a
  trace, mesh frames are stamped with `traceparent`/`tracestate` extensions and
  Kafka records with `ce-traceparent`/`ce-tracestate` headers (with no trace, both
  leave the bytes identical); the receiving side restores around dispatch (mesh
  `CloudEventBus.deliver`, Kafka `KafkaSubscriber.processRecord`), so downstream
  handlers observe the sender's causality. It reuses `TracePropagator`'s
  inject/extract (the same semantics as HTTP), and the convergence point is the
  new `cloud.event.EventTrace` (static, stateless; outbound reads the ambient
  context, inbound with an absent or malformed header runs bare — it never clears
  the consuming thread's existing ambient). Only trace propagates: principal and
  baggage are not restored from the wire (the event path has no authentication,
  and identity assertions must not be forgeable). The costs are recorded honestly:
  kafka now depends on `freeway-cloud` (the transport enhances cloud, and context
  lives in cloud; it drags in the http stack too — a deployment using only Kafka
  bridging carries artifacts it does not use); `Parsed` gains two fields (a record
  shape change, the compiler is the migration, with two test construction sites
  updated); `publishAsync/publishOrdered` run on executor threads where the trace
  is not captured at submission — async-channel trace remains a gap, listed for
  later. `EventTraceTest` covers four cases (stamp/parse/restore/no-trace leaves
  ambient alone/malformed runs bare — the last three are individually
  load-bearing) plus one each for Kafka send and receive.
- mesh inbound does not wrap in `Defer.within`: the drain runs outside scope
  binding, so wrapping would observe nothing; past the publish point there is
  nowhere to throw, and the rollback branch is unreachable.
- F4/F5/F6 (fabric finishing, no behaviour change): three hand-written self-loop
  checks converge into `cloud.event.EventOrigin::isOwn` (an empty identity is
  never one's own; `EventOriginTest` pins the truth table with three cases; the
  kafka side is reworded to match); `EventSink` gains two contract paragraphs —
  "reliability profile" and "no cross-transport ordering" — the mesh being a
  volatile fabric (failure drops the connection and its events with it), Kafka the
  durable backbone (retry/consumer group/DLQ), and fan-out guarantees no
  cross-transport order (`eventId` identifies an event, not a sequence; ordering
  exists only inside each transport's own mechanisms such as Kafka `send`'s key
  parameter); `publishOrdered`'s "global ordering" is scoped to within the JVM.
- `SymbolSpec.orDefault(fallback)`: the default for a layered read is given by
  **the value being built**, so a key table need not restate defaults;
  `SymbolSource.resolve(spec)` is one line per key (`SymbolSpecTest` pins "a
  missing key keeps the original value, a blank keeps the original value, and a
  present one parses through the chain's `Coercer`").
- `HttpServer.create`'s two overloads, `HttpPipeline.of/withX`,
  `HttpServerConfig.from` and `CompressionConfig.from`, `CorsFilter.from`,
  `HealthFilter.from`, `HealthFilter`'s `enabled()/healthPath()/healthCheck()`
  reads and `withPath/withCheck`, and `CorsFilter`'s seven field readers.
- Tests: `HttpServerStandaloneTest` (build and serve with zero containers, mapper
  precedence, every wither taking effect) and `HttpModuleErrorHandlerOrderTest`
  (the same precedence on the container path).
- `freeway-http` gains a `package-info.java` per package: the root package
  declares the contract surface's four groups (the assembly half, the seam's data
  half, the TLS contract, the shared vocabulary) and the stability stance;
  `engine*` declares placement rules (session bridges belong in `engine`, pure
  frame codecs in `http2`/`ws`, all without a stability promise); and
  `route`/`filter`/`body`/`websocket`/`sse`/`staticfile`/`event` declare the
  application-facing roles; the long-stale description inside
  `internal/package-info` (after the config snapshot and TLS builders moved out)
  is corrected in the same pass.
- `RequestViewTest` pins the HTTP/WS shared read face: `HttpRequest` and
  `WebSocketSession` both `extends RequestView`, and the read face is exactly
  those 10 read-only accessors — adding a method to the shared face means both
  halves inherit it.
- Role-audit conclusions (merge/absorb/remove all evaluated and rejected): the
  four public types in `engine` all have cross-package roles — `HttpResponseWriter`
  is implemented across packages by `engine.http2.Http2ResponseWriter`,
  `ResponseFraming` and `HttpContextImpl` are reused by the ext adapters and the
  benchmark, and `FreewayHttpEngine` is `HttpModule`'s binding; zero demotions.
  `RequestView` is the common read-only face of exchanges and WS sessions (2
  implementors); absorbing it would have both interfaces restate 10 declarations
  (a second owner), and removing it would leave WS sessions either copying the
  declarations or falsely claiming to be an `HttpRequest`. `ErrorResponses` has 5
  call sites (`HttpServer`×3, `Http1xSession`, `StaticResourceMount`); absorbing
  it means writing the body and headers of 404/500 five times — exactly what its
  anti-drift promise prevents.
- `MediaTypes.contentType(fileName)`: the
  extension → Content-Type table moves into the shared vocabulary (the single
  classification source `MediaTypes`' javadoc promises), and the static resource
  server reads from it — `StaticResourceMount` used to keep a private copy of the
  same table, so new extensions now land in one place.
- `Contribution.add(String, Function<Container, ? extends T>)`: the **container
  factory form** of a named contribution — the same shape as
  `Binding.to(Function)`. The instance form builds the value at bind time with no
  container handle; the factory form goes through the same `pendingCreates`
  deferred line as `add(Class)` (created after all modules bind), gets the
  container at instantiation, is insensitive to declaration order, and its
  `before/after` land on the real entries via `DeferredOrdering` as intended
  (`ContributionWiringTest`, three cases: container identity and late-bound
  services, ordering still effective after deferral, duplicate ids erroring).
  Subscribers and other contributions whose values require services no longer
  need a static container holder. **Migration**: when `T` itself is a
  single-argument functional interface, a call passing a lambda as a **value**
  collides with the factory form — cast explicitly back to `T` (four cloud tests
  are exactly this case; the cast says "this lambda is a value").
- `WebSocketRoute.of(path, Class<? extends WebSocketEndpoint>)`: the class form of
  a WS route — isomorphic to `Route`'s class form: the record slot holds a
  `LazyEndpoint` wrapper (`WebSocketRoute`'s shape unchanged), `HttpModule` calls
  `resolveEndpoint` alongside `resolveLazy` when building the `WebSocketIndex`,
  container `create` constructor-injects, and resolving before the handshake fails
  loudly naming the endpoint class (`WebSocketIndexTest`, two cases: deferred
  matching under a standalone index + error on use-before-resolve; container
  resolution + constructor injection + `subprotocols` delegation). WS endpoint
  classes no longer need a static container holder.
- `EventBus.publishOrdered(String topic, Object payload)`: the topic form of the
  ordered channel — symmetric with `publishAsync`'s class/topic pair, so a
  transactional outbox wired per topic no longer has to use the unordered
  `publishAsync(topic, …)`; the same globally-ordered single-threaded queue, and a
  submission inside Defer still drains in order after commit
  (`EventBusOrderedDeferredTest` pins it, plus `publishAfterCloseThrows` adds the
  post-close rejection).
- `EventBusStats.streamDrops` counting (a trailing record component on
  `EventBus.EventBusStats`, see the migration table) and the matching Metrics
  counter `eventbus.stream.drops`: stream-overflow drops used to land only in
  logs, invisible to operations via `stats()` as "the channel went mute"
  (`overflowDropsAreCountedNotSwallowedSilently` pins it).

### Removed

- **The bus bridge seam is dismantled wholesale** (event plane separation):
  `EventSink`, `EventBridge`, `EventBridgePolicy`, `EventBusInbound`,
  `EventBus.Keyed`, `annotation/Topic` are deleted; `CloudEventSink` disappears
  (the outbound loop moves into `CloudEventBus.publish`). The precedent is our own
  RPC domain: the `CallBus` removal (`358bbf42`, "hiding the one fact that
  matters — where the service lives") had the same disease in the broadcast
  domain's last surviving seam — the bridge let module loading and peer
  subscriptions decide whether a fact left the JVM, invisibly to the call site.
  The RPC domain caged "quietly crossing processes" two weeks ago; this round
  installs the same gate on the broadcast domain. `EventBus`'s javadoc states the
  new contract plainly: "The bus is the in-process plane, and knows no other".
  Framework lifecycle events (`AppStoppingEvent` etc.) no longer leave the JVM
  automatically with the bridge; mirror them explicitly when needed. ext's kafka
  changes to explicit planes in the same batch: `KafkaEventSink`/`publishInbound`
  are deleted and `KafkaEvents` takes over both directions (ext `d0bc985`,
  released with 1.5.5; ext is tagged `v1.5.5` too).
- `WebServerBuilder` (209 lines) and `internal.HttpModuleConfig` (151 lines): the
  former a second assembly root, the latter a second owner of the defaults; the
  derivation rules now exist only in `HttpServer.create`, and keys and defaults
  only in the value types.
- `DatabaseBuilder` (175 lines): the standalone face's second assembly root —
  coercion assembly, `DatabaseImpl` construction and URL typing all lived on it,
  while the container face `DbModule` still borrowed its static `dialectForUrl`.
  Assembly folds into `Database.create` (+ the `Wiring` delta) and typing into
  `Dialect.of`, the same shape as `HttpServer.create` + `HttpModule`; standalone
  construction is preserved and needs no container.
- `CorsFilter.Builder`: it was not sugar for "fewer arguments" but a second
  holder of the defaults — methods/headers/maxAge were hard-written again beside
  `DEFAULT`, the `*` + credentials check ran twice with different exception types,
  and it was narrower than the canonical constructor (no way to give
  `enabled=false`, `exposedHeaders`, `maxAge`).

### Fixed

- **cloud registered the wrong scheme**: `HttpServiceDeclaration` used
  `WebServer.secure()` to decide `http`/`https`, and that read freeway's own
  `freeway.http.ssl.*` keys — when TLS was terminated by an Undertow/Jetty adapter
  that may ignore those keys, an https node registered as `http://`. The criterion
  moves up to `HttpEngine.secure()`, so the registered identity matches the
  component that actually provides the transport.
- **Applications could not remap exceptions the framework had already mapped**
  (container path): see the sixth-from-last row of the migration table.
- **The `freeway.web.*` config keys that silently did nothing**: the old prefix
  was renamed to `freeway.http.*` in v1.2.2 and the fallback deleted later
  (e37ba527), so config still using the old prefix has been silently ignored ever
  since, with the server running on defaults. `HttpModule` now probes at startup
  for the one shape that can silently fail — "an old-prefix key exists while the
  corresponding new key is missing" — and emits one WARN naming each dead key and
  the fix (`old key → new key`); if the new key exists there is no report — the
  value takes effect regardless, including via `${freeway.web.*}` references to
  old keys; an expansion failure in a dead key's own value is reported, not
  thrown.
- **The access log recorded 200 on the exception path**: error mapping used to
  wrap the whole chain from outside, and the filter's `finally` (where
  `AccessLogFilter` read `ctx.status()`) ran during exception unwind before the
  outer `catch`, reading the unmapped default 200. Mapping now sinks into the
  chain (wrapping dispatch and each filter level; the mapper list and the
  "first mapper wins" rule unchanged): an exception thrown by a handler no longer
  pierces the filter chain, and outer filters observe the mapped final value; the
  outermost catch of request handling stays as the last net.
  `HttpServerFeatureTest.accessLogRecordsMappedStatusOnHandlerException` pins the
  path.
- **`Dialect.of` typed URLs case-sensitively**: scheme matching read the raw URL
  (`url.contains("jdbc:…")`), so an upper-cased `JDBC:POSTGRESQL://` fell straight
  into the "unsupported database" error; `toUpperCase()` also carried no `Locale`,
  so under a Turkish default locale `jdbc:sqlite`'s `i` became `İ` and failed the
  same way. Matching now runs on a `Locale.ROOT` upper-cased copy, consistent with
  the documented case-insensitivity (`DatabaseCreateTest` pins both regressions).
- **A class-route WebSocket endpoint's unresolved state was silently deferred to
  the first upgrade**: with a hand-built `WebSocketIndex` (bypassing `HttpModule`)
  nobody ever resolved the `LazyEndpoint`, so the first connection upgrade threw —
  and upgrade-path exceptions logged at TRACE only, dropping the connection in
  perfect silence. `WebSocketIndex` now rejects unresolved class endpoints at
  construction (`HttpModule` finishes resolution before construction; only paths
  bypassing the module can trigger this); endpoint callback failures during the
  upgrade handshake (`subprotocols`/`open`/`onOpen`) are raised to WARN naming the
  endpoint class, while read-loop and client disconnects stay at low levels.
- **Deferred ordering constraints inside the drain window were silently dropped**:
  `DeferredOrdering` rejected `before()`/`after()` only after seal, but instance
  application happens during the drain, before seal — constraints declared in
  that window entered the buffer and were never replayed. An `applied` flag
  closes it: declaring after application fails as loudly as after seal.
- **Inbound dedup swallowed MQ redelivery after a Defer rollback (events lost
  forever)**: the wire id was claimed at publish time, while Defer-buffered
  dispatch was discarded with the rollback — the id was burned, the event
  undelivered, and a redelivery carrying the same id was judged "duplicate" and
  dropped. The claim moves into the deferred action (the moment of actual
  dispatch): a rollback leaves the id unclaimed and redelivery is accepted as
  usual; after commit the claim happens as before, and duplicates still deliver
  exactly once (`EventBusInboundDedupTest`, two cases:
  `rollbackLeavesIdUnclaimedSoRedeliveryIsAccepted`,
  `committedInboundStillDedupsRedelivery`; the `inboundDeduplication` javadoc is
  reworded to match).
- **`@Primary` on an implementation class had no effect on by-type resolution**:
  `to(Impl.class)` collected `@Primary` into the marker set, but `get(type)` read
  a separate `primary` boolean — two bindings, one marked `@Primary`, still
  reported "Multiple services match" by type, contradicting the `@Primary` javadoc's
  own "equivalent to `.primary()`". The boolean is deleted and `isPrimary()` reads
  the `Primary` marker directly: `.primary()`, `@Primary` on the class and
  module-level `@Marker(Primary.class)` are one answer from now on
  (`primaryAnnotationOnImplementationSelectsTheBinding`).
- **`SymbolSource.resolve(name, default)` read the default as an expression**: the
  default implementation assembled `"${name:default}"` and ran the expansion
  syntax — `":-"` swallowed a leading dash (`resolve(k, "-1")` produced `"1"`),
  the first `}` in the default ended the expression early (`"a}b"` produced
  `"ab}"`), and a `${…}` inside the default got expanded. `resolve(name)` now
  catches `UnknownSymbolException` and falls back: the default returns verbatim,
  while expansion errors like a nested missing symbol still propagate
  (`lenientResolveReturnsTheDefaultVerbatim`). A side effect: replacement sources
  no longer need to re-implement `resolve(name, default)` for the identity-`expand`
  case (ext testkit's `Symbols` was one such, now deletable).
- **The realize lock was JVM-global**: `ServiceRuntime.REALIZE_LOCK` was `static`,
  so one container's slow constructor blocked another container's first
  instantiation (1.5.x fixed only the cache-hit path, not the lock), and a
  provider waiting on an instantiation in another container could deadlock across
  containers. The lock becomes one per container
  (`firstRealizationDoesNotQueueBehindAnotherContainer`).
- **THREAD scope leaked values across containers**: `ScopedCache` is process-level
  but keyed only on `(type, id)` — two containers binding the same type under the
  same explicit id got the same value inside one `within()`. The key gains the
  owning container (`threadScopeKeepsTwoContainersApart`).

### Changed

- `freeway-http`'s 66 test constructions switch to direct `HttpServer.create(...)`
  (zero containers, with the engine supplied by `TestHttp.engine()`); only the two
  tests of `HttpModule` itself still go through the container. The ext testkit and
  the benchmark keep using `HttpModule`: contracts must measure "the server an
  application actually gets", where the defaults of `Metrics`/`CorsFilter`/
  `HealthFilter` still come from keys.
- AGENTS' naming section is rewritten (a builder must not hold defaults; `withX`
  writes and bare nouns read), with `docs/architecture.md`,
  `freeway-http/README.md`, `docs/developer-guide.md` and both skills updated to
  match.
- Two inaccurate statements corrected: the root `package-info` no longer claims
  `ExchangeMetaDefault` is replaceable — two `new`s construct it directly and the
  container never consults it, so a `.primary()` binding would not be honored; and
  `HttpModule`'s ACCESS_LOG comment no longer calls itself "the only key this
  module reads itself" (`freeway.http.h2.*`'s two keys are read alongside), with
  the adjacent spec comments normalized in style.
- `Graph`'s public constructor becomes package-private: the repo's only
  `new Graph(…)` is same-package `GraphSpec.create()`, so the public constructor
  was a second derivation point; blueprint usage is unchanged
  (`graphSpec.create()` / `FlowEngine.load(spec)`).
- `WebSocketUtil` → `WebSocketUtils`: aligned with the family's `BinUtils` /
  `HttpUtils` / `JsonUtils` / `ReflectiveHandles` `Utils` suffix (the repo stood at
  4:1; the only caller, `WebSocketUpgrade`, changes in the same batch).
- Two fully-qualified `com.jujin.freeway.cloud.context.TraceContext` names in
  `Tracer` method signatures become imports (with `InvocationContext` cleaned up
  in the same file) — signature types unchanged, no migration item.
- `FlowExchanger` → `FlowEvaluation`: the type is "the state of one in-flight
  evaluation" (carrying graph/engine/driver/context/execState; the javadoc's own
  word was evaluation) and does not evaluate anything itself — the evaluators are
  `FlowEngine` + `FlowDriver`; `Exchanger`'s two-party swap semantics
  (`java.util.concurrent.Exchanger`) and the agentive `-er` suffix both misled.
  It is the parameter type of every `FlowDriver` hook, and the parameter renames
  `exchanger` → `evaluation` accordingly.
- flow's component family aligns with the repo's Handler convention
  (`RouteHandler`/`BodyHandler`/`ErrorHandler`…): `TaskComponent`/
  `NamedTaskComponent`/`ConditionComponent` → `TaskHandler`/`NamedTaskHandler`/
  `ConditionHandler` (the method names `run`/`test`/`name`/`title` unchanged);
  derived identifiers fold too, leaving no second vocabulary: `TaskDesc`/
  `ConditionDesc`'s fields and the `component()` accessor → `handler()`,
  `isComponentRef()` → `isHandlerRef()`, `NodeSpec`/`LinkSpec`'s `whenComponent`
  field and accessor → `whenHandler`, `resolveComponent` → `resolveHandler`, and
  "component" in error messages and comments → "handler" (`ExprEvaluator`'s
  "array component" means an array element and stays as is).
- `DatabaseHub` → `DatabaseRegistry`: the interface's own javadoc first sentence
  was already "**Registry** for multiple named `Database` instances",
  `get/primary/all/of(Map)` are all registry semantics, matching cloud's
  `ServiceRegistry` word for word and concept for concept; "Hub" in this repo
  already meant `PeerHub` (mesh interconnect), which does not match this role.
  `DatabaseHubImpl` → `DatabaseRegistryImpl`, with test names following.
- `db.util.Names` merges into `SqlTextParser`: its only two public methods were
  lexical validation of named-parameter identifiers (`isValidParamStart`/
  `isValidParamChar`), and "Names" suggested a table/column-name utility; its only
  consumer was always the same-package lexer, so folding it in as private methods
  removes one concept.
- **The contribution mechanism converges (freeway-ioc)**, three decisions landed
  together:
  1. **Seal at composition time**: `Extension` seals when assembly ends
     (bind → drain), and `add`/`before`/`after` throw "sealed" afterwards. The old
     "contribute to a built container at runtime" capability sat in an in-between
     state that tests exercised but no document promised (the concurrent
     invalidation protocol existed for it); it is now closed under plan B:
     `Extension`'s dedicated lock, all `synchronized` blocks, the `version()`
     counter and `EventSubscriptionIndex`'s double-check loop are deleted, and the
     read side is lock-free from seal onward. `Extension.get(String)` had zero
     production references and is deleted in the same batch (precedent:
     `Extension.of`). `EventBusOrderedDeferredTest`'s "contribute a subscriber
     after the first publish" becomes a rejection assertion — late subscriptions
     go through `EventBus.subscribe`.
  2. **SymbolProvider unifies through contribute; the register channel is
     deleted**: the contributed tier enters the extension store only, and
     `SymbolSource` pulls (the new
     `SymbolSource.of(coercer, supplier, tiers…)` overload accepts the live view,
     with the ordering cache keyed on the view's identity for invalidation);
     `SymbolSource.register()`'s interface method, the container's
     `infrastructureWiring` push table, the `contributedProviders` list, the
     post-load replay, the on-demand predicate and the `LazySymbolProvider` facade
     are all deleted — five pieces of special machinery go to zero, incidentally
     removing the read gap between "replacement installed" and "replay runs". A
     replacement source's contract becomes **taking the view itself**:
     `.to(c -> new MySource(c.extension(SymbolProvider.class)))`; the old "blind
     replacement fails fast at startup" goes with register, and
     `SymbolSourceReplacementTest` re-pins the positive pattern.
  3. **Lifecycle unification**: the new `internal/ContributionRegistry` owns the
     DSL, the extension store, the deferred queues, the drain and the seal
     exclusively; `BinderImpl` returns to being a pure binding delegate. Deferred
     creation becomes two queues — the config layer (`SymbolProvider`/
     `CoerceRule`) constructs first and the rest after (the old FIFO left
     "config layer declared after its consumer" to luck); `add(Class)` desugars to
     `add(id, factory)`, so one deferred path has one implementation. The only
     remaining push is `CoerceRule` into the coercer (commons' `Coercer` lives in
     another module, and pulling would touch its rule store and priority
     semantics — the benefit does not buy the risk, so it stays for later).
  Three doc-vs-code contradictions are fixed in the same batch: both skills
  claimed "a missing ordering id errors" (the general path actually WARNs and
  ignores; only hooks run `validateOrdering`), and `InjectionResolver`'s javadoc
  first sentence still claimed `Extension<Foo>` was injectable (the same method
  explicitly rejects it).
  **Migration note**: with the `add(id, factory)` overload living beside
  `add(id, value)`, when the extension-point type V is itself a functional
  interface and the call site passes a bare lambda as a **value**
  (`.add("id", c -> …)`), both overloads are "potentially applicable" and javac
  reports ambiguity — cast explicitly to the value semantics
  (`.add("id", (V) c -> …)`) or assign the factory lambda to a
  `Function<Container, ? extends V>` local first; four cloud tests in the repo use
  exactly that fix.
- `Contributions<T>` → `Contribution<T>` and the ordering handle `Contribution` →
  `Ordering`: the singular/plural pair read as "one vs a basket", but neither type
  holds values — the former is the write-side DSL returned by `contribute()` (an
  `add` facade, stateless itself), the latter the handle for declaring
  `before/after` after a named `add` (its javadoc's first sentence was already
  "Ordering handle", yet disambiguation required appending "handle", and
  `ARCHITECTURE` once even wrote it as `Contribution<RuntimeHook>`). After the
  rename it matches `bind()` → `Binding<T>`: `Binder.contribute` returns
  `Contribution<T>`, and `.add(id, v)` / `.add(Class)` return `Ordering` (the stem
  from the existing `validateOrdering` / "Ordering reference" word family);
  `BinderImpl.DeferredContribution` → `DeferredOrdering` follows. The entry-domain
  word "contribution" in error messages and prose stays.
- **EventBus's publish hot path goes fully lazy**: local publish no longer mints a
  UUID (SecureRandom) at the entry — the id exists only for outbound sink fan-out,
  so minting moves into `sendToSinks` after the emptiness guard, one per fan-out
  shared by all sinks (`EventSink`'s "bus-minted, never null" contract unchanged;
  publishing with no sinks, rolled back or forwarded inbound costs zero);
  `@Topic` parsing enters a `ClassValue` cache (the same pattern as
  `SUPER_TYPES`), paying reflective annotation lookup once per class; the
  sinks-empty guard hoists above evaluating the sink parameter — no sinks means no
  topic parsing; subscriber/failure alert labels become lazy `Supplier`s, and
  strings are built only on real failures.
- **The class-channel index walks once**: `EventSubscriptionIndex.classSubs(eventType)`
  merges the two `SUPER_TYPES` traversals of the old `classHandlers` +
  `runtimeClassSubs` into one, matching module and runtime sets in the same pass;
  an exact hit returns the live list directly (the module list is immutable after
  seal and runtime is COW, so read-only iteration is safe), deleting the old
  unconditional `ArrayList` copy — copying now happens only when merging across
  supertypes.
- **`EventSinkRegistry` becomes a volatile immutable list**: add/remove/clear are
  rare and copy-on-write under the monitor, so the publish hot path's
  `isEmpty()`/`snapshot()` become plain field reads — no more locking and
  `List.copyOf` on every publish.
- **The bus's own executor shuts down with a bound**: `EventExecutorSupport.close()`
  no longer uses the unbounded `ExecutorService.close()` and waits forever —
  after `shutdown()` it waits with a limit (default 5 s; on timeout it WARNs naming
  the blocked channel and calls `shutdownNow()`). A hung subscriber used to hang
  the whole container's shutdown (`internal.Shutdown` orders the EventBus after
  every `@PreDestroy`); `setAsyncExecutor`'s ownership is written down at the same
  time: **a self-set executor is never closed** — its lifecycle belongs to the
  installer, and only the bus's own default executor stops with `close()`.
- `EventBus.close()` switches to an `AtomicBoolean` CAS: concurrent closes go from
  a read-modify-write race to exactly one executor; `DeadEvent.source` changes from
  the internal `EventDispatcher` to `EventBus` itself (see the migration table);
  and the subscriber order within one dispatch is written into the class javadoc:
  module subscribers (contributed at composition, in their ordering) precede
  runtime subscribers (in subscription order).
- **freeway-ioc's internals converge** (no public shape change; all existing tests
  in the repo pass without assertion changes):
  - `ServiceRuntime` owns the two caches and the realize lock exclusively, and
    `ContainerImpl` no longer holds/clears/migrates maps; shutdown's "set closed →
    final drain → clear caches" becomes one atomic step, `ServiceRuntime.seal(finalDrain)`.
    Concrete-class singletons no longer occupy both the proxy cache and the target
    cache (the old `serviceCache` kept only interface proxies and is renamed
    `proxyCache`).
  - The instance-binding machinery is deleted: `prebuiltInstance`/`setInstance`/
    `requireSingletonScope` and the instance check inside `scope()` existed only
    for 5 built-in services (the public DSL has no `toInstance`); built-ins move to
    providers, and `registerBuiltin`/`registerBuiltinLazy` merge into one.
  - `ServiceRuntime.requireAdviceSupported` is deleted: `advise()` already rejects
    non-interface types at bind time, so the check was unreachable;
    `ProxyFactoryImpl` → `ServiceProxy` (there is no `ProxyFactory` interface, so
    the `Impl` suffix named nothing real; it is stateless and becomes static), and
    the three-argument `create` ladder with its duplicated `Object`-method and
    interface guards is deleted with it.
  - One selection rule: `BindingIndex.select` served both by-type and by-marker
    lookups — "one match wins, several take the unique primary, otherwise
    ambiguous" was written twice, in `BindingIndex.selectUnique` and
    `MarkerIndex.findByMarker`; `findUnique`'s multi-binding branch no longer
    scans a set `typeIndex` had already produced, and `ScanResult` is deleted.
  - `@Marker` reading unifies (`extractModuleMarkers`/`extractClassMarkers` share
    one helper), and markers are validated once, where they enter the binding;
    module markers are computed once per module instead of one reflective read per
    `bind()`.
  - `EventDispatcher`'s constructor goes 7 → 4: `isClosed`/`deadEventPublisher`/
    `topicResolver` were all indirections pointing back at the bus; `@Topic`
    parsing (whose only user was sink fan-out) moves in with it, and the class and
    topic channels' dispatch bodies merge. `EventBus`'s constructor drops
    `@Inject` — `Container` is not injectable, and the annotation advertised a
    path that could not work.
  - `EventStats`'s 6×(LongAdder + Counter + increment + read) folds into one
    `Tally` record and `snapshot()`; the `public` modifiers on package-private
    classes (`EventStats`/`EventSinkRegistry`/`EventExecutorSupport`) are removed;
    `Subscription`'s two constructors merge into one.
  - `SymbolSpec.list` gains key validation (the one factory that skipped the
    `blank` check), and `normalizedKey`'s dead parameter is deleted.
  - Stale statements: `ContainerImpl`'s "both buses" (only `EventBus` remained
    long ago), `@Value` (a nonexistent annotation, 3 places), `@Marker` as
    "builder method annotations", `Ordering` listing only one `add` that returns
    it, and a dozen-plus singular/plural "event" slips in `EventBus`'s javadoc.
- Test hygiene: `EventBusAsyncPublishTest`'s two `Thread.sleep(200)` become the
  repo's standard `Await.until` (eliminating the fixed-sleep flaky surface); new
  regressions pin this batch's contracts — redelivery after rollback, bounded
  shutdown not hanging forever, sink failure counting and isolation, stream
  overflow counting, the ordered topic channel, and `DeadEvent.source` being the
  bus.
- **event types move into the `ioc.event` package**: the root package's 13 event
  types (`EventBus`, `EventSink`, `EventSubscriber`, `EventBridge`,
  `Subscription`, `DeadEvent` etc.) move wholesale into
  `com.jujin.freeway.ioc.event` — the root package returns to a pure container
  face, and package-private mechanisms like `EventBridge`/`EventDispatcher`/
  `EventStats` have their visibility domain narrowed from "the whole root
  package" to the event domain itself. A pure import change with zero semantic
  change (imports and fully-qualified names in and out of the repo are rewritten
  compiler-driven; the white-box shutdown test of `EventExecutorSupport` inside
  `EventBusAsyncPublishTest` splits into `event.EventExecutorSupportTest`,
  placed beside its class per "tests live beside what they cover"). Three
  contract documents land in the same batch: `setAsyncExecutor` states it is a
  **deliberate runtime exception** in a sealed world (hot-swappable, lifecycle
  owned by the installer — composition-time data cannot express either half);
  the orphaned javadoc on `deferOrRun` pointing at a deleted dedup method is
  cleared (the capacity trade-off and the claim-at-dispatch semantics move into
  `EventBridgePolicy`'s parameter docs); and `EventSink` gains one contract line —
  "fan-out visit order = the contribution topological order (before/after in
  effect), but only this JVM's loop order".
- **Rule ② carve-out (single-transport redelivery suppression)**: the rule kills cross-transport
  identity machinery (shared ids, windows keyed on them, bus coupling) — and stays killed.
  What returns, by explicit exception, is strictly less: an opt-in bounded seen-set inside
  ext's `KafkaEvents`, keyed on the record's own CE id, default off, poison-first ordering,
  no bus contact. Rebalance redelivery is routine (every deploy/scale), business-key
  idempotency doesn't cover keyless signals, and the old `dedup.enabled` switch died with
  the bridge — this restores exactly the single-transport half, nothing of the cross-transport
  machinery. See the ext CHANGELOG for the shape (`freeway.kafka.dedup-capacity`,
  `duplicatesDropped`).

## [1.5.3] - 2026-09-20

### Migration

Module composition converges from "tree wrapper + fake modules" to a single
`ModuleNode`: the structure has only an application root and module nodes, bundles
are declared with `@SubModule`, and class declarations switch to load-time
instantiation. Replace per the table when upgrading:

| Old API / behaviour | New API / behaviour |
|---|---|
| `ModuleNode.tree()` → `TreeNode<ModuleEx>` | `ModuleNode.children()` (`List<ModuleNode>`) and `ModuleNode.render()` |
| `ModuleNode.children()` → `List<TreeNode<ModuleEx>>` | `List<ModuleNode>` |
| `ModuleNode.module()` | The node carries its own declaration: `type()` / `instance()` / `resolve()` |
| `ModuleNode.bindOrder()` → `List<ModuleEx>` | `List<ModuleNode>` (module nodes), containing modules only; the application root no longer appears |
| `ModuleNode.group(name, …)` | Deleted; bundles use `@SubModule` (a submodule can still be placed alone via `of(...)` for a subset) |
| `ModuleNode.of(module, children…)` (explicit children) | Deleted; a module node uses `ModuleNode.of(class \| module)`, and bundles are declared with `@SubModule` |
| `ModuleNode.of(TreeNode<ModuleEx>)` | Deleted; the tree value is itself a `ModuleNode` |
| `CloudModules.standard()` | `ModuleNode.of(CloudModule.class)` (or `.add(CloudModule.class)`) |
| `Freeway.create(Collection<? extends ModuleEx>)` | `Freeway.create(modules.toArray(ModuleEx[]::new))` |
| `EventBus.enableInboundDeduplication(int)` / `disableInboundDeduplication()` | `EventBus.inboundDeduplication(int)` (capacity ≤0 disables) |
| `FreewayApp.run()` | `FreewayApp.run(new String[0])` |
| A second application root in `AppBuilder` was silently nested | `IllegalStateException`; to merge, construct one `ModuleNode.app(...)` explicitly |
| A class declaration instantiated at `of(Class)` | Instantiated at load time (container creation); the error timing for a missing no-arg constructor moves accordingly, with the same message |
| `freeway-flow`'s 52 property accessors `getXxx()` | Bare accessors: `getNodes()` → `nodes()`, `getTitle()` → `title()` (on Spec, `title()` reads / `title(String)` writes as same-name overloads); `FlowContext`'s keyed lookups keep the Map vocabulary `get` / `getAs` / `getOrDefault` |
| `PlantumlOptions` / `PlantumlDisplayContext` / `PlantumlDisplayResult`, `Graph.toPlantuml(…)` | `PlantUmlOptions` / `PlantUmlDisplayContext` / `PlantUmlDisplayResult`, `toPlantUml(…)` |
| `CloudHttpClientDefault.Wiring(…, 9 args)` | The canonical constructor takes 10 (trailing `shutdownGrace`; pass `null` for the default); the old-arity delegating constructor is not kept |
| `Sql.insert(…).set("col", v)` / `Sql.update(…).set("expr", v)` | `setColumn("col", v)` / `setExpression("expr", v)` — the mode is written in the method name, and using the wrong one throws `IllegalStateException` naming the other method |
| `new HttpServerConfig(host, port, backlog, grace[, …])` | `HttpServerConfig.defaults().withPort(…).withBacklog(…)…` (tests use `TestServerConfig.loopback()` / ext testkit's `EngineFixture.defaultConfig()`) |
| `HttpServerConfig.builder()…build()` | `HttpServerConfig.defaults().withX(…)` |
| `new WebServer(engine, config, sink, pipeline)` | `WebServerBuilder.builder().engine(…).config(…).route(…)….build()` (ext tests go through the testkit's `TestServers.start(engine, config, pipelines)`) |
| `FreewayApp.of(…)` | `FreewayApp.create(…)` (entry points uniformly use `create`; `of` stays with the record factories that "build a value from given parts") |
| `FlowEngine.newInstance(…)` | `FlowEngine.create(…)` |
| `freeway-log.properties` at the classpath root | Not read (startup prints one stderr notice about the rename); rename it to `freeway-logging.properties` |
| `SymbolSource.systemProperties()` (the standalone source without a container) | Deleted; standalone assembly uses the same chain `SymbolSource.of(coercer, SymbolProvider.systemProperties())` (supply your own `new CoercerDefault()` as the coercer), and the system-properties tier is `SymbolProvider.systemProperties()` |
| `PlantUmlOptions.DEFAULT` + `isShowGatewayType()` / `showGatewayType(boolean)` (mutable) | `PlantUmlOptions.defaults()` (a record value); read `showGatewayType()` / `showIdInTitle()`, change with `withShowGatewayType(…)` / `withShowIdInTitle(…)` |
| flow's "No driver found" fix text pointing at `newInstance(Map…)` | Points at `FlowEngine.create(Map…)` (the test asserting it also pins "must not point at a deleted API") |
| RPC taking POJOs / Lists / Maps as handler parameters | Directly callable: the server rebinds by the declared parameter types (previously it surfaced as a `ClassCastException` and was reported as a business failure) |
| RPC argument count mismatching the exported signature | Judged `Kind.REJECTED` before dispatch (400 + reject reason); previously it fell into business-failure classification |
| A singleton holder injecting an `@NotThreadSafe` implementation through an interface | Rejected at startup (same as injecting the concrete class directly): the singleton proxy caches exactly one target, and one layer of interface does not wash off the marker |
| flow graphs with `"version": 2`, `task: "$metaKey"`, `task: "!marker"` | Bump to `3`; static values go in the node's `data` field, and marker matching becomes a contribute with an id + `@name` — the old spellings error in `GraphSpec.create()` with directions |
| `engine.register(TaskComponent)` / `engine.markerIndex()` / `@FlowMarker` | Deleted; task resolution goes only through `@name` → `container.get(TaskComponent.class, name)` |
| The `FlowContainer` adapter seam | Deleted; `FlowDriverDefault` holds the `Container` directly |
| `engine.addInterceptor(i[, index])` / `removeInterceptor` / `FlowOptions.interceptorAdd` / `FlowInvocation` | `FlowInterceptor` is contributed via `binder.contribute(FlowInterceptor.class)`, loaded into a chain and immutable afterwards; `interceptFlow(context, graph, chain)` takes a `FlowChain` |
| `eval(graph, steps, ctx)` / `recordNode` / `FlowTrace` / `ctx.trace()/enableTrace/lastRecord/lastNodeId/interrupt()` / `FlowContext.exchanger()` | Deleted — replay-and-skip is not durable execution; stopping uses `ctx.stop()`, early completion is legal; serialization via `toJson()` is diagnostic only |
| `NodeType.UNKNOWN` / `NodeType.code()` | Deleted; parsing becomes total, and an unknown type name errors on the spot listing the legal values |

Behaviour changes (no call sites need changing, but worth knowing):

- flow execution switches to an iterative frontier walk: path length no longer
  consumes the JVM stack (a 20,000-node chain runs fine in testing), and the
  `MAX_EXECUTION_DEPTH` guard and its `StackOverflowError` fallback are deleted;
  build-time cycle detection's DFS is also made iterative (it would have blown the
  stack at the same depths first).
- flow's build gate widens: all `when` expressions compile in `create()`; `join`
  keys are allowed only on PARALLEL with values restricted to `merge`/`shared`;
  the `data` key must be non-blank. Errors that used to surface only at runtime now
  report at build.
- flow PARALLEL defaults to `join: "merge"`: branch writes buffer thread-locally,
  merge on clean completion, and write-write conflicts error — shared mutable
  context goes from "an apology in the docs" to a declaration on the graph;
  `shared` opts back into the old behaviour explicitly.
- An `@graph` subgraph that never reaches END now throws at the calling node
  instead of silently interrupting the parent flow.
- flow's join counters, loop iterators and dead-end markers move into `ExecState`'s
  engine-private namespace; custom drivers no longer get a string bag sharing the
  pool with the engine's monitor keys (`vars()`/root counters/the generic
  `stack()` — surfaces with zero callers outside tests — are deleted).

- An interface singleton proxy's steady-state method calls no longer contend on
  the JVM-level realize lock: the lock protects only first construction and the
  shutdown seal, and a cache hit returns lock-free — the comment's "cached lookups
  are lock-free" is finally true (measured before: one unrelated slow construction
  could block calls on another container's already-built singletons for 1.2 s).
- `Pool.borrow()` returns a fresh borrowed handle each time (the pooled object is
  unchanged): a late `release`/`invalidate` on an already-consumed handle is a
  silent no-op and no longer takes the connection away from its current holder;
  foreign objects are still rejected with the same message.
- enum parameters bind by `name()` (the read side already used `valueOf`); the
  write side previously handed the raw enum to `setObject`, which H2/PostgreSQL
  rejected with a conversion error.
- mesh reconnection's exponential backoff advances on "handshake failure" and
  clears only after a hello ack — a peer that accepts the socket but rejects the
  hello is no longer re-dialed at full speed.
- The load balancer reads `ServiceInstance.weight()`: weight is a share of the
  cycle, with 0/negative treated as 1; zone/canary remain custom-policy territory.
- JSON number tokens are capped at 1000 characters (up to 10 MiB used to be legal,
  and BigDecimal parsing is super-linear — a single token could occupy a core for
  minutes); JUL's size estimation no longer loops forever inside the handler
  monitor on a cyclic cause chain.
- A hook calling `close()` inside its own `start()`: that hook stops cleanly and
  startup aborts, and later hooks no longer start (previously the closer was never
  stopped, and subsequent hooks started on an already-closed container with nobody
  to stop them again).
- A provider returning null reports `Provider returned null for X@id` (previously
  an anonymous NPE from `targetCache.put`).
- HTTP/2 for an RST_STREAM arriving right after HEADERS: the stream input is
  marked ended before teardown and the connection's read loop no longer stalls;
  the dead RST branch in stream dispatch is deleted, leaving reset exactly one
  path.

- A class declaration is a **declaration**: loading the same class-only tree into
  several containers gives each container fresh modules; instance/lambda
  declarations remain the one object from composition time, shared across
  containers.
- A module node's `name()` is the class's simple name before resolve; only a
  module whose `name()` is overridden shows a custom name when placed as an
  instance.
- `TreeNode` is deleted with this convergence (added in Unreleased, never
  released); an application calling `moduleTree().tree()` directly migrates to
  `children()` / `render()`.
- Standalone assembly (no container) is now a real chain: `SymbolSource.of(...)`
  expands `${...}`, `SymbolSpec` parses with the `Coercer` you pass, and
  `register(SymbolProvider)` works — none of the three was true of the old flat
  source. A `-D` value containing an unknown `${...}` now errors like the
  container path instead of returning verbatim.

### Changed

- **freeway-flow redesigned: from "a ported engine" to "freeway's graph
  interpreter" (schema v2→v3)** — the module used to call itself "port of
  solon-flow 4.0.2" and the comments made "preserving upstream behaviour" the
  goal; the mechanism layer carried a small independent framework's accessories
  (its own DI seam `FlowContainer`, its own marker table `FlowMarkerIndex`, its
  own interceptor chain `FlowOptions` + mutable registration, its own
  replay-and-skip "durability" `FlowTrace`). This round swaps every
  self-built mini mechanism for freeway's existing answer, leaving only the graph
  interpretation itself:
  - **Execution**: recursive descent becomes an iterative frontier walk; the
    `MAX_EXECUTION_DEPTH` guard and the `StackOverflowError` fallback are deleted;
    the depth-guarded recursive build-time cycle detection is made iterative in the
    same pass. The build-time validation posture's invariants (dead ends loud,
    task_exec aligned exactly once, failure classification) are kept one by one,
    with tests.
  - **Mechanisms come home**: interceptors = a contributed
    `Extension<FlowInterceptor>` (loaded into a chain, immutable after startup,
    deleting `addInterceptor/removeInterceptor/FlowOptions/FlowInvocation` and the
    COW ordering race); `@name` goes straight to
    `container.get(TaskComponent|ConditionComponent, name)` (deleting the
    `FlowContainer` adapter seam); `!marker` is deleted wholesale; `ExecState`
    narrows to the engine-private (graph,node) key space (deleting `vars()`, the
    root counter and the generic `stack()` — none had callers in the repo); and
    solon-style public dead surface `Node.TAG`, `TaskDesc/ConditionDesc.isNotEmpty`,
    `ConditionDesc.attachment` are deleted with them (migration is `node.type()`
    and `!desc.isEmpty()`).
  - **Semantic honesty**: pause/resume/`FlowTrace`/`steps`/`reverting`/`interrupt()`
    are deleted (replay-and-skip is not durable execution, and the demo and the
    repo made zero use of them); a subgraph that never reaches END errors at the
    call site; `FlowContext.put(null)` changes from silently dropping to clear
    semantics, and LOOP's remove workaround goes with it; `toJson()`'s docs are
    reworded to diagnostic-only.
  - **schema v3**: the task vocabulary is closed to `@name`/`#graphId`/inline
    components + the node's `data` field (replacing the `$meta` magic keys); `when`
    expressions compile in `create()`; PARALLEL nodes gain a `join` declaration
    (`merge` by default: branch writes isolated + conflicts error; `shared` opts
    back explicitly); the `NodeType.UNKNOWN` sentinel and `code()` are deleted. A
    v1/v2 document format or an old vocabulary hit errors at build time with
    directions.
  - **Docs**: the `package-info` and README are rewritten from "porting notes"
    into a semantic specification (keeping the Apache 2.0 attribution); the current
    schema spec lives in `freeway-flow/docs/graph-v3.md`, with `graph-v2.md` marked
    as a historical proposal; `migration-notes/assessment/plan` stay as dated
    trajectory records.
- **SymbolSource converges to one chain, and system properties become a
  SymbolProvider (freeway-ioc)** — the SYS tier previously had two implementations
  inside ioc with different semantics: in the container chain it was a
  `SymbolProvider` (`order()=TIER_SYS_PROPS`, constructed anonymously by
  `SymbolSourceDefault.standard()`), while the standalone source
  `SymbolSource.systemProperties()` was another flat implementation — it did not
  expand `${...}` (the javadoc even recorded the difference as "nothing to
  expand", though a one-tier chain can expand `-D` values just fine), carried its
  own container-independent `CoercerDefault`, and its `register()` simply threw.
  Now the tier is defined in exactly one place: `SymbolProvider.systemProperties()`;
  and the chain has one implementation: `SymbolSource.of(Coercer, SymbolProvider...)`
  (`SymbolSourceDefault` accordingly moves from `ioc.internal` to `ioc.symbol`,
  staying package-private, taking its `Coercer` at construction, and dropping the
  set-after-the-fact `coercer(...)` setter plus the `standard()` that served only
  it). `ContainerImpl` starts its chain with
  `SymbolSource.of(coercer, SymbolProvider.systemProperties())`, passing the
  container's own `Coercer`, so a module-contributed `CoerceRule` now takes effect
  for `resolve(SymbolSpec)` too (this path had no test before; the new
  `SymbolSourceSpecTest` covers the container path's `resolve(SymbolSpec)` and the
  contributed rules, one case each). Standalone assembly no longer has a second
  semantics, and ext's Jetty / Undertow / Hikari container-less constructors each
  change by one line — the API surface shrinks instead of growing, deleting an
  entry point that "looked like a source but had half the chain switched off".

- **Batch B (sixth): entry-point factories unify on `create` (freeway-boot /
  freeway-flow)** — audit B3. The framework's entry points previously used two
  verbs: `Freeway.create(...)` and `FreewayApp.of(...)` were parallel entry points
  with different names, and `FlowEngine.newInstance()` was a third. The roles are
  now divided and the rule written into `AGENTS.md`: **`of` builds a "value"**
  (the record factories: `Endpoint.of`, `ServiceInstance.of`, `SymbolSpec.of`,
  `ModuleNode.of`, aligned with `List.of`); **`create` is the framework entry
  point**, handing you something to configure or run (`Freeway.create`,
  `FreewayApp.create`, `FlowEngine.create`, `Graph.create`); and **`.builder()`**
  is fluent assembly of a configured object (`WebServerBuilder.builder()`).
  Renames: `FreewayApp.of(...)` → `FreewayApp.create(...)` (4 overloads + 69 call
  sites), `FlowEngine.newInstance(...)` → `FlowEngine.create(...)` (2 overloads +
  24 call sites). Renaming this side rather than `Freeway.create` (306 call sites)
  to `of`: the same result for a third of the churn, and `Graph.create`/
  `GraphSpec.create` were already "entry points use create".

- **Batch B (fifth): `Orm.findAll`'s positional sentinels become `FindOptions`
  (freeway-db)** — the old signature
  `findAll(Class, String orderBy, int limit, int offset)` had two adjacent `int`s
  that were interchangeable at the call site: `findAll(type, "id ASC", 20, 0)` and
  `(…, 0, 20)` both compiled with opposite meanings; `""`/`0` also doubled as
  "unset". Now `Orm.FindOptions.defaults()` + `withOrderBy`/`withLimit`/`withOffset`
  (one wither touches one field), a blank `orderBy` normalizes to "no ORDER BY",
  and **a negative limit/offset throws on the spot** (the old implementation
  judged with `limit > 0`, so a negative was silently treated as "unlimited" — one
  typo became a full-table return). `findAll(Class)` remains as the convenient
  form of `defaults()`; 11 call sites migrated via the compile errors (10 in db
  tests + 1 in the ext benchmark).

- **Batch B (fourth): `PeerConnector`'s 7 positional parameters become `Wiring`,
  and `PoolConfig` gains withers (freeway-cloud / freeway-db)**:
  - `PeerConnector(PeerHub, Duration connectTimeout, String scheme, Duration
    handshakeTimeout, long backoffBaseMs, long backoffMaxMs, SSLContext)` becomes
    `PeerConnector(PeerHub, Wiring)`. The old signature had **two pairs of
    adjacent same-typed parameters** (two `Duration`s, two `long`s), so swapping
    either pair compiled — at the price of the mesh timing out or backing off at
    the wrong cadence. Every default in `Wiring.defaults()` comes from the
    declared defaults of `freeway.cloud.event.*` (a new `CONNECT_TIMEOUT`
    constant, sourced with the config key), and `withBackoff(base, max)` takes
    both values at once — a swap is no longer expressible at the call site; the
    only assembly point, `CloudEventLifecycleHook`, converges into one wither
    chain.
  - `PoolConfig` (12 components) gains its 12 `withX`s, and the class javadoc's
    example changes from "12 positional parameters" to
    `defaults(url, user, pass).withMaxSize(20)…`; `DEFAULT_HEALTH_CHECK_QUERY =
    null` is deleted ("no query" does not need a null constant — pass `null` to
    the canonical constructor, with a comment noting that `isValid` decides).
    Validation stays in the compact constructor, so every wither re-validates.
- **Two contract tests added**: `PeerConnector.Wiring`'s defaults/normalization/
  `withBackoff`, and `PoolConfig`'s "defaults says it all + a wither touches one
  field + every step re-validates" (with counter-cases: a swap or a hand-mutated
  field turns red).

- **Batch B (third): two "positional parameter matrices" become parameter records
  (freeway-db / freeway-http)**:
  - `MigrationRunner`'s two constructors (4-arg and 5-arg, the latter only adding
    `lockTtl`) become `MigrationRunner(Database, Options)`; the `Options` record
    holds `enabled`/`path`/`table`/`lockTtl`, `defaults()` is the **only** source
    of the defaults (`DbModule` previously restated `"db/migration/"` and
    `"_migrations"`), normalization (backslashes, trailing slashes, the table-name
    allowlist) moves into the compact constructor, and `withLockTtl(null)` restores
    the default lease. 24 call sites migrated via compile errors, with
    `new MigrationRunner(db, true, "db/migration", "_migrations")` becoming
    `new MigrationRunner(db, Options.defaults())` — the reader no longer counts
    arguments to learn what the positional boolean `enabled` was.
  - `FreewayHttpEngine`'s five positional constructors become
    `FreewayHttpEngine.Wiring`: it was not a ladder of "add one per step" but a
    matrix of optional parameters — tier 3 added metrics, and tier 4
    `(codec, coercer, ssl, http2)` silently reset metrics to noop. Now
    `Wiring.defaults(jsonCodec, coercer)` + `withSsl`/`withSslParameters`/
    `withMetrics`, one wither per field; `WebServerBuilder`'s ternary and
    `HttpModule`'s two paths converge into one chain accordingly.
- **Two contract tests added**: `MigrationRunner.Options`'s defaults/normalization/
  `withLockTtl(null)`, and `Wiring`'s "withers do not overwrite each other" (using
  a non-noop metrics stand-in — otherwise "was reset" and "was kept" are
  indistinguishable; this assertion was initially toothless precisely because a
  `NoopMetrics` was passed).

- **Batch B (second): old-arity convenience constructors and internal public types
  referenced only by tests (freeway-commons / boot / cloud)**:
  - `JULFileHandler(String, long, int, boolean)` is deleted: it was the
    convenience form "one `flushIntervalMs` short of the canonical constructor" —
    the same kind of constructor removed in earlier rounds; 21 test call sites
    switch to passing `JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS` (semantically
    identical, just stating the default at the call site).
  - `HookLifecycle`, `ConfigFileReader` (boot) and `ServiceIdentity` (cloud)
    narrow from public to package-private: by `AGENTS.md`'s criterion ("another
    package must assemble or substitute it to justify public"), they had only
    same-package users — the earlier reference count had mistaken a **comment**
    in `AppRuntimeDefaultTest` for a reference.
  - `ConfigSources` stays public, with the reason written in
    `boot/internal/package-info.java`: a cross-module test
    (`CloudSymbolPrecedenceTest`) needs to assemble the layered sources directly
    to pin cascade precedence — the one exception where "test assembly" rather
    than "container assembly" reaches it.

- **Batch B (first): export surface, aliases, bind-time validation, JSON
  accessors and a cache leak (freeway-cloud / http / ioc / commons)** — the first
  five items of audit §6's batch B:
  - **The RPC export surface follows the declared type** (cloud): `RpcTarget` used
    to go into `handler.getClass().getMethods()`, so
    `bind(narrow interface).to(broad implementation)` exported the
    implementation's extra public methods too — the opposite of the
    "exporting is declaring" stance. It now reads `export.type()`'s method
    surface; `RpcExport`'s javadoc is unified on "the type is the boundary"
    (declare a facade type when you want a smaller one). A new case asserts
    directly that "a method not on the declared type does not enter the method
    table".
  - **`ResponseFraming.shouldGzipFile` deleted** (http): it was a pure forward to
    `shouldGzipStream` while its javadoc claimed an extra `body-allowed` gate
    (which `shouldGzipStream` already checked) — an alias with no behaviour
    paired with an untrue description. The only core call site is renamed, and
    the duplicated assertion group in the tests is deleted.
  - **`.advise()` validates the interface constraint at bind time** (ioc): the
    constraint is knowable right at `bind(GreeterImpl.class).advise(...)`, but the
    error used to wait for the first `get()` and land on an unrelated call site;
    it now throws at the binding call with the fix ("bind X to an interface to
    use .advise()"). `Scoping` gains `default void within(Runnable)`, and the
    tests' eight `within(() -> { …; return null; })` tails disappear.
  - **The constructor cache no longer pins class loaders** (commons):
    `BeanIntrospector` kept wrappers in a `WeakHashMap<Constructor,
    BeanConstructor>` whose values strongly referenced their keys — entries were
    never reclaimed, and every class that passed through, with its class loader,
    was pinned by a static map. It becomes a `ClassValue<Map<…>>` sharded by
    declaring class (the same approach as `JsonCoercions`), reclaimable when the
    loader unloads; a new case pins "the same Constructor is wrapped once".
  - **JSON write methods renamed, and type mismatches stop being silent**
    (commons): `JsonObject.object(key)`/`array(key)` and `JsonArray.object()`/
    `array()` were **write** operations (creating and attaching a child node) that
    differed from the readers `getObject`/`getArray` by one `get`; they become
    `newObject`/`newArray`/`addObject`/`addArray`. `JsonAccessors.object/array`
    used to return `null` both when the key was missing and when it existed with
    the wrong type; now only a missing key returns `null` and a type mismatch
    throws `IllegalArgumentException` (consistent with its numeric/boolean
    siblings, with the actual type stated via `JsonUtils.typeName`).

- **ioc: substitutable roles are named by "can the outside `.primary()` it", and
  the contribution chain follows the resolved instance (freeway-ioc)** — audit A8:
  - `SymbolSourceImpl` → `SymbolSourceDefault`, `LoggerSourceImpl` →
    `LoggerSourceDefault`: both can be displaced by a module's `.primary()`
    (`InjectionResolver`'s comment already said so), so by the criterion they are
    `XDefault`, not `XImpl`. `AGENTS.md` writes the test as one sentence — "after
    a module binds with `.primary()`, does the container honour it" — and states
    explicitly that "the framework wires it concretely itself" does not make an
    `XImpl`.
  - **Replacing `SymbolSource` no longer silently drops the config chain**: the
    container used to register `SymbolProvider` contributions into its own built-in
    instance while the injection side went through
    `container.get(SymbolSource.class)` — the moment a module replaced the source,
    boot's whole cascade (CLI/env/files) landed in an instance nobody read.
    Contributions are now recorded into an ordered list and replayed into the
    source that is actually resolved **after all modules finish binding** (a
    module's own bindings register only after its `bind` body runs, invisible at
    declaration time); the built-in instance still receives them immediately at
    declaration time, so bind-time queries are unaffected.
  - `SymbolSource.register(SymbolProvider)` becomes a contribution seam on the
    interface whose default implementation **throws**: a replacement that cannot
    accept contributions reports at startup instead of quietly losing a chain.
    `SymbolSourceReplacementTest` pins both halves.

- **`HttpServerConfig` converges to one spelling: canonical constructor +
  `defaults()` + per-field withers (freeway-http)** — the 13-component record
  previously carried a **4-tier delegating constructor ladder** and an embedded
  `Builder` at once, and its defaults were declared three times and had drifted:
  `Builder` said port 0 / grace 0, while the module's declared `freeway.http.*`
  and `WebServerBuilder` said 8080 / 2s. Only the canonical constructor remains
  (validation still in one place) + `defaults()` (host 127.0.0.1, port 8080,
  backlog 0, grace 2s plus the libraries' defaults) + 13 `withX`s;
  `HttpModuleConfig`'s key defaults and `WebServerBuilder` now reference it, and
  the constructor ladder, the `Builder` and the character-heuristic detection are
  all deleted. Call sites migrated via compile errors (about 60 in core tests,
  about 25 in ext), with the test-only shapes gathered into `freeway-http`'s
  `TestServerConfig.loopback()` and ext testkit's `EngineFixture.defaultConfig()`.
- **`WebServer` keeps one constructor, and it answers `secure()` by the real
  criterion (freeway-http)** — the old public 4-arg constructor hard-wired
  `secure` to false: any TLS server built through it answered "not TLS" to
  `secure()` — ext's TLS tests stood exactly on this (production went through
  `HttpModule`'s 6-arg form, so live was unaffected). The only public assembly
  path is now `WebServerBuilder` (judging `secure` from the provided
  `SSLContext`). ext's six adapter test files switch to the testkit's `TestServers`
  + `Pipelines` (they previously hand-assembled `RequestComponents`, a different
  path from real assembly); `Pipelines` grows an error-handler tier so migration
  does not lose the 413-mapping and exception-capture assertions.

- **flow: v1 leftovers cleared, failure messages name the fix, snapshot semantics
  aligned (freeway-flow)** — audit P1/P2:
  - `NodeType`'s "missing/empty type defaults to ACTIVITY" is deleted: the v2
    parser already guaranteed non-empty via `requireString`, so the branch was
    unreachable and its only effect was handing a future caller a silently wrong
    node type; following factory naming, `nameOf` is renamed `of` (an unknown type
    throws and lists the legal values), and `code()` gains a purpose note.
  - `FlowMarkerIndex.register` changes from a silent `return` on an **empty marker
    set** to one WARN line naming the fix: lambdas and anonymous classes cannot
    carry `@FlowMarker`, which is precisely the common way to lose a handler, and
    the failure used to surface only at eval as "no component matched".
    `freeway-flow/README.md`'s example is corrected to
    `engine.markerIndex().register(component, Set.of(...))` — the original
    registered a lambda while referencing a `!marker` task, so copying it verbatim
    was guaranteed to fail.
  - The error messages of component resolution, `$in` parsing, `Graph.nodeOrThrow`
    and `GraphSpec.validateEntry` gain node/graph context and the expected shape
    (they previously included messages like `"The task component 'x' not exist"`,
    ungrammatical and fixless).
  - `GraphSpec.fromDom` becomes package-private (`fromText` is the only version
    gate); `FlowEngineDefault.graphs()` returns a `List.copyOf` snapshot,
    consistent with `FlowTrace`/`FlowMarkerIndex`'s snapshot conventions; and
    `FlowTrace`'s `setRootGraphId`/`enable` become `rootGraphId(...)`/`enabled(...)`,
    matching the module's bare write methods.
  - Docs: README's quick start gains `version: 2` (the example lacked the field and
    the gate rejected it outright) and drops the "v1 still supported" claim;
    `migration-notes.md` and `graph-v2.md` note that v1 is deleted; the AST cache
    entry in `docs/design/flow.md` is updated to the present
    (`synchronizedMap`, with why the read-write lock was withdrawn).
  - **Deliberately kept and recorded in the design decisions**: the string form of
    `trace` in flow's state documents remains readable — it is historical data
    applications have already persisted (the same kind as rows written by old
    versions in the migration table), not API compatibility; the removal
    condition is attached.

- **Docs: 17 statements contradicting the implementation corrected (whole repo)**
  — the most common audit finding was not design errors but "the documentation
  lies", corrected item by item: http's `HttpResponse` had two stacked javadoc
  blocks (the dangling one claimed `contentLength` must be known, when `-1` for
  chunked is allowed) and references to nonexistent methods
  (`request()`/`response()`/`status(s)`); cloud's event mesh hook had its
  **ordering stated backwards in three places** (the comment said before, the code
  runs after, with the reason written in the code) and readiness's "always
  healthy" (the implementation has three unhealthy answers); db's `DatabaseHub`
  "unmodifiable view" (in fact a construction-time snapshot); ioc's `ServiceRuntime`
  "64-way lock striping" (a single lock, with the same file's own text saying
  otherwise) and `Contributions`' id shape (in fact `snake@package`); boot's
  cascade javadoc missing the `-D` tier, and `AppRuntime`/`AppState` gaining their
  lifecycle contracts; commons' `JsonUtils` gaining its contract (including the
  implicit "closes the input stream" side effect); and `AGENTS.md`'s SLF4J
  declaration aligned with reality (6/7 modules declare it explicitly; only ioc
  inherits).

- **boot: file-read failures have one policy, profile variants can no longer
  rewrite activation keys, and composition-time failures also close the config
  (freeway-boot)** — three audit P1s:
  - One "unreadable file" previously had three fates: classpath and
    working-directory base hard-failed; working-directory profile variants and
    `freeway.config.file` additional files only WARNed and then the whole file's
    keys vanished; an explicitly declared missing path was completely silent. Now:
    **a file that exists but cannot be read → `IllegalStateException`** (naming
    the path and the fix), with the watcher keeping the old snapshot during hot
    reload; a path declared by `freeway.config.file` but missing → one WARN line;
    optional working-directory files missing remain silently normal.
  - `overrideFiles` becomes **role-tagged** (`BASE` / `PROFILE_VARIANT` /
    `DECLARED`): a profile variant's `freeway.profile` is stripped just like the
    classpath side, so `profiles()` and the resolved values can no longer diverge —
    `AppConfig`'s "cannot disagree" promise had only been half-kept (classpath
    side only).
  - `AppBuilder.start()`'s **composition section moves into the try**: declaring
    the same module class twice or a bad SPI provider throwing now also runs
    `config.close()`; otherwise every "catch and retry" by the caller leaked a hot
    reload watcher (thread + `WatchService`).

- **`Coercer`: Number→boolean no longer judges truth by `int` truncation
  (freeway-commons)** — values like `0.5`, `0.9` and `4294967296L` (2³²) —
  "non-zero but with a zero integer part" — silently became `false` (the
  implementation was `n.intValue() != 0`), while the same file's integer path
  lists exactly this pattern as corrupting data and forbids it. The decision now
  uses the exact decimal magnitude; the two non-finite sources keep their declared
  behaviour (NaN false, ±Infinity true — the latter was true only by saturation
  before, now stated explicitly). **Behaviour change**: `0.5` goes from `false` to
  `true` — a fix of a silently wrong value, not a taste adjustment.
- **Logging: one read policy, keys centralized, MDC no longer presumes
  application fields (freeway-commons)** — three places:
  - The same `freeway.log.file.*` keys previously had opposite policies on two
    read paths: the framework path silently swapped invalid values for defaults,
    while the natively-registered `JULFileHandler` threw outright. Unified as
    "**report and fall back**": one `logEarly` line naming the key, the original
    value and the default actually applied; neither throwing (which would lose the
    whole handler at `LogManager` instantiation) nor silence (which is how a typo
    survives a week).
  - `freeway.log.*`'s key names centralize into the package-private `LogKeys`: 23
    literals were previously scattered across five classes where a rename could
    only silently misbind; now change one place, and a missed one fails to compile.
  - `freeway.log.mdc.priority`'s default is emptied: the framework no longer
    publishes some application's field names (`code,market,diagId`) as its own
    default in the docs; unconfigured, all MDC keys sort alphabetically, and an
    application declares the keys to highlight via `-D`/env.

- **Design rule revision: compatibility is not a goal (whole repo)** — the
  "records used as adapter assembly points keep an old-arity delegating
  constructor" rule written into `AGENTS.md` last round is deleted: it protected
  **already-compiled call sites** at the price of every new component leaving an
  old path beside the canonical constructor, with readers forever asking "which
  one should I use". The rule flips — adding a component changes the canonical
  constructor's shape directly, **the compile error is the migration path**, and
  `freeway-ext` adapts in the same batch. Two known residues are cleaned with it:
  - `CloudHttpClientDefault.Wiring`'s 9-arg delegating constructor ("the shape
    before shutdown grace") is deleted, and its only call site (ext's
    `RemoteRpcContract`) passes 10 arguments.
  - `freeway-log.properties`' transitional read is deleted: the old name is only
    **detected**, never loaded, and `JULEnhancer.renamedFileNotice()` prints the
    rename notice at startup — the config is not adopted, but it never fails
    silently. The contract is pinned by `JULEnhancerLogSourceTest`.
  Two things are explicitly **not** deleted, because they are not API
  compatibility but facts that already exist in the outside world: migration
  validation's dual-track checksum (rows already recorded in the database, CRLF
  checkouts) and `CoercerDefault`'s duration input forms.
  - **The definition of stability is written down alongside** (`AGENTS.md`):
    stability lives in **semantics**, not in **shape** — a frozen signature with
    drifting behaviour is the worst instability, bypassing the compiler straight
    into production; adapting is a "visible" problem, since a consumer (or an
    agent) can keep up as long as it can see what changed, so the change record
    belongs to the change itself. Three criteria: a behaviour change must carry
    its "why" in the CHANGELOG; a shape change must ring in the compiler (**only a
    clean compile counts as verification** — a stale `target/` swallows it, as the
    `Wiring` episode's false green showed); and a break worth paying for leaves
    the caller's code **smaller or unchanged** — rename-only, add-an-argument,
    move-a-concept breaks are churn, not evolution.

- **`WebServer`'s constructor ladder narrows from 4/5/6 to 4/6** — the
  package-private 5-arg constructor (one `readinessProbe` more than the 4-arg)
  had no callers left in the repo: core's assembly points (`HttpModule`,
  `WebServerBuilder`) all use the 6-arg form, and the 4-arg is kept for external
  adapters. Deleting it changes no behaviour and makes the "public 4-arg /
  internal 6-arg" boundary clearer; the other two review conclusions are in the
  docs audit record (`HttpContextImpl.reset`'s 10-arg form and
  `ServiceRegistry.drainWindow()` verified as still worth keeping).

### Added


- **`@SubModule` (freeway-ioc)** — the bundle declaration: a module class lists
  the submodules placed along with it (pre-order, in declaration order), expanded
  into a `ModuleNode` subtree when the tree is built. `ModuleNode.of(Bundle.class)`
  is the whole bundle; a submodule is an ordinary module, and
  `ModuleNode.of(SubModule.class)` is the subset, so no exclusion API is needed. A
  `@SubModule` cycle errors when the tree is built. `CloudModule` (freeway-cloud)
  uses it to declare the 8 standard cloud modules, replacing the
  `CloudModules.standard()` factory.
- **`SslSettings` / `SslContexts` (freeway-http root package)** —
  `freeway.http.ssl.*` is now declared once: `SslSettings.from(SymbolSource)`
  reads all the TLS keys (enabled / key-store(+password/type) /
  trust-store(+password/type) / client-auth / protocols / ciphers / sni-directory
  / reload-interval / http2) and makes the same three-state decision (explicit
  true/false wins; unset falls to keystore presence; all empty is plaintext);
  `SslContexts` is promoted from the internal `SslContextFactory`, publishing
  `build(SslSettings)` and `parameters(SslSettings)` (keystore/truststore loading,
  SNI multi-certificate, protocol and cipher restriction). This "key reading +
  three-state" block used to be written once in the built-in engine's
  `HttpModuleConfig` and once in each of the two adapters; now the built-in engine,
  Jetty and Undertow share one copy, and `internal.HttpModuleConfig.Ssl` and
  `internal.SslContextFactory` are deleted (`SniKeyManager` moves into the root
  package). Adapters keep only their own exclusive keys (Jetty's
  `ssl.key-password`/`key-alias`).
- **Fixed `SymbolSource.systemProperties()`'s SymbolSpec parsing**: the standalone
  source previously had only `resolve(String)`, so an adapter reading via
  `SymbolSpec` hit "has no parser" — the container path worked while direct
  construction failed. It now matches the container chain, internally wiring a
  `CoercerDefault` and going through `spec.parse(raw, coercer)`.
- **Three core seams for external adapters to reuse** — a normalization comparison
  of the two HTTP adapters (Jetty/Undertow) showed that three of their duplicated
  blocks contained no engine API at all, only core lacking support for its own
  public surface, so the same logic existed in three copies (the built-in engine's
  included). They now sink into core:
  - **`Compression` (freeway-http)** — `acceptsGzip(List<String>)` /
    `acceptsGzip(String)` + `gzip(byte[])`. It sits beside the root package's
    `MediaTypes` (the same reason: one negotiation shared by the built-in engine
    and external adapters instead of each writing its own string scan). The
    semantics unify as: a missing header means "no preference" and no compression,
    `gzip` enables, `gzip;q=0` refuses, the `q` parameter name is case-insensitive,
    and a malformed `q` counts as accepting. The built-in engine, the Jetty adapter
    and the Undertow adapter each previously carried their own
    `acceptsGzip`/`qValueIsZero`/`gzip`.
  - **`AbstractWebSocketSession` (freeway-http.websocket)** — the session's
    "request identification + exchange metadata" half: correlationId/startTime/
    principal/attributes and method/path/pathVar(s)/queryParam(s)/header(s), with
    maps always deep-copied immutably. Implementors add only the engine's frame
    operations. It mirrors `AbstractHttpContext`'s division of labour for HTTP
    exchanges exactly; the built-in engine's `WebSocketSessionImpl` and both
    adapters' sessions switch to extending it.
  - **`SymbolSource.systemProperties()` (freeway-ioc)** — a standalone source
    reading only JVM system properties (strict accessors throw
    `UnknownSymbolException` on a missing key, lenient accessors fall back to the
    default, `expand` returns verbatim). Mechanism only, no key names, so it lives
    on `SymbolSource` rather than being re-written by every adapter that has both
    a container path and a standalone one (previously Jetty/Undertow/HikariCP, three
    copies).
- **Fixed `closeReason`'s UTF-8 truncation**: cutting the close reason to 123
  bytes hard-split on bytes, which can land mid-multibyte-character; the
  replacement character (U+FFFD) re-encodes to 3 bytes, so "cut to 123" could
  itself exceed 123 bytes again (measured at 125), and a strict peer would reject
  it. It now backs off to a code-point boundary, guaranteeing ≤123 bytes with no
  replacement characters. Both adapters carried this defect, fixed along with the
  shared implementation.
- **`ModuleNode` (freeway-ioc)** — the value type of module composition:
  `app(name, …)` for the application root, `of(class | module)` for module nodes
  (each carrying its declaration: class or instance); when a module class declares
  submodules with `@SubModule`, `of` expands the bundle subtree, and submodules can
  still be placed alone via `of` for a subset.
  **The conventional spelling is the class**: `of(OrderModule.class)`,
  `app("orders", OrderModule.class, HttpModule.class)`,
  `FreewayApp.run(OrderModule.class)`, `AppBuilder.add(Class…)` — the class is
  named explicitly by composition (no scanning of any kind) and instantiated via
  its **no-arg constructor at load time**; only a module whose constructor takes
  parameters is placed as an instance (`of(new TenantModule("acme"))`), because a
  module's constructor carries configuration, not dependencies (the container does
  not exist yet, so there is nothing to inject). Declaring by class without a
  no-arg constructor errors at load, naming the class and the fix; declaring the
  same class twice, and two instances of one class, error the same way (the fix:
  declare once, and share the same node value for reuse). `bindOrder()` gives the
  module nodes' pre-order (each node carries `type()` / `instance()` /
  `resolve()`), `render()` / `children()` give the structure, `classes()` shows
  SPI discovery which classes are already in the tree, and `isApplication()` lets
  entry points reuse a root the caller built.
- **`Pool.invalidate(PooledConnection)` (freeway-db)** — the "destroy" semantics
  on the connection-pool SPI: the pool must physically close the connection and
  release its slot rather than recycle it to the next borrower. "This connection
  can no longer be used" previously had no way to be expressed — the only option
  was `conn.connection().close()`, which for a self-built pool happened to equal
  destruction but for a pool handing out proxies (HikariCP) was only "rollback +
  reset state + recycle", so the three paths that must destroy a connection whose
  state cannot be restored (`Database.transaction`'s state restore, `BatchQuery`'s
  autoCommit restore) degraded to silent recycling under adapters. All three now
  call `invalidate`: `PoolDefault` destroys and releases the permit, and
  `HikariPool` goes through `HikariDataSource.evictConnection`. Handles already
  returned or destroyed are idempotent no-ops (cleanup paths may call freely), and
  foreign handles error just like `release`. Alternative `Pool` implementations
  need to follow with the method.

### Changed

- **The module tree converges to the single type `ModuleNode`, bundles are
  declared with `@SubModule`, and class declarations switch to load-time
  instantiation (freeway-ioc + freeway-boot + freeway-cloud)** — the same tree
  previously had three representations: `ModuleNode` wrapped a generic
  `TreeNode<ModuleEx>`, structural nodes were played by the fake module
  `GroupModule` implementing `ModuleEx` (with the `isStructural`, `classes()`
  exclusion and `bindOrder`-counts-groups patches that followed); and the class
  form instantiated at `of(Class)` immediately, so the tree always stored
  instances. `ModuleNode` is now itself the immutable tree with exactly two node
  kinds: the application root (named, binds nothing) and module nodes (each
  carrying its declaration: class or instance, via `type()` / `instance()` /
  `resolve()`) — no more `TreeNode`, no fake modules, no "named groups". A library
  packaging several modules declares its submodules on the bundle class with
  `@SubModule`, expanded at tree build (static metadata, not a callback module);
  submodules are ordinary modules that can be placed alone for a subset, so there
  is no exclusion API. `bindOrder()` contains only module nodes (the application
  root binds nothing, so the startup log's module count is accurate), and
  structural display belongs to `render()`. Class declarations no longer
  instantiate at composition time: container load (`BinderImpl.load`) calls
  `resolve()` per node to construct, and only then `bind`. Structural validation
  (duplicate declarations, two-path errors, `@SubModule` cycles) therefore
  completes before any user constructor runs, and class-only trees/fragments can
  be loaded repeatedly by several containers, getting fresh modules each time;
  lambda/anonymous modules are instance declarations (captured state cannot be
  rebuilt) and keep being deduplicated by identity only, outside the class
  identity rules. `ModuleNode.group(...)`, the three `of(…)` factories and the
  `tree()` accessor are deleted, `ModuleNode.module()` is replaced by
  `type()` / `instance()` / `resolve()`, and the no-arg-constructor failure moves
  from composition time to load time (same message); `Freeway.create(Collection)`
  (no callers in the repo) is deleted with them. On the cloud side the
  `CloudModules.standard()` factory is deleted, restored to `CloudModule` +
  `@SubModule` (all bindings live in the submodules; the bundle itself stays as
  the shared face). `AppBuilder` no longer "accumulates a composed list and
  rebuilds a tree": it accumulates children directly, with at most one application
  root (a second fails explicitly, where it used to nest silently), and name and
  children each keep their own place. The discovery path's
  `ServiceConfigurationError`
  catch scope is corrected at the same time: it used to wrap only the loop body,
  while provider loading fails inside `hasNext()` / `next()`, so the
  classloader-context error never fired on the most common failure path.
- **`freeway-flow`'s accessors get Freeway naming (breaking)** — the 52 JavaBean
  `getXxx()` property accessors ported from solon-flow become bare accessors:
  `graph.nodes()` / `spec.title()` / `node.meta(key)`; `FlowContext`'s keyed
  lookups keep the JDK `Map` vocabulary (`get` / `getAs` / `getOrDefault`); and
  `Plantuml*` unifies as `PlantUml*` (including `Graph.toPlantUml`). The JSON/graph
  definition format and runtime behaviour are unchanged — pure naming alignment;
  the upstream source and attribution stay in `package-info`, which also notes the
  deliberate fork of "property accessors have been Freeway-ized".
- **Public-surface edge cleanup (freeway-commons + freeway-ioc + freeway-boot)** —
  public members that lost their role go with the module-tree convergence:
  `TreeNode` (added in Unreleased; zero references from core and freeway-ext once
  the tree became values) and its tests; `EventBus`'s
  `enableInboundDeduplication(int)` / `disableInboundDeduplication()` merge into
  `inboundDeduplication(int)` (capacity ≤0 disables and releases the window); and
  the no-arg `FreewayApp.run()` is deleted in favour of `run(new String[0])`.
  `ModuleNode.app(String)` stays: it disambiguates the one-argument form
  (`app(String, ModuleNode...)` and `app(String, Class...)` cannot be told apart
  by varargs alone).
- **Config classification rules + per-key tiers, and 16 doc/sample inconsistencies
  fixed (docs/configuration.md)** — the config surface previously had only three
  tiers ("decision keys / best default / advanced"), mixing declared keys,
  mechanism keys and silent tuning keys in the same tier. The rule itself is now
  written down: four questions (no reasonable default? the default is only a
  development stance? only written when disabling a feature? a pure tuning
  number?) map to six tiers (**required / decision / stance / tuning / declared /
  mechanism**), with shape suffixes marking where a value comes from
  (`·auto` / `·presence` / `·sentinel` / `·aggregate gate`) and a new "tier"
  column on the 126-row key table; a new "how to organize config files" section:
  one file by default + profiles as the environment axis, splitting per module is
  an optional convention (three disciplines, paired with `ConfigMaps`' cross-file
  duplicate-key warning). Three of the fixed inconsistencies were wrong when
  copied verbatim: the log file tuning key was written as `freeway.log.max-size`
  (the real name lives under `log.file.*`, silently ineffective), the prod JSON
  sample's `freeway.cloud.events.*` (the real name is `freeway.cloud.event.*`;
  8 keys silently dead), and the dev sample pairing `allowed-origins=*` with
  `allow-credentials=true` (`CorsFilter` throws at construction); the prod
  properties sample's empty `db.username` becomes a placeholder. The rest are type
  and annotation fixes: `cors.max-age` is an Integer, the four CORS keys are
  `List(String)`, `log.file.flush-interval` is a Long; `ssl.key-store-password`'s
  "required in production" becomes "when TLS is enabled" (the code does not
  pre-check); the duplicated `db.query-timeout` row is deleted; and
  `freeway.app.name` plus three process-level keys (`-D app.name`,
  `-D slf4j.provider`, `NO_COLOR`) are added. The logging section now tells the
  truth: `log.color` / `log.mdc` / `log.mdc.priority` / `log.caller-info` work
  only via `-D`/environment variables and are silently ineffective in either file
  home.
- **The new `ConfigDocsConsistencyTest` (freeway-boot)** — pins the config surface
  from two directions: every `freeway.*` key appearing in a sample must have a key
  row in `docs/configuration.md`, and every key in the doc must be readable by
  some module's source (keys are discovered the same way the framework reads them:
  string literals, plus constants composed as `PREFIX + "suffix"`). The former
  fails when someone writes a name like `freeway.log.max-size`; the latter fails
  when the document promises a knob nobody reads. It runs inside the repo and
  skips automatically when a module builds alone.

- **`auto` derivation of registered identity: scheme / host / drain
  (freeway-cloud + freeway-http)** — three keys' defaults become `auto`, because
  their correct values only hold on this machine and cannot be written statically;
  all three say which choice they made in the startup log:
  - **`registry.service-scheme`**: the new `WebServer.secure()` (`HttpModule`
    passes the resolved SSL verdict; `WebServerBuilder` passes whether an
    `SSLContext` was provided), and `auto` (the default) follows whether the HTTP
    server has TLS. The registration's `http/https` and the event mesh's dial
    `ws/wss` now come from **one derivation** (the mesh reads the instance
    endpoint resolved by `HttpServiceDeclaration`) — the default used to be
    hard-coded `http`, so enabling TLS and forgetting this key registered
    `http://` and dialed the mesh in plaintext, with only the "token configured
    but using ws" combination warning. Explicit values accept only
    `http`/`https`; anything else fails startup naming the key.
  - **`registry.service-host`**: `auto` (the default) uses the bound address when
    the server binds a specific one (it listens only there); for `0.0.0.0`/`::`
    bindings it prefers `POD_IP`, then the first routable local address (IPv4,
    excluding loopback/link-local/any), falling back to the bound address with the
    original not-routable warning only when neither exists. Multi-NIC hosts name
    it explicitly; the derivation does not guess.
  - **`registry.shutdown-drain`**: `auto` (the default) is answered by the
    registry backend — the new `ServiceRegistry.drainWindow()` default method
    (default `0s`): the built-in in-process registry answers 0, and adapters
    answer their own propagation window. The docs previously could only demand
    "hand-configure 5s when using a registry"; now the backend that actually knows
    the number answers. An explicit duration wins, and a negative fails startup.
  - Breaking: none (all three are default changes + new default methods and
    accessors; explicit configuration behaves as before).

- **The log-specific config file is renamed: `freeway-log.properties` →
  `freeway-logging.properties` (freeway-commons)** — the new name is the only
  official one, at the classpath root; the old name is **no longer read** — while
  the file remains at the classpath root, startup prints one stderr warning naming
  the rename (the config is not adopted, but it does not fail silently either).
  The reference template is renamed to
  `docs/reference/logging.properties.reference` in step. The "every log key can live
  in a file" claim is corrected too: `freeway.log.color` / `.mdc` /
  `.mdc.priority` / `.caller-info` work only via `-D`/environment variables (they
  are read at class-load time, before any file parsing), and both the template and
  the docs say so.

- **Docs: freeway-cloud's four design documents merge into
  `docs/design/cloud.md`** — `freeway-cloud-unified-design.md` (the master
  design), `freeway-cloud-events-design.md`, `freeway-cloud-rpc-design.md` and
  `freeway-cloud-implementation-plan.md` overlapped heavily and had drifted into
  real contradictions (the master design declared "no method-level RPC" at the top
  while §10 excluded it from the exclusions; hook ordering disagreed with both
  other documents and the code; dedup was placed in the interceptor rather than
  `EventBus.publishInbound`; the error mapping table lacked
  `INTERRUPTED`/`DISPATCH`/`OTHER`). They now merge into one design baseline:
  positioning and principles, modules and assembly, core objects, nine capability
  designs (including the wire protocol table and the full `kind()` table), config
  key shape, explicit non-goals, roadmap, revision log; the four old documents are
  deleted and in-repo references (`DEVELOPER-GUIDE`, `freeway-config`,
  `RemoteCaller`/`RpcExport` javadoc) point at the new one. The per-key list is no
  longer duplicated and uniformly points at `docs/configuration.md` so the two
  cannot drift; the delivered Phase 0–8 task lists keep only their acceptance
  criteria and regression lists, the still-valid exclusions (MQ semantics, a
  global membership view, webhook outbound, the `@CloudEvent` annotation entity)
  and core follow-ups fold into §7/§8, and the previously unrecorded capability
  boundaries are added (no application-layer heartbeat, no mesh backpressure, no
  metric labels, readiness not published to the registry).

- **Docs: AGENTS.md becomes the single convention source, and architecture sinks
  into docs/architecture.md (repo root)** — `CLAUDE.md` and `AGENTS.md` were both
  auto-loaded guidance files with overlapping content. They now split into two
  layers, one home each: **`AGENTS.md`** = repo-level **conventions** (build,
  module map, naming, design rules, testing, regression watchlist, commit rules,
  further reading); **`docs/architecture.md`** = **architecture and internals**
  (dependency graph, module boundaries, injection annotations, the config cascade
  mechanism, lifecycle), pointed to as needed by README/AGENTS/guides and **not
  auto-loaded**, so conversations do not carry it every time. Accompanying
  cleanup: DEVELOPER-GUIDE's "Naming Rules"/"Code Style" sections (a third copy of
  the same rules) merge into a one-line pointer to AGENTS.md, fixing the
  nonexistent `RequestContext` type they referenced; the "follow CLAUDE.md"
  references in design docs and READMEs now point at
  ARCHITECTURE.md/AGENTS.md. `CLAUDE.md` is ultimately deleted — see Removed.

- **API audit landed: failure discrimination, the registry heartbeat contract, and
  mesh TLS (freeway-cloud)** — three API design issues and one written rule:
  - **`CloudException.Kind`**: `noInstance`/`circuitOpen`/`rateLimited` previously
    had identical fields and differed only in message text — a caller telling
    "the service is not deployed" from "the circuit is open" (different
    operational actions) had to string-match, and the design doc §6 error table
    was unreachable in code. Every failure now carries a `kind()`
    (`NO_INSTANCE`/`CIRCUIT_OPEN`/`RATE_LIMITED`/`CONNECT`/`TIMEOUT`/`TRANSPORT`/
    `INTERRUPTED`/`HTTP`/`BUSINESS`/`REPLY_UNREADABLE`/`REJECTED`/`DISPATCH`/
    `OTHER`, one value per caller action), and three named factories
    `business(...)`/`unreadableReply(...)`/`rejected(...)` replace the three
    generic `of(...)` calls. Breaking: none (new accessor and factories;
    `of(...)` stays for extension authors, with kind `OTHER`).
  - **`ServiceRegistry.renew` returns boolean** (an SPI break): it used to return
    void and **silently no-op** when the entry was already evicted (the contract
    did not say), forcing the heartbeat hook to spend an extra discovery query to
    self-check — one more network round trip every 10 s per adapter backend. Now
    `renew` answers "does the registry still hold this instance", the hook heals
    from it (re-registering when gone, counting it against the readiness strikes),
    and no second query is sent. Implementors must answer honestly: returning true
    while not holding the entry tells the caller it is reachable when it is not.
    ext has no implementation of this SPI, so nothing to change.
  - **Mesh outbound TLS**: `TransportSecurity` calls itself outbound transport
    security, but only the RPC client honoured it — the event mesh built its own
    `HttpClient` without TLS config, so wss dials had no client identity.
    `PeerConnector` now builds its client from the same `TransportSecurity`'s
    `SSLContext` (an optional dependency: without the RPC module it falls back to
    the JDK default), and the role doc states "both outbound legs share one
    identity" and "resolved once at startup; rotation needs a restart".
  - **Docs**: the API shape rule enters `AGENTS.md`'s design rules (then still in
    `CLAUDE.md`) — one or two optional parameters use a documented overload
    ladder, three or more use a parameter record (`defaults()` + withers). The
    same batch's "records used as adapter assembly points keep an old-arity
    delegating constructor" (which once broke ext's engine tests) **is later
    overturned**: keeping old shapes costs a path per new component — see the rule
    revision in Changed's first item.

- **API consistency: contribution id namespaces, the mesh's internal surface
  narrows, and `@Local` coverage completes (freeway-cloud)** — three places
  inconsistent with the framework's existing rules:
  - **Contribution ids**: controller/route ids mixed bare names (`metrics`,
    `health-live`, `health-ready`, `cloud-event`, `trace`, `baggage`, `http`) with
    dotted names (`freeway.cloud.rpc`). An id is **one global namespace per
    extension type** and a duplicate fails startup, so a bare name meant an
    application naming a **different path's** route `metrics` would fail startup
    inexplicably. The framework side is now uniformly `freeway.cloud.*`
    (`freeway.cloud.metrics`, `freeway.cloud.health.live`, `freeway.cloud.event`,
    `freeway.cloud.propagation.trace`/`.baggage`,
    `freeway.cloud.declaration.http`); the intent of "pre-empting a framework
    route" is still rejected loudly by a **duplicate path** and a **duplicate
    primary binding** (`Duplicate route detected: GET /metrics` /
    `AmbiguousBindingException`), clearer semantics than "duplicate id".
    Breaking: code anchoring `before`/`after` on bare ids needs renaming (no such
    anchors exist in the repo).
  - **The mesh's internal surface**: `PeerConnector` and `CloudEventSink` narrow to
    package-private; `PeerHub`/`PeerConnection` keep only the **inspection face**
    public — `connections()`/`origin()`/`serviceId()` and the connections' read-only
    accessors plus `close()` — while `wire`/`addInterceptor`/`register`/
    `unregister`/`token`/`subscriptions`/`receive`/`codec`/`send`/`matches` all
    return to the package (they had only been public incidentally). The class docs
    state that the public face is the inspection face.
  - **The `@Local` marker**: every role with "a built-in implementation that an
    adapter can `.primary()` away" should carry it (it is both an injection-point
    qualifier and the backend guard's criterion). `Metrics` and `TransportSecurity`
    had been missing it; now added.
- **Shutdown finishing: a drain window + graceful in-flight calls + the mesh's
  goodbye frame (freeway-cloud)** — there used to be no gap between deregistering
  and closing the socket, so a load balancer still holding the endpoint during a
  rolling update hit closed connections; outbound in-flight calls failed outright
  with an exception from `close()` (the peer may already have executed). Now:
  - `freeway.cloud.registry.shutdown-drain` (Duration, default `0s`): shutdown
    **signals first** — readiness flips to `draining` (probe-driven LBs pull the
    instance immediately) and the registration is removed (registry-driven LBs
    also stop routing at once) — then serves for this window before the HTTP
    server stops. The default `0s` is right for the built-in in-process registry
    (no propagation delay within one JVM); adapter deployments set it to the
    backend's propagation window.
  - `freeway.cloud.rpc.shutdown-grace` (Duration, default `5s`):
    `CloudHttpClient.close()` first stops accepting new calls (sync and async
    alike), then waits for in-flight calls to finish; anything still unfinished
    past the window fails with `CloudHttpClient is closed` and a WARN logs the
    remaining count. An idle process closes immediately — no in-flight calls, no
    wait.
  - The mesh client sends WebSocket `1001 going away` at shutdown (waiting up to
    200 ms, with abort as the fallback when the peer is already gone), so the peer
    can tell "this node is leaving" from "the connection broke" (the server's
    session already sends a close frame).
  - Breaking: none (only two advanced-tier config keys are added).

- **`@Local` drops the class-level placement (freeway-cloud, a compile-time
  tightening)** — the same criterion as `@Marker`/`@Primary`/`@Builtin`: nothing
  reads `@Local`'s `TYPE` (framework markers on classes are read by ioc's
  `MarkerIndex`, which cannot know cloud's annotations), so on an implementation
  class it **compiled and silently did nothing**. Only the injection-point
  placements remain (`PARAMETER`/`FIELD`, read through the marker index); to mark
  an implementation use the binding-side `.marker(Local.class)` or the module-level
  `@Marker(Local.class)`. A test pins the placement set.
- **Docs: distinguishing "declared keys" from "selection keys" (freeway-cloud)** —
  the four `freeway.cloud.*.type` keys stay, but the docs never made clear that
  they **do not select implementations**: the framework does no reflective
  class-name loading and no classpath scanning; what actually decides the
  implementation is a `.primary()` binding / the `@Local` marker / the adapter
  module; `*.type` is only a declaration of "which backend I expect", consumed by
  the adapter, which warns once at startup when "an external backend is declared
  while the local implementation is still active" (`BackendTypeGuard`). Three
  places sync: `configuration.md` (the two-key-kind comparison table), the
  DEVELOPER-GUIDE and the cloud design doc.
- **Docs: the static file validator contract (freeway-http)** — the cache headers
  come from the probe taken at the start of the request and the body opens
  afterwards, so in the concurrent-replacement window "the body and the ETag can
  be from different generations". This is the inherent TOCTOU of
  stat-then-sendfile, which the framework **deliberately keeps** (the path is
  re-resolved at use time to hold the mount root, defending symlink swaps); it
  converges on its own: the next `If-None-Match`/`If-Range` compares against the
  file as it is then. The contract is now in `serve(...)`'s javadoc rather than a
  comment that could not be honored.

- **Remote invocation converges onto one explicit channel (freeway-cloud,
  breaking)** — the provider declares exports and the consumer binds a typed
  client at the composition root; the location (local implementation or remote)
  is a fact of **composition**, not a runtime guess:
  - An export is a data contribution:
    `binder.contribute(RpcExport.class).add(RpcExport.of("user",
    UserHandlers.class))` (optionally `.propagateMessages()`); the framework
    contributes **one** `/rpc/{mapping}/{method}` wildcard route and one
    `.before(HTTP_SERVER)` assembly hook, and the hook resolves the handler from
    the container (injected, singleton, lifecycle owned by the container) and
    builds the method dispatch table that requests call directly — no registry in
    between, hence no possibility of "the endpoint looking up one thing, the
    client another";
  - Everything checkable at startup is checked and **fails naming the problem**:
    duplicate mappings, an exported type with no binding (the message gives the
    `binder.bind(...)` to add), overloaded handler methods (positional arguments
    cannot distinguish overloads);
  - The consumer side: `RemoteProxyFactory.of(caller).serviceId(..).mapping(..).build(Api.class)`
    binds to the interface, and business code keeps `@Inject Api`; `RemoteCaller`
    is still bound by the framework, and direct calls and per-call budgets use its
    `invoke` overloads;
  - The wire protocol is unchanged (path, the positional argument array, the
    version header and the exception header untouched), as are the idempotency
    gate, error mapping, resilience and propagation.
  - Breaking: `RpcEndpoint.of(mapping, bus, codec[, propagateMessage])` is deleted
    (since v2); `RemoteProxyFactory.of(callBus, caller)` → `of(caller)`, and
    `Mode`/`localFirst()`/`remoteOnly()` are deleted (remote-only remains);
    standalone assembly (ext engines' `RouteIndex`, custom mounts) uses the
    container-free `RpcEndpoint.route(RpcExport, handler, JsonCodec)` — it takes
    an already-obtained handler instance and no longer needs a fake container or a
    bus. freeway-ext's Undertow/Jetty RPC integration tests migrate in step;
    application migration is in the DEVELOPER-GUIDE.
- **The duplicate-route rule unifies (freeway-http, behaviour change)** —
  WebSocket routes were deduplicated by path inside `WebSocketIndex` with the rule
  "explicit routes override same-named routes from group expansion", while HTTP
  routes in `RouteIndex` failed **at startup** on the same method+path — one
  framework, one trie, two opposite rules. It unifies as **duplicate = failure**:
  when a group-expanded route and an explicit one collide on method+path, an
  `IllegalStateException` is thrown and neither side silently wins (consistent
  with the existing "fail startup rather than silently skip" principle and with
  the handling of duplicate RPC export mappings). `WebSocketIndex` consequently
  becomes a thin adapter — groups expand first, individuals follow, and all enter
  the same trie — one concept and one special case fewer; each side gains a test
  pinning the collision behaviour.
- **Annotations' `@Target` declares only placements with a reader (freeway-ioc, a
  compile-time tightening)** — `@Marker`/`@Primary`'s METHOD and `@Builtin`'s TYPE
  had no reader (the container has no producer-method bindings; `@Builtin` lands
  as the module-level `@Marker(Builtin.class)` or `.marker(...)`): on a method or
  a class they **compiled and silently did nothing**. They are removed from
  `@Target`, making misuse a compile error; new tests pin all three annotations'
  placement sets so readerless placements do not grow back.
- **`StaticAsset` drops its write-only meta component (freeway-http)** — cache
  headers come from the `ResourceSource.meta()` probe before serving, and the meta
  returned by `load()` was never read; the comment in
  `DirectoryResourceSource.load` about "refreshing the metadata when the file
  changed between probe and read, so ETag/Last-Modified match the served bytes"
  was therefore false (the refreshed meta was discarded immediately). The
  component and the refresh logic are deleted; the code no longer promises what it
  cannot do.
- **`CloudEventEnvelope.translate` gains full parameter docs (freeway-cloud)** —
  `topic` takes no part in framing on the CLASS channel (the CloudEvents `type` is
  the event class name) and was previously only `requireNonNull`-checked with no
  explanation; its role is now documented per channel, with `@param` covering all
  parameters.
- **`CorsFilter` converges to one constructor (freeway-http, breaking)** — the two
  7-arg constructors differed only in element type (`List<String>` vs a
  comma-separated string), both "two spellings of one rule" and both making
  call sites passing `null` fail to compile outright
  (`new CorsFilter(false, null, null, null, null, null, false)` matched both
  overloads — freeway-ext's engine tests were stuck on exactly this). The list
  form is the one kept: it is the precise form, module assembly passes the
  configured list through unchanged, and the comma spelling decodes only at
  boundaries that genuinely hold strings (`CorsFilter.DEFAULT` and
  `Builder.build()` call `SymbolSpec.splitList` explicitly). Migration: the string
  form switches to
  `CorsFilter.builder().allowAllOrigins()/.allowedOrigins(s).allowCredentials(b).build()`,
  or replaces the comma string with `SymbolSpec.splitList(s)` / `List.of(...)`.
- **Application-facing API gains pinning tests + a unified status-code vocabulary
  (freeway-http)** — two new end-to-end test groups pin public surface that had
  zero coverage but that **application developers use** (no callers in the repo
  only means the applications using it live outside the repo):
  `TypedRequestApiTest` covers `HttpRequest`'s typed read family
  (`queryParam/header/pathVar` with a `Class`: parsing normally, empty on
  absence, erroring on illegal values instead of letting garbage through), the
  typed body factories (`post/put/patch(path, type, handler)`'s deserialization
  and route ordering), **bean validation failure → 400** (`Route.wrapBody`'s
  validation branch had been judged "unreachable in the repo"), and
  `Route.head/options`' verb semantics; `WebSocketReadLoopTest` gains cases
  pinning `WebSocketSession.ping` and `sendTextBatch`'s frame sequences (the PING
  payload verbatim, batches keeping order, exactly the given count sent).
  Four hard-coded status codes also switch to `HttpStatus` constants
  (`StaticResourceMount`'s 416/206, `CorsFilter`'s 204, `HealthFilter`'s 200) —
  the constants are the user-facing status-code vocabulary and **stay**; what was
  missing was the modules using them.
- **cloud-side same-kind members deleted (freeway-cloud)** — the criterion is the
  previous batch's ("definition site + call sites", checked one by one, confirmed
  in both repos): `PeerConnector`'s two delegating constructors and the no-arg
  `start()`, plus the **always-empty `staticPeers`** (the only constructor passed
  `List.of()`, and config peers go through `start(peers)`/`setPeers`) — peers
  thus have one entry point; `PeerConnection`'s 3-arg constructor and
  `remotePrefixes()`; `CloudEventEnvelope`'s 6-arg `translate` (a second
  translation path minting its own UUID); `RemoteProxyFactory`'s
  catch-and-rethrow no-op; `ActiveBindingProbe.hasMarker` (its only caller just
  forwarded, inlined into `isLocal`); `SecretStoreDefault.reload()` narrows to
  private (called only from the constructor); and `MetricsDefault.TimerData`
  narrows to private (an internal structure of the implementation).
  One **doc over-promise** is also fixed: `CloudEventEnvelope`'s javadoc wrote
  `subject` = `Keyed#key()` without limiting the channel, but it is only written
  on the CLASS channel (a topic payload is opaque to the bus and has no ordering
  key) — the new test `keyedEventCarriesItsKeyAsTheWireSubject` pins both
  channels' behaviour (CLASS carries a subject and the class-name type; TOPIC has
  no subject and its type is the topic), and the javadoc narrows in step. This
  test failed once as it was written — the living proof of "an unpinned wire
  contract".
- **Engine members unreachable from the engine deleted (freeway-http)** — each
  item judged by its "definition site + call sites" distribution before deletion,
  with zero references confirmed in both the workspace and `freeway-ext`:
  - Write-only state: `HttpContextImpl`'s `http10` field and `isHttp10()`
    (HTTP/1.0 keep-alive is decided by `Http1xParser`'s
    `keepAlive = !isHttp10`; the context's copy was never read) — `reset(...)`
    loses one parameter accordingly; `ParsedRequest.httpVersion` (the parsing
    local remains; the component had no reader); `WebSocketFrame.closeCode`'s
    field and accessor (the `CloseCode` enum **stays**: it is the protocol
    vocabulary users pass to `session.close(int, String)`, same reasoning as
    `HttpStatus`).
  - Uncalled members: `Headers.size()`, `Http1xParser`'s `CR`/`LF` constants (the
    code uses `'\r'`/`'\n'` literals), `FrameHeader`'s instance `encode()` (the
    static overload is the source of truth), `WebServer.notFound` (folded into an
    `ErrorResponses` call), `DataFrame.padLength()`, `MultipartForm.part(String)`,
    `PathPattern.template()`, and `StaticResourceMount`'s three uncalled getters
    (the setters are documented API and stay).
  - Uncalled overload constructors: `HttpSession`'s 7-arg, `HttpConnection`'s 1/2-arg,
    `SessionBufferedOutputStream`'s 1-arg, `SettingsFrame()`/`HeadersFrame()`'s
    no-arg, `WebSocketException`'s 3-arg; plus one pair of address accessors each
    on `HttpConnection`/`Http2Connection` (addresses always come from
    `HttpSession.remoteAddress(connection.socket())`).
- **Module composition becomes data (freeway-ioc)** — the container resolves the
  whole module tree in one pass before binding any module: parents before their
  children, siblings in declaration order; an instance reached twice (shared
  submodules or mutual references) binds once, and two instances of one class
  fail at startup. `subModules()` must be a stable view, read multiple times.
- **SPI discovery fills only gaps (freeway-boot)** — a class already declared in
  the module tree (including as a submodule) is no longer added a second time by
  auto-discovery, so "declaring `new HttpModule()` inside `subModules()`" and
  "enabling autoDiscovery" no longer conflict.
- **bootstrap key channels unify (freeway-boot, behaviour change)** —
  `freeway.env.prefix` / `freeway.config.file` are set via `-D<key>` or
  `FREEWAY_<key>` only; putting them in config files is no longer silently
  ignored — startup WARNs naming the key.
- **Config file read limits unify (freeway-boot, behaviour change)** — the 16 MiB
  cap extends from "classpath resources only" to every source: working-directory
  override files, `freeway.config.file` additional files and hot-reload re-reads
  alike.
- **`AppConfigDefault`'s construction changes (freeway-boot, breaking)** — the
  static form becomes `AppConfigDefault.of(Map<String,String>, List<String>)`;
  the cascade form becomes `AppConfigDefault(ConfigSources, List<Path>)`.
- **`AppConfigModule` → `BootModule` (freeway-boot)** — renamed with narrowed
  duties: the module does one thing, wiring the loaded `AppConfig` (and its
  declared symbol source) into the container. The runtime hooks' lifecycle and
  config release belong to `AppRuntimeDefault`'s own collaborators,
  `HookLifecycle` is no longer registered as a container service, and the
  `"freeway.config"` hook id disappears with it (code anchoring `before`/`after`
  on it will report an unknown hook id at startup). (Lives in `boot.internal`,
  no stability promise.)
- **CloudEventBus's naming unifies on the singular (freeway-cloud, breaking)** —
  the package `cloud.events` → `cloud.event`, the WS endpoint `/cloud/events` →
  `/cloud/event`, the config keys `freeway.cloud.events.*` →
  `freeway.cloud.event.*`, the constants `CloudConfigKeys.EVENTS_*` → `EVENT_*`
  and `CloudHooks.EVENTS` → `EVENT`, and boot's event classes move into
  `boot.event`. Config semantics are unchanged, naming only; code referencing the
  constants and key names in config files/env rename in step.

### Removed

- **`CLAUDE.md` deleted (repo root)** — it had shrunk to a single `@AGENTS.md`
  line (Claude Code's native import, because Claude Code does not read
  `AGENTS.md` natively). The reason for deletion: the repo already holds "one
  home per convention", and keeping a forwarding file that exists only for one
  tool is a second entrance to the same rules. With Claude Code, pick one — a
  one-line `@AGENTS.md` file, or a plugin that reads `AGENTS.md`; other agents
  (Codex, DSH and friends) already read `AGENTS.md`, so their behaviour is
  unchanged.

- **`CallBus` and its satellite types deleted (freeway-ioc, breaking)** — the six
  public types `CallBus`, `CallTargetRegistry`, `CallAdviceChain`,
  `CallProxyFactory`, `CallStats` and `DeadCallException` are deleted (718 lines
  of core main, 1076 of tests), and the container no longer registers a `CallBus`
  builtin. The reasoning: same-process invocation overlaps IoC injection (the
  container resolves mutually dependent services directly), "optional capability
  absent degrades gracefully" in IoC is just binding a default implementation,
  runtime hot-swapping conflicts with the framework stance of "composition is
  data, startup is static", and its one distinctive feature — the "structured
  interface" (zero compile-time edges) — is achievable with one adapter at the
  composition root while buying back compile-time checking. Cross-process
  invocation becomes explicit: the location is decided by composition, exports
  are declared by `RpcExport`, and the address is given by `serviceId`.
  Same-process invocation switches to `@Inject` (mutual dependencies included),
  and cross-process invocation uses `RemoteProxyFactory` (see the previous
  entry). `EventBus`'s messaging-domain description accordingly goes from three
  channels to two. The design document `freeway-remote-callbus-design.md` is
  renamed `freeway-cloud-rpc-design.md` with its stance section rewritten.
- **`Binder.install(ModuleEx)` removed (freeway-ioc, breaking)** — module
  composition is no longer expressed as imperative installation inside `bind()`.
  Motivation: install-style composition made the module graph a side effect of
  `bind()`, invisible to the rest of the framework (assembly validation, SPI
  deduplication, the startup log, tests). It was briefly `ModuleEx.subModules()`
  and finally became the **entry-point-built `ModuleNode` tree** (next entry).
- **`ModuleEx.subModules()` and `ModuleTree` removed: module composition becomes
  an entry-point-built tree (freeway-ioc, breaking)** — `ModuleEx` keeps only
  `bind(Binder)` (plus the display-only `name()` default method), and modules
  declare only bindings; composition is built by entry-point code:
  `ModuleNode.app("order-service", ModuleNode.of(new
  OrderModule()), CloudModule.class)`, which the container binds in pre-order and
  holds. Migration: `b.install(new HttpModule())` / `subModules()` →
  `ModuleNode.app("app", ModuleNode.of(new HttpModule()))`
  (`FreewayApp.run(new HttpModule())` is the flat spelling of the same thing: a
  direct child of the application root). Motivation: `subModules()` made
  composition a method the framework **calls back into user code** (read several
  times per startup, hence the "must be a stable view, use a field + getter"
  contract to constrain it), and the umbrella module consequently owned its
  submodules, so the application could not replace one of them; the tree is a
  value — built once, visible at the assembly point, fragments reusable, and
  validation lands on the line of code that built it. Repeated arrivals of the
  same instance still fold (sharing fragments is normal usage), two instances of
  one class still fail startup, but the error now names both paths
  (`app → web → HttpModule`). Binding order = pre-order: deterministic but **no
  longer a contract**; for sequencing use `RuntimeHook` anchors or contribute
  your own `order()`. The startup log and `Container.moduleTree()` read the same
  value, and `Container.modules()` (introduced in the same round, never released)
  is deleted with it.
- **`CloudModule` becomes a bundle module (freeway-cloud, breaking)** — umbrella
  module → fragment factory: `CloudModules.standard()` → `CloudModule` +
  `@SubModule`; the whole bundle is `ModuleNode.of(CloudModule.class)`, and taking
  one module alone is `ModuleNode.of(CloudRpcModule.class)` (which an umbrella
  module could not do). Migration: `new CloudModule()` /
  `CloudModules.standard()` → `ModuleNode.of(CloudModule.class)`
  (or `FreewayApp.of(...).add(CloudModule.class)`).
- **`AppConfig.snapshot()` removed (freeway-boot, breaking)** — the cascade no
  longer exposes a map shape: `SymbolSource` is the only reading entry, and
  `AppConfig` narrows to `profiles()` / `providers()` / `close()`. Migration:
  `config.snapshot().get(k)` →
  `container.get(SymbolSource.class).resolve(k, null)` (or `resolve(spec)` per
  declaration).
- **`PutResult.versionId` (freeway-cloud, breaking)** — the interface has no
  version-addressed read/delete (`get`/`delete` take only a key, and
  `ObjectEntry` has no version field), so this freshly-minted-per-write UUID let
  a caller do nothing but print it: the contract did not hold. Versioning is
  scheduled together with the operations that could use it, rather than shipping
  a return value nothing can reach. The design doc updates in step;
  `ObjectStorageDefault.put`'s javadoc states that the local backend
  **deliberately ignores** `ObjectMetadata` (content-type/user metadata is the
  contract for ext backends) instead of half-honouring it.
- **`EventSubscriber`'s ordering id (freeway-ioc, breaking)** — the `id` field,
  `of(String id, Class, Consumer)`, `of(String id, String topic, Consumer)` and
  the in-package `id()` are all deleted: the id was never read by anything (the
  subscription index reads only topic/eventType/handler), and the ordering id
  belongs to the contribution — `Contributions.add(id, value)` is the only
  definition site. The old doc example read
  `.add(EventSubscriber.of("notify", …)).after("index")`, which neither compiled
  (`add(T)`'s `Contributions` has no `after`) nor ordered correctly, silently;
  both examples become
  `.add("notify", EventSubscriber.of(…)).after("index")`.
- **`SymbolSpec.description` and two zero-caller factories (freeway-ioc,
  breaking)** — the `description` component was written and never read (the
  javadoc's promised "docs/registry use" does not exist), and with it go
  `of(key, type, default, description)` and `required(key, type, parser,
  description)`; the only call site that passed it (`CloudResilienceModule`)
  turns that sentence into a comment. The record now carries only the inputs the
  mechanism actually uses: key, type, default, parser, required.

### Fixed

- **The "claims decoupled from implementation" sweep (whole repo)** — this
  round's audit made every "comment/error/doc promising something the
  implementation does not have" true, or corrected the wording: flow's driver
  error and javadoc pointed at the long-deleted `newInstance` (pinned by a test
  assertion) → they point at `FlowEngine.create` now, with the assertion flipped
  to pin "must not point at a deleted API"; `GraphSpec`'s `System.Logger` →
  SLF4J, and the `@author noear`/upstream `@since` porting residue is cleared;
  `PlantUmlOptions.DEFAULT`, a mutable static global, becomes a record value +
  withers per FlowOptions' own principle; the `JsonException` class promised by
  `JsonUtils`' javadoc does not exist → the doc states the real contract
  (`IllegalArgumentException` carrying the position); `HPackContext`'s encoder
  javadoc claimed "the encoder's dynamic table advances per block" while no such
  table exists → the lie goes, and the lock's real reason (contiguous frame
  order) enters the comment; and `ServiceRuntime`'s "cached lookups are
  lock-free" comment is made true by the implementation below it.
  `@NotThreadSafe`'s wrong advice — "switch to an interface (proxy)" — cannot
  cure the disease it demonstrates (the proxy shares one target) → it now gives
  the three fixes that actually work.
- **The JVM-level lock on the interface-singleton hot path (freeway-ioc)** — the
  interface singleton proxy re-entered `realize()` for the static `REALIZE_LOCK`
  on every method call, even on a cache hit — one container's slow construction
  could block calls on another container's built singletons (measured at 1.2 s),
  contradicting "the lock only guards first construction". The target is now
  published into the CHM only once fully constructed, and cache reads take the
  lock-free fast path; the shutdown seal protocol (sealed-then-clear must leave
  no orphan singletons) is unchanged. Same round: `@NotThreadSafe` was not
  enforced across an interface binding (validation early-returned for interface
  targets) → it checks the chosen binding's impl markers, with THREAD-scoped
  interface proxies exempt (each thread gets its own instance); and a provider
  returning null produces a clear error naming the binding instead of an
  anonymous NPE inside the cache put.
- **HEADERS + immediate RST froze the connection's read loop (freeway-http)** —
  the active RST_STREAM path only `close()`d the stream without marking input
  ended: when the reader thread itself fell into `DataIn.close()`'s drain branch
  it parked forever on an empty queue (nothing would wake it), so two frames on
  one connection could stall it; the RST branch in dispatch that actually set
  `peerReset/halfClosed` was dead code. Reset now goes only through
  `resetByPeer()` (mark → end → teardown, one path), and `wakeupReader` gains a
  null guard (`unpark(null)` throws an NPE on the reader thread). A regression
  test pins "the connection accepts the next request after a reset".
- **The enum write path and the pool's late release (freeway-db)** — the DDL side
  mapped enums to VARCHAR and the read side used `valueOf`, but the write side
  handed the raw enum to `setObject`: H2/PostgreSQL threw conversion errors,
  making enums "readable, not writable" with zero round-trip tests. All binding
  points (positional/named/expanded collections/batch) now go through
  `StatementValues.bindValue`. The pool reused one wrapper object, so a
  cross-thread late `release()`/`invalidate()` could take or destroy a connection
  someone else was using (reproduced in testing): each borrow now gets a fresh
  handle, and late calls on a consumed handle are harmless no-ops.
- **RPC argument binding, the reconnect storm, and weight (freeway-cloud)** —
  `decodeArgs`'s comment claimed container parameters "are rebound by the
  handler's own casts", but dispatch did no such thing: DTO parameters always
  surfaced as `ClassCastException` disguised as a business failure. Arguments now
  rebind through the codec by declared parameter type (`JsonCodec` gains a
  `convert` seam: nodes convert directly, the default implementation round-trips
  text, third-party implementations are unaffected), varargs tails assemble into
  real arrays, and the argument count is judged before dispatch (a caller error =
  `Kind.REJECTED`, no longer a handler exception). The mesh re-dial's backoff
  counter moves from "socket opened" to "hello ack": two nodes with mismatched
  tokens no longer dial each other at full speed; and `LoadBalancerDefault` reads
  its own `ServiceInstance`'s exposed `weight()` (weight = share of the cycle,
  0/negative as 1) — previously "configured but unread".
- **The number guard and cause cycles (freeway-commons)** — `MAX_NUMBER_LENGTH`
  was copied from the string limit (10 MiB) rather than a cost bound: a 10M-digit
  token passed legally while BigDecimal parsing is super-linear (1M digits ≈ 11 s
  measured; 10M digits still running past 60 s). The cap becomes 1000 characters
  with the reason stated, and the tests pin both "rejects fast" and "parses
  within the bound". `JULFileHandler.estimateThrowableSize` had no visited set
  for cause/suppressed cycles (the library's other three sites do) → added: it
  runs inside the handler monitor, so one poisoned record could lock up the whole
  logger.
- **Re-entrant close during hook startup (freeway-boot)** — when the first hook
  called `close()`, the nested stop saw only the hooks "already registered": the
  closer itself (registered after start returns) was never stopped, and later
  hooks kept starting on an already-closed container with nobody to stop them.
  The start loop now checks the seal flag per hook, the closer issues its own
  stop after its `start` returns, and the remaining hooks do not start.
- **Inbound requests get a span, and traceId reaches the logs (freeway-cloud)** —
  the only span in the module used to be outbound calls: inbound requests did
  context extraction only, so the callee was a flat line in the trace and
  **inbound logs carried no traceId** to correlate. `TracingFilter` (contributed
  by `CloudObserveModule`, which owns the `Tracer`) now opens a server span per
  application request and **binds it as the request scope** — that part is the
  key: `InvocationContext.current()` prefers the scoped layer, which a handler
  that only writes the ambient context cannot see, so outbound propagation would
  skip this hop. For this `Tracer.Span` gains `context()` (the call context the
  span established, default null = this tracer owns no context), and
  `TracerDefault` returns its already-computed child context (inherited
  principal/baggage included). The framework's own probes and `/metrics` are not
  traced. W3C `tracestate` passthrough is added too (`TraceContext` gains a
  `traceState` component, strictly validated at construction — CR/LF injection
  and overlong values are rejected; the inbound side is lenient: invalid values
  are dropped with a debug log).
- **Readiness is no longer always-true, and a lost registration heals
  (freeway-cloud)** — the local registry's readiness contributor returned healthy
  unconditionally, so `/health/ready` was a permanent 200 on the default install;
  and `RegistryStore.renew` silently no-oped when the entry no longer existed, so
  an evicted instance stayed "running but invisible" until restart. The heartbeat
  now, besides renewing, verifies its own entry is still registered using **the
  same discovery query a consumer would make**, re-registers (with a WARN) when
  gone, and publishes the result to `RegistryRenewal`: after {@code 3}
  consecutive failures (about 30 s by default) `/health/ready` returns 503 with
  "heartbeat failed N times in a row". A process with no registered instances
  reports `not registered` instead of an empty all-clear.
- **Secret file rotation without a restart (freeway-cloud)** —
  `SecretStoreDefault` read only at construction (`reload()` was private), so
  after a k8s volume swap or a rewritten properties file the process kept using
  the old secrets. It now re-reads on size + **full-precision mtime** changes,
  throttled to 1 s; on a read failure or the file momentarily disappearing (a
  projected volume's atomic swap) it **keeps the loaded values** and warns — it
  never blanks secrets in use. The design doc's claimed "explicit reload()" never
  existed and has been synced to the real semantics.
- **A startup warning when the mesh token crosses plaintext ws:// (freeway-cloud)**
  — `event.token` crosses the wire in the hello in plaintext, with its protection
  coming from the transport; when `registry.service-scheme` is not https, dials
  are `ws://`. In sidecar/mesh mTLS-terminating deployments this is normal
  topology, so it is **not refused** — instead startup warns loudly once: either
  switch to https (wss://) or confirm it stays inside the mesh/trusted network.

- **The topic channel's DeadEvent no longer triggers itself (freeway-ioc)** —
  `dispatchEvent` (the CLASS channel) had the guard "`DeadEvent` itself produces
  no DeadEvent", but `dispatchTopic` (the TOPIC channel) did not: publishing a
  `DeadEvent` as a topic payload produced one "zero subscribers" diagnostic for
  it, then a second one for that diagnostic. Both channels now state the same
  rule; new tests pin both "a zero-subscriber topic publish reports one
  diagnostic" and "publishing the diagnostic produces no second one".
- **Two contract statements on the framework's extension face (freeway-cloud)** —
  `LoadBalancer`'s interface javadoc read "Reads routing inputs
  (zone/weight/canary) from `ServiceInstance#metadata()`", which is false as a
  description of the **default implementation** (`LoadBalancerDefault` is pure
  round-robin, reading no instance attributes) and true only of the **role**. It
  now says: the default round-robin reads no attributes, and
  weighted/zone-aware/canary are the job of an application's or adapter's
  `.primary()` (the division of labour fixed in design doc §263-266), while
  `ServiceInstance.weight()/zone()/version()/isCanary()` are the **input
  vocabulary** of those strategies (the definition site of the key names and
  parsing; applications should not read metadata keys themselves). The accessors
  **stay** — no callers in the repo only means "that CanaryLb lives outside the
  repo". `RegistryStore.liveReady` used to inline-rewrite `Health.isStale`'s
  threshold logic and now calls `Health.isStale(maxAge)`, returning the rule to
  its owner (`Health`'s javadoc already declared this eviction semantics), so the
  accessor is no longer "a member nobody reads".
- **No more oversized HEADERS frames when the response head exceeds the peer's
  frame limit (freeway-http, a protocol fix)** —
  `HPackContext.encodeResponseHeaders` encoded the whole head block into **one**
  HEADERS frame and never compared the peer's `SETTINGS_MAX_FRAME_SIZE`
  (initially 16384, `Http2Connection.peerMaxFrameSize`) while the local budget
  was 64 KiB; and the limit previously applied only to DATA fragmentation. So a
  16 KiB–64 KiB response head block (many `Set-Cookie`s, long CSP/Link headers,
  or simply many headers) went out as an oversized frame, and a conforming peer
  must treat it as a **connection error** per FRAME_SIZE_ERROR. It now splits
  into HEADERS + CONTINUATION per the peer's limit (RFC 9113 §6.10), with
  `END_STREAM` on the HEADERS and `END_HEADERS` only on the last frame, the whole
  sequence written by `Http2FrameWriter` under one lock with no other frames
  interleaved; `ContinuationFrame` goes from "parse-only" to a real write path,
  and `FrameHeader.DEFAULT_MAX_FRAME_SIZE` becomes the sole definition of that
  protocol constant (`Http2Connection` previously carried a second literal of the
  same value). `HeadersFrame.writeTo` serializes by its own parsed flags —
  `BaseFrame.writeTo` is abstract and the class must implement it, but it used to
  hard-code `END_HEADERS` and drop `END_STREAM`, a trap that produced only wrong
  frames. The new
  `H2WireFormatTest.oversizedHeaderBlockIsSplitIntoContinuationFrames` splits a
  2 KiB head block at 512 bytes, validates type/flags/limit per frame, and
  reassembles the same block with the engine's own decoder.
- **Node identity unifies into one derivation (freeway-cloud)** — the registry's
  instance id used to be `service-instance-id` or `service-id@host:port`
  (derived once the HTTP server was up), while the event mesh's origin was
  `service-id@<random UUID>` (the mesh wires up before the HTTP server and does
  not know the port) — yet the comments and the design doc claimed the two were
  the same. `HttpServiceDeclaration.of(container)` is now the only definition
  site: registration and the mesh both consume the same `ServiceInstance` it
  returns, and the mesh wires with `.after(HTTP_SERVER)` to get the real port;
  the cost is that a hello arriving before wiring receives 1013 and reconnects
  with the existing backoff (which is already the mesh's contract). `PeerHub` no
  longer fabricates a UUID fallback.
- **Three behavioural defects in the HTTP engine (freeway-http)** — each
  reproduced by the audit with a concrete input:
  - **Response-frame leak after RST**: `markAborted()` set only
    `responseAborted` and not `streamOutputClosed`, and the pre-write check ran
    after `writeResponseHeaders` — on a DATA frame following
    `HEADERS[END_STREAM]`, the server kept sending HEADERS/DATA/END_STREAM after
    its own RST_STREAM (RFC 9113 §5.1). Both are fixed, and no frames go out for
    that input.
  - **A quoted charset produced mojibake**: for
    `Content-Type: text/plain; charset="ISO-8859-1"`, `bodyText()` silently fell
    back to UTF-8 because `Charset.forName("\"ISO-8859-1\"")` failed, while
    `Part.text()` on the same header decoded correctly. The quotes are now
    stripped before lookup, making both paths agree.
  - **`isMultipart()` was a substring check**: `text/plain;
    note=multipart/form-data` counted as an upload, so `multipart()` threw and
    the default handler returned 400. It now compares the media type exactly.
  - Also fixed: `CompressionConfig` silently turned negatives into 256 (every
    other component of the record throws) and now throws
    `IllegalArgumentException`; HTTP/1.1 gains the reason phrases for 415/416
    (the wire previously showed `HTTP/1.1 415 ` with an empty phrase);
    `WebServer`'s null sink goes through the noop sentinel; and `cors.max-age`
    changes from an unvalidated String to an Integer (`=abc` no longer lands in
    the response header verbatim — startup fails instead).
- **Config tiers and doc corrections (freeway-http / freeway-cloud / docs)** —
  `HttpConfigKeys` and `CloudConfigKeys` reorganize by "decision keys / best
  default / advanced (low probability)" with reasons stated, and each advanced
  cluster names the aggregate key governing it (`rpc.resilience=auto|off`,
  `event.enabled` presence, `event.dedup.enabled`); `docs/configuration.md`'s
  "required config quick reference" becomes the "decision key quick reference".
  Corrections: the docs used `freeway.http.port` as an example in four places,
  but that key **does not exist** (the real key is
  `freeway.http.server.port` ↔ `FREEWAY_HTTP_SERVER_PORT`); `secret.file`/
  `secret.keys` are annotated as `-D` only; `rpc.connect-timeout`/
  `request-timeout` are stated as **not** governed by `resilience=off`; the WS
  endpoint `/cloud/events` → `/cloud/event` (6 places); `CloudEventModule`'s
  "enabled default false" becomes the real presence-driven rule;
  `ExceptionMapper` → `ErrorHandler` (a residue of the 1.3.8 rename); and the
  DEVELOPER-GUIDE's RPC export example referenced `container` inside
  `bind(Binder)` (does not compile) and now has the module hold the `CallBus`.
- **`freeway-ioc` internal convergence (no API change)** — cleaned by "a concept
  must earn its own place":
  - The bus's collaborators share the host's package and visibility:
    `EventStats`/`EventSinkRegistry`/`EventExecutorSupport`/`CallStats`/
    `CallTargetRegistry`/`CallAdviceChain`/`CallProxyFactory` move from
    `ioc.internal` (public) into `ioc` (package-private), matching the existing
    `EventDispatcher`/`EventStreams`/`EventSubscriptionIndex`; `ioc.internal` now
    holds exactly one public type, `ContainerImpl`.
  - `ProxyFactory` (a package-private interface with one implementation and no
    external substitution point) folds into `ProxyFactoryImpl`.
  - `@Inject Logger` routes through `container.get(LoggerSource.class)` (the same
    principle as `SymbolSource`/`Coercer`): it previously went through a
    hard-wired field, so binding a primary `LoggerSource` alternative had no
    effect on it.
  - `ContainerImpl`: the zero-caller pure-forwarding `resolveArguments` is
    deleted; the module context's save/restore, which always restored null (the
    tree flattens first, no recursion), becomes set/clear; the single-use
    `Scoping` field is inlined; and `close()` gains `markerIndex.clear()` (the
    marker index previously held every binding for the container's lifetime).
  - `BindingIndex`: the "update the type index" no-op block in `updateId` that
    wrote the same reference back, and `scanBindings`' unused `type` parameter,
    are deleted. `MarkerIndex.register`/`sync` merge into one (marker validation
    already happens at the binding entry; no more three copies).
  - `Shutdown`'s two identical catch bodies merge; `EventExecutorSupport`'s
    always-true, return-discarded `BooleanSupplier` becomes a `Runnable`;
    `EventBus` deletes the unused `LOG` field left over from extracting
    `EventDispatcher` and fixes `publishOrdered`'s javadoc describing the deleted
    `key` parameter; and `ContainerImpl`'s thread-scope registry drops its
    never-read owner value (`Map<Object,ContainerImpl>` → an identity `Set`).
- **The profile segment order of filesystem override files (freeway-boot)** — the
  working-directory and `freeway.config.file` profile variants used to be
  ordered "per profile: properties+json", inconsistent with the classpath side's
  "all properties first, then all json": with multiple profiles the same file
  contents produced different winners on the two sides
  (`application-dev.json` beat `application-prod.properties` on the classpath
  but lost to it on the filesystem). Both sides now put format first.
- **bootstrap-only keys' WARN covers every config file channel (freeway-boot)** —
  only classpath files were checked before; `freeway.env.prefix`/
  `freeway.config.file` declared in working-directory profile variants or
  `freeway.config.file` additional files were still silently ignored. Now
  `AppConfigDefault` names them uniformly while reading each filesystem file
  (filename included), and CLI arguments (`--freeway.config.file=...`) are named
  too.

## [1.5.1] — 2026-09-06

### Added

- **HTTP/2 inbound RST burst circuit breaking (freeway-http)** — when
  "cancelled without a response" exceeds the threshold inside a sliding window,
  the connection gets `GOAWAY(ENHANCE_YOUR_CALM)` and is torn down (the
  CVE-2023-44487 family). Only live streams whose response is uncommitted count:
  cancels after the response (normal client behaviour) and RSTs on already-reaped
  dead streams never trip the breaker. Configurable via
  `freeway.http.h2.reset-burst-limit` (default 200, `0` disables) /
  `freeway.http.h2.reset-window` (default 10s), plumbed end to end
  `HttpConfigKeys` → `HttpServerConfig` (validation + Builder) → `Http2Session`,
  landing on the newly added overloaded constructor.
- **TLS certificate reload becomes event-driven (freeway-http)** — a
  `WatchService` triggers immediately with debounced rescheduling: the watcher
  thread only signals (zero I/O, zero sleeps), while snapshot/digest/reload all
  converge onto one scheduler thread, and `check()`'s public monitor becomes a
  private lock; polling stays as the fallback for unreliable filesystems such as
  NFS, and either layer alone can drive the same snapshot comparison.
- **Regression coverage** — RST breaking with custom limits, cancel-after-commit
  semantics, GOAWAY last-id, watcher reload within seconds under 60 s polling,
  migration owner-token takeover, pool foreign-connection rejection, `Defer`
  Error shape preservation, `CallBus` null-argument passthrough. 1879 cases
  green.

### Changed

- **GOAWAY's last-stream-id reports `lastHandledStreamId` (freeway-http)** — a
  rejected stream only advances `lastSeenStreamId` (replay validation
  unchanged), so the frame no longer lies that it "may have been processed" and
  misleads the peer into giving up retries (RFC 7540 §6.8).
- **Public monitors converge to private locks** — `Extension` (including inner
  `Entry.before/after` under the same lock), `LazyValue`, `ScopedCache.Session`,
  `Defer.DeferredSupplier`, `SslReloader`. Landed after confirming repo-wide that
  no `wait/notify` pairs on them; behaviour unchanged.
- **Modern idiom convergence (all modules, no behaviour change)** — pattern
  switches (`JsonLeaves`' 18 branches, shutdown's three-way dispatch, `PeerHub`'s
  prefixes; the compiler enforces arm order), `equals` pattern matching, immutable
  factories (`copyOf`/`of` — order-preserving Maps and live views excluded under
  three keep rules), `Thread.ofPlatform` builders, explicit charsets, raw-type
  cleanup, and FQ/import hygiene.
- **`Extension.asMap`'s docs state the snapshot semantics** — it returns an
  immutable point-in-time snapshot; later contributions are visible only to new
  calls. Not renamed (`asMap` describes the shape; the temporality belongs to the
  doc layer, per the assessment).

### Fixed

- **Two migration-lock races (freeway-db)** — stale takeover changes from "check
  then unconditionally delete" to a conditional delete on `executed_at` (0 rows
  means back off); `release` deletes conditionally on the owner token written
  into the `description` column, so after a timed-out migration is taken over,
  the slow owner's late release no longer deletes the new lock.
- **The pool releasing a foreign connection (freeway-db)** — the hard cast to
  `PooledConnectionImpl`'s `ClassCastException` becomes a guiding `SqlException`
  (the connection was released to the wrong pool).
- **`Defer.supply`'s Error wrapping (freeway-commons)** — an `Error` passes
  through with its original type and is cached; checked/exception keeps the
  existing wrapping shape (the first version broke existing tests and has been
  narrowed back to compatible).
- **`CallBus` proxy null arguments (freeway-ioc)** — `List.of(args)` becomes a
  null-tolerant view consistent with `RemoteProxyFactory`, so an interface
  method's null arguments dispatch normally.
- **`Node` construction no longer sorts the caller's list in place
  (freeway-flow)** — it copies first, then sorts.
- **`WebSocket` accept-key encoding (freeway-http)** — fixed to UTF-8: the old
  code used the platform encoding plus `length()` as the byte count, truncating
  the digest on non-ASCII.
- **Config hot reload's WatchKey re-registration (freeway-boot)** — `reset()`
  moves into a finally, so a failed iteration no longer silently unsubscribes
  the directory.
- **H2 preface decoding (freeway-http)** — decoded as US-ASCII, removing the
  platform default charset dependency.

## [1.5.0] — 2026-09-05

### Changed

- **The CLASS channel denies inbound by default (freeway-cloud, security,
  breaking)** — `PeerHub.receive()`'s CLASS channel deserialization becomes
  deny-by-default: with `allowed-types` unconfigured, CLASS events are always
  dropped instead of falling back to "accept any class". The empty-allowlist
  semantics used to be "admit any class", which together with the unauthenticated
  endpoint formed an arbitrary class-loading + reflective deserialization surface;
  the TOPIC channel (plain JSON, no class resolution) keeps its documented
  "empty = allow all" semantics unchanged. The startup warning for an empty
  `allowed-types` is reworded to "CLASS events will be dropped". The E2E test
  `keyedEventsPreserveTheOrderingSubject` accordingly whitelists its types
  explicitly.
- **freeway-cloud's internal structure converges (no behaviour change)** —
  landed per the structural audit as a set of mechanical refactors:
  `PeerConnector`'s address parsing (`toUri`/`parsePort`/`sameEndpoint`) is
  extracted into the `PeerAddress` record (parsing + rendering + endpoint
  equality, incidentally removing `URI.getHost()`'s IPv6 bracket ambiguity);
  `PeerHub.ServerSessionHandler`'s handshake admission (origin + token +
  subscribe parsing) is extracted into the pure method `validateHello` plus the
  `HelloAdmission` result type, symmetric with the already-separated `receive`
  inbound gate; `CloudHttpClientDefault`'s three telescoping constructors converge
  into the `Wiring` record + one constructor; comma-splitting list parsing is
  extracted into `internal/ConfigLists` (`splitAndTrim` + list `spec`);
  `CloudEventLifecycleHook`'s remaining bare `resolve`s go through `SymbolSpec`;
  and `freeway-boot`'s dependency declaration drops to test scope (zero main
  references).
- **`SymbolSource` gains the `resolve(SymbolSpec<T>)` default method (a public
  ioc interface, backward compatible)** — replacing the project's 32 occurrences
  of the two-step boilerplate `spec.parse(symbols.resolve(spec.key(), null))`
  with one step, `symbols.resolve(spec)`. The default method is backward
  compatible (existing `SymbolSource` implementations need no change), and the
  key name, default and parser are still declared once, on the `SymbolSpec`. All
  call sites and javadoc examples in `freeway-db` / `freeway-cloud` /
  `freeway-boot` / `freeway-commons` are updated. Pure mechanical convergence,
  zero behaviour change.
- **`XDefault`/`XImpl` naming finalized (freeway-commons/ioc/db/http/flow/cloud,
  breaking renames)** — the criterion settles as "external substitutability":
  when a role implementation can be replaced externally (a `.primary()` binding /
  construction choice / config activation), the framework's default implementation
  is named `XDefault`, regardless of which feature package it lives in — cloud's
  replaceable defaults all sit in their feature packages (discovery / resilience
  / observe / storage / secret / rpc, and `internal` no longer houses replaceable
  defaults), and staying in `internal` does not change the name (`db/internal`'s
  `PoolDefault` is still externally `.primary()`-able); implementations with no
  external substitutability are named `XImpl`. The net renames against 1.4.0:
  - Named `XImpl`: `HttpContextDefault`→`HttpContextImpl` (the built-in engine's
    context contract; ext engines bring their own), `LoggerSourceDefault`→
    `LoggerSourceImpl` (a hard-wired singleton on the injection path, never
    consulting bindings), `PooledConnectionDefault`→`PooledConnectionImpl` (a
    per-pool artefact type; the Hikari adapter brings its own connection type),
    `ProxyFactoryDefault`→`ProxyFactoryImpl` (a package-private interface with one
    implementation and no external substitution point);
  - Named `XDefault`: `FlowEngineImpl`→`FlowEngineDefault` (the default flow
    engine); `TransportSecurityDefault` keeps the substitutable-default name and
    moves from `cloud.internal` into `cloud.rpc` (assembled via `fromKeyStore`
    from the `freeway.cloud.rpc.tls.*` keys; ext can bind alternatives);
    `CoercerDefault` / `ExchangeMetaDefault` keep their names;
  - `MetricsDefault`'s private inner classes `DefaultCounter` / `DefaultTimer` →
    `CounterImpl` / `TimerImpl` (clearing the `DefaultX` prefix residue, no API
    impact).
  The criterion and the `internal` semantics (implementation details with no
  stability promise, like `RegistryStore` / `ConfigLists`) are written into
  `CLAUDE.md` / `AGENTS.md` / `DEVELOPER-GUIDE.md` (AGENTS.md also gains the
  "placement and suffix are orthogonal" stance), rename-class references across
  the docs unify on the current names, and freeway-ext's references rename in
  lockstep.
- **cloud config keys single-sourced, and `Wiring` withers (freeway-cloud, no
  behaviour change)** — seven config keys' defaults are declared once as
  `*_DEFAULT` constants in `CloudConfigKeys` (the RPC TLS keystore/truststore
  four keys' empty-string defaults, the storage base-path, the registry
  service-scheme), so the config layer and the in-library fallbacks stop
  double-writing; the RPC request/connect timeouts and the peer network timeout
  constants share one source between the config spec and
  `CloudHttpClientDefault.Wiring`'s library fallbacks (without the module, the
  constants are reused directly, so the two layers cannot drift). `Wiring` gains
  7 withers (`withPropagators` / `withRetryer` / `withBreaker` / `withRateLimiter`
  / `withTransport` / `withRequestTimeout` / `withConnectTimeout`) for stepwise
  customization.

- **Package structure tightens and doc stances sync
  (freeway-commons/ioc/boot/http/db/flow/cloud, no behaviour change)** — landed
  per the 2026-09-05 package structure audit's B/C lists:
  - The http engine family's visibility tightens: 21 public types with zero
    out-of-package references drop to package-private (engine root's
    `SessionBufferedInput/OutputStream`; engine/ws's `WebSocketFrame` / `OpCode` /
    `CloseCode` / `WebSocketException`; engine/http2's `BaseFrame` frame family /
    `FrameSerializer` / the `Settings` family). The remaining public types in
    engine/ are just contracts between the engine's subpackages (Java has no
    sub-package visibility), noted in CLAUDE.md; and engine/ws's static read-loop
    utility `WebSocket` is renamed `WebSocketReadLoop` (ending "naming a utility
    after the protocol").
  - commons logging: `JULLoggerFactory` / `JULLoggerAdapter` / `JULMDCAdapter`
    become package-private (the types loaded by name via SPI and LogManager stay
    public).
  - flow: the engine parts `Stepper` / `FlowContextImpl` move into the new
    `flow.internal` (classes stay public, referenced only by the root package's
    assembly, no stability promise); the root package-info gains a Stability
    section.
  - db: `RowMapperTest` moves to the mirrored internal test package; a new
    `DialectSyntaxTest` unit-tests the four dialects' syntax surfaces directly
    (quoting/reserved words/DDL fragments/DML clauses); `DatabaseHub.of(Map)`'s
    root factory replaces the javadoc steering readers to construct internal
    classes.
  - The `internal` packages (ioc/boot/http/db/cloud/flow) and commons.util gain
    package-info files ("no stability promise" / package boundaries); ioc's
    `Container` class javadoc moves above the imports (it had never attached to
    the interface).
  - `ContextExecutor` settles: it stays a public API (an executor with explicit
    `ScopedValue` propagation), and the util package boundary is written into its
    package-info.
  - Doc stances sync: CLAUDE.md's dialect substitution path (the built-in default
    `PostgresDialect` is itself `.primary()`-able; a custom dialect = an id
    binding + the `freeway.db.dialect` key), the engine public-face wording, the
    `@Primary`-and-binding-DSL equivalence, `ExprEvaluator`'s ~600 lines, the
    module dependency graph (db's ioc is actually compile and referenced only by
    DbModule, cloud→boot is test scope, http declares commons explicitly);
    AGENTS.md's module table syncs; and flow README's "GraphSpec2" wording is
    fixed (v2 is carried by `GraphSpec` with a `version=2` marker).

### Removed

- **Dead dependency declarations cleaned (root `pom.xml`)** — the
  `<dependencyManagement>` entries for `freeway-starter` / `freeway-starter-boot`
  / `freeway-starter-web` / `freeway-starter-db` are removed. These artifacts
  have no matching module in `<modules>`, no directories, and zero references
  from any code or module (only the pom's own declarations) — leftovers from
  early planning. After removal the 7 internal modules in
  `<dependencyManagement>` correspond one-to-one with `<modules>`. No effect on
  resolving released artifacts (these were never released).

### Fixed

- **One process could export multiple RPC mappings (freeway-cloud)** —
  `RpcEndpoint.of()` always returned the same route pattern
  `Route.post("/rpc/{mapping}/{method}")`, so a second export fought the first
  for the same trie node and startup failed with
  `IllegalStateException: Duplicate route detected: POST /rpc/{mapping}/{method}`,
  contradicting the class javadoc's "each mapping you hand to `of()` becomes
  reachable", with an error message pointing nowhere near the cause. The mapping
  is now baked into the path literal (`/rpc/<mapping>/{method}`), and the
  mapping name is validated at export (reusing `RpcPaths.validateSegment`, so an
  illegal name fails on the spot instead of silently shadowing a sibling mapping
  at request time). The outward URL shape and caller-side code are unchanged. New
  regressions: two mappings coexisting and reachable, cross-prefix still 404, and
  an illegal name throwing at export.
- **RPC rejection details no longer magnify a 4xx into a 500** —
  `X-RPC-Reject-Reason` used to carry the raw text, and the `method` segment,
  having gone through path URL decoding, could carry CR/LF; freeway-http rejects
  control characters in header values, so
  `POST /rpc/user/greet%0d%0aX-Injected%3a...` got a 500 plus a SEVERE log line,
  replacing the carefully distinguished 400/404 contract with a server fault. The
  header is now form-encoded per the existing wire-doc convention (consistent
  with `X-RPC-Exception` / `X-RPC-Message`, which consumers already decode), the
  error body is serialized by `JsonCodec`, and the hand-written `escape()`
  (which handled only `\\` and `"`, producing invalid JSON for control
  characters) is deleted. CRLF response-header injection was already blocked by
  the HTTP layer in practice; what got fixed is the status code it took down with
  it.
- **mesh inbound fragmented text frames no longer lose events** — the client
  side's `PeerConnector` used the JDK `WebSocket.Listener`, which callbacks per
  **frame**; non-final frames were ignored outright, and a subsequent
  CONTINUATION frame failing to parse alone would `abort()` the connection. Peer
  servers fragment above 4 MiB (the inbound cap is 16 MB), so events of 4–16 MiB
  necessarily lost frames and broke the link repeatedly, while the server's
  inbound was already merged by the engine — asymmetric semantics on the two
  sides. A `TextMessageAssembler` is added to reassemble, rejecting over-long
  messages at the same server-side cap (16 MiB) — "the peer never sends FIN"
  must not be answered with unbounded memory.
- **The race between `callAsync` and `close()`** — the future used to join
  `inFlight` **after** the task was submitted; if `close()` finished its
  traversal between the two steps, a task dropped by `shutdownNow` had nobody to
  settle it. Registration now happens "register first, then submit" under
  `close()`'s monitor, and a call inside the closing window throws
  `IllegalStateException` synchronously (the same shape as `requireUsable`).
- **The registry hook's stop no longer masks the real failure** —
  `RegistryLifecycleHook.stop()` executed `container.get(ServiceRegistry.class)`
  outside its try, so when the container was half-torn-down, that lookup's
  exception displaced the failure already in progress. It is now best-effort: if
  the registry is unobtainable, one WARN plus tracking the instance for cleanup,
  and the lease expires naturally.
- **The RPC retry policy is no longer silently nulled without the resilience
  module (freeway-cloud)** — `CloudHttpClientDefault` stored the resolved default
  retryer into the `this.retryer` field (a dead field, written never read) but
  passed the possibly-null raw parameter straight to `ResiliencePolicy` — so
  without the resilience module (retryer null), the first retryable failure hit
  `retryer.shouldRetry` with an NPE. The dead field is deleted; the policy is
  built with the resolved non-null retryer, falling back to
  `RetryerDefault.withDefaults()` when absent.
- **HTTP/2 streams no longer receive response frames after being RST
  (freeway-http, including a flaky fix)** — `Http2Stream.close()` used to run
  `outputStream.close()` even when the stream ended by RST, sending
  `DATA END_STREAM` to a finished stream; the woken handler would then run
  `ctx.send(200,...)`, writing stray frames interleaved with later responses on
  the same connection. The race violated RFC 7540 §8.1 (after a stream is RST,
  no more frames for it may be sent), and real clients respond with GOAWAY
  closing the connection — the cause of
  `Http2ProtocolTest.h2cResetStreamWithNoErrorReleasesHandler`'s flakiness. It
  now converges uniformly: streams gain the `peerReset` (peer RST) and
  `responseAborted` (server-initiated RST) markers, and `close()` writes no
  response frames under either; a handler's later `ctx.send`/`write` under
  `peerReset` throws a clean `IOException` to finish rather than erroring
  mid-write. All RST-sending paths are covered — peer `RST_STREAM`,
  `abortResponse()` (PROTOCOL_ERROR), `sendReset()` (exception paths), stream
  errors caught in `dispatchToStream`, and `WINDOW_UPDATE` send-window overflow
  (FLOW_CONTROL_ERROR) — and none of them appends END_STREAM after the RST.
  `sendReset()` becomes idempotent on `responseAborted` (repeated calls send one
  RST). The normal completion path (a handler-initiated close) already had the
  `outputStream.closed` guard preventing duplicate frames, so it is unchanged.
- **`SslContextFactory` no longer NPEs on an empty keystore password
  (freeway-http, P3 hygiene)** — a `null` `keyStorePassword` hit
  `password.toCharArray()` and threw an NPE (a confusing stack rather than a
  config error). A passwordless keystore (e.g. an empty-password PKCS#12) is
  legal, and `KeyStore.load` expects a `null` char array rather than an empty
  one. The new `keyStorePasswordChars` passes `null` through verbatim, removing
  the NPE without breaking the passwordless-keystore case (the three call sites
  `loadKeyStore` / `defaultKeyManagers` / SNI's `SniKeyManager` all route through
  the helper).
- **cloud module P3 hygiene items closed in bulk (freeway-cloud / freeway-http,
  no behaviour change)**:
  - `ReadyHandler`: each `/health/ready` contributor check gets a 2 s timeout
    budget (a virtual thread + `Future.get(timeout)`), so one blocked contributor
    judges unhealthy instead of hanging the probe endpoint and piling up probe
    threads (P3-8).
  - `TracerDefault`: span construction captures the **owning thread**'s `Frame`,
    and `restoreThreadState` uses it to operate the span stack — fixing a
    cross-thread `close` leaving stale items on the owning thread's span stack
    and garbling later MDC/`diagId` (P3-11).
  - `CloudHttpClientDefault`: breakers/rateLimiters shard by `serviceId` with a
    1024-capacity bound (evicting one when over), preventing unbounded growth
    under a churning registry source (P3-1).
  - `PeerConnector`: handshake timeout (10 s)/reconnect backoff (1 s/30 s) move
    from hard-coded private constants to `SymbolSpec`-configurable
    (`freeway.cloud.events.handshake-timeout-ms` / `backoff-base-ms` /
    `backoff-max-ms` / `connect-timeout-ms`, defaults identical to the previous
    hard-coded values, behaviour unchanged); `CloudEventLifecycleHook` plumbs
    them through (P3-5).
  - Note: `Baggage`'s limits (P3-10) were verified to already live at the
    `BaggagePropagator` propagation layer (inject/extract truncate to
    `MAX_ENTRIES` / `MAX_ENCODED_LENGTH`), so the constructor is not capped again,
    which would break the oversized-propagation regression test.
- **mesh sessions enforce a hello-first handshake (freeway-cloud, security)** —
  `PeerHub` / `PeerConnector` each hold a per-session handshake state machine:
  CE frames arriving before hello/ack are protocol errors (the server closes
  1002; the client side aborts) and can no longer reach `hub.receive()` — any
  client that could establish a WebSocket previously skipped the token-gated
  hello admission and injected TOPIC events directly (`allowed-topics` defaulted
  to allowing any topic). Hello is once per session, and a repeated hello is a
  protocol error. New regressions including `PeerHubHandshakeStateTest` (red
  before the fix).
- **mesh reconnection semantics corrected (freeway-cloud)** — whether to
  reconnect no longer depends on "who called close()": the outbound side's
  `close()` only tears down the transport, and `handleDisconnect` re-dials
  uniformly, unless the hub registry still serves that origin (the suppression
  case for duplicates leaving a twin behind is kept); a removal caused by a sink
  send failure (unregister + close) now re-dials instead of silently losing the
  peer until restart. The new `PeerConnectorReconnectTest` covers it.
- **Outbound call exception-header sanitization and the event source's identity
  fallback chain (freeway-cloud)** — `RemoteCaller` decodes and sanitizes the
  peer exception header exactly once, with the same clean value feeding both the
  outer message and `RemoteInvocationException` (whose constructor still
  sanitizes itself), so CRLF forgeries and overlong values cannot enter logs via
  the cause chain; and `CloudEventLifecycleHook` resolves the mesh's own identity
  through the same registry service-id → `freeway.app.name` → freeway-app
  fallback chain as `HttpServiceDeclaration`, keeping the registered identity and
  the event source identity consistent.
- **TLS hot reload follows the effective engine (freeway-http)** — `SslReloader`
  was assembled onto the built-in `FreewayHttpEngine` unconditionally: once an ext
  engine (Undertow/Jetty) became the primary `HttpEngine`, the reloader watched
  and reloaded an engine that never started, silently breaking TLS hot reload.
  `HttpModule` now first probes whether the active `HttpEngine` binding is still
  this module's built-in one
  (`container.isActiveBinding(HttpEngine.class, Builtin.class)`, reading binding
  metadata only, never instantiating the engine), and assembles the reloader only
  then; otherwise it logs that certificate rotation is the effective engine
  module's responsibility. The built-in engine path is unchanged. A new startup
  regression uses a fake primary engine with a nonexistent keystore.
- **`/metrics` binds one instance under three roles (freeway-cloud)** — the
  observe module registers the same `MetricsDefault` instance under three roles
  (the concrete class, `Metrics` primary, and `MetricsSnapshot`), so the
  `/metrics` export view no longer instanceof-checks the resolved JDK proxy (the
  proxy implements only the requested interface and threw on the first request);
  replacing the metrics backend = skip `CloudObserveModule` and assemble your
  own. The "follows automatically" javadoc claims on `CloudObserveModule` /
  `MetricsSnapshot` / `MetricsHandler` are corrected downward. New regressions:
  the standard assembly exporting 200, an ext-style primary replacement with no
  ambiguity, and two primaries failing loudly (`AmbiguousBindingException`).

### Documentation

- **Event mesh inbound security and production token configuration** —
  `docs/freeway-cloud-events-design.md` gains §4.3 "Inbound gating: token and the
  two allowlists (required reading for production)": it separates the three
  inbound gates' differing empty-value semantics (`token` peer authentication /
  `allowed-types` CLASS deserialization / `allowed-topics` TOPIC injection) and
  gives four constraints for configuring tokens across a multi-node production
  deployment (all nodes agree on the value, inject via
  `FREEWAY_CLOUD_EVENTS_TOKEN` rather than writing it into config files,
  constant-time comparison against probing, rotation requires a rolling
  restart); it also records why no default token is built in — a hard-coded
  default is a published password that manufactures a security illusion, and a
  random default would split the mesh at startup over token mismatch.
- **Stale doc statements corrected** — "empty allowlist = allow all" in
  `DEVELOPER-GUIDE.md` and `configuration.md` syncs with the behaviour change
  above: the CLASS channel is deny-by-default, and only the TOPIC channel keeps
  "empty = allow all". The production samples
  `application-prod.properties.sample` / `.json.sample` gain an events section
  and the token injection hint.
- **How `secret.file` / `secret.keys` actually take effect** — these two keys
  read the JVM system properties directly (the secret provider participates in
  symbol resolution and cannot read its own config through that chain), so
  putting them in `application.*` or `FREEWAY_*` environment variables is
  **silently ineffective** — and the allowlist's deadness is exactly what keeps
  the sharp default of "any symbol name consults environment variables first"
  working. `docs/configuration.md`'s secrets table and env-mapping section gain
  the "‑D only" annotation and the exception note.
- **The thread-affinity contract of circuit-breaker reporting** —
  `CircuitBreaker`'s javadoc states: the half-open probe is held by the thread
  calling `allowRequest()`, and `onSuccess` / `onFailure` must settle on the same
  thread — otherwise the probe does not count and the circuit stays HALF_OPEN
  until the open window re-arms.
- **RPC export surface docs sync** — `DEVELOPER-GUIDE.md` and
  `docs/freeway-remote-callbus-design.md` §3.2 now describe each export's own
  `/rpc/<mapping>/{method}` route (multiple mappings coexisting) and the
  export-time mapping-name validation.

## [1.4.0] — 2026-09-02

### Added

- **A unified config file parser (freeway-boot)** — the new `ConfigFileReader`
  (boot internal): `.json` parses as JSON and flattens to dotted keys, everything
  else parses as properties, all UTF-8. The classpath cascade and the hot-reload
  file layer share the one implementation — whether a file sits on the classpath,
  in the working directory or under `freeway.config.file`, startup and every
  reload parse it identically.

### Changed

- **Config reading unifies under the framework (freeway-boot, breaking)** —
  `ConfigLoaderDefault` now returns `AppConfigDefault` (the only implementation,
  with static/dynamic construction forms): the file layer = the classpath baseline
  (packaged `application*.properties/json`, static) + filesystem overrides
  (same-named standard files in the working directory + files named by
  `freeway.config.file`, comma-separated; the filesystem overrides the classpath
  and the latter overrides the former), with `WatchService` hot reload, and with
  no override files the behaviour matches the static config exactly. Hot reload
  is pull semantics: the config model declares its own `SymbolProvider`s directly
  (`AppConfig.symbolProviders()`), file sources read the live snapshot on every
  lookup, and re-parsed `@Value`/`@Symbol` see the new values. A custom loader's
  static config moves from "the top tier (TIER_CLI)" to "the file tier
  (TIER_FILES)" — env/CLI and module sources (such as secrets) now correctly
  outrank it.
- **cloud's config mechanism is deleted (freeway-cloud, breaking)** —
  `CloudConfigModule` / `CloudConfigDefault` / `CloudConfigSymbolProvider` /
  `CloudConfig` / `ConfigRef` / `ConfigSubscription` / `ConfigChangedEvent` and
  the `freeway.cloud.config.*` keys are all removed; the config-centre file uses
  the framework key `freeway.config.file` (the old key
  `freeway.cloud.config.file` is retired); and `CloudModule` no longer installs a
  config module.
- **The reading entry converges on SymbolSource (freeway-ioc/boot, breaking)** —
  `SymbolSource` returns to being a pure resolution base (`resolve`/`expand`,
  String in / String out) that knows nothing of `SymbolSpec`/`Coercer`; typed
  reads become the explicit two steps
  `SPEC.parse(symbols.resolve(SPEC.key(), null)[, coercer])`, with the key and
  default declared once, on the spec. `AppConfig` is no longer a reading facade:
  `get(String)` / `get(SymbolSpec)` / `DefaultCoercer` are deleted, leaving
  `profiles()` / `asMap()` (a cascade snapshot that never contains secrets) /
  `symbolProviders()` / `close()`. `freeway-ioc` henceforth has zero dependency
  on `commons.config`.
- **Typed parsing converges (freeway-cloud/db/http, breaking)** —
  `ConfigValues` (the int/long/double static utilities) is deleted; `HttpConfig`'s
  reflective read helpers, DbModule's key/default double-writing, and cloud
  modules' bare `Boolean.parseBoolean(symbols.resolve(...))` all converge into
  declarative `SymbolSpec`s. cloud's `*_DEFAULT` constants upgrade to typed
  values, and the config layer and in-library fallbacks share one source.
- **Config tiers are made explicit as 4 framework tiers (freeway-ioc,
  breaking)** — `SymbolProvider`'s constants consolidate into `TIER_CLI(0)` /
  `TIER_SYS_PROPS(5)` / `TIER_ENV(10)` / `TIER_FILES(20)`; the module slot is held
  by the module (cloud's secret declares order 15, between env and files). All
  providers enter one ordered list: JVM `-D` rises above the file tier
  (process-level ops overrides, aligned with Spring Boot's convention); and the
  raw-env fallback tier is deleted — environment variables enter the chain only
  through the declared prefix mapping, so unknown symbols fail fast instead of
  silently hitting an unrelated variable (eliminating the `path`→`PATH` accident
  surface under Windows' case-insensitivity). A bare container (no boot) keeps
  system properties as its only builtin source.

### Removed

- **`EventBus.hasSubscribers(String/Class)` (freeway-ioc, breaking)** — a query
  API with zero consumers in the library; the DeadEvent diagnostics and
  `EventDispatcher`'s internal checks already covered the need.
- **`EventBus.publishOrdered(Object key, Object event)` (freeway-ioc,
  breaking)** — a placeholder API whose key parameter the implementation ignored;
  the single-argument `publishOrdered(event)` is the only form, and per-key
  ordering will be implemented with real semantics in the future.
- **The `@RoundRobin` annotation (freeway-cloud, breaking)** — a pseudo-SPI
  concept: a strategy marker on a single implementation; the load-balancing
  extension mechanism is `LoadBalancer` (@FunctionalInterface) + a primary
  binding. `LoadBalancerDefault` is now marked `@Local`, consistent with the
  selector on every built-in default implementation.

### Fixed

- **JSON override files parsed as properties (freeway-boot)** — the hot-reload
  file layer used to `Properties.load()` every override indiscriminately, so an
  `application.json` or a `.json` file under `freeway.config.file` parsed into
  garbage keys like `{"app.name"="..."}` with all real keys lost; after the
  unified parser dispatches by extension (case-insensitive), JSON override files
  work at startup and on hot reload alike.
- **A test stub's contract deviation (freeway-cloud)** — `SecurityTest`'s
  `SymbolSource` stub now throws `UnknownSymbolException` (it previously threw a
  plain `IllegalArgumentException`, so `resolve(name, default)` could not
  recognize a miss by type).

## [1.3.11] — 2026-08-30

### Added

- **`Defer.within` result forms (freeway-commons)** — new `within(Supplier<T>)`
  and `within(Function<DeferScope, T>)`: the scope produces a return value with
  the commit semantics unchanged (normal return drains; rollback/exception
  discards and produces nothing). Symmetric with `ScopedCache.within`'s three
  forms, plus a `Consumer<DeferScope>` void form.
- **A unified observability SPI (freeway-commons/cloud)** — `Metrics.Timer` gains
  `record(Duration)` and `record(Supplier)` default overloads (nanos as the
  canonical unit), making commons the framework's single observability SPI.
  `CloudObserveModule`'s registry now covers the container's Noop builtin with a
  primary binding: installing it routes all the framework counters (EventBus,
  HTTP engine and friends) into `/metrics` (previously http/ioc and cloud each
  had an incompatible registry, so the framework counters were invisible by
  default). Counters are integer-semantics (LongAdder), so counts in the
  Prometheus text no longer carry decimal points. `/metrics` serves the primary
  registry through the new capability interface `MetricsSnapshot` — an ext
  backend that replaces `Metrics` and can render itself is followed
  automatically, and one that cannot fails at startup during route resolution.
- **Structured binding exceptions (freeway-ioc)** — the new
  `AmbiguousBindingException` and `UnknownSymbolException`: multi-match/dual
  primary and a missing symbol can now be caught by type instead of matching
  exception message text. Both extend `IllegalArgumentException`, so existing
  catch sites are unaffected, and `Container.get`'s javadoc documents both.
- **EventBus subscription queries (freeway-ioc)** — `hasSubscribers(String)` /
  `hasSubscribers(Class)` complete the query face symmetric with
  `CallBus.handles`.
- **Migration lock TTL (freeway-db)** — `freeway.db.migration.lock-ttl` (default
  PT1H): a lock row left by a crashed process is taken over automatically after
  the timeout, with no manual DELETE; `0` explicitly disables takeover.
- **Connection dialing login timeout (freeway-db)** — `PoolDefault` sets
  `DriverManager.setLoginTimeout` before connecting (from
  `freeway.db.pool.health-check-timeout`): a hung dial no longer holds a pool
  permit indefinitely and blocks later borrowers.
- **Migration lock expiry loses its timezone dependence (freeway-db)** — the
  expiry check now reads the lock row's `executed_at` and the database's own
  `current_timestamp` inside one query, both converted through the same JDBC read
  path, so a DB session timezone ≠ JVM timezone can no longer pre-empt a live
  lock by the whole offset between them.
- **DatabaseHub fails fast on duplicate names (freeway-db)** — a duplicate name
  among `NamedDatabase` contributions now throws `IllegalStateException` instead
  of silently letting the latter overwrite the former.
- **Expression multiply/divide/modulo (freeway-flow)** — condition expressions
  support `*` `/` `%` (precedence between additive and unary; divide/mod by zero
  errors explicitly rather than producing Infinity/NaN).
- **Cross-transport event identity and inbound dedup (freeway-ioc/cloud)** — one
  `publish` now has the `EventBus` mint **one** event id handed to every bridge,
  so an event fanning out to N transports carries one identity across them.
  Previously each bridge minted its own id, and two copies of the same event
  could not be correlated by any consumer — cross-transport dedup was
  structurally **impossible**, not "not yet implemented". `EventBridge` gains the
  `send(topic, event, channel, eventId)` default method (defaulting to the
  three-argument form, so existing implementations are unaffected), and
  `CloudEventEnvelope.translate` gains an overload with `eventId`. The inbound
  side gains `publishInboundWithId(event, eventId)` /
  `(topic, payload, eventId)`, which with
  `enableInboundDeduplication(capacity)`'s bounded window delivers an event that
  reached this node via two transports exactly once. Dedup defaults **off**: it
  changes delivery semantics and costs memory, and must not be a side effect of
  installing a second transport. Config:
  `freeway.cloud.events.dedup.enabled` / `.dedup.capacity` (default 4096). The
  group is deliberately **not** implemented as overloads of `publishInbound` — a
  `(String, String)` call cannot be disambiguated between the three-arg topic
  form and the generic two-arg form. A null/blank event id always delivers, so
  old producers without the id header do not lose events.
- **`ReflectiveHandles.defaultMethodHandle` (freeway-commons)** — a cached
  non-virtual dispatch method handle (`findSpecial`): the correct handle form for
  calling an interface default method on a proxy receiver (`methodHandle`'s
  virtual dispatch would hit the proxy itself and recurse forever). Cached per
  Method with lock-free reads, isomorphic to the existing handle caches.
- **`ConfigValues` (freeway-ioc, the `symbol` package)** — bind-time typed config
  parsing: `intValue`/`longValue`/`doubleValue` error with the key name and the
  raw value (e.g. `freeway.cloud.rpc.connect-timeout must be an integer: 'soon'`),
  replacing scattered bare `Integer.parseInt`s. cloud's four modules and ext's
  `freeway-mq-kafka` are wired; it sits beside its consumer `SymbolSource`, so
  every module parsing config can use it directly.

### Changed

- **The cross-thread transaction guard's error clarified (freeway-db)** —
  `checkNoForeignTransaction`'s message now says "another thread holds this
  Database's transaction" and gives the two ways out (run on the transaction's
  thread, or use a separate Database instance for concurrency), instead of
  letting an unrelated thread's caller misread it as their own mistake.
- **`Orm.of(db)` completes the JDBC coercion rules (freeway-db)** — the default
  Coercer now carries the Date/Timestamp/Time → java.time rules just like the
  `DatabaseBuilder`/IoC path, so Orm's internal conversions such as generated-key
  write-back no longer differ by construction path.
- **Naming clarity (freeway-commons/ioc)** — `ReflectiveHandles`'
  `invoke(handle, receiver, args)` is renamed `invokeOn(...)`: it was
  indistinguishable at the call site from the positional form
  `invoke(handle, args...)`, and Lifecycle had been expressing receiver semantics
  with `invoke(handle, instance)`, working only by the coincidence of a no-arg
  handle; `JULLoggerAdapter.fixCallerInfo` → `applyCallerInfo` ("fix" presumes
  breakage when it sets); and `JsonLeaves.leaf` → `stringForm`.
- **CallBus registers as a container builtin (freeway-ioc)** — usable out of the
  box, closing after all `@PreDestroy`s just like EventBus. Code that manually
  bound `bind(CallBus.class).to(CallBus::new)` now creates an ambiguity and must
  drop that binding or make it `.primary()`.
- **Breakers/rate limiters shard per service (freeway-cloud)** — an injected
  instance now acts as a configuration template: each serviceId gets independent
  state with the same policy via `newShard()`, so one service's failures no
  longer poison the others. Custom implementations keep shared semantics through
  the interface default methods. HALF_OPEN probe timeout reset converges to the
  single-probe invariant (probeLock).
- **Tracer span durations enter the metrics (freeway-cloud)** — a span freezes
  its duration at close and records it into the `tracer.span.duration` timer,
  visible on `/metrics`; `Span.elapsedNanos()` reads it programmatically.
- **Response-side header validation aligns with the parse side (freeway-http)** —
  `validateHeaderValue` rejects all CTLs except HTAB plus DEL, exactly matching
  Http1xParser's inbound rules.
- **Performance smoke assertions become floor thresholds (freeway-cloud)** —
  CloudPerformanceTest's scenarios uniformly use an order-of-magnitude guard
  adjustable via `-Dcloud.bench.floor` (default 1k ops/s); measured values still
  print, and slow CIs stop misreporting.
- **`EventBridge.send(topic, event)`'s default channel unifies
  (freeway-cloud/ext)** — `CloudEventBridge` defaulted to `Channel.CLASS` and
  `KafkaEventBridge` to `Channel.TOPIC`, disagreeing; both are now `CLASS` (the
  two-argument form receives a concrete event object and derives the topic from
  the type — inherently the class channel). The bus always passes an explicit
  channel, so only code calling the SPI directly is affected.
- **CallBus dispatch goes through cached method handles (freeway-ioc)** — the hot
  path switches from bare `Method.invoke` to registration-time-resolved
  `ReflectiveHandles.invokeOn`, matching AOP/Lifecycle's existing convention;
  business exceptions no longer unwrap through `InvocationTargetException` (the
  method handle throws the original directly), and the DeadCall default-method
  fallback uses the shared cached non-virtual handle. One behavioural difference:
  when a direct `call(topic, payload)` mismatches argument counts/types, the
  failure type changes from `IllegalArgumentException` to
  `WrongMethodTypeException`/`ClassCastException` (both RuntimeExceptions; no
  contract depends on it).

### Removed

- **Design redundancy cleanup (freeway-db)** — `Orm`'s five typed overloads
  `insert/save/update/delete(T, Class<T>)` and `BatchQuery.rows(List<Object[]>)`
  are deleted (no usage sites; the varargs forms are supersets), along with
  `DatabaseStats.averageBorrowWaitNanos()/averageBorrowWait()`. The same batch's
  micro-optimizations: `Schema.ensure` reflects each entity once, and named-param
  batch execution validates structure only on the first row.
- **`EventBus.setEventBridge` (freeway-ioc, breaking)** — a single-slot setter
  cannot coexist with multi-bridge fan-out: a later-starting module silently
  unmounted the channel an earlier module had installed (the very cause of the
  documented "silent partition"). `addEventBridge`/`removeEventBridge` are now
  the only channel API, the safety property guaranteed structurally by the API's
  shape rather than by runtime rejection. Callers switch to `addEventBridge`.
- **`EventBus(Container, EventBridge)` two-arg constructor (freeway-ioc)** — the
  last single-slot seam, with zero call sites in both repos (the container used
  `new EventBus(this)`); it also contradicted the newly settled multi-bridge API,
  so it goes with it.
- **The `fwtimes` envelope extension (freeway-cloud, wire format)** — always 1,
  read by nobody in either repo, and its two intended purposes were already
  superseded: no forwarding exists inside the mesh (inbound goes through
  `publishInbound` and never re-bridges), and both copies of a cross-transport
  event have generation 1, so it cannot serve idempotency; dead-letter
  diagnostics actually use Kafka's `X-DLQ-Original-Offset`/`X-DLQ-Reason`.
  Idempotency is now carried by the bus-minted shared event id plus `EventBus`'s
  inbound dedup window. **Wire compatibility**: `parse` reads only the known keys
  it needs and ignores other extension attributes (required are
  `specversion`/`id`/`source`/`type`/`fwchannel`), so frames from un-upgraded
  nodes that still carry `fwtimes` parse normally and old and new nodes can mix.

### Fixed

- **freeway-db** — `DatabaseImpl.startsWithInsert`, a one-line wrapper, is
  deleted: its name described naive prefix matching while the real semantics were
  top-level INSERT detection (skipping CTEs/comments); call sites use the
  accurately-named `SqlTextParser.hasTopLevelInsert` directly, and the
  transaction-binding expressions duplicated across query/execute/batch are
  extracted as `currentTx()`.
- **freeway-ioc** — the error message when an injection point carries both a
  service injection and a config annotation now names the owner type (it used to
  print the anonymous `InjectResolver$1@...`, worth zero diagnostics);
  `normalizedId`'s signature narrows to `Inject` (the `Annotation` form's else
  branch was unreachable by construction); `ContainerImpl`'s three `get`
  overloads' duplicated closed double-checks and missing-binding errors are
  extracted into `requireOpen()`/`missingOrClosed()` (the hit path stays
  zero-allocation); and `BindingIndex.duplicateMessage` is renamed
  `duplicateBinding` (it returns an exception, not a message).
- **freeway-boot** — `AppConfig.get(SymbolSpec)` no longer allocates a fresh
  `CoercerDefault` per call (the nested holder shares a singleton); `loadJson`
  reuses the already-decoded text to parse directly, dropping the second byte
  stream wrapper; negative CLI value recognition extends to exponential forms
  (`--port -1e5`); `openBoundedStream` is renamed `findBoundedStream` (nullable
  semantics), `putAllIgnoringProfileActivationKey` shortens and extracts the
  `PROFILE_KEY` constant; and FreewayApp's header format aligns. A new
  `HookLifecycleTest` covers startup-failure rollback, Error passthrough and
  suppression chains.
- **freeway-commons** — `SymbolSpec.parse(raw, coercer)` no longer bare-NPEs when
  there is no parser and the coercer is null, throwing an
  `IllegalStateException` with the key name instead (with the config package's
  first dedicated test class, SymbolSpecTest); `Defer`'s deferred supply merges
  its duplicated compute/get logic (failure caching and exactly-once semantics
  unchanged); and `ScopedCache.onClose`'s stacked leftover javadoc is cleaned.
- **freeway-commons (logging)** — when SLF4J has already been pinned to the JUL
  fallback by a prematurely-created static logger, `LogBootstrap.ensureProvider()` no longer
  prints the misleading "pinning so it wins" and instead verifies the effective
  provider after binding with an actionable WARN (naming the detected external
  provider, explaining the cause, and pointing at `-Dslf4j.provider=` as the fix);
  `freeway.log.console.enabled=false` no longer removes a user-customized
  ConsoleHandler (any formatter other than the stock SimpleFormatter counts as
  user configuration) and only removes freeway's own and the JVM's stock handlers
  — the ownership contract is tiered (own/stock/customized) and documented; the
  env reverse-mapping's fold mismatch for hyphenated keys is fixed
  (`FREEWAY_LOG_FILE_MAX_SIZE` now reconciles back to
  `freeway.log.file.max-size`); and a forced LogManager initialization failure
  inside `configure()` no longer aborts the whole logging setup.
- **freeway-cloud**: the injected-path breaker/rate-limiter sharding was broken
  (the container proxy defeated instanceof, so all services shared one instance —
  one service tripping its breaker refused healthy services too); plus the
  concurrent reset race after losing the HALF_OPEN probe.
- **freeway-ioc**: EventBus's stream bridge, in the window between "taking the
  publisher" and "the first subscription", either hung subscribers or let an ISE
  escape `subscribe()` (the Flow spec requires no throw); late subscribers after
  the bridge closed now settle via onError.
- **freeway-flow**: a PARALLEL branch hitting a shutdown executor threw
  RejectedExecution and leaked the join latch (await hanging forever); the first
  error is now recorded, its slot released, and later branches skip fast. The
  nested-PARALLEL + fixed-pool deadlock risk is documented on
  FlowDriverDefault.
- **freeway-db**: parallel transactions on one Database instance overwrote each
  other's thread-guard registry; BatchQuery's autoCommit restore failure now
  destroys the physical connection instead of returning a dirty one; and the
  migration lock's duplicate-key detection narrows from "23xxx prefix + broad
  keywords" to exact status codes (23505/23000/40001) + narrow keywords, so
  check/not-null violations no longer misreport as lock contention (with a
  table-driven regression test).
- **freeway-http**: HPACK header-name validation switches to `Locale.ROOT` (no
  more misdetection under a Turkish locale).
- **freeway-commons**: AOP's hot-path global synchronized MethodHandle cache
  becomes a layered concurrent ClassValue cache (lock-free reads), with
  AdvisedHandler gaining a per-proxy handle cache; Coercer re-registration left
  duplicate entries in the assignable-source index; and the JSON parser's BOM
  check uses an escaped literal instead of a bare U+FEFF character.
- **freeway-ioc**: EventBus/CallBus switch from eager instantiation during
  container construction to **lazy realize at first resolution** (the standard
  singleton path) — construction-time `container.get(Metrics)` used to freeze the
  preloaded NoopMetrics, so a module-supplied primary registry never received the
  `eventbus.*`/`callbus.*` counters (contradicting the observability module's
  documented promise); Shutdown semantics are unchanged (an unresolved bus needs
  no close, and one resolved during close still gets the deferred close). The new
  `BuiltinMetricsWiringTest` pins the behaviour.
- **freeway-cloud** (the audit-fix batch) — `ObjectStorageDefault`: `delete`
  emits `ObjectDeletedEvent` only when an object was actually removed (it used to
  emit ghost delete events for nonexistent keys, violating the interface's no-op
  contract); `put` switches to a temp file + atomic replace (ATOMIC_MOVE degrading
  to REPLACE_EXISTING), so a symlink at the target is replaced as a link rather
  than followed, with no TOCTOU window between check and write; and `list` skips
  symlinks pointing outside the root (aligned with `get`'s refusal semantics, no
  longer leaking external file names). `BaggagePropagator`: key/value
  percent-encoding (all UTF-8 bytes outside RFC 3986 unreserved escaped), so
  baggage containing `,`/`=`/spaces/non-ASCII round-trips losslessly instead of
  corrupting the wire structure, with extraction degrading tolerantly on
  malformed escapes. `CloudHttpClientDefault`: unexpected local exceptions during
  dispatch (bad URLs/headers, discovery backend defects) map uniformly to
  `CloudException.dispatch` (retryable=false) — half-open probes no longer lose
  their outcome to an escaping exception, and rate-limited/rejected calls count
  into `cloud.rpc.failures` and duration. `Endpoint`: construction validates URI
  renderability and normalizes basePath (adding the `/` prefix, dropping the
  trailing slash, tolerating IPv6 brackets), and the client side drops its
  trailing-slash compatibility splicing. `PeerConnector`: peer address parsing supports IPv6
  (bracketed or bare literals) and validates the port, and a handshake watchdog
  is added (aborting reconnection when a socket stays open 10 s without
  hello/ack). `CloudConfigDefault`: change notifications move to a dedicated
  single-threaded ordered deliverer — `reload()`'s snapshot swap is no longer
  blocked by slow listeners. `CloudObserveModule`: a primary `Metrics` not
  implementing `MetricsSnapshot` errors at startup with a named error (previously
  a bare ClassCastException). `ReadyHandler`: duplicate contributor names fail at
  construction (previously every health probe 500ed). `HttpServiceDeclaration`:
  registering a bind-all address (`0.0.0.0`/`::`) warns at startup suggesting a
  service-host override; and the server-missing check catches
  `MissingBindingException` precisely.

### Removed

- **freeway-cloud**: the `observe.MeterRegistry` interface is deleted (it
  coexisted with commons' `Metrics` as a second track with drifting type
  semantics — long/double, nanos/Duration); the implementation
  `MeterRegistryDefault` is renamed `MetricsDefault` and implements commons'
  `Metrics`, with the snapshot accessors and Prometheus text export unchanged.
  Custom observability backends implement
  `com.jujin.freeway.commons.metrics.Metrics` directly.
- **freeway-ioc**: `Extension.of(...)` (zero references repo-wide);
  `Contributions.add(Class)`'s default-throw implementation becomes abstract (its
  only implementor, BinderImpl, already overrode it); and the internal
  InstanceFactory class folds into ContainerImpl.

### Test

- **freeway-cloud**: `PrincipalPropagationTest`'s `@AfterEach` did not clear
  `freeway.cloud.auth.extract.enabled`, leaking the system property into later
  test classes, so `SecurityTest.authPropagatorIgnoresInboundIdentityByDefault`
  failed deterministically in the full suite by class order (passing alone) —
  the cleanup is added.
- **freeway-ioc test reorganization** — the 2900-line single-file FreewayTest
  splits by domain into 9 test classes plus FreewayFixtures/FreewayTestSupport;
  the 1000-line EventBusTest splits into 6; and the streaming views' close race
  and the SSE pump's cancellation race each gain deterministic regression tests.


## [1.3.9] — 2026-08-24

### Added

- **EventBus streaming views (freeway-ioc)** — the new `stream(Class)` /
  `stream(String)` return a JDK `Flow.Publisher` (zero external dependencies; the
  spec is `java.util.concurrent.Flow` itself). The bridge builds on
  `SubmissionPublisher`: lazy cold-start mounting (the bus registers the stream
  only on the first downstream subscribe; an unconsumed one leaks nothing),
  drop-on-backpressure-overflow (never blocking the bus's dispatch thread), whole
  removal when any downstream cancels, and onComplete to every stream at close. An
  active stream is a real subscriber — a topic consumed by a stream stops
  producing DeadEvents, and delivered counts the bridge once.
- **SSE stream pumping (freeway-http)** — `SseEmitter.from(Flow.Publisher)` /
  `from(publisher, mapper)` pipes an event stream straight into an SSE response:
  request(1) single-flight backpressure climbs the TCP stack hop by hop, the
  current virtual thread blocks until the source ends (pair with the handler's
  try-with-resources), source completion closes the response, a source failure
  logs at debug and closes, and a client disconnect is woken event-driven through
  a latch and cancels the upstream subscription. Heartbeats keep the connection
  alive during idle periods as before.
- **The CallBus request-reply bus (freeway-ioc)** — topic-addressed local RPC:
  providers `register(mapping, target)` (public methods become
  `mapping.methodName` topics, slot-style hot swap, overloads rejected); consumers
  use `consumer(mapping, api)`'s JDK dynamic proxy or call
  `call(topic, List args[, Duration])` directly; positional argument encoding (no
  `-parameters` dependency); exceptions travel uniformly through the stage
  (RuntimeExceptions as-is, checked ones wrapped per the join/get convention);
  no listener throws `DeadCallException` (the reply-side counterpart of
  DeadEvent), and the proxy falls back to interface default methods. Calls inside
  a transaction dispatch inline, semantically identical to a local method call —
  side effects that should wait for commit are facts, and EventBus publishes defer
  inside Defer (avoiding the blocked-reply deadlock). Four metrics counters
  (callbus.called/served/failed/dead) mirror the eventbus style.
- **CallBus invocation-chain advice (freeway-ioc)** — `advise([selector,] advice)`
  wrapping aspects: short-circuit the value space (a return is a cache-hit reply /
  a throw is a circuit-break fast-fail), registration order layered, and advice
  can see business exceptions (the reflective ITE is unwrapped at the terminal
  chain link). Isomorphic with freeway-ioc AOP's `MethodAdvice`.

### Changed

- **EventBus's inbound channel (freeway-ioc)** — the new `publishInbound(Object)`
  / `publishInbound(String, Object)` publish events from external sources (such
  as MQ subscribers feeding back): local dispatch semantics are identical to
  `publish` (including Defer buffering/DeadEvent), but the event **never
  re-bridges to MQ** — bridging an inbound event would loop it back to the queue
  forever. `EventBridge` gains the `Channel` enum (CLASS/TOPIC) and the
  three-argument `send(topic, event, channel)` default method (two-argument
  implementations unaffected), so a bridge adapter can mark the dispatch channel
  on the wire envelope and the inbound side dispatches symmetrically by the mark.
  The accompanying Kafka adapter changes are in freeway-ext.
- **`EventBus.Keyed` partition keys (freeway-ioc)** — an event class may
  optionally implement the nested contract
  `EventBus.Keyed { String key(); }` (isomorphic to `EventBus.Stoppable`), and
  bridge adapters use its return value as the message key (the Kafka record key):
  events of one aggregate stay ordered across JVMs and consumers can process in
  parallel by key. Events not implementing it bridge with a null key (no
  cross-JVM ordering guarantee). Events stay zero-coupled to the framework.
- **cloud type-key validation (freeway-cloud)** — `BackendTypeGuard`: when a
  local backend is assembled, the `freeway.cloud.{config,secret,storage,
  discovery,registry}.type` keys are validated; a non-`local` value without the
  corresponding ext adapter installed warns (no longer silently ignored),
  aligning with the marker fallback's "not fully silent" principle.
- **Observability wiring (freeway-cloud)** — with `CloudObserveModule` installed,
  `CloudHttpClient` calls open spans (`cloud.rpc.<service>`) and record metrics
  (`cloud.rpc.calls`/`failures`/`duration`); `freeway.cloud.rpc.trace.enabled`
  (default true) controls span creation, while metrics always record.
- **Baggage propagates across processes (freeway-cloud)** — `BaggagePropagator`
  implements the W3C `baggage` header (k=v, comma-separated), so an application's
  own KV pairs cross service boundaries with HTTP requests in both directions,
  honouring `Baggage`'s "propagated across service boundaries" promise; the
  trace/auth propagators' extract empty-value semantics are fixed (unset returns
  null instead of overwriting later-propagated baggage with emptiness).
- **cloud structure cleanup (freeway-cloud)** — `TransportSecurity`'s binding
  drops the static `@None` marker (capability is decided by runtime config, which
  a static marker cannot express; `@Mtls` stays as the ext contract surface);
  hook names centralize into `CloudHooks` constants and reference
  `HttpModule.SERVER_HOOK`; the only redundant `.scope(SINGLETON)` is deleted
  (singleton is the default); `PropagationFilter` sinks into `internal`;
  `/health/ready` gains the built-in `RegistryHealthContributor` (registry
  connectivity + instance count; an ext backend's connectivity is the adapter's
  job); and four empty `{@code }` javadoc blocks are fixed.

- **SLF4J provider selection becomes deterministic (freeway-commons)** —
  `LogBootstrap.ensureProvider()` probes the classpath for an external provider
  before SLF4J initializes and pins the `slf4j.provider` system property
  (precedence logback > log4j > slf4j-simple); with an external provider present,
  the JUL fallback no longer activates. The provider used to be decided by
  classpath order, and freeway-commons as a base dependency usually appears
  first, silently displacing Logback and voiding its configuration (logback.xml).
  A user-set `-Dslf4j.provider` always wins and is never overridden; the JUL
  enhancements configure themselves only when JUL is actually in effect.
- **`@Inject("id")` qualified injection (freeway-ioc)** — a `List<V>`/
  `Map<String, V>` injection point with an explicit id resolves the bound service
  with that id first (previously a service bound as `List<X>` was permanently
  shadowed by the contribution set), falling back to the contribution view only
  without a binding. Constructor parameters still consume contributions
  implicitly (an unannotated `List`/`Map` parameter is the contribution set),
  field injection still needs an explicit `@Inject`; injecting `Extension<V>` is
  explicitly rejected with the pointer to `@Inject List<V>` /
  `@Inject Map<String, V>`.
- **`${name:-default}` default-value semantics corrected (freeway-ioc, behaviour
  change)** — the default strips a single leading `-`, so `${port:-8080}` parses
  as `"8080"` rather than `"-8080"`, matching the documented shell semantics.
- **CLI parsing strictens (freeway-boot, behaviour change)** — empty-key arguments
  (bare `--` / `-D`) and keys containing `=` (`--=x`) are rejected with an error
  instead of silently producing `freeway.` / `freeway.=x` garbage keys; positional
  arguments are WARN-ignored with a hint to use `--key=value` / `--key value` /
  `-Dkey=value`.
- **An empty `application.json` counts as no config (freeway-boot, behaviour
  change)** — an empty/blank `application.json` no longer crashes startup,
  matching empty properties by skipping as "no config", unifying the four file
  layers' behaviour.
- **Duplicate modules fail fast (freeway-boot, behaviour change)** — explicitly
  passing two different instances of the same module class (e.g.
  `new DbModule("ds1")` + `new DbModule("ds2")`) fails at startup with guidance,
  instead of silently discarding the second's config; the same instance passed
  twice and anonymous/lambda modules remain leniently deduplicated; explicit
  instances take precedence over SPI discovery.
- **H2 pseudo-header validation (freeway-http, behaviour change)** — an HTTP/2
  request's `:path` must be origin-form (starting `/`), `:authority` validates by
  HTTP/1.1 Host rules (rejecting `@`/whitespace/control characters), and
  `:authority` is optional on non-CONNECT requests; an illegal `:path`/
  `:authority` is rejected, aligning with HTTP/1.1 and closing the
  proxy-confusion/smuggling surface.
- **HTTP/1.1 control-character rejection (freeway-http, behaviour change)** —
  control characters/non-token bytes in request header values are rejected
  (previously a bare CR could flow into the `X-Request-Id` echo path and 500 the
  whole session, and was a weak response-splitting primitive); `Content-Length`
  is strictly `1*DIGIT`, so `+5` and friends are no longer accepted.
- **`bodyAsJson` media-type validation (freeway-http, behaviour change)** — a
  typed body read requires `Content-Type` to be `application/json` or
  `application/*+json`; missing or mismatched throws
  `UnsupportedMediaTypeException`, mapped by the built-in error mapping to **415**
  (previously an illegal state → 500).
- **MySQL DDL migration guard (freeway-db, behaviour change)** — when the dialect
  does not support transactional DDL (MySQL/MariaDB), a migration containing DDL
  statements fail-fasts before the transaction with the fix (split the migration,
  make statements idempotent); previously the DDL implicitly committed while the
  checksum row was lost, so the next startup re-ran the DDL, failed outright, and
  startup was stuck forever.
- **Cross-thread transaction rejection (freeway-db, behaviour change)** — doing
  DB work from another thread inside a transaction is explicitly rejected with a
  `SqlException`; `ScopedValue` did not propagate across threads, so a child
  thread's writes ran on an independent connection, committed even after the
  parent's rollback, and atomicity broke silently.
- **Unknown JDBC URLs fail fast (freeway-db, behaviour change)** — an
  unrecognized JDBC URL scheme fails at startup listing the supported schemes
  (mysql/mariadb/sqlite/h2/postgresql) instead of silently falling back to the
  PostgreSQL dialect (Oracle/SQL Server/DB2 URLs used to produce PG syntax); and
  a plain `jdbc:h2` (no MODE) switches to the native `H2Dialect`.
- **Dialect capability extensions (freeway-db)** — `Dialect` gains
  `backslashEscapesStrings()` (so MySQL's `\'` backslash-escaped strings lex
  correctly) and `supportsTransactionalDdl()` (false for MySQL);
  `Schema.ensure()` explicitly refuses inside a user transaction on a
  non-transactional-DDL dialect; `execute(Sql)`/`query(Sql)` validate through the
  dialect, and RETURNING semantics are documented.
- **A gateway dead end throws `FlowException` (freeway-flow, behaviour change)** —
  an EXCLUSIVE gateway with no matching branch and no default, or a join missing
  arriving branches, throws `FlowException` naming the graph and node when the
  eval completes; previously only a WARN, with tasks silently lost. Runs aborted
  by an interceptor are exempt.
- **Expression evaluation semantics corrected (freeway-flow, behaviour change)** —
  `&&`/`||` short-circuit (both operands used to evaluate first, so
  `false && (x - 1)` threw); unary minus fully implemented (`-x`, `-(a+b)`,
  `--x`, with type preservation); Number/String mixed comparison compares
  numerically (`"10" > 9` is true; lexicographic comparison used to distort it).
- **Graph v2's version gate unifies (freeway-flow, behaviour change)** —
  `Graph.fromText` and `GraphSpec.fromText` share one version validation, and
  only canonical v2 documents (`version: 2` + `nodes`/`links`) load; v1
  documents previously loaded silently through the main API.
- **Bean serialization reads through getters (freeway-commons)** — JSON output
  prefers getters (getter-only/computed properties no longer vanish silently
  from output), the `isX()` boolean convention is supported, and getter-only
  properties are treated as read-only.
- **Internal refactoring (all modules)** — extensive internal deduplication and
  dead-code cleanup (shared helper extraction, a unified JSON write-container
  skeleton, etc.), behaviour unchanged.
- **The `freeway.web.*` config key fallback is removed (freeway-http)** — v1.2.1
  used the `freeway.web.*` prefix; v1.2.2 switched to `freeway.http.*` and kept a
  legacy-key fallback. The legacy fallback is now removed
  (`HttpConfigKeys.LEGACY_PREFIX` and the `config(...)` helper's legacyKey
  parameter): only `freeway.http.*` takes effect, and applications still using
  `freeway.web.*` must move to the new prefix.
- **Dependency upgrades** — JUnit Jupiter 5.12.0 → 6.1.3 (JUnit 6), SLF4J
  2.0.17 → 2.0.18 (the newest stable on the 2.0 line; 2.1 is still alpha), H2
  2.3.232 → 2.4.240 (a test dependency). JMH 1.37 was already current.
- **`HttpContext.headerSet(...)` renamed to `setHeader(...)`** — aligns the
  response-header setter with the chainable `status(...)`/`maxBodySize(...)`
  style and the `header(...)` getter (breaking rename; `headerSet` is gone).
- **SQL parsing becomes dialect-driven** — `Dialect` declares lexical
  capabilities (identifier quoting, `#` comments, `$tag$`/`E'...'` literals),
  and `SqlTextParser` converges to a single `scan()` primitive; queries and
  migrations parse against the target database's profile, and the scanner no
  longer hard-codes database syntax.
- **`Dialect` extracted into its own package, and DDL assembly moves out of the
  interface** — `com.jujin.freeway.db.dialect` is self-contained (depending only
  on `db.Database`); CREATE TABLE/INDEX/ALTER/DROP are assembled by
  `Schema`/`SchemaGenerator` from dialect primitives, and `Dialect` only declares
  syntax features (inline PK, DROP CASCADE, ALTER-constraint capabilities, etc.).

### Fixed

- **keep-alive state isolation (freeway-http, security)** — before a connection
  is reused, `ExchangeMetaDefault.reset()` clears principal/attributes, rotates
  the correlationId and refreshes startTime, and `HttpContextDefault.reset()`
  clears before applying `X-Request-Id`; previously request N's authentication
  context was visible to request N+1 on the same socket (an unauthenticated
  request "was authenticated") and the correlationId was reused across requests.
- **HPACK Huffman decoding rewritten (freeway-http)** — a static
  `CODE_BY_LENGTH` prefix table replaces the per-symbol full-table scan, making
  single-symbol decoding an O(1) lookup; a 64KB malicious header block used to
  mean ~130k symbols and billions of loop iterations, pinning a connection's read
  thread (an unauthenticated attack surface).
- **The accept loop self-heals (freeway-http)** — transient errors
  (EMFILE/ENOBUFS/EINTR) retry with a bounded 50 ms backoff, breaking only on
  "close" conditions; the first error logs at ERROR and later ones at DEBUG.
  Previously any transient IOException exited permanently, leaving the listener
  dead while the engine still reported started, with new connections silently
  refused at the OS backlog layer.
- **`maxBodySize` enforced on the streaming path (freeway-http)** —
  `LimitedInputStream` counts and filters by `maxBodySize` on the streaming read
  path, so a long-lived `bodyStream()` can no longer bypass the limit (it used to
  apply only to `readAll()`/the drain path).
- **WebSocket idle connections (freeway-http)** — after a WS upgrade, the long
  idle connection is no longer force-closed with a 1006 for lacking a close frame
  (idling past readTimeout used to kill it, inconsistent with the H2 path).
- **Static file and routing fixes (freeway-http)** — subdirectory `index.html`
  serves correctly, and a mount without the asset no longer blocks later mounts
  (the `hasResource` probe); route regex segments cap at 1024 characters (ReDoS
  mitigation); If-Range compares truncated to whole seconds, fixing range
  resumption answered as a full 200; HEAD + sendfile writes the head only (RFC
  9110); `WebServerBuilder`'s custom exception mappers append instead of
  replacing the built-in mapping wholesale; `status()` validates 100–599; routes
  registered with percent-encoded literal segments are matchable (encoded slashes
  guarded against collisions); and non-ISO-8859-1 response header values are
  explicitly rejected (they used to silently become `?`).
- **HTTP/2 protocol hardening (freeway-http)** — a truncated HPACK integer
  throws `COMPRESSION_ERROR` (previously an `ArrayIndexOutOfBoundsException` the
  peer could never see as an error code); `SETTINGS_HEADER_TABLE_SIZE` gets
  uint32 range validation and the decoder clamps negatives; literal header names
  get token validation (pseudo-headers exempt); and 204/205/304 responses drop
  `Content-Length` (HEAD keeps it).
- **`Orm.save()` with primitive-typed keys (freeway-db)** — a zero value on a
  primitive `@Generated` key counts as "unset", taking the insert path to fetch
  and write back the auto-increment key; a `long` key explicitly written as 0
  previously bypassed the sequence (the upsert path), so a second save updated
  0 rows.
- **The migration checksum's dual track (freeway-db)** — the checksum is computed
  from the raw bytes with a second CRLF→LF-normalized pass for backward
  compatibility; a Windows checkout's line endings used to produce a spurious
  checksum mismatch. `one()`'s multi-row truncation, the empty-collection
  `IN (:ids)` error guidance, and cross-database transactions without XA are all
  documented and pinned by tests.
- **Connection pool races (freeway-db)** — `PoolDefault`'s borrow/close race is
  fixed (`handOut()` re-checks inside the lock) with a deterministic race test;
  introspection failures fail fast instead of being swallowed by
  `Dialect.querySet` into an empty set and re-creating indexes.
- **IoC container lifecycle (freeway-ioc)** — the close/realize race hardens
  further: `closed` is set atomically under the lock plus a final drain, and
  `get()` uniformly reports closed (an instance from a concurrent slow
  construction no longer becomes an orphan without `@PreDestroy`); THREAD-scoped
  proxies reject calls after close (matching the singleton path's contract);
  advised PROTOTYPE lazily caches per proxy (repeated calls on one proxy get the
  same instance; each call used to build a fresh target); a `Binding.id()` change
  migrates caches (a late id change no longer instantiates a second singleton);
  and a final field carrying an injection annotation fails at construction
  (previously silently skipped).
- **EventBus isolation and draining (freeway-ioc)** — a subscriber throwing an
  `Error` no longer escapes (Throwable is caught and dispatch continues,
  consistent with the existing exception isolation); a `@PreDestroy` throwing an
  Error does not interrupt the drain; async/ordered publishes inside Defer drain
  silently after the bus closes (previously emitting a spurious warning); and
  `publish(String)`'s semantics — a class event, not a topic — are documented
  and pinned by tests.
- **Startup assembly (freeway-boot)** — re-entrant `close()` during `start()`
  now walks the state machine correctly (the hook completion re-checks
  `shutdownAttempted` and no longer overwrites RUNNING or re-fires
  `AppStartedEvent`); `AppBuilder.start()` guards single use with an
  AtomicBoolean (concurrent starts no longer build two containers with two
  shutdown hooks); a hook ordering reference to an unknown id fails startup
  (`Extension.validateOrdering()` opt-in, honouring AGENTS.md's regression
  requirement); `AppConfig.get(SymbolSpec)` parses with CoercerDefault by default
  (a spec without its own parser used to throw IllegalStateException); and the
  profile tier strips `freeway.profile` (config reads and `profiles()` no longer
  diverge).
- **JSON/reflection/Defer (freeway-commons)** — self-referential generic bounds
  (`Node<T extends Comparable<T>>`) deserialize with a visited-set falling back
  to `Object`, no longer StackOverflowError; JSON number tokens cap at 10 MB and
  `parse(String)` caps input at 32 MB (an over-long digit string used to cause
  CPU/memory spikes through BigInteger/BigDecimal); `DeferScope` validates
  duplicate ids at registration, and on an ordering failure executes all actions
  in registration order before rethrowing (previously silently skipping every
  deferred action); the string `"NaN"` is rejected consistently with Infinity
  (previously asymmetric, with NaN→boolean yielding false); and JSON duplicate
  keys' last-wins semantics are documented and pinned by tests.
- **Log file handling (freeway-commons)** — log files at the same path dedupe
  their global handlers (a registry keyed on the normalized absolute path, with a
  fresh handler after reset; two handlers previously shared one file and their
  rotations stomped each other, silently landing records in archives or losing
  them); purge excludes `.gz.tmp` and the source file mid-compression
  (compression's atomic rename used to fail and silently lose the archive);
  `freeway.log.console.level` applies only to freeway's own handler (no longer
  clobbering a user-configured ConsoleHandler).
- **Flow execution fixes (freeway-flow)** — `$for` LOOP preemption is atomic
  (concurrent re-entry no longer double-executes); subgraphs inherit the
  caller's per-eval interceptors without re-running them; a fresh run clears
  EventBus subscriptions (subgraphs exempt); `onNodeEnd` still pairs when
  `onNodeStart` throws; INCLUSIVE join counts reset per iteration; `putAll`
  filters nulls consistently with `put`; and `IocContainerAdapter` falls back to
  null only for "no binding", rethrowing real errors.
- **Transaction semantics** — an `Error` thrown inside a transaction rolls back
  correctly (the connection-state restore used to silently commit the failed
  transaction); transaction binding keys on connection identity (concurrent
  transactions no longer kill each other's in-flight queries); and one Database's
  transaction no longer leaks into another Database (a cross-database query used
  to run silently on the wrong connection).
- **Connection pool lifecycle** — fixes the cleaner/borrow race (double-destroyed
  connections, drifting counters), the release/close race (connections stranded
  in a closed pool), active connections borrowed past maxIdleTime being wrongly
  destroyed, and creation failures leaking physical connections; close wakes
  queued waiters and distinguishes its error messages.
- **ORM/Schema** — `Orm.save()` with a non-zero auto-increment id no longer
  silently inserts a duplicate row (`ON CONFLICT` actually applies); `@Index`
  combined with a renamed `@Column` generates the right column; OFFSET without
  LIMIT renders per dialect; adding a primary key/auto-increment column to an
  existing table is explicitly refused; and a zero-property type mapping errors
  explicitly.
- **SQL parsing and building** — backtick/bracket identifiers, MySQL `#`
  comments, `E'...'` escaped strings and dollar-quoting skip correctly per
  dialect; INSERT detection reads only the statement head; and the `Sql`
  builder's empty insert/update, out-of-order clauses and INSERT-expression misuse
  all fail fast.
- **Migrations** — numerically equal versions like `V1` and `V01` dedupe; an
  applied-but-missing migration errors at startup; and version identity
  normalizes (renaming a file neither replays it nor reports it missing).
- **HTTP/2 and HTTP resources** — trailers, RST_STREAM, connection-level flow
  control, HPACK overflow and frame size bounds harden per RFC 7540; a WebSocket
  close wakes the read loop, a failed SSL handshake no longer leaks the fd, and a
  failed SSE write closes the emitter.
- **Numeric conversion correctness** — `BigInteger` saturation,
  `1e400 → Double/Float` overflow, `getLong`'s silent out-of-range truncation and
  char-digit sources' low-bit wraparound are all explicitly rejected; an Optional
  target's null becomes `empty()` with overflow guarded.
- **JSON and reflection** — concrete collection/Map targets (ArrayList/HashMap)
  deserialize; JDK supertype types no longer crash on module access (method
  handle lookup uniformly falls back to publicLookup).
- **Logging** — `applyNamedFileLoggers()` no longer double-mounts named file
  handlers; rotation no longer loses buffered records, GZIP writes atomically,
  and startup purges expired archives; unrelated `*_LEVEL` environment variables
  no longer create phantom loggers.
- **Validation and scopes** — `@Valid` supports Optional/Iterable with a depth
  limit of 100 (against StackOverflow); `Defer.supply`'s failure rethrows
  explicitly and caches Errors too; and `ScopedCache`/`Defer`'s nesting contract
  is documented with a runtime warning.
- **IoC container** — PROTOTYPE+advice no longer shares a cached target; services
  instantiated during the close drain get full `@PreDestroy`/close (services
  realized after the snapshot used to leak); `publishAsync` honours an active
  Defer scope (an async event inside a transaction defers to after commit and is
  discarded on rollback); close and realize are mutually exclusive (removing both
  the orphan-instantiation race and the deadlock vector); `SymbolProvider` class
  contributions wire on demand (declaration registers a lazy facade, so
  declaration order no longer affects construction); `.marker()`/`.primary()`
  declared after registration take effect; `@Value List<...>` parameters and
  nested `${a:${b}}` default parsing are fixed; and a singleton injecting a
  THREAD concrete class with no scope active gets a dedicated diagnostic.
- **EventBus** — event dispatch matches hierarchies (a subclass event reaches a
  parent-class subscriber without a bogus DeadEvent); `stats()` reports dispatch
  statistics; `publishOrdered(key, …)`'s globally ordered channel serves
  transactional outbox ordering scenarios; Stoppable events short-circuit
  bridging; and the failure model is documented explicitly (at-most-once:
  subscriber exceptions are isolated and counted, never retried).
- **The workflow engine (freeway-flow)** — expression evaluation fixes a
  concurrent cache race, over-long expressions are rejected at compile time (eval
  used to overflow the stack and poison the cache), comparisons at long≥2⁵³ no
  longer distort, and boolean strings ("false"/"0") interpret by value
  consistently with `==`; interrupt becomes a global semantic; resume fails
  explicitly when replay cannot reach the resume point (previously silently
  skipping every task); a subgraph re-executes its body on every call (the second
  call used to skip silently) and closing trace no longer breaks subgraph calls;
  the INCLUSIVE join count resets on activation; LOOP stacks isolate per node;
  `$in` containing null no longer leaves the previous round's loop variables; and
  the graph model rejects UNKNOWN types, an explicit entry coexisting with START,
  unconditional duplicate links, escapes PlantUML output, and preserves the trace
  restore position through JSON round-trips.
- **Startup assembly (freeway-boot)** — profile selection precedence is fixed
  (`FREEWAY_PROFILE` used to lose silently to a file's `freeway.profile`,
  contradicting the documented cascade); a ServiceLoader-discovered module
  failing produces a clear error with the classloader context; and `AppBuilder`
  is single-use (a repeated `start()` used to register a second shutdown hook and
  build an independent container).
- **Concurrency contract markers (freeway-ioc)** — the new `@ThreadSafe`/
  `@NotThreadSafe` marker annotations reuse the existing `@Marker` mechanism:
  they annotate implementation classes (a `to(Class)` binding picks them up
  automatically) and resolve via `container.get(type, ThreadSafe.class)`; a
  singleton holder injecting an `@NotThreadSafe` concrete class is rejected
  (prototype/THREAD-scoped holders share nothing and are allowed); and marking
  both `@ThreadSafe`+`@NotThreadSafe` is rejected at bind time. Declaration is
  not proof — unannotated services are not checked, adopting gradually. +4 tests.
- **Framework primitives (freeway-commons/ioc/boot)** — `LazyValue<T>`, a
  thread-safe lazy value (volatile double-check, exactly-once computation,
  retry-on-failure, null refused — replacing each module's hand-rolled
  double-checks); the `Metrics` observability SPI (counter/gauge, zero
  dependencies, with the container builtin defaulting to NoopMetrics and a
  primary able to override) — EventBus wires in (published/delivered/
  subscriber_failures/dead_events mirror counters); and `SymbolSpec<T>` typed
  config (parsing + default + error messages carrying the key context, with
  `AppConfig.get(SymbolSpec)` as the unified entry replacing scattered
  parseInts; **the parser-less form (Coercer-default parsing)**:
  `of(key, type, default)` + `parse(raw, Coercer)` supports Duration/"2s"/user
  CoerceRules — DbModule's pool config adapts wholesale (URL/USERNAME required,
  pool sizes via parseInt, 6 Duration keys through the Coercer, hand-written
  helpers deleted). **Moves into commons.config** (so http/db and other modules
  can declare typed keys without depending on boot); the new `required()` factory
  (naming reviewed: ConfigKey → SymbolSpec, distinguishing the bare key constant
  family) fails fast on missing/blank instead of silently defaulting. +10 tests.

- **WebSocket idle keep-alive (freeway-http, fix)** — a successfully upgraded
  WebSocket clears `SO_TIMEOUT` after the 101 response: idle
  connections are no longer closed with a 1006 by the default 30 s readTimeout
  (a connection with no frame exchange used to be cut after about 30 s). Dead
  connections are still reclaimed by TCP keepalive probes, and no server ping is
  sent. `WebSocketIdleTimeoutTest` is corrected to really bind a 1 s readTimeout
  over this path.
- **`HttpServerConfig.builder()` (freeway-http, new)** — named-setter config
  construction avoiding long positional parameter lists binding a `Duration` to
  the wrong slot (readTimeout landing in shutdownGrace); the defaults match the
  canonical constructor and `build()` runs the same validation.
- **`AbstractHttpContext.readBodyLimited` renamed (freeway-http)** — the
  request-body rate-limited read helper becomes `readBody` (semantics unchanged);
  the old name is gone and callers switch.
- **`WebSocketRoute`'s record shape simplified (freeway-http)** — the `pattern`
  component that never took part in matching is removed (matching is the route
  trie's job by path); the 3-arg constructor goes with it — use
  `WebSocketRoute.of(path, endpoint)`.

- **JSON media-type detection completes the `+json` suffix (freeway-http, fix)** —
  `MediaTypes.isJson` now accepts `application/*+json` structured syntax suffixes
  (e.g. `application/vnd.api+json`, `application/json-patch+json`), consistent
  with the documented 415 check of `bodyAsJson`.
- **EventBus closes last at container shutdown (freeway-ioc, fix)** — publishing
  an event from a `@PreDestroy`/`close()` callback no longer throws
  `IllegalStateException` because the EventBus closed before other services,
  magnifying a benign shutdown into a shutdown failure; events published during
  close deliver normally.
- **Lifecycle method conflicts across the hierarchy fail fast (freeway-ioc,
  behaviour change)** — when a subclass and its parent declare **differently
  named** `@PostConstruct`/`@PreDestroy` methods, startup errors naming both
  (previously only the subclass's ran silently and the parent's init/cleanup was
  dropped); a same-name override still runs only the subclass's, once (Java's
  override semantics).
- **Non-writable property injection errors are distinguished (freeway-ioc,
  improvement)** — when `@Inject` hits a non-writable property, a final field and
  a getter-only derived property each get their own accurate error and fix
  (previously both reported "Cannot inject into final field").

### Docs

- **The code review report** — the new `docs/CODE-REVIEW.md`: the fix-status
  checklist of the three rounds (S1/S2/S3, 68 items total), the overall
  assessment, cross-module consistency observations and the test coverage
  analysis; after the three rounds **1517 tests green**
  (commons 374 / ioc 182 / boot 60 / http 349 / db 453 / flow 99).
- **Defer documentation completed** — `docs/design/defer.md`: the
  three-party wiring for DB transactions, the commit/dispatch sequence diagram
  (mermaid), the timing contract and the semantic boundaries; plus a new chapter
  on the nesting contract with `ScopedCache`.

## [1.3.6] — 2026-08-07

### Added

- **TLS session on `HttpContext`** — `sslSession()` exposes the TLS protocol and peer certificates; `isSecure()` derives encryption state from the session.
- **Schema dialect derived from the database** — `Database.dialect()` drives schema DDL; redundant `Dialect` parameters removed from `Schema.ensure()`, `Schema.drop()`, `SchemaEntity.of()`, and related APIs.

### Changed

- **`Extension` read paths lock-free** — `all()`/`asMap()`/`get()` use volatile double-checked caching (invalidated on `add()`); only writes take the lock. `EventBus.executor()` lazy init is double-checked as well. Runtime reads of contributions no longer contend.
- **Naming conventions aligned across modules** — breaking renames: `FreewayHttpContext → HttpContextDefault`, `ServerHandle → HttpServerHandleDefault`, `BootConfigLoader → ConfigLoaderDefault`, `SQL → Sql`, `DatabaseNamed → NamedDatabase`, `Coercer.supported() → conversions()`, `Temporary → ExecState`; engine package `http20 → http2`.
- **freeway-flow single canonical GraphSpec** — legacy solon-flow v1 `layout` format removed; `GraphSpec2`/`NodeSpec2`/`LinkSpec2` promoted to the root package as `GraphSpec`/`NodeSpec`/`LinkSpec`; `Graph.toMap()/toJson()` emit the canonical `nodes`+`links` format; `Graph.fromText()` accepts canonical JSON only.
- **IoC boundaries tightened** — `Container` and `Extension<V>` are no longer injectable; consume contributions via `List<V>` / `Map<String, V>`.

### Fixed

- **HTTP/2 wire-format audit** — full byte-level verification against RFC 7540 §4-§6 / RFC 7541 §5, locked by `H2WireFormatTest` (frame headers, RST_STREAM/WINDOW_UPDATE/GOAWAY/SETTINGS complete-frame bytes, RFC §5.1 integer examples, §5.2 Huffman flag, C.4.1 vector) and extended `HPackContextTest`; all frame writes confirmed inside the connection lock (no interleaving). Header strings now encode/decode with explicit UTF-8 (platform-default encoding previously made non-ASCII header values corrupt on non-UTF-8 JVMs).
- **IoC close lifecycle** — thread-scope values remain registered after container close so the global scope-exit hook still runs their `@PreDestroy`/`AutoCloseable` cleanup (unregistering on close leaked them); regression-tested. `JULEnhancer.resetForTest()` clears its tracked-handler set.
- **Flow join semantics** — INCLUSIVE gateways now join exactly once when all incoming branches arrive (standard BPMN join; previously every arrival passed through); `FlowContext.stop()` keeps the serialized `stopped` flag in sync when used standalone.
- **IoC close contract** — `@PreDestroy` callbacks now run before the container is sealed, so cleanup code can still resolve services via `get()`/`extension()`; `close()` is synchronized against concurrent double-shutdown. Documented: only *realized* singletons are cleaned up (a never-invoked lazy proxy gets no `@PreDestroy`).
- **Boot lifecycle** — `AppStoppingEvent` is only published for a runtime that actually ran (not a startup-failed `FAILED` state); close-once semantics documented.
- **Flow parallel hardening** — `ExecState` composite operations (inclusive/loop join `peek→count→pop`) are atomic under shared stacks, removing structural races between PARALLEL branches converging on the same gateway; status documented in `demo/reference/flow-parallel-context-isolation.md`.
- **IoC class contributions resolve across modules** — `contribute(T).add(Impl.class)` instantiation deferred until after every module's `bind()` has run (`flushPendingCreates()`), so a contributed class may depend on services declared by a later module regardless of declaration order. Previously each module's flush instantiated its class contributions immediately, failing on unregistered bindings from later modules.
- **HTTP engine** — h2c upgrade repair, request-line cap, connection draining on shutdown, multipart parsing guards, WebSocket subprotocol negotiation.
- **DB** — never recycle closed physical connections; `queryTimeout=0` supported.
- **Boot/config** — documented config cascade honored in value injection; negative CLI values parsed correctly.
- **Commons** — coercion/validation/JSON edge cases hardened; symbol escape; EventBus lifecycle and extension concurrency hardened. Short/Byte string coercion now matches Integer/Long decimal semantics; `Defer` deferred suppliers are synchronized against duplicate computation; `DeferScope` drain continues past throwing actions.
- **Flow** — LOOP iteration cap (`MAX_LOOP_ITERATIONS`), cyclic graphs rejected at build time, `ExprEvaluator` nesting depth guard, `FlowEventBus.clear()`, `@beanName` condition components resolved via `FlowModule`'s container adapter, singleton realize lock deduplicated across concurrent first resolution. `Node` topology caches and the graph registry are now safe under concurrent execution; EXCLUSIVE gateways warn on multiple/default-missing branches.
- **HTTP (HTTP/2)** — response header size budget enforced before HPACK encoding (64 KB), rejecting oversized header values instead of unbounded buffer growth.
- **Boot/config** — documented config cascade honored in value injection; negative CLI values parsed correctly; env-var→key mapping covered by tests. `freeway.env.prefix` is now a single replaceable prefix: default `FREEWAY_` keeps mapping into `freeway.*`; a custom prefix hands the mapping to the app (`APP_SERVER_PORT` → `server.port`, `APP_FREEWAY_HTTP_PORT` → `freeway.http.port`).
- **Logging env convention unified** — all `freeway.log.*` env lookups (`JULEnhancer` cascade, console color/MDC, MDC priority, caller-info flags) now honor `freeway.env.prefix` via a shared mapping (`freeway.log.level` ↔ `FREEWAY_LOG_LEVEL`, or `APP_FREEWAY_LOG_LEVEL` under a custom prefix), consistent with the config cascade.
- **IoC** — `String` constructor parameters now pass through the same marker/scope validation as other types (previously skipped scope-compatibility checks); `Extension` read paths lock-free.

### Docs

- **Naming rules clarified** — `XImpl` is the definitive implementation; `XDefault` is the replaceable default; `ModuleEx` avoids `java.lang.Module` collision.
- **Docs and English skill synced** — stale class names and API signatures updated across `docs/` and `skills/freeway-dev/`.

## [1.3.5] — 2026-07-23

### Added

- **`freeway-log.properties`** — dedicated logging configuration file at classpath root. Replaces JUL's `logging.properties` as the single entry point for all logging config. The file is not bundled in the JAR; all defaults are built into code.
- **Multi-file logging** — `freeway.log.files=biz,audit` declares named log files with independent `JULFileHandler` instances, each configurable via `freeway.log.file.<name>.*` keys. Supports per-file logger binding and level control with `useParentHandlers=false` isolation.
- **`FREEWAY_*` env var support for all `freeway.log.*` keys** — `FREEWAY_LOG_LEVEL=DEBUG` is equivalent to `-Dfreeway.log.level=DEBUG`. Config cascade: `-D` > env var > `freeway-log.properties` > code default.
- **Config-driven console control** — `freeway.log.console.enabled=true|false` and `freeway.log.console.level` in the config file.
- **Per-logger level via any `.level` key** — `com.myapp.audit.level=FINE` sets the corresponding JUL logger level. Accepts SLF4J names (TRACE/DEBUG/INFO/WARN/ERROR) and JUL names (FINEST/FINE/INFO/WARNING/SEVERE), case-insensitive via `parseLogLevel()`.
- **Caller info propagation through SLF4J bridge** — `JULLoggerAdapter.fixCallerInfo()` uses `StackWalker` to correctly set `sourceClassName` and `sourceMethodName` on each `LogRecord`.
- **`LogBootstrap.applyNamedFileLoggers()`** — late-stage re-attachment API for named file handlers that may have been cleared during JUL's lazy `LogManager` initialization. Safe to call multiple times.

### Changed

- **`JULEnhancer` rewritten** — owns the full config lifecycle: level management, console handler creation, formatter installation, single and multi-file handler activation. Reads `freeway-log.properties` via three-tier classloader cascade (TCCL → own → system).
- **`JULLoggerServiceProvider.initialize()` triggers `JULEnhancer.configure()`** — ensures JUL enhancements are active regardless of when SLF4J initializes, guarding against `LoggerFactory.getLogger()` calls before Freeway bootstrap.
- **DB: `DbModule.buildConfig()` provides friendly parseInt error messages** — non-integer pool config values now produce clear errors instead of bare `NumberFormatException`.
- **DB dialect system rebuilt** — `Dialect` gains `upsertClause()` (PostgreSQL `ON CONFLICT`, MySQL `ON DUPLICATE KEY`), capability flags (`supportsReturning`, `supportsOnConflict`, `truncateTable`, `forUpdateClause`), and `dialectId()`; `Database` exposes `dialect()`/`truncate()`; ORM identifiers quoted via `quoteName()`; new `H2Dialect`; `SQL.sql(Dialect)` validates dialect-specific clauses; SQL parameter scanning consolidated into `SqlTextParser`.
- **DB dialect fixes** — PostgreSQL INFORMATION_SCHEMA case handling, SQLite truncate/addColumn, `RowMapperResolver` cache race, `SqlTypeMapping` instanceof removal.

### Removed

- **`logging.properties` removed from JAR** — Freeway no longer depends on JUL's standard config file. `freeway-log.properties` is the replacement, and it's user-provided, not bundled.

### Docs

- **Sample configs cleaned** — `application-*.properties.sample` no longer carry `freeway.log.*` keys. Logging config lives in `freeway-log.properties`; samples point to the reference template.
- **`docs/freeway-log.properties.reference`** — annotated reference with best practices, `auto` semantics, multi-file patterns, and level formatting.
- **SKILL files updated** — `SKILL.zh.md` gains comprehensive logging section; `SKILL.md` and `references/commons.md` updated with multi-file, env var, and late re-attach docs.

## [1.3.3] — 2026-07-17

### Added

- **Auto file logging by default** — `JULEnhancer` now writes `logs/{app.name}.log` (or `logs/freeway.log`) without configuration; opt out with `-Dfreeway.log.file=off`. Time + size dual rolling `JULFileHandler` with GZIP compression.

### Changed

- **Logging module audit and polish** — compacted log formatters, simplified `JULMDCAdapter` (`ThreadLocal.withInitial()`), MDC priority keys configurable via `-Dfreeway.log.mdc.priority`; redundant FQN cleanup across modules.

### Fixed

- **SQL builder PostgreSQL `::` casts** — `SQL.where()`, `.set()`, and `.having()` correctly handle `::` type casts in named-parameter fragments (e.g. `created_at::date = :d`); the second colon is no longer misread as a named-parameter start.

### Docs

- **Logging docs updated** — DEVELOPER-GUIDE.md logging section reflects auto file logging defaults and configuration.

## [1.3.2] — 2026-07-07

### Added

- **MDC context display in log formatters** — `JULLogFormatterSupport` renders MDC key-value pairs in log output when MDC context is present. Both `JULConsoleFormatter` and `JULFileFormatter` support MDC rendering.



### Docs

- **Flow design decisions** — completes the flow module's design-decision document: one build path, the driver extension point, keeping entry types, cache invalidation, serialization of unreachable nodes, subgraph drivers, the exception policy, and FlowOptions defensive copying.

## [1.3.1] — 2026-07-03

### Added

- **`GraphSpec2`** (v2 graph definition) — canonical DAG format with explicit `entry`, separated `nodes` + `links`, and `normalize()` validation (link references, BFS reachability). Designed as the primary authoring surface going forward.
- **`@Marker` service disambiguation** — `@Marker(Builtin.class)` on modules, `bind().marker(Fast.class)` on individual bindings, `container.get(type, marker)` for resolution. `MarkerIndex` with `containsAll` semantics. Extends Flow with `@FlowMarker` for `!markerName` task resolution.
- **`Contributions.add(Class)`** — auto-generates canonical id as `snake_name@package`, ordering via `before`/`after`.

### Changed

- **Flow v1/v2 unified** — `GraphSpec.create()` internally converts to `GraphSpec2`, eliminating duplicate `Graph`/`Node`/`Link` constructors. Runtime always builds through `Graph(GraphSpec2)`. `Graph.fromText()` auto-detects format. Renamed `GraphBlueprint`→`GraphSpec2`.
- **Flow task resolution** — consolidated under `!markerName` (marker intersection via `@FlowMarker`) and `@beanName` (IoC container lookup). The `!marker` mechanism replaces class-name-based task matching with a more flexible, refactoring-safe alternative.
- **`Container` API refined** — `instantiate()` renamed to `create()`; `RouteIndex` no longer depends on `Container`.
- **`Module2` renamed to `ModuleEx`** — the module entry-point type renamed to avoid collision with `java.lang.Module`. This is a breaking change for early adopters: replace all `Module2` references with `ModuleEx`.
- **`Contributions.add(T)` fluent chaining** — `add(value)` now returns `Contributions<T>` instead of `void`, enabling chained calls. Note: `before()`/`after()` ordering is only available via `add(id, value)` or `add(Class)`, which return `Contribution`.
- **Flow driver extension point** — `FlowDriver` is a contributed extension point; `FlowModule` builds `FlowDriverDefault` as id `"default"` and graphs select a driver via the `"driver"` field. Custom drivers are contributed via `binder.contribute(FlowDriver.class)`.
- **Logging system completed** — JUL logging upgraded from console-only fallback to a full-featured system: `JULFileHandler` (time+size dual rotation, async GZIP compression), `JULFileFormatter` (ISO 8601 timestamps, recursive exception rendering), `LogBootstrap.ensureProvider()` (auto-detects Logback/Log4j, installs JUL only as fallback), `logging.properties` loaded from classpath, virtual-thread-aware thread name rendering. Fixes: SLF4J state constants (2=FAILED in 2.x), DCL race in provider install, GZIP resource leak, `Files.move` missing `REPLACE_EXISTING`.

### Fixed

- **HTTP/1.1 parser hardening** — duplicate `Content-Length` rejection, `Transfer-Encoding` comma+unknown rejection, pipeline buffer preservation, truncated request/header rejection, `Upgrade` requires both `Connection: Upgrade` and `Upgrade: websocket`.
- **HTTP/2 frame correctness** — `DataFrame` PADDED off-by-one, `PingFrame.writeTo` body, `WindowUpdateFrame` 31-bit masking, HPACK integer bounds/header lowercase/dynamic table tracking.
- **WebSocket strict compliance** — UTF-8 validation on text frames, close code reserved range rejection, extended 8-byte length for >65535 payloads, fragmented message assembly.
- **Coercion edge cases** — NaN/Infinity/BigInteger/BigDecimal guards, narrow overflow rejection, `@Min`/`@Max` BigDecimal comparison, `@Size` Map support, Optional/OptionalInt/OptionalLong/OptionalDouble coercion.
- **IoC lifecycle** — `findOwnerBinding` walks full interface hierarchy; module dedup uses `IdentityHashMap`; PROTOTYPE+advise routes through `createAdvised()`; thread scope cycle detection.
- **Multipart** — boundary terminator validation, semicolons in quoted strings.
- **SSE** — `\r` handling, field injection prevention.

## [1.2.2] — 2026-06-28

### Added

- **`freeway-flow`** — lightweight graph orchestration engine (port of solon-flow). 7 node types, JSON-based definitions, PlantUML export, execution tracing, subgraph calls, interceptor chains. Zero extra dependencies.
- **HTTPS auto-configuration** — `HttpModule` reads `freeway.http.ssl.*` config keys; creates TLS 1.3 engine when `ssl.enabled=true`. Supports PKCS12/JKS keystores and HTTP/2 over TLS via ALPN.
- **Express-style `:name` path variables** — routes support both `:name` and `{name}` syntax.
- **`JsonObject.getBigDecimal()` / `JsonArray.getBigDecimal()`** — convenience accessors.
- **Handler class injection for routes** — `Route.handlerType` enables IoC-injected handlers without manual `container.create()`.
- **CLI auto-prefix** — args without a dot (e.g. `--profile=dev`) auto-receive `freeway.` prefix.

### Changed

- **`@Named` removed** — superseded by `@Inject("id")`.
- **Documentation restructured** — `DEVELOPER-GUIDE.md`, config samples, and module summaries moved to `docs/` directory.

### Fixed

- **Header key normalization** — HTTP/1.1 parser normalizes header keys to lowercase per RFC 7230.
- **Header value OWS tolerance** — trailing whitespace stripped per RFC 7230 §3.2.6.
- **HEAD Content-Length** — HEAD responses report same Content-Length as GET (RFC 7231 §4.3.2).
- **Connection header token-list** — parsed as comma-separated per RFC 7230 §6.1.
- **BufferedOutputStream close ordering** — resolved ordering issue in HTTP response flush.
- **`setAccessible` fallback** — when module system blocks `privateLookupIn`, falls back to `setAccessible`.
- Response header injection hardening — `headerSet()` validates no `\r`/`\n` in values.

## [1.2.1] — 2026-06-23

### Fixed

- **4KB response crash** — `FreewayHttpContext` had a fixed 4096-byte buffer; bodies larger than 4KB crashed the handler. Response now streams directly to raw socket output.
- **Keep-alive path variable leak** — `pathVariables` not cleared on context reset, causing cross-request variable leakage between keep-alive requests on the same connection.
- **Daemon acceptor thread** — `acceptor.setDaemon(true)` caused the JVM to exit immediately after `main()` returned, because the acceptor and all virtual request-handling threads were daemon. Acceptor is now a non-daemon thread, matching the behavior of JDK HttpServer, Tomcat, Undertow, and Netty.

### Changed

- **`HttpConfigKeys` / `DbConfigKeys`** — config key constants extracted from `HttpModule`/`WebServer`/`HealthFilter` and `DbModule`/`PoolConfig`. All raw string literals (`"freeway.web.health.path"`, `"freeway.db.url"`, etc.) replaced with constant references.
- **Deferred binding registration** — bindings flushed after each module's `bind()` completes instead of immediately in `BinderImpl.bind()`. Default ids are now unique (`type@N` counter suffix), avoiding false cross-module collisions.

### Performance

- **HTTP request hot path** — request-line and path parsing rewritten to manual scanning, parser buffers and filter chain pre-built, per-request allocations eliminated.

### Removed

- **`freeway-benchmark`** — migrated to [freeway-ext](https://github.com/dzb/freeway-ext). All 31 source files, benchmark scenarios, and CLI tooling removed from core repository.
- **GitHub Actions auto-deploy** — `publish-release.yml` and `publish-snapshot.yml` deleted. Deploys now done manually via `mvn deploy`.

## [1.2.0] — 2026-06-22

### Added

- **`JULConsoleFormatter`** — ANSI-colored single-line JUL console output, auto-detected from the attached console. Colors are disabled when output is piped, redirected, or `NO_COLOR` is set. Override with `-Dfreeway.log.color=always|never`. Opt out entirely with `-Dfreeway.log.format=simple` or `FREEWAY_LOG_FORMAT=simple`.
- **`MySqlDialect`** — built-in MySQL/MariaDB dialect with backtick quoting, `AUTO_INCREMENT`, `VARCHAR(36)` UUID, `DATETIME(6)` Instant, `LONGBLOB` binary.
- **`SqliteDialect`** — built-in SQLite dialect with double-quote quoting, `AUTOINCREMENT`, `TEXT` UUID/Instant, `BLOB` binary, `sqlite_master` introspection.
- **Dialect auto-detection** — `detectDialect()` maps JDBC URLs to built-in dialects. H2 maps to PostgreSQL (or MySQL if `MODE=MySQL`). Explicit unknown dialect throws `IllegalStateException`; auto-detected unknown falls back with warning.
- **`SymbolSource.resolve(name, defaultValue)`** — default value overload. Returns `defaultValue` when the key is not found; delegates to `expand()` with `${name:default}` syntax.
- **`commons.util`** — consolidated utility package: `IoUtils` (bounded/readBytes streams), `Strings` (blankToNull, camelToSnake), `Maps` (nested flatten), `Digests` (sha256Hex/sha256Base64). Replaces `commons.io.InputStreams`.
- **Coercer API** — `canCoerce` → `supports`, `conversions` → `supported`, `CoerceRule.converter` → `mapping`. `coerceInternal` if-else chain replaced with O(1) `Map<Class, BuiltinCoercer>` dispatch; class reduced 531→370 lines.
- **Config keys** — `web.*` → `freeway.web.*`, `shutdown-grace-seconds` → `shutdown-grace` with `Duration` type. Profile-specific config samples for dev/prod in properties + JSON.
- **`List<T>` contribution injection** — contributions can now be injected directly as `List<T>` instead of requiring `Extension<T>` + manual `.all()`. Constructor params auto-resolve; fields need `@Inject`. (`resolveContributed`)
- **`HealthCheck`** — `@FunctionalInterface` for pluggable health endpoint responses. Default returns `{"status":"ok"}`; bind a custom implementation for DB/external service checks.
- **`HealthFilter`** — `HttpFilter` that intercepts the health endpoint (`freeway.web.health.enabled`, `freeway.web.health.path`) before routing. Injected into `WebServer` alongside `CorsFilter`.
- **CRLF validation in `headerSet`** — all `HttpContext` implementations now reject `\r`/`\n` in header values, preventing HTTP response header injection.

### Fixed

- `toUpperCase()`/`toLowerCase()` without `Locale.ROOT` across 12 files — Turkish locale would corrupt SQL DDL, config keys, column labels, and migration lock detection.
- `SymbolSource.resolve(name, null)` no longer expands to string `"null"` — fixes health-check-query default.
- `SqliteDialect.addColumn()` no longer doubles `ADD COLUMN`.
- `IndexDef.toSql()` conditionally omits `IF NOT EXISTS` for MySQL (via `Dialect.supportsIndexIfNotExists()`).
- `RequestContext.attribute()` now validates null key (was inconsistent with `setAttribute()`).
- `StaticResourceMount` `URLDecoder` `+` → space bug fixed by pre-replacing `+` with `%2B`.

### Changed

- **`Coercer.coerce()`** — throws `IllegalArgumentException` instead of `IllegalStateException` for coercion failures.
- **`CorsFilter`** — only intercepts genuine CORS preflight (`Access-Control-Request-Method` header present). Non-preflight `OPTIONS` requests pass through to route handlers.
- **`HttpServerConfig`** — invalid port/backlog/shutdownGrace now throw `IllegalArgumentException`; `shutdownGrace` is now `Duration` (config key `freeway.web.server.shutdown-grace`, e.g. `2s`), consistent with DB pool duration keys.
- **`PathPattern.validateRegistrationPath()`** — rejects empty path segments (`/a//b`), unbalanced braces (`/{id`), and empty parameter names (`{}`, `/:regex`).
- **`RouteIndex`** — wildcard params (`{path:.*}`) now reject literal children and vice versa, preventing unreachable routes. Param conflicts now compare regex by pattern string (value equality).
- **`MigrationRunner.isDuplicateKey()`** — SQL state code checking (`state.startsWith("23")`) added as fallback to keyword matching.
- **`BatchQueryImpl`** — rejects mixed positional/named parameters at construction time. Defensive `List.copyOf()` for `rows()`/`named()` inputs.
- **`Extension.order()`** — throws `IllegalArgumentException` on unknown `before`/`after` ids (was silent skip).
- **Environment variable mapping** — `FREEWAY_DB_URL` now maps to `freeway.db.url` (prefix stripped, `_` → `.`, `freeway.` prepended).
- **Extension mechanism simplified** — removed `Extension.Key` record (was `Class<?> entryType` + `String name`, the latter dead). `extensions` map changed to `Map<Class<?>, Extension<?>>`. FQN-based binding registration removed; extensions live exclusively in their own `ConcurrentHashMap`.
- **`Binder` API cleaned** — removed unused `contribute(Class, String name)` overload. Removed never-implemented `contributeMapped`.
- **HTTP package restructuring** — filter, route, body, event, sse, staticfile, and websocket classes extracted into sub-packages. `JdkHttpContext`/`JdkHttpEngine`/`RequestContextDefault` moved from `internal` back to root. `PathJoiner` moved to `route`. `RequestBodyTooLargeException` renamed to `BodyTooLargeException`. Test packages mirrored to match source layout.
- **`PooledConnection` interface** — extracted from the old concrete class (now `PooledConnectionDefault`). Public `Pool` API now returns the interface, eliminating the cross-module `internal` boundary violation in the HikariCP adapter.
- **`HikariPoolModule`** — now binds `Pool.class` instead of `HikariPool.class`, aligning with `DbModule.resolvePool()`.
- **`Schema.ensure()` / `drop()`** — no-dialect convenience overloads removed; caller must supply explicit dialect. `SchemaGenerator` no-arg constructor removed.
- **Engine selection** — switched from config-key-based (`freeway.web.engine`) to `.primary()`-based IoC resolution. `HttpModule` binds `FreewayHttpEngine` without `.primary()`; extension modules (e.g. `UndertowModule`) bind with `.primary()`. No config key needed — just add or remove the extension module.

### Removed

- **`JdkHttpContext` / `JdkHttpEngine`** — built-in JDK `com.sun.net.httpserver` engine removed. The only built-in engine is now `FreewayHttpEngine`. Users needing an alternative engine add `freeway-http-undertow`.
- **HTTP/2 frame types** — flat `engine/` subpackage classes restructured into `engine/http20/frame/`, `engine/http20/hpack/`, and `engine/http20/util/`. Deleted: `BufferedBuilder` (replaced by `StringBuilder`), `ChunkedOutputStream` (replaced by inner class), `FixedLengthOutputStream` (unused).
- **Strict mode (`freeway.strict`)** — removed entirely. Duplicate modules now logged (not thrown). Unbound concrete types always auto-instantiate. Engine fallback always warns + falls back. Eliminates `System.setProperty` side channel between `AppBuilder` and `ContainerImpl`/`WebServer`.
- **Extension adapter modules** — `freeway-http-robaho`, `freeway-http-undertow`, `freeway-http-jetty`, `freeway-mq-kafka`, and `freeway-db-hikari` moved to the [freeway-ext](https://github.com/dzb/freeway-ext) repository. Core modules (`commons`, `ioc`, `boot`, `http`, `db`) remain in this repository, keeping their zero-external-dependency guarantee.

## [1.1.1] — 2026-06-13

### Added

- **ScopedCache** — scoped value cache primitive built on top of JDK 25 `ScopedValue`. Provides a key-value cache that lives within a scope boundary and is automatically discarded on scope exit. Prunes the IoC scope layer by replacing heavier scope machinery with a lightweight cache primitive. (`78e448f`)
- **ModuleEx** — `@FunctionalInterface` for module definitions. Adds `binder.install()` to compose modules declaratively. Enables multiple `FreewayApp` instances per JVM. (`2eadd5f`)
- Comprehensive **docs/developer-guide.md** — dual-purpose documentation for humans and AI assistants, with a dedicated Module section. (`fd0f67c`, `20114cb`)

### Changed

- **StaticResourceMount** — added fallthrough behavior when no static file matches, allowing the request to continue to the next handler. (`097f218`)
- **Query.execute()** — new terminal operation for DML statements (INSERT/UPDATE/DELETE) that returns an `ExecuteResult`. (`097f218`)
- **Named parameter auto-bind** — query named parameters (`:name`) now auto-bind to matching record/bean property names. (`097f218`)
- **Generics audit** — eliminated all raw types and unchecked casts across the codebase. (`f1ed490`)



## [1.1.0] — 2026-06-10

### Added

- **Defer** — scope-bound deferred execution mechanism. Actions buffered inside a scope drain on commit, discard on rollback. Powers transaction-aware `EventBus.publish()`, per-HTTP-request scopes, and per-Kafka-record scopes with zero user wiring. `ScopedCache` is the companion scope-lifetime cache. (`5b1aba8`)
- **EventBus** — in-process publish-subscribe event bus with string topics, `DeadEvent` diagnostics, O(1) subscriber indexing, and `publishAsync`. (`50605d5`, `58728ce`, `694425f`, `e55d14a`)
- **freeway-mq-kafka** — distributed EventBus extension via Kafka, enabling cross-process pub/sub with the same EventBus API. (`cd8e2ea`, `fc38a63`)
- **freeway-db-hikari** — HikariCP connection pool adapter. (`288c7ed`)
- **Connection pool abstraction** — `Pool` interface + `PoolDefault` built-in implementation, selectable via `freeway.db.pool`. (`afb5aa9`, `9b7187b`)
- **JsonCodec** moved into `freeway-commons`, making JSON serialization available without IoC dependency. (`fc38a63`)
- **Lifecycle events** — `AppStartedEvent` / `AppStoppingEvent` published on the EventBus. (`9efa7e9`)
- **HTTP events** — `HttpRequestEvent`, `HttpErrorEvent`, `AssetServedEvent`, WebSocket open/close events published on the EventBus. (`f8c5cff`)
- **Schema auto-migration** — `@Table`, `@Column`, `@Id`, `@Index` annotations + `AutoMigrate` for automatic DDL generation. (`f6a9ee9`)
- **Orm** — basic CRUD operations with audit support. (`5168479`)
- **freeway-db standalone** — `DatabaseBuilder` + `PoolConfig` allow DB usage without the IoC container. (`0c2211a`, `66406ad`)
- **ExecuteResult key** — `ExecuteResult.id` changed from `long` to `Object key`, supporting non-numeric auto-generated keys. (`732a4bf`)

### Changed

- **ExtensionPoint<V>** — new extension point system with `ScopedValue`-based thread scoping. (`d2f69fb`)
- **Database API** — `Database.query()` and `Database.execute()` split query vs write entry points. (`5e3eac5`)
- **Coercion system** — unified type conversion with `Coercer`/`CoerceRule` refactored; JDBC date coercion rules added. (`f36acdf`)
- **Logging bootstrap** — SLF4J `ServiceLoader` standardization; JUL-backed fallback provider only activates when no external SLF4J provider is detected. (`f55c85d`, `e140304`)
- **freeway-db** — decoupled from IoC container, making it usable as a standalone library. (`0c2211a`)
- Removed `afterCommit`/`TransactionContext` in favor of the Defer mechanism. (`bd71f4d`)

### Fixed

- Config cascade priority corrected: env vars now properly override file-based config. (`f993ddd`)
- `ConcurrentHashMap.computeIfAbsent` JDK 25 false recursion during engine readiness polling. (`0acd60d`)
- 304 Not Modified response not sent in `StaticResourceMount`. (`419415c`)

## [1.0.x] — 2026-05

### 1.0.8

- **ExecuteResult** — `long id` → `Object key` for non-numeric auto-generated keys.
- **EventBus** — initial in-process event bus implementation.
- **Orm** — basic CRUD with audit support.
- **Schema** — `@Table`/`@Column`/`@Id`/`@Index` + `AutoMigrate`.

### 1.0.7

- Logging completion + container close clears extensions.
- SLF4J `ServiceLoader` standardization.
- Unified naming, logging, and language feature style across codebase.
- `Database.query()` + `execute()` API split.

### 1.0.5

- ExtensionPoint<V> system + `ScopedValue` thread scoping.
- Coercion system refactored with JDBC date `CoerceRule`.
- Register/Module simplification.

### 1.0.3

- Refined naming conventions.
- Removed obsolete design and audit notes.

### 1.0.2

- Logging auto-configuration — auto-defers to external Logger when present.
- Dynamic SQL design document added.

## [Initial Release] — 2026-05

- **Freeway 2** initial release — a modern, lightweight Java application framework built on JDK 25+.
- **freeway-commons** — shared utilities: JSON, coercion, logging bootstrap.
- **freeway-ioc** — IoC container with singleton/prototype/thread scopes, constructor and field injection, `@Symbol`/`@Value` config injection, extension/contribution mechanism.
- **freeway-boot** — application launcher with config cascade (CLI → env → profile files → default files), profile activation, and runtime lifecycle hooks.
- **freeway-http** — HTTP/WebSocket layer with trie-based routing, path variables, regex constraints, static resources, multipart, SSE, pluggable engines.
- **freeway-db** — JDBC data access with ORM, connection pooling, transactions, and query builder with named parameters and collection expansion.
- Extension adapters (robaho, undertow, jetty, hikari, kafka) available in [freeway-ext](https://github.com/dzb/freeway-ext).

[Unreleased]: https://github.com/dzb/freeway/compare/v1.5.1...HEAD
[1.5.1]: https://github.com/dzb/freeway/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/dzb/freeway/compare/v1.4.0...v1.5.0
[1.3.1]: https://github.com/dzb/freeway/compare/v1.2.2...v1.3.1
[1.1.1]: https://github.com/dzb/freeway/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/dzb/freeway/compare/v1.0.0...v1.1.0
