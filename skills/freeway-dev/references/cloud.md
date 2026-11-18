# Cloud Reference (`freeway-cloud`)

The cloud-native layer. One rule governs everything here: **single-machine and
cloud-native are the same code, placed differently** — behavior is composed by
which modules are installed, never by classpath presence (there is no scanning).

## Modules & Composition

- `CloudModule` — the bundle; `@SubModule` declares 8 standard modules:
  context propagation, secrets, **discovery/registry**, remote invocation (RPC),
  observability, resilience, health, object storage.
- Every sub-module is an ordinary module: place a subset directly
  (`new RpcModule()` for RPC only). No exclusion list exists.
- `EventMeshModule` — deliberately NOT in the bundle: it opens a listener and
  dials peers, so the application places and enters it explicitly.

## Service Registry & Discovery — one seam, three backends

| Backend | Place | Infrastructure |
|---|---|---|
| Static | `CloudModule` + `StaticModule` | none — fixed endpoints or platform VIP |
| Freeway-native fleet | `CloudModule` + `MemberModule` + `FleetModule` | 1–N deployable registry nodes |
| Consul | `CloudModule` + `MemberModule` + ext `ConsulModule` | Consul cluster |

Switching = swapping the module and its keys; application code (`@Inject
ServiceDiscovery`, `RemoteProxyFactory`, `RpcExport`) never changes.

For static discovery, either pass a typed map with `StaticModule.of(...)` or use
configuration:

```properties
freeway.cloud.discovery.static.services=catalog=http://catalog.default.svc.cluster.local:8080,orders=https://orders.internal:443
```

`new StaticModule()` reads that key once at startup. Each entry is
`serviceId=scheme://host:port[/basePath]`; repeat a service id for multiple
instances. Explicit ports are required; userinfo, query and fragment are
rejected. The instance id is the normalized endpoint URI. There is no
registration, heartbeat, readiness, eviction or hot reload. The configuration
form carries no weight/zone/canary metadata; use `StaticModule.of(...)` for those.

### Fleet client and server (freeway-ext)

`freeway-cloud` owns the backend-neutral membership lifecycle and contracts.
The Fleet transport lives in **freeway-ext**: `FleetModule` is the client
discovery/registry adapter, and `FleetServerModule` is the deployable server.

Application side:

```java
FreewayApp.run(
    new HttpModule(),
    new CloudModule(),
    new MemberModule(),   // register, renew, readiness, drain
    new FleetModule());   // Fleet client + client-side load balancer
```

Server side:

```java
FreewayApp.run(Launch.of(args), new HttpModule(), new FleetServerModule());
```

```properties
freeway.http.server.port=8060
freeway.fleet.token=s3cret
```

The lifecycle is shared: `MemberHook` registers on start, renews, re-registers
after a lost lease and deregisters on stop; `MemberReadiness` reports the state
observed by the core. The Fleet adapter supplies transport only.

Fleet client keys:

```properties
freeway.fleet.nodes=http://n1:8060,http://n2:8060   # required when FleetModule is active
freeway.fleet.token=s3cret
freeway.fleet.timeout-ms=3000
freeway.fleet.cache-ttl=5s
```

The server accepts `freeway.fleet.token-previous` during online token rotation
and reads its store from `freeway.fleet.db.*`. The wire vocabulary and shared
token keys are owned by `FleetProtocol` / `FleetKeys`; client-only node,
timeout and cache keys live in `FleetModule.ConfigKeys`. See the ext Fleet
documentation for deployment details.

**Readiness.** A warming application registers as
`ServiceHealth.starting()` — present in the registry, absent from discovery
results — and flips to ready only when every `CloudHealthContributor` passes.
No contributors means ready on registration. `ReadinessAwareRegistry` is the
optional backend capability; this is not a boolean flag on every registry.

**Caching** is the discovery adapter's own policy. A network-backed adapter may
wrap itself with `CachingDiscovery.around(delegate, ttlPolicy, metrics)`; static and
pushed discovery do not need that wrapper.

## Remote Invocation

Two cut points, one interface:

```java
// provider: declare the export
binder.bind(UserHandlers.class);
binder.contribute(RpcExport.class).add(RpcExport.of("user", UserHandlers.class));

// consumer: typed client over discovery + load balancing
binder.bind(UserApi.class).to(container -> RemoteProxyFactory
    .of(container.get(RemoteCaller.class))
    .withServiceId("user-service").withMapping("user")
    .build(UserApi.class));
```

Business code always stays `@Inject UserApi`: whether the implementation is
local or remote is a decision of the composition root, never of the call site.

- served route: `POST /rpc/{mapping}/{method}`; only declared mappings are
  reachable; duplicate exports, exporting an unbound type, and overloaded
  handler methods fail startup
- error model: connect-refused → retryable, outcome known; timeout / mid-flight
  / 5xx → retryable, **outcome unknown** (replay only for idempotent calls —
  mark `@Idempotent`); remote handler exception → not retryable
- calls are POST and travel outside any local transaction — post-commit side
  effects belong on the EventBus (Defer), not on RPC
- per-method bounds: `@RemotePolicy(timeoutMillis, maxAttempts)` on the method
  or interface; `maxAttempts` is total attempts (1 = no retry, 0 = inherit the
  client Retryer); timeout 0 inherits the factory. It selects bounds only, not
  a second resilience policy.

- `LoadBalancerDefault` prefers this process's zone when
  `freeway.cloud.discovery.local-zone` is set and instances declare `zone`;
  no local candidate falls back to the whole list. `canary` is a weight/custom
  policy input, not an automatic traffic switch.

## Event Mesh (`EventMeshModule`)

Presence-driven activation:

```properties
freeway.cloud.event.peers=10.0.0.11:8080        # non-empty list IS the activation
freeway.cloud.event.enabled=true                # or explicit, e.g. discovery-fed mesh
freeway.cloud.event.discovery-feed-ms=10000     # peers polled from the bound discovery
freeway.cloud.event.token=mesh-secret           # production MUST set it; all nodes must agree (WS 1008 on mismatch)
```

- publish: `mesh.publish("order.created", payload)` — topic string is the wire
  key (class names never travel); Defer-aware (buffered until commit, rollback
  discards)
- receive: `contribute(MeshSubscription.class).add("orders",
  MeshSubscription.of("order.", OrderCreated.class, handler))` — the
  declaration IS the allowlist; unmatched topics are dropped without
  deserialization
- `discovery-feed-ms`: poll the bound `ServiceDiscovery` for instances of this
  node's own service and hand the whole snapshot to the mesh (self excluded;
  failed poll keeps the previous set; empty snapshot clears it; static `peers`
  survive — union semantics)
- delivery is at-most-once with no redelivery, real-time; durable per-key
  ordering is the Kafka plane. Local `EventBus` is untouched; mirror a remote
  fact into the local bus explicitly inside the handler
- `freeway.cloud.event.keepalive-ms` (default off): each connection pings its
  peer and requires the pong before the next tick, catching half-open links a
  NAT/LB drops silently; a missed probe closes the connection and the existing
  reconnect/unregister path takes over. No protocol change — it reuses WebSocket
  ping/pong (`WebSocketListener.onPong` is how the mesh sees the answer)
- observability: `stats()` — `published / framesQueued / framesDropped /
  sendFailures / inboundDelivered / droppedLegacy / droppedNoSubscriber`; the
  same counters plus the peer count are scraped as `cloud_event_*` gauges on
  `/metrics` when an observability module is installed (registered even with no
  peers: "no peers" is a fact)

## TLS Material Rotation

```properties
freeway.cloud.rpc.tls.reload-interval=1s     # outbound: client certs + trust anchors
freeway.http.ssl.reload-interval=5s          # inbound: the certificate the server presents
```

Outbound rotation cannot rebuild the `HttpClient` (the JDK captures its
`SSLContext` at construction, and a rebuilt client would reopen the connection
pool and strand in-flight calls), so it keeps one context and delegates to
rotating material — the key/trust managers re-read the files per handshake
(throttled, keep-on-failure). Inbound rotation is the engine reading the current
context on each handshake, so the server presents the new certificate the same
way. Point both at short-lived certificates and rotation no longer needs a
restart.

## Health, Observability, Resilience, Secrets, Storage

- health: `contribute(CloudHealthContributor.class)`; `/health/ready` reports
  what the heartbeat verified (registered / draining / consecutive failures),
  never a constant; shutdown reports `draining`
- observability: `@Inject Metrics` / `Tracer` / `MetricsSnapshot`; every request
  runs in a server span, `traceparent`/`tracestate` propagate; span names use
  `METHOD + route template` (`GET /users/{id}`, `GET unmatched`); HTTP/RPC
  durations are fixed-bucket histograms, and labels are bounded dimensions
  (`method`/`route`/`service`). Failures classify via `Failure`; see
  `docs/reference/metrics.md` and `docs/reference/failures.md`.
- resilience: defaults bound and used by the RPC client; `@Inject Retryer` /
  `RetryBudget` / `CircuitBreaker` / `RateLimiter`, `.primary()` to replace.
  The retry budget is per service: successes replenish, retries consume, and a
  drained budget fails the original failure immediately instead of retrying.
- secrets: `@Inject SecretStore`; file reread on size/mtime change; keys also
  symbol-resolvable (`@Symbol("db.password")`)
- storage: `@Inject ObjectStorage`; `ObjectStoredEvent`/`ObjectDeletedEvent` on
  the bus

## Semantics Worth Remembering

- cloud keys are read once when the owning component is built (exceptions:
  per-call budgets, the secret file reread on size/mtime change, and the registry
  token, re-read per request so rotation needs no restart) — changing any other
  key needs a restart
- shutdown order: registry hook first (draining → deregister → drain window
  `freeway.cloud.member.shutdown-drain`, default `auto` answered by the
  backend), then the HTTP server stops accepting; in-flight outbound calls get
  `freeway.cloud.rpc.shutdown-grace` (5s); mesh peers receive `1001 going away`
- `freeway.cloud.*.type` declares intent, never selects an implementation — the
  `.primary()` binding is the selector; a mismatched declaration warns once
  (`BackendTypeGuard`)

## Full Guides

- Development guide: `docs/modules/cloud.md`
- Design rationale: `docs/design/cloud.md`
- Every key: `docs/configuration.md`
