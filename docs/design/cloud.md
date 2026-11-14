# freeway-cloud Design (Final)

## 1. About This Document

> **Status: design baseline (from 2026-08-19, merged and finalized on 2026-09-12)**. This document is
> the single design baseline for `freeway-cloud`; it merges and supersedes `freeway-cloud-unified-design.md`
> (overall design), `freeway-cloud-events-design.md` (event mesh),
> `freeway-cloud-rpc-design.md` (cross-process invocation) and
> `freeway-cloud-implementation-plan.md` (implementation plan; the phase checklist has fully landed,
> and the parts still in effect are merged into §8.3).
>
> **Document division of labor**: repository conventions are in `AGENTS.md`; module boundaries, injection annotations, configuration cascade and
> lifecycle mechanisms are in `docs/architecture.md`; configuration key semantics, defaults and examples are in
> `docs/configuration.md`; the usage index is in `docs/developer-guide.md`. This document covers only
> cloud design and boundaries, and **neither repeats the above nor enumerates every configuration key** (§6 gives the shape classification
> and index). **The source code is the final authority**: where this document disagrees with the code, the code wins, and this document is corrected.

### Table of Contents

- [2. Positioning and Principles](#2-positioning-and-principles) | [3. Modules and Assembly](#3-modules-and-assembly) |
  [4. Core Objects](#4-core-objects) | [6. Configuration Keys Overview](#6-configuration-keys-overview) |
  [7. Explicit Non-Goals](#7-explicit-non-goals) | [8. Roadmap](#8-roadmap) |
  [9. Revision History](#9-revision-history)
- [5. Capability Design](#5-capability-design):
  [5.1 Service Discovery](#51-service-discovery-discovery) | [5.2 Cross-Process Invocation](#52-cross-process-invocation-rpc) |
  [5.3 Cross-Node Events](#53-cross-node-events-events) | [5.4 Observability](#54-observability-observe) |
  [5.5 Resilience](#55-resilience-resilience) | [5.6 Health Checks](#56-health-checks-health) |
  [5.7 Secrets](#57-secrets-secret) | [5.8 Object Storage](#58-object-storage-storage) |
  [5.9 Service-to-Service Security](#59-service-to-service-security)

## 2. Positioning and Principles

`freeway-cloud` is a **core module** (not a collection of ext adapters): it provides the
**seams + runnable local defaults** for cloud-native capabilities, so an application can
start, invoke, observe, rate-limit and probe health without any third-party cloud SDK.
Implementations that genuinely depend on external infrastructure (Nacos/Consul,
S3, Vault, OTel, gRPC) are **optional alternatives**, overridden by extension modules with `.primary()`.

Principles (check each one when adding a capability):

1. **Zero third-party dependencies**: compile-time dependencies are only `commons` / `ioc` / `http` + SLF4J
   (`boot` and JUnit are test scope only). Third-party SDKs always stay in ext adapters.
2. **Explicit declarations, no magic**: no classpath scanning, no bytecode weaving, no transparent remote beans.
   Exports, subscriptions and bindings are all written in the composition; out-of-the-box configuration does not guess.
3. **Runnable by default**: every SPI has a production-grade default (the quality bar follows
   `JsonCodecDefault` / `PoolDefault`), not a placeholder stub; it runs completely without
   installing any external backend.
4. **Assembly is choice**: implementation selection happens in the binding table (`.primary()` / the `@Local` marker /
   which module is installed), never at runtime by reflecting on a configuration string. `freeway.cloud.*.type`
   is a **declaration key**, not a selection key (§6).
5. **Interfaces in the feature package, wiring in `*Module`**: the `XDefault` default implementation lives in the same package as its interface;
   `internal/` holds only non-public implementation details with no stability promise.
6. **Capabilities may be absent**: not installing a submodule means its keys, routes and threads are never used; absence
   is not degradation and produces no silent failure — an invocation failure must reach the caller.

## 3. Modules and Assembly

```
freeway-cloud  (com.jujin.freeway.cloud)
  compile dependencies: freeway-commons, freeway-ioc, freeway-http + slf4j-api
  test dependencies: freeway-boot, junit
```

`freeway-http` is used only to provide the HttpServer / routing surface: cloud contributes
`/health/live|ready`, `/metrics`, `POST /rpc/{mapping}/{method}` and the
`WS /cloud/event` endpoint on top of it, and plugs into propagation and tracing through the `HttpFilter` extension point.
The dependency direction is one-way (cloud → http), and http has zero external dependencies.

### 3.1 Modules and Bundles

`CloudModule` is the **standard cloud bundle**: at class level it declares the 8 standard modules
`CloudContextModule` / `SecretModule` /
`DiscoveryModule` / `RpcModule` / `ObserveModule` /
`ResilienceModule` / `HealthModule` / `StorageModule` via `@SubModule`;
placing `CloudModule` places them (pre-order, after the bundle). All bindings live in each module's `bind()`;
`CloudModule.bind()` is the bundle's shared surface (currently empty), and the container receives
the whole tree before bind.

- **A bundle is a declaration; submodules remain ordinary modules**: `@SubModule` is static
  metadata read when assembling the composition (see `docs/module-system.md`), not a framework callback that
  asks a module for submodules. An application can place the whole bundle, or place submodules one by one for a
  subset — there is no exclusion API; the fallback is "load each module individually":
  ```java
  FreewayApp.run(
      new OrderModule(),
      CloudModule.class);                // whole bundle
  FreewayApp.run(
      new OrderModule(),
      new RpcModule());             // only one, configured your way
  ```
- `EventMeshModule` is an **optional add-on, not in the bundle**: it opens its own listener and
  outbound dialing, and is installed explicitly when a WebSocket event mesh is needed.
- **Assembly validation completes when the composition is assembled**: placing the same instance twice collapses
  (sharing an instance is normal usage), a second declaration of the same class raises `IllegalStateException` immediately and names both
  paths; a `@SubModule` cycle likewise fails when the composition is assembled.

### 3.2 Package Structure

```
com.jujin.freeway.cloud
├── CloudModule (contains nested ConfigKeys) / CloudHooks
├── annotation/   @Local backend marker
├── context/      InvocationContext, TraceContext, PrincipalContext, Baggage,
│                 Propagator, CloudContextModule
├── discovery/    ServiceEndpoint, ServiceHealth, ServiceInstance, ServiceDiscovery(+Default),
│                 ServiceRegistry(+Default), ServiceDeclaration,
│                 LoadBalancer(+Default), DiscoveryModule
│                 registry/ FleetRegistryModule, FleetRegistryClient,
│                 FleetServiceRegistry, FleetServiceDiscovery, RegistryNodes
├── rpc/          RpcHttpClient(+Default), RpcRequest/RpcResponse,
│                 RpcException, TransportSecurity(+Default), RemoteCaller,
│                 RemoteProxyFactory, Idempotent, RpcExport, RpcEndpoint,
│                 RpcModule
├── event/        EventMeshModule, PeerHub, PeerConnection, MeshEnvelope,
│                 MeshInterceptor (optional add-on)
├── observe/      Tracer(+Default), MetricsDefault, MetricsSnapshot, ObserveModule
├── resilience/   CircuitBreaker/RateLimiter/Retryer(+Default), ResilienceModule
├── health/       CloudHealthContributor, HealthModule (HealthResult is the shared HTTP value type)
├── secret/       SecretStore(+Default), SecretSymbolSource, SecretModule
├── storage/      ObjectStorage(+Default), ObjectMetadata/ObjectEntry/PutResult,
│                 StorageException, ObjectStoredEvent/ObjectDeletedEvent,
│                 StorageModule
├── context/internal/   AuthPropagator, BaggagePropagator, TracePropagator,
│                       PropagationFilter
├── discovery/internal/ RegistryStore, RegistryRenewal, RegistryLifecycleHook,
│                       DiscoveryConnectionHook, ReadinessGate,
│                       RegistryHealthContributor
├── health/internal/    ReadyHandler
├── observe/internal/   MetricsHandler, TracingFilter
└── internal/           cross-domain shared mechanisms: HttpServiceDeclaration, ServiceIdentity,
                        BackendTypeGuard, ActiveBindingProbe, OptionalBinding,
                        CloudHttpClients, ConstantTime
```

Visibility is deliberate: types in `internal/` may be `public` (cross-package assembly needs it)
but carry no stability promise; conversely `PeerConnector` / `RpcTarget` /
`RpcExportHook` / `RpcPaths` are **package-private implementations**, and `PeerHub` / `PeerConnection`
are the public **inspection surface** (operations and tests read connection state), not extension points.

### 3.3 Backend Markers and Assembly Protocol

- The composition unit is the list of modules placed at the entry point; the cloud side provides the bundle class `CloudModule` (`@SubModule` declares the standard modules).
- Local default implementations are all bound through `.marker(Local.class)`; the assembly surface can use
  `@Inject @Local ServiceDiscovery` to name "the built-in one" explicitly. `@Local` is allowed only
  on **parameters and fields** — no code reads it from a class, so putting it on an implementation class would compile
  yet be silently ineffective; the `TYPE` target was therefore removed outright, and misuse is a compile error.
- A replacement implementation = bind the same interface and use `.primary()`; an adapter with its own backend
  annotation defines and registers its marker itself (core does not pre-provision anything like `@Nacos` / `@S3` / `@OTel`).
- If the assembly surface references an unregistered marker, ioc ignores the annotation and falls back to the default
  binding, warning once per `(annotation, owner)` pair.
- When a backend type key (`freeway.cloud.secret/discovery/registry/storage.type`) is set
  while the local implementation is still in effect, `BackendTypeGuard` warns once at startup — **it does not silently discard it**.

### 3.4 RuntimeHook Ordering

Hook names are centralized in `CloudHooks`; cross-module ordering references only constants:

```
start:
  freeway.cloud.discovery / rpc   before("freeway.http.server")  connection lease, resolve exports, mount /rpc routes
  freeway.http.server                                            server starts, host:port becomes known
  freeway.cloud.registry / event  after ("freeway.http.server")  unified registration + heartbeat; mesh origin = actual identity
  freeway.cloud.secret / storage / resilience                     startup-time validation only, no ordering constraint

stop (reverse order):
  freeway.cloud.event             stop the dialer, close connections (1001 going away)
  freeway.cloud.registry          shed traffic first (deregister), keep a propagation window per shutdown-drain (auto = ask the registry backend)
  RPC transport                   wait for in-flight calls (shutdown-grace)
  freeway.http.server             close the server
```

- The RPC/discovery hook must run **before** the server starts: a call cannot arrive before the routes are mounted
  (ordering `freeway.cloud.rpc` before `HTTP_SERVER` is how that guarantee is implemented).
- The registry hook must run **after** the server starts: the address is known only after startup.
- The mesh endpoint exists before the hook does; a dial before the handshake is closed with `1013 not wired` and reconnects
  with backoff — this is normal, not a fault.
- The registry hook's ordering reference requires `HTTP_SERVER` to exist: `freeway-http` is auto-discovered by default via
  the `META-INF/services` SPI; explicitly turning `autoDiscovery` off without installing
  `HttpModule` fails at startup (`HookLifecycle` validates strictly), and that is deliberate design.

## 4. Core Objects

| Object | Responsibility |
|---|---|
| `serviceId` (an ordinary string, no public type) | Who it is |
| `ServiceInstance` | Which available instances exist |
| `ServiceEndpoint` | How to reach it |
| `ServiceHealth` | Whether the instance is usable right now (maintained by the discovery layer, not in the instance) |
| `InvocationContext` | What is propagated across boundaries |

`ServiceId` is **intentionally not a public type** (see `docs/architecture.md`): a service id is just a
string, and normalization (trim + non-empty guard) is done implicitly on binding ids by ioc's
`ServiceIds.normalize`; `ServiceInstance.serviceId()`, `Container.get(type, id)` and
`RpcHttpClient.call(serviceId, …)` share the same string, and cloud does not invent a wrapper type
or a second normalization.

```java
public record ServiceEndpoint(String scheme, String host, int port, String basePath) {
    public URI uri();
}

public record ServiceInstance(
    String serviceId, String instanceId, ServiceEndpoint endpoint, Map<String,String> metadata) {}

public record ServiceHealth(boolean live, boolean ready, Instant lastSeen) {
    public static ServiceHealth up();        // registered means ready
    public static ServiceHealth starting();  // registered, not yet ready
    public boolean isStale(Duration maxAge);
}
```

- `ServiceEndpoint` normalizes basePath at construction (empty → `""`, prepend `/`, strip trailing
  slashes) and validates that the whole tuple renders as a legal URI — a bad locator fails at assembly time and is not left
  to a request thread. IPv6 literals get brackets per RFC 3986 (inputs that already carry brackets are tolerated).
- `instanceId` is decoupled from location: a container reschedule that changes IP/port is still the same instance;
  only `endpoint` is updated.
- `metadata` is a free-form bag (zone/version/weight/canary), read through typed accessors with
  default-value short-circuiting; `loadBalancer.choose(instances)` receives only instances that are `live && ready` and not
  stale.
- `InvocationContext` is a `ScopedValue` slot with exactly three kinds of sub-context: `TraceContext`
  (owned by infrastructure), `PrincipalContext` (owned by security, unforgeable) and `Baggage`
  (owned by the application). It carries no business data, configuration snapshot or object cache. In-process asynchronous propagation uses
  `ContextExecutor`; **MDC is display-only** (ThreadLocal-based, not cross-thread).
- Propagation always goes through `Propagator`: inbound `extract(headers)` returns a partial context,
  merged by `PropagationFilter` with "non-empty wins"; outbound `inject(ctx, headers)`.
  Built in: `TracePropagator` (W3C `traceparent`/`tracestate`), `AuthPropagator`
  (`x-principal` / `x-principal-roles`) and `BaggagePropagator` (W3C
  `baggage`). A new concern = contribute a `Propagator`; core does not change.

## 5. Capability Design

### 5.1 Service Discovery (discovery)

| Role | Contract |
|---|---|
| `ServiceRegistry` | `register(instance)` / `renew(serviceId, instanceId)` / `unregister(instance)` (lifecycle) |
| `ServiceDiscovery` | `instances(serviceId)`; `instance` is an unordered default convenience method |
| `ServiceDeclaration` | Extension point: any module declares "which endpoints to register for this startup" |
| `LoadBalancer` | `choose(List<ServiceInstance>)`, performing only instance selection before outbound calls |

- `renew` returns **boolean**: `false` means the registry no longer knows this instance (evicted,
  lease expired, backend restarted), and the caller should `register` again. Heartbeat self-healing follows from
  this — a registry returning `true` for an instance it does not hold would make the caller believe it is reachable.
- `ServiceDeclaration.resolve(container)` is called after the HTTP server starts
  (host:port is settled); the built-in `HttpServiceDeclaration` registers the `HttpServer` address;
  other protocols/multiple ports are contributed by their own modules. Backend adapters for cross-process discovery (Nacos/K8s
  endpoints, etc.) go through `.primary()` (**not delivered**, §8.1).
- serviceId resolves from `freeway.cloud.registry.service-id` → `freeway.app.name`;
  instanceId is derived by default and can be pinned with `registry.service-instance-id`.
- **The two identity `auto` values** (the value cannot be written down, only derived, and one line is logged at startup stating which was chosen):
  `registry.service-scheme=auto` (default) follows whether the HTTP server has TLS enabled
  (`HttpServer.isSecure()`, which since 1.5.5 relays `HttpEngine.isSecure()`: whoever terminates TLS answers, so an
  adapter engine that ignores `freeway.http.ssl.*` will not register an https node as `http://`) —
  the registered `http/https` and the mesh dial's `ws/wss` come from
  the same derivation, so enabling TLS cannot be overlooked and no `http://` URL is registered by mistake; `registry.service-host=auto`
  (default) uses that address when the server binds a concrete address (it only listens there), and when bound to
  `0.0.0.0`/`::` prefers `POD_IP`, then the first routable local address, and only falls back to the bind
  address with a startup warning if neither exists. Multi-NIC hosts should name it explicitly.
- `registry.shutdown-drain=auto` (default) is answered by the registry backend
  (`ServiceRegistry.drainWindow()`): the built-in in-process registry answers `0s` (same JVM, no propagation
  delay), and a registry-center adapter answers with its own propagation window (Nacos/K8s endpoints usually a few seconds),
  so it need not be written once per deployment; an explicit duration wins, and a negative value fails at startup.
- Static third-party services (PostgreSQL/Redis, etc.) get their address from configuration and **do not go through discovery**.
- `LoadBalancerDefault` is a **weighted** round robin that is safe across virtual threads: each selection reads
  `ServiceInstance.weight()` (default 1), and the weight sum is taken modulo the call sequence. zone/canary
  remain metadata only; a custom policy just binds a primary implementation (the interface is a
  `@FunctionalInterface`).
- **Reusing discovery results is the discovery implementation's own policy** (`CachingServiceDiscovery`, default
  `registry.cache-ttl=5s`): TTL + single-flight refresh + keep-stale-on-failure. The implementation wraps the cache itself,
  so every consumer (RPC, the mesh feeder, the application reading for itself) gets it while the seam still has a single method;
  keep-stale-on-failure also lands in the layer that knows about the failure. The in-process store does not wrap it (a table lookup itself costs 0),
  and push-style backends (K8s informer) do not wrap or answer `0`.

### 5.2 Cross-Process Invocation (rpc)

Invocation model: **same process = a binding-table method call; cross-process = an explicitly declared RPC**. There is no call
bus and no "if nobody local answers, go over the network" path — whether a call is local or remote is written in the composition (bindings),
not hidden at runtime.

**Export (server side)**

```java
public record RpcExport(String mapping, Class<?> type, boolean propagateMessage) {
    public static RpcExport of(String mapping, Class<?> type);
    public RpcExport withPropagatedMessages();   // allow the exception message across the boundary
}
```

`RpcExport` is a **data contribution**: `RpcModule` resolves all declarations before `HTTP_SERVER`,
builds a method table per mapping (`RpcTarget`, `MethodHandle` dispatch), and
then mounts a wildcard route `POST /rpc/{mapping}/{method}`. The export surface **contains only explicitly listed
mappings**; there is no "export all methods" switch. Duplicate mapping names and overloaded-method conflicts fail at startup,
not as a runtime 404.

`RpcEndpoint.route(export, handler, codec)` is the **container-independent standalone assembly** entry point
(the ext engine's `RouteIndex`, custom mounting): it receives an already-obtained handler instance.

**Consumption (client side)**

```java
binder.bind(UserApi.class).to(c -> RemoteProxyFactory
    .of(c.get(RemoteCaller.class))
    .withServiceId("user-service").withMapping("user").withTimeout(deadline)
    .build(UserApi.class));
```

`RemoteProxyFactory` has only the remote-only mode (`of(RemoteCaller)`). `RemoteCaller`
is the raw transport surface: `invoke(serviceId, mapping, method, args, returnType
[, timeout [, idempotent]])`. `RpcHttpClient` is the lower-level general HTTP surface
(`call` / `callAsync`; async only moves the final socket wait off the calling thread, and the orchestration is unchanged).

**Wire protocol v1**

| Item | Value |
|---|---|
| Method/path | `POST /rpc/{mapping}/{method}` |
| Version header | `X-RPC-Version: 1`; missing or mismatched → `400` (`REJECTED`) |
| Request body | Positional JSON array, `JsonCodec.toJson` per element, `null` encoded explicitly |
| Segment constraint | mapping/method limited to `[A-Za-z0-9_.]` — this is a wire path segment, not free text |

Success: `200` + `application/json`; handler returns `null` → `200` with an empty body, and the consumer
gets `null`.

Business failure: `400`, **the class always crosses the boundary**, the message crosses only with `.withPropagatedMessages()`.

| Header/body | Content |
|---|---|
| `X-RPC-Exception` | The peer exception class FQN (form-encoded) |
| `X-RPC-Message` | The peer message; `remote handler failed` when not declared |
| body | `{"error": …}` |

Rejection (malformed call shape): `404` for a non-exported mapping / unknown method; `400` for a version mismatch
/ malformed argument array. The response carries `X-RPC-Reject-Reason`. Header values are always form-encoded: they often
carry control characters, the HTTP layer rejects CTL; otherwise a deliberate 4xx would turn into an unhandled 500.

**Full error mapping table** — `RpcException.kind()` is the structured form of this table; one value
corresponds to one caller action:

| Peer situation | consumer throws (`kind()`) | retryable |
|---|---|---|
| handler returns normally | return-value JSON deserialization | — |
| handler throws a business exception | `BUSINESS` (cause = `RemoteInvocationException(classFqn, message)`) | no |
| connection failure / connect-timeout | `CONNECT` | yes (the request never left this process) |
| no reply / mid-flight I/O / 5xx | `TIMEOUT` / `TRANSPORT` / `HTTP` | idempotent operations only (result unknown) |
| a 4xx that is not the structure above | `REJECTED`(status) | no |
| reply body cannot be deserialized into returnType | `REPLY_UNREADABLE` | no (deterministic failure) |
| unknown `X-RPC-Version` / non-exported mapping / unknown method | `REJECTED` | no (the declaration is the boundary) |
| no live instance | `NO_INSTANCE` | no (a deployment-state problem, replay does not help) |
| circuit open / local rate limiting | `CIRCUIT_OPEN` / `RATE_LIMITED` | no |
| calling thread interrupted | `INTERRUPTED` | no |
| local defect before outbound (bad URL/header, discovery defect) | `DISPATCH` | no |
| other mapped failure | `OTHER` | no |

`RemoteInvocationException extends RuntimeException`, with fields `remoteClass`
(the peer exception class FQN) and message (the peer message). **No faking the original type's inheritance chain**: restoration is
impossible, and faking it would mislead instanceof.

**Idempotency and the replay gate**: a retry must re-select an instance (a different instance after the discovery refresh) and must not
hammer a dead instance; before replaying it passes two gates — ① the category gate (connection failure/timeout are retryable);
② the idempotency gate (timeout / mid-flight I/O / 5xx are "result unknown", replayed only for idempotent operations).
`RpcRequest` derives idempotency from the verb (RFC 9110: GET/HEAD/PUT/DELETE/OPTIONS/TRACE are
idempotent, POST/PATCH/unknown are not), with `withIdempotent(...)` as an explicit override; on the RPC wire it is always
POST, so idempotency is declared by the consumer interface's `@Idempotent` (method-level or interface-level) and read
reflectively on every call. The circuit-breaker count for an ambiguous outcome is not affected by the idempotency gate — it is still a real service failure.

**Shutdown**: the outbound transport waits for in-flight calls at most `freeway.cloud.rpc.shutdown-grace`
(default 5s); calls still unsettled at the deadline are cancelled rather than holding up container close.

### 5.3 Cross-Node Events (events)

Position: **plane separation**. `EventBus` is the main channel for in-process broadcast + streams and knows nothing about
any transport; cross-node broadcast is the `MeshEventBus` facade (outbound `publish`, inbound delivery into the
facade's subscription table), and whether a fact leaves the process is decided at the call site. Cross-node is **at-most-once**,
and states so plainly — no ack/re-delivery; when reliable, key-ordered delivery is needed, use the Kafka plane (ext's
`freeway-mq-kafka`, `KafkaEvents`).

**Connection and handshake** (`WS /cloud/event`, subprotocol `freeway.event.v1`):

```json
→ {"proto":1,"origin":"order-1","serviceId":"order","subscribe":["order."],"token":"…"}
← {"proto":1,"accept":true,"origin":"user-1","subscribe":["user."]}
```

- The first text frame must be hello (`proto` / `origin` / `serviceId` / `subscribe` /
  optional `token`), and only once per session; a CE frame arriving before hello gets
  `1002 hello expected` — otherwise any client that can establish a connection could bypass admission and inject events directly.
- The token is compared in constant time; a mismatch is `1008 unauthorized`; a repeated hello `1002`;
  an unrecognized frame `1002`; a frame-processing exception `1011`.
- hello touches `receive()` only **after** the admission check, and `receive()` serves only admitted
  peers (symmetric on the client side: no CE frame is delivered before the ack). The server ack returns only
  `proto` / `accept` / `origin` / `subscribe`.
- `subscribe` elements are **prefixes**; the `{prefix, group}` form is tolerated (`group` currently does not
  participate in the semantics and exists only for forward compatibility).
- Outbound dialing has a handshake watchdog: a peer that accepts the socket but never returns hello is aborted and reconnected with
  exponential backoff (starting at 1s by default, capped at 30s).
- **No application-level heartbeat**: connection liveness relies on TCP/WS-layer behaviour, on an outbound `send` failure
  dropping the connection, and on immediate awareness of a peer close (the design once reserved a `keepalive` key, which never
  landed — no such key exists in `freeway.cloud.event.*`). Half-open connections are caught by the handshake watchdog —
  if hello/ack is not completed within `event.handshake-timeout-ms` after the socket opens, it aborts;
  application-level heartbeat is listed as a pending extension (§8.2).

**Event frame**: CloudEvents 1.0 JSON, with the `fwchannel` (`class`/`topic`) and `fworigin`
extensions carrying Freeway semantics; a `specversion` mismatch is rejected. PEER identity is
host+port; only one connection is kept per origin, and the rule is **the connection initiated by the lexicographically smaller side wins**
(same rule on both sides, so simultaneous dials necessarily converge to one).

**Outbound pipeline**: `MeshEventBus.publish(topic, payload[, subject])` (Defer-aware:
buffered inside a transaction, leaves the process only after commit, discarded on rollback) → filtered by the subscription prefixes declared by the peer
(filter-then-translate: if nobody is interested, nothing is serialized) → **enqueued** per connection. Each connection gets a bounded 256-frame queue plus
its own sending thread: a publishing thread never waits for a peer (one blocking write could hang the HTTP/Defer thread);
a full queue or a failed write drops that connection and re-dials; **there is no traffic backpressure** (no backpressure on the publisher, at the cost of
dropping connections/frames rather than slowing down). The sending thread takes one batch (≤64 frames) per handoff to the transport, and the inbound leg goes through the engine's
`sendTextBatch` — one writeLock, one flush, not one per frame.

**Inbound pipeline**: frame parsing (CloudEvents 1.0, `fwchannel`/`fworigin` extension attributes) →
origin self-loop suppression → interceptor chain → facade subscription-table matching. The `Class` declared by a subscription is the inbound allowlist:
a topic matching no subscription is **discarded without deserialization**, and an undeclared class never hits reflection. In-flight legacy-bridge
CLASS frames are still parsed, dropped by channel and counted (`stats().droppedLegacy()`). Events of local origin
(origin = self) are dropped outright to avoid mesh loops.

Admission is no longer a pair of configuration keys: the subscription table is itself the allowlist — a topic matching no subscription is dropped and not
deserialized, so the two loose postures "empty allowlist accepts everything / empty CLASS allowlist rejects everything" disappear with it,
and the only remaining startup warning is the token one (no token = any peer that can connect may connect).

**Deduplication retired with the bridge**: an event no longer has "multiple copies via multiple transports" — after plane separation, mesh frames enter only
the facade subscription table, mesh itself is at-most-once with no re-delivery, and Kafka's at-least-once idempotency belongs to the consumer's
business key. Shared ids, dedup windows and the `dedup.*` keys were deleted wholesale; cross-plane correlation is not the framework's responsibility.

**The dial list has two sources**: `freeway.cloud.event.peers` (static, unchanged for the connector's entire lifecycle) and
the **discovery snapshot** — enabled as soon as `freeway.cloud.event.discovery-feed-ms` is set: at that interval the event module queries the bound
`ServiceDiscovery` for instances of this service and feeds the whole set into `MeshEventBus.replaceDiscoveredPeers` (this node
is excluded; a failed query keeps the previous set — one failure must not be read as "everyone left"), and the set actually dialed is the union of the two.
An address missing from the snapshot = a node that left the mesh: interrupt its dial loop, gracefully disconnect the outbound session already
established, and delete it from the list — **the reconnect path follows the list**, so disconnecting alone will not dial it back. The two facts are kept separate rather than merged because
"the discovery backend found nothing" must not cancel the static list, while only the discovered half may shrink.

**Assembly and shutdown**: `EventMeshModule` is installed explicitly; an explicit
`freeway.cloud.event.enabled` value wins (Boolean vocabulary parsed by the container Coercer: true/false, yes/no, on/off, 1/0);
when unset, "peers configured" means enabled, and with nothing set the module stays
inert (installing the module alone produces no side effects). At shutdown the dialer stops before connections close; outbound connections send
`1001 going away`, and a very short close-frame window is left so container close does not wait for a peer that has already left.

### 5.4 Observability (observe)

- `Tracer` generates traceId/spanId, propagated across boundaries through `InvocationContext`
  (with `ContextExecutor` for async); MDC is display-only.
- Outbound injection and inbound extraction of the **W3C `traceparent`**, passed through together with this node's existing
  `tracestate` (`TraceContext.traceState`), interoperating with OTel.
- The inbound span is created by `TracingFilter` (`HttpFilter`, order `-105`) and explicitly
  bound into `InvocationContext` (`Tracer.Span.context()`) — a span attached only to the ambient
  layer is shadowed by the scope layer, a point that has really failed before. Probe/metrics paths
  (`/healthz`, `/health/live`, `/health/ready`, `/metrics`) create no span.
- Inbound `PropagationFilter` (order `-110`) performs extract → merge → `runWith`,
  and writes the inbound traceId into MDC.
- `Metrics` is a commons SPI; `ObserveModule` binds the module's own
  `MetricsDefault` registry **in three roles**: the concrete class (singleton), the `Metrics` SPI
  (primary, overriding the container's built-in `NoopMetrics`), and the `MetricsSnapshot` export view read by the
  `/metrics` route — all three always point at the same registry. `/metrics` outputs Prometheus
  text format (`text/plain; version=0.0.4`, hand-written with zero dependencies); timers emit `_count` / `_seconds_total`.
- **An honest capability boundary**: currently there are only 4 **unlabeled** series — `cloud.rpc.calls`,
  `cloud.rpc.failures`, `cloud.rpc.duration`, `tracer.span.duration`. No labels, no per-service attribution, no mesh metrics (§8.2).
- Switching the export backend = do not install `ObserveModule`; primary-bind `Metrics` /
  `Tracer` yourself and provide the export route (see the `ObserveModule` javadoc).

### 5.5 Resilience (resilience)

Three SPIs; the default implementations use only JDK concurrency primitives:

- `CircuitBreakerDefault`: sliding failure-count window (default 60s); exceeding
  `failure-threshold` (default 5) flips to OPEN; after OPEN has lasted `open-window` (default 30s)
  it lets **a single** half-open probe through — success returns to CLOSED, failure reopens; success resets the failure window.
- `RateLimiterDefault`: token bucket, burst defaults to 1 (strict rate); **off by default**.
- `RetryerDefault`: exponential backoff + **jitter** (base/2..base, to avoid synchronized retries).

**By default they take effect uniformly at the `RpcHttpClient` layer** (the most stable and easiest path to implement):

```
rateLimiter.tryAcquire()
  → breaker.allowRequest()
  → transport attempt: discovery.instances → loadBalancer.choose → build URL
              → inject InvocationContext → httpClient.send
  → RpcResponse / RpcException
(retry restarts from the rate-limit step, re-selecting an instance on every attempt)
```

- **Local rejection semantics**: circuit-open / rate-limited are `RpcException`s with retryable=false
  (retrying a rate-limited call fails again immediately), and they count toward `cloud.rpc.failures`.
  Rate limiting precedes the circuit breaker — a local rejection does not consume a half-open probe slot.
- Unexpected local exceptions during dispatch (bad URL/header, discovery backend defect) are uniformly mapped to
  `RpcException.dispatch` (retryable=false): the call surface is single, a half-open probe always reaches
  an outcome, and the circuit breaker cannot get stuck half-open.
- The aggregate switch `freeway.cloud.rpc.resilience = auto | off`: `auto` (default) is governed by the individual
  keys; `off` is an explicit master switch (NO_RETRY / NOOP / UNLIMITED, ignoring all individual keys),
  the escape hatch for mesh takeover and fault diagnosis; an illegal value fails at startup validation.
- Timeouts are **not** governed by resilience and always apply (`rpc.connect-timeout` /
  `rpc.request-timeout`).
- Without `ResilienceModule` installed, the client degrades to built-in defaults (3 retries / backoff starting at 100ms
  / threshold 5 / unlimited rate limiting), and the default values share one source with the configuration layer: the
  `*_DEFAULT` constants in `CloudModule.ConfigKeys` — one number, one source.
- The `@Retry` / `@CircuitBreak` / `@RateLimit` annotations + `Advisor` weaving are **not delivered**
  (§8.2).

### 5.6 Health Checks (health)

`HealthModule` contributes two K8s-semantics endpoints with **fixed (non-configurable) paths**:

- `/health/live` — process liveness, fixed `{"status":"ok"}`.
- `/health/ready` — aggregates the `CloudHealthContributor` set (contribute mode);
  all healthy `200`, otherwise `503`.

Registry readiness is provided by `RegistryHealthContributor` (delivered with `DiscoveryModule`),
and its semantics declare only facts the framework **can verify**:

- Never registered (no HTTP module, no service declaration) → reports `not registered`, rather than a
  hollow healthy.
- After registering, **3 consecutive** heartbeat failures (renew throws, or the existence check returns empty) → unhealthy;
  any single success clears the count — one blip should not take the pod out of rotation.
- During shutdown (already deregistered, only finishing work already accepted) → unhealthy immediately, which is exactly how to get
  probe-driven load balancers to take it out first.

When the health module is installed alone, the contributor set is empty (always ok). Connectivity checks for external backends are
provided by the adapter that replaces the registry/storage backend (not delivered, §8.1).

`freeway-http`'s own `/healthz` (`freeway.http.health.*`) is a **different** set of endpoints:
the http probe lives in `HttpModule`/`HealthFilter`, and the cloud probe is registered only when
`HealthModule` is installed. Their semantics are independent; do not configure them onto the same path to compete.

### 5.7 Secrets (secret)

```java
public interface SecretStore {
    Optional<String> get(String key);
    default Optional<byte[]> getBytes(String key);
}
```

- **No `asMap()`, no defaults**: secrets cannot be exposed in bulk and must be configured explicitly.
  This is an API-level security boundary and the core reason it is separate from the configuration system (exposure surface, audit level and
  rotation semantics all differ).
- The default `SecretStoreDefault` sources are env (keys uppercased, `.`→`_`) → the secrets file;
  external backends (Vault/KMS) are plugged in by an adapter's `.primary()` (not delivered, §8.1).
- File-backed storage **re-reads when the size or full-precision file mtime changes** (throttled to 1s); on a read failure or a
  transiently missing file it keeps the old value and warns — matching the atomic volume replacement of a k8s projected volume.
  The SPI **has no `reload()`**: leases/TTLs of a remote backend are the adapter's concern.
- `SecretSymbolSource` wraps `SecretStore` and participates in `SymbolSource` resolution, so
  `@Symbol("db.password")` can resolve a secret; its precedence is declared with `order()=15` (between the env tier at 10
  and the file tier at 20) and is **independent of module installation order**. Its own configuration
  (`freeway.cloud.secret.file` / `secret.keys`) is deliberately read straight from system properties —
  the provider participates in symbol resolution, so routing the path through it again would recurse; these two keys therefore work only via `-D`.

### 5.8 Object Storage (storage)

`ObjectStorage`: `get` / `put` / `delete` / `list` / `presignedUrl` — a **synchronous
API** following the `Database`/`Pool` pattern, with concurrency left to virtual threads.

`ObjectStorageDefault` is the local filesystem (`root/bucket/key`):

- **Path safety** (matching freeway-http staticfile's regression requirements): bucket validation (rejects
  `..` and separators), key normalization followed by rejection of absolute paths and `..` prefixes, and read/write/delete paths all
  validated with `toRealPath` to stay inside the mount root.
- **Writes go through a temp file + atomic replacement** (`ATOMIC_MOVE`, falling back to `REPLACE_EXISTING`):
  even if a symlink is planted at the target, it is replaced as the link itself rather than followed, so there is no TOCTOU window
  between check and write.
- `list` lists only regular files and skips symlinks pointing outside the root (consistent with `get`'s rejection semantics,
  avoiding leaking external file names).
- `delete` emits `ObjectDeletedEvent` only on a real removal (symlink or regular file);
  a missing key is a no-op and **emits no ghost delete event**.
- `presignedUrl` has no signing semantics locally and returns empty; `put` returns an etag (SHA-256) and
  **does not return a versionId**: the interface has no version-addressed read/delete, so a version identifier could only be a value the caller
  prints and throws away — version capability is scheduled together with the operations that would use it.
- The domain events `ObjectStoredEvent` / `ObjectDeletedEvent` are published via `EventBus`.
  Decoupled from the main chain (discovery/rpc/observe/resilience), it can be installed independently.

### 5.9 Service-to-Service Security

Scope boundary: **service-to-service cloud-native security**, not an application-level login/session framework.

- **Transport encryption (mTLS)**: `TransportSecurity` (`sslContext()`, default constant
  `NONE`, i.e. development-mode plaintext). Core uses the JDK `SSLContext` + file-based
  keystore/truststore (PKCS12/JKS; keys in `docs/configuration.md`):
  `RpcModule` resolves by key — empty keystore → `NONE`, otherwise it builds
  `TransportSecurityDefault`. **The same outbound security surface serves both RPC (`https://`)
  and the event mesh (`wss://`)**: the mesh is not a second configuration surface. The capability is decided by runtime configuration,
  and a static marker cannot express a conditional capability, so core does not pre-provision `@Mtls` / `@None`; a custom
  implementation (Vault dynamic certificates, etc.) can simply bind.
- **Identity propagation**: `PrincipalContext` is injected/extracted via `InvocationContext` + `AuthPropagator`
  as `x-principal` / `x-principal-roles`, on the same pipeline as `traceparent`.
  What is propagated is the **verified identity**, not raw credentials.
  **Trust boundary**: inbound extraction trusts the propagation headers and is **off by default**
  (`freeway.cloud.auth.extract.enabled=false`), enabled explicitly only inside a trusted service network;
  outbound injection is always on (it forwards only the locally verified identity). Production token validation (JWT/Opaque) is
  the responsibility of a custom security module; core does not include it.
- **Mesh admission**: `freeway.cloud.event.token` is compared in constant time, applied to hello.
  When a token is configured but dialing uses plaintext `ws://`, it **warns at startup rather than refusing** — terminating TLS in a sidecar
  is a normal deployment, and the token is then protected by the mesh's own mTLS.
- **Server-side authorization** falls back to the application's own Routes/Filters: the framework does not include
  built-in authentication for `/rpc/*`, `/metrics`, `/health/*`; a deployment should treat them as internal endpoints.
- **Secret source**: the `SecretStore` of §5.7 (`order()=15`).

## 6. Configuration Keys Overview

**Key semantics, defaults and examples are always in `docs/configuration.md` §5 (Cloud)**. This section
only fixes how keys are read and the three shapes, so the same list does not drift in two places.

- **When keys are read**: cloud keys are resolved at startup (each hook's `start`) and never re-read afterward.
  Values that genuinely need hot reload at runtime go through boot's configuration cascade; the secrets file is the only exception
  (§5.7).
- **Shape one: behaviour keys** — ordinary values (timeouts, thresholds, addresses, allowlists, tokens). They have defaults,
  with the `*_DEFAULT` constants in `CloudModule.ConfigKeys` as the single source, shared by the module configuration layer and the
  library-level fallback. **Boolean keys always go through the container `Coercer`** (accepting `true/false`,
  `yes/no`, `on/off`, `1/0`); an unrecognized value fails at startup and names the key and value — silently becoming
  `false` would quietly turn off switches like `auth.extract.enabled`.
- **Special case of shape one: `auto`** — used only when the value must be derived and cannot be written down, and the derived result must be
  visible in the startup log (`registry.service-scheme` / `service-host` /
  `shutdown-drain`, §5.1). Adding `auto` to a constant default would merely be a second way of saying the same rule;
  it is not done.
- **Shape two: declaration keys** — `freeway.cloud.secret/discovery/registry/storage.type`.
  They **do not select an implementation**: the framework does no class-name reflective loading or classpath scanning; what actually
  selects an implementation is a `.primary()` binding, the `@Local` marker and which adapter module is installed. While the local
  implementation is still in use and a type key is non-empty and not `local`, `BackendTypeGuard` warns once at startup.
- **Shape three: presence switches** — a few keys decide behaviour by whether they appear, most typically
  `freeway.cloud.event.enabled`: an explicit `true`/`false` wins; when unset, "`event.peers`
  configured" means enabled; with neither set the module is inert.
- `freeway.cloud.rpc.resilience = auto | off` is the only **aggregate key**: `off` is the
  escape hatch, and an illegal value fails at startup validation.
- `freeway.cloud.secret.file` / `secret.keys` accept only `-D` (§5.7).
- **The decision surface: only five keys require a deployment decision** — `freeway.cloud.event.peers` and
  `freeway.cloud.event.token` (both are needed for a multi-node mesh; configuring peers alone is the switch),
  `freeway.cloud.rpc.tls.key-store` and its passphrase (mTLS, presence-driven),
  `freeway.cloud.secret.file` / `secret.keys` (only `-D`, see above). Every other key ships with a recommended value.
  So the mental load of this surface is not the number of keys but **the number of decisions: five**; the advanced tier
  (timeouts/thresholds/backoff) has already been compressed by its aggregate keys (`rpc.resilience` /
  `event.enabled`), and a second aggregate switch would be a second way of saying the same thing.

## 7. Explicit Non-Goals

Exclusions and rationale (the first four are **structural** and will not change just because they might be needed later):

- **Transparent local-first invocation** ("if nobody local answers, quietly go over the network"): whether a call is local or remote is written in the
  composition (bindings). An earlier version had a `CallBus` (a topic-addressed call bus + automatic
  cross-process on a local miss) with its companion `CallBridge` / `RemoteProxyFactory.localFirst()`, deleted wholesale because it
  inflated the concept count and hid "where the call goes" at runtime; `freeway-ioc` has no bridging seam prepared for it either.
  **The broadcast domain's seam of the same kind was dismantled in 1.5.5 by the same precedent**: the local
  `EventBus.publish` once quietly left the process based on which modules were installed (`EventSink` fan-out bridge + `publishInbound`
  inbound funnel + the `@Topic` type-routing annotation — the violator of this section's "no routing annotations" entry);
  now the bus knows no transport, cloud-native broadcast goes through the explicit `MeshEventBus`, persistent streams go through `KafkaEvents`,
  and there is no event bridging seam between `freeway-ioc` and `freeway-cloud`.
- **Annotation routing / `@CloudClient` transparent beans / `CloudExporter` auto-export / `@CloudEvent`
  annotated entities**: exports, invocations and event routing are all explicit declarations; no routing annotations are attached to business types,
  and there is no annotation-driven auto-registration.
- **Binary/multiplexed protocols (gRPC bridge)**: JSON over HTTP/1.1|2 is enough; do it when profile
  data says it is not.
- **Business data entering `InvocationContext`, instance attributes entering `@Marker`**: the former holds only three
  kinds of KV — infrastructure, security and application — and the latter is a static type; runtime attributes go through
  `ServiceInstance.metadata`.
- **Distributed transactions / saga compensation / distributed locks**: the impossibility of inlining a transaction is a fact that cannot be eliminated,
  and the framework does not cover for it. Writes needing eventual consistency go through `EventBus` (outbox), not RPC.
- **Discovery by capability** ("who exported mapping X"): the address is a `serviceId`, named by the
  consumer in the composition; extending the discovery surface would require every adapter to implement a new query contract, and the benefit does not justify the cost.
- **Runtime reconstruction of generic return types**: a single level of `Class<T>` covers all current internal use cases;
  use a sealed result type when complex generics are needed.
- **Facts pushed back into the invocation channel**: the syntax gate between fact and question is isolated at the source.
- **Classpath-scanning auto-registration** (except `ServiceLoader`, which can be turned off).
- **Third-party SDKs entering core**: any implementation that needs a third-party client is an ext adapter.
- **Ingress traffic governance / cluster scheduling / certificate platform**: K8s Service, Ingress, gateways,
  Serverless and distributed scheduling are infrastructure and are not core capabilities.
- **The event mesh carrying MQ semantics**: cross-node is at-most-once — no ACK/re-delivery/dead letter/delayed
  delivery/`qos` pass-through/explicit event transaction batches, and the frame carries no bit the framework cannot honor. For reliable delivery use MQ
  (ext's `freeway-mq-kafka`); "buffered with the DB transaction" is already covered by `Defer`.
- **A global membership view inside the mesh**: membership is "connected is a fact"; no distributed registry or consistent
  membership table; subscription matching is prefix-only, with no implementation of the CNCF subscription expression spec — prefixes are enough
  and that is where it stops.
- **webhook / HTTP outbound channels**: integration with third-party systems is done by the application layer subscribing and then going outbound itself;
  inter-node delivery does not use HTTP — it has no connection semantics, and therefore no "the peer left" fact.
- **Version-addressed object storage (versionId)**: the interface has no version read/delete, so a version identifier could only be
  a value the caller prints and throws away (see §5.8).

## 8. Roadmap

Two tiers: **not delivered (unscheduled)** means "core already provides the seam, waiting for real demand to drive it";
**evaluated, awaiting scheduling** is closure core owes itself. Each item states why it is worth doing and the minimal
closing action — not doing it is fine, but know what is owed.

### 8.1 Not Delivered (Unscheduled)

All belong to ext (a separate repository); the integration protocol is §3.3: bind the corresponding interface and `.primary()`,
bring your own backend marker and type-key consumption when needed, and it must be a complete implementation, not a placeholder.

| Capability | Seam | Minimal closing action |
|---|---|---|
| Registry / discovery backend (Nacos, K8s endpoints are adapted by ext; **freeway's own backend is already delivered in the main body**: a node = a `RegistryServerModule` composition, the client = `FleetRegistryModule`, write fan-out / discovery union, zero third-party dependencies; **Consul is in ext**) | `ServiceRegistry` + `ServiceDiscovery` | One adapter module + real-backend tests; `renew` already returns boolean, so the self-healing contract is ready |
| Secrets backend (Vault, KMS) | `SecretStore` | The adapter owns leases/TTLs (the SPI has no `reload()`); file rotation semantics are settled |
| Object storage backend (S3-compatible) | `ObjectStorage` | Schedule version capability together with version-addressed read/delete; do not deliver only half |
| Metrics export (OTel/Prometheus push) | Do not install `ObserveModule`; primary-bind `Metrics` + provide your own export route | Design the label dimension of §8.2 along with it |
| trace export (OTLP) | Same as above (`Tracer`) | `tracestate` is already passed through, `TraceContext` is enough |
| Kafka persistent stream plane | `KafkaEvents` (`freeway-mq-kafka` is already in ext) | Reliable delivery goes through it, not the mesh |

### 8.2 Evaluated, Awaiting Scheduling

| Item | Why it is worth doing | Minimal closing action |
|---|---|---|
| Metric dimensions (labels) + per-service attribution | There are 4 unlabeled series today; a failure cannot answer "which service" | Add an optional tags parameter to `Metrics` (without breaking the existing call surface); tag `cloud.rpc.*` with `service` first |
| retry budget | Retries are per-call only; a retry storm adds pressure when a dependency fails broadly | Add a per-service token budget to `Retryer`; over budget fails immediately instead of backing off further |
| zone / canary default policy | weight is already read by `LoadBalancerDefault`; zone/canary remain metadata only, "labeled but useless" | Prefer by zone when the metadata is present, keep weighted round robin when it is absent |
| ~~Mesh drop-count metrics~~ **Delivered (1.5.7)**: `/metrics` exposes the `cloud_event.*` series (published/frames_queued/frames_dropped/send_failures/inbound_delivered/dropped_legacy/dropped_no_subscriber/peers); the label dimension is still pending |
| ~~Mesh application-level heartbeat~~ **Delivered (1.5.7)**: `WebSocketListener.onPong` is visible + mesh-side `MeshKeepalive` (`event.keepalive-ms`, one shared policy for both legs), no protocol change (reuses WS ping/pong) |
| ~~readiness published to the registry + startup probe~~ **Delivered (1.5.7)**: `ServiceRegistry.markReady` (no-op by default, so adapters need no change) + registration as `ServiceHealth.starting()` + readiness gate (it flips only when all contributors pass); the startup probe semantics are simply "not ready = not in discovery results" |
| ~~outbound certificate rotation~~ **Delivered (1.5.7)**: enabled as soon as `rpc.tls.reload-interval` is set — the `SSLContext` is built once, and `KeyManager`/`TrustManager` delegate to rotatable material (size/mtime + throttling, keeping the old material on failure). This is **not** rebuilding the context: the JDK `HttpClient` captures it at construction, so rebuilding would reopen the connection pool and redo in-flight settlement |

| Per-method resilience policy | Today the whole `RpcHttpClient` shares one set of thresholds; reads and writes should have different retry views | A per-method declaration alongside `@Idempotent` (timeout/retry limit), read by `RemoteProxyFactory` |
| Resilience annotations for local calls (`@Retry` / `@CircuitBreak` / `@RateLimit` + `Advisor` weaving) | The three SPIs currently take effect uniformly only on the `RpcHttpClient` outbound path (§5.5): a slow dependency called directly in-process has no equivalent governance, and the application has to write template code | Reuse ioc's existing `Binding.advise` / `Advisor.wrap(selector, advice)`, read method-level (falling back to class-level) annotations and reuse the same SPIs; annotations only select, thresholds remain the existing keys, with no second configuration set |

### 8.3 Delivery Criteria (Carried Over from the Implementation Plan)

Runs without any third-party cloud SDK; every core abstraction has a local default implementation; RPC, observability, resilience and health
can each work independently and their assembly is optional; `freeway-ext` is responsible only for third-party adaptation and enhancement and does not affect core's
runnability or default semantics; anything that can be closed inside core is done in core first, anything depending on a specific cloud platform or
infrastructure goes to ext, and ingress traffic governance / cluster scheduling / certificate platform are out of core; every capability has
regression test coverage. Regression coverage is added along boundaries: module assembly and default-implementation resolution,
register/discover/deregister/heartbeat eviction, remote invocation success/timeout/failure/retry-with-new-instance, trace propagation, metrics output,
circuit breaking/rate limiting/retry, security propagation, live/ready probes, object storage read/write.

## 9. Revision History

| Date | Change |
|---|---|
| 2026-08-19 | Overall design finalized (Phases 0–7 landed): core objects + local defaults, service discovery and remote invocation, observability, resilience, security, object storage; supersedes the earlier design-A/design-B parallel proposals |
| 2026-08-27 | RPC v1: `RemoteCaller` + `RemoteInvocationException` + `RemoteProxyFactory` (local-first/remote-only dual mode) + `RpcEndpoint.of(...)`; `callAsync` and per-call timeout wired end to end |
| 2026-08-28/29 | Event mesh revision: in-frame timestamp `fwtimes` removed (CloudEvents `time` used instead); deduplication moved from an "interceptor" to the single funnel `EventBus.publishInbound` |
| 2026-09-03 | Configuration center deleted wholesale (`CloudConfig` / `ConfigRef` / `ConfigSubscription` / the `config/` package): configuration cascade and hot reload belong uniformly to boot (`AppConfigDefault` / `freeway.config.file`), and cloud contributes only `SecretSymbolSource` (order 15) |
| 2026-09-04 | Documentation closure: default implementations in the feature package (not `internal/`), the single-argument `choose(instances)` signature, health endpoint paths fixed and non-configurable, and the core/ext boundary rewritten around "ext currently contains no cloud adapter" |
| 2026-09-12 | RPC v2: export became **declarative** (`RpcExport` data contribution + one `/rpc/{mapping}/{method}` wildcard route + a `.before(HTTP_SERVER)` assembly hook), `RemoteCaller` is bound by the framework, and standalone assembly uses `RpcEndpoint.route(...)`; on the same day v3 designed a `CallBridge` to make `CallBus` automatically go cross-process on a local miss, **withdrawn before implementation in the finalized design** |
| 2026-09-12 | RPC v4: **`CallBus` deleted** along with its satellite types; `RpcEndpoint` calls the container-resolved handler directly (the `RpcTarget` method table, with overloads failing at startup); `RemoteProxyFactory` converged to remote-only; the document was renamed from `freeway-remote-callbus-design.md` |
| 2026-09-12 | Audit round: failures gained `kind()` (13 values); `ServiceRegistry.renew` returns boolean and self-heals; readiness states only what the framework verifies (3 failures mark unhealthy, unregistered reports `not registered`, shutdown is unhealthy); secrets rotate on size + full-precision mtime; mesh outbound TLS shares one security surface with RPC, with a startup warning for plaintext + token; shutdown gained `registry.shutdown-drain` / `rpc.shutdown-grace` and WS `1001 going away`; contribution ids unified as `freeway.cloud.*`; `@Local` narrowed to parameters/fields and extended to `Metrics` / `TransportSecurity`; added the optional-input rule and the `Wiring` compatibility constructor |
| 2026-09-13 | Module composition switched to a tree: `ModuleEx.subModules()` and `ModuleTree` deleted, composition became the `ModuleNode` value built by the entry point (held by the container, `Container.moduleTree()`); the umbrella module `CloudModule` became the fragment factory `CloudModules.standard()`, from which an application can replace or extract any module |
| 2026-09-14 | Structural nodes changed from "named groups" to "bundle modules": the `group` concept deleted, and `ModuleEx`'s bundle class declares submodules with `@SubModule` (expanded when building the tree, not a callback); the `CloudModules.standard()` factory deleted, restored to `CloudModule` + `@SubModule`, and submodules can still be placed individually for a subset (no exclusion API) |
| 2026-09-12 | Configuration surface audit: `registry.service-scheme` / `service-host` / `shutdown-drain` defaulted to `auto` (following HTTP server TLS, deriving a routable address, and answered by the registry backend respectively), and the mesh dial scheme reads the resolved instance endpoint; boolean keys uniformly parsed by `Coercer` (garbage values fail at startup, no more silent `false`); a duplicate key in an override file warns at startup and names both files; added `HttpServer.isSecure()` and `ServiceRegistry.drainWindow()` |
| 2026-09-12 | Document merge: this document supersedes `freeway-cloud-unified-design.md` / `freeway-cloud-events-design.md` / `freeway-cloud-rpc-design.md` / `freeway-cloud-implementation-plan.md`; the still-valid exclusions and capability boundaries of the four documents (no application-level heartbeat, MQ semantics, global membership view, webhook outbound, `@CloudEvent` annotated entities) and the core follow-ups (including `Advisor` weaving and mesh heartbeat) are merged into §5 / §7 / §8; the configuration key list moved out to an index into `docs/configuration.md` |
| 2026-09-14 | Compatibility is no longer a goal: the optional-input rule in `AGENTS.md` removed "record the old-arity delegating constructor kept as an adapter assembly point" (written in the previous 09-12 round) — adding a component directly changes the canonical constructor shape, and the compile error is the migration path; accordingly the 9-arg compatibility constructor of `RpcHttpClientDefault.Wiring` was deleted (ext `RemoteRpcContract` changed to pass 10 args in the same batch) along with the transitional read of `freeway-log.properties` (the old name is only detected and warned about, not loaded) |
| 2026-09-24 | Broadcast domain plane separation: dismantled the `EventSink` bridge — `EventBridge`/`EventBridgePolicy`/`EventBusInbound`/`@Topic`/`Keyed` deleted, and the four keys `subscriptions`/`allowed-types`/`allowed-topics`/`dedup.*` retired; `MeshEventBus` is an explicit facade (`publish` addressed by topic, Defer commit coupling, subscriptions declaration-only and the subscription table = the inbound gate); ext kafka switched to the independent plane `KafkaEvents`; in-flight CLASS frames are parsed, dropped by reason and counted, with the wire format unchanged |
| 2026-09-28 | Composition surface convergence: `ModuleNode` moved into `ioc.internal` as package-private (`Container.moduleTree()` deleted, so the public tree value recorded in historical rows is no longer valid), and application naming goes through `FreewayApp.name` / `Freeway.create(String, …)` |
