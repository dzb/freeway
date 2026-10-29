# Cloud Reference (`freeway-cloud`)

The cloud-native layer. One rule governs everything here: **single-machine and
cloud-native are the same code, placed differently** — behavior is composed by
which modules are installed, never by classpath presence (there is no scanning).

## Modules & Composition

- `CloudModule` — the bundle; `@SubModule` declares 8 standard modules:
  context propagation, secrets, **discovery/registry**, remote invocation (RPC),
  observability, resilience, health, object storage.
- Every sub-module is an ordinary module: place a subset directly
  (`new CloudRpcModule()` for RPC only). No exclusion list exists.
- `CloudEventModule` — deliberately NOT in the bundle: it opens a listener and
  dials peers, so the application places and enters it explicitly.

## Service Registry & Discovery — one seam, three backends

| Backend | Place | Infrastructure |
|---|---|---|
| In-process (default) | `CloudModule` or `CloudDiscoveryModule` | none — single instance / static topology |
| Freeway-native fleet | `CloudModule` + `RegistryFleetModule` | 1–N deployable registry nodes |
| Consul | `CloudModule` + ext `ConsulModule` | Consul cluster |

Switching = swapping the module and its keys; application code (`@Inject
ServiceDiscovery`, `RemoteProxyFactory`, `RpcExport`) never changes.

### The registry node (deployable)

A normal freeway application:

```java
FreewayApp.run(Launch.of(args), new HttpModule(),
    new CloudDiscoveryModule(), new RegistryServerModule());
```

```properties
freeway.cloud.server-port=8060
freeway.cloud.registry.token=s3cret      # X-Registry-Token required on reads and writes
```

Protocol and credentials (both are versioned/rotatable on a live node):

```properties
freeway.cloud.registry.token=s3cret            # current shared secret
freeway.cloud.registry.token-previous=old      # accepted during rotation, then cleared
```

- wire protocol: clients send `X-Registry-Proto: 1`; an unsupported value is
  answered `426` (with the supported version), never a 404. A node always
  accepts older protocols, so nodes upgrade before clients.
- `GET /registry` is readable without credentials: it announces the protocol,
  the four routes and the auth posture — a mismatch is diagnosable before auth.
- `POST …/instances/{id}/ready` (body `{"ready":true|false}` or `?ready=`)
  flips readiness immediately; the same bit also rides the 10s heartbeat, so an
  older node is a delay, not a divergence.

Run 2+ nodes for availability. Semantics by design: **no persistence** (the
table is rebuildable — a restarted node is repopulated by the next heartbeat of
every instance, which re-asserts itself on a 404), **no consensus replication**
(clients fan writes out to all nodes and union reads — any node alive keeps the
fleet serving; nodes are independent peers).

### The fleet client (`RegistryFleetModule`)

```properties
freeway.cloud.registry.nodes=http://n1:8060,http://n2:8060   # required
freeway.cloud.registry.token=s3cret                          # matches the nodes
freeway.cloud.registry.timeout-ms=3000
```

- register: fan-out to every node; any node refusing fails startup loudly
- renew (every 10s): fan-out; a 404 re-asserts the instance this client
  registered (cached, never guessed) on that node
- instances: union across alive nodes by instance id; all nodes down throws
- unregister: best-effort fan-out during shutdown (after the drain window)

The lifecycle itself (register on start, heartbeats, lost-lease self-heal,
deregister, startup compensation) is driven by the core `RegistryLifecycleHook` —
adapters implement transport only.

**Readiness (the instance is not discoverable until it can serve).** An
application that contributes `CloudHealthContributor`s registers as
`Health.starting()`, and a gate flips it to ready only when **every** check
passes (retried every second, off the startup thread). No contributors ⇒ ready
as registered — the old behaviour, deliberately. `ServiceRegistry.markReady` is
the seam (default no-op, so Consul-style adapters are unaffected).

**Caching is the discovery implementation's own policy.** A network-backed
implementation wraps itself with `CachingServiceDiscovery.around(delegate, ttl,
metrics)` — freshness, single-flight refresh, and serving the last answer while
the backend is failing (grace = max(10×ttl, 30s)). The in-process store does
not wrap (a map read), and a pushed backend (K8s informer) answers `0`.

```properties
freeway.cloud.registry.cache-ttl=5s                  # default; 0 disables caching
freeway.cloud.registry.cache-ttl.checkout=1s         # per service, optional
freeway.cloud.registry.cache-ttl.legacy=0            # no cache for this service
```

## Remote Invocation

Two cut points, one interface:

```java
// provider: declare the export
binder.bind(UserHandlers.class);
binder.contribute(RpcExport.class).add(RpcExport.of("user", UserHandlers.class));

// consumer: typed client over discovery + load balancing
binder.bind(UserApi.class).to(container -> RemoteProxyFactory
    .of(container.get(RemoteCaller.class))
    .serviceId("user-service").mapping("user")
    .build(UserApi.class));
```

- served route: `POST /rpc/{mapping}/{method}`; only declared mappings are
  reachable; duplicate exports, exporting an unbound type, and overloaded
  handler methods fail startup
- error model: connect-refused → retryable, outcome known; timeout / mid-flight
  / 5xx → retryable, **outcome unknown** (replay only for idempotent calls —
  mark `@Idempotent`); remote handler exception → not retryable
- calls are POST and travel outside any local transaction — post-commit side
  effects belong on the EventBus (Defer), not on RPC

## Event Mesh (`CloudEventModule`)

Presence-driven activation:

```properties
freeway.cloud.event.peers=10.0.0.11:8080        # non-empty list IS the activation
freeway.cloud.event.enabled=true                # or explicit, e.g. discovery-fed mesh
freeway.cloud.event.discovery-feed-ms=10000     # peers polled from the bound discovery
freeway.cloud.event.token=mesh-secret           # production MUST set it (WS 1008 on mismatch)
```

- publish: `mesh.publish("order.created", payload)` — topic string is the wire
  key (class names never travel); Defer-aware (buffered until commit, rollback
  discards)
- receive: `contribute(CloudEventSubscription.class).add("orders",
  CloudEventSubscription.of("order.", OrderCreated.class, handler))` — the
  declaration IS the allowlist; unmatched topics are dropped without
  deserialization
- `discovery-feed-ms`: poll the bound `ServiceDiscovery` for instances of this
  node's own service and hand the whole snapshot to the mesh (self excluded;
  failed poll keeps the previous set; empty snapshot clears it; static `peers`
  survive — union semantics)
- delivery is at-most-once, real-time; durable per-key ordering is the Kafka
  plane. Local `EventBus` is untouched; mirror a remote fact into the local bus
  explicitly inside the handler
- `keepalive-ms` (default off): each connection pings its peer and requires the
  pong before the next tick, catching half-open links a NAT/LB drops silently;
  a missed probe closes the connection and the existing reconnect/unregister
  path takes over. No protocol change — it reuses WebSocket ping/pong
  (`WebSocketListener.onPong` is how the mesh sees the answer)
- observability: `stats()` — `published / framesQueued / framesDropped /
  sendFailures / droppedNoSubscriber / droppedLegacy`; the same counters are
  scraped as `cloud_event_*` gauges on `/metrics` when an observability module
  is installed (registered even with no peers: "no peers" is a fact)

## TLS Material Rotation

```properties
freeway.cloud.rpc.tls.reload-interval=1s     # outbound: client certs + trust anchors
freeway.http.ssl.reload-interval=5s          # inbound: the certificate the server presents
```

Outbound rotation cannot rebuild the `HttpClient` (the JDK captures its
`SSLContext` at construction), so it keeps one context and delegates to
rotating material — the key/trust managers re-read the files per handshake
(throttled, keep-on-failure). Point it at short-lived certificates and the
rotation no longer needs a restart.

## Health, Observability, Resilience, Secrets, Storage

- health: `contribute(CloudHealthContributor.class)`; `/health/ready` reports
  what the heartbeat verified (registered / draining / consecutive failures),
  never a constant; shutdown reports `draining`
- observability: `@Inject Metrics` / `Tracer` / `MetricsSnapshot`; every request
  runs in a server span, `traceparent`/`tracestate` propagate
- resilience: defaults bound and used by the RPC client; `@Inject Retryer` /
  `CircuitBreaker` / `RateLimiter`, `.primary()` to replace
- secrets: `@Inject SecretStore`; file reread on size/mtime change; keys also
  symbol-resolvable (`@Symbol("db.password")`)
- storage: `@Inject ObjectStorage`; `ObjectStoredEvent`/`ObjectDeletedEvent` on
  the bus

## Semantics Worth Remembering

- cloud keys are read once when the owning component is built (exceptions:
  per-call values, the secret file reread) — changing a key needs a restart
- shutdown order: registry hook first (draining → deregister → drain window
  `freeway.cloud.registry.shutdown-drain`, default `auto` answered by the
  backend), then the HTTP server stops accepting; in-flight outbound calls get
  `freeway.cloud.rpc.shutdown-grace` (5s); mesh peers receive `1001 going away`
- `freeway.cloud.*.type` declares intent, never selects an implementation — the
  `.primary()` binding is the selector; a mismatched declaration warns once
  (`BackendTypeGuard`)

## Full Guides

- Development guide: `docs/freeway-cloud.md`
- Design rationale: `docs/freeway-cloud-design.md`
- Every key: `docs/freeway-config.md`
