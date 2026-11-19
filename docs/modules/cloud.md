# freeway-cloud Development Guide

This document is an overview of how to develop with `freeway-cloud`: how an application moves from single-node to cloud-native, how registry and discovery, remote invocation,
and the event mesh are each used and deployed, and where the boundaries are. For design trade-offs see
[cloud design](../design/cloud.md); for all configuration keys see
[configuration.md](../configuration.md).

The core promise is one sentence: **single-node and cloud-native are two placements of the same code**. Business code writes no registration, no addressing,
and no retries; "having neighbors" is expressed by the composition root (which modules are placed, which keys are configured).

## Modules and Composition

`CloudModule` is the cloud capability bundle: it declares 7 standard modules with `@SubModule` — context propagation, secrets,
remote invocation, observability, resilience, health, and object storage. Discovery and service membership are deliberately separate:
for fixed endpoints place `StaticModule`; for dynamic membership place `MemberModule` plus `FleetModule` or an ext adapter. Placing the bundle installs these seven;
each submodule is also an ordinary module, so **for a subset, place the subset directly** — there is no exclusion list:

```java
FreewayApp.run(
    new OrderModule(),
    CloudModule.class);                                // the whole bundle

FreewayApp.run(
    new OrderModule(),
    new RpcModule());                             // just remote invocation
```

`EventMeshModule` is deliberately **not** in the bundle: it opens its own network listener and dials out, so the application
places it explicitly and enters it explicitly (`@Inject MeshEventBus`, subscription contributions).

These modules are reached through exactly three layers:

| Layer | What the application writes | What it covers |
|------|-----------------------------|--------------|
| **Environment layer** | Nothing for the bundle; service membership is explicit | With `MemberModule`, automatic service membership (register, heartbeat renewal, deregistration on shutdown); `GET /health/live`, `/health/ready`, `/metrics`; every outbound call carries metrics/tracing/resilience/context propagation |
| **Declaration layer** | Data contributions: `binder.bind(Handlers.class)` + `contribute(RpcExport.class)`, `contribute(MeshSubscription.class)` (mesh), `contribute(CloudHealthContributor.class)` | What this service provides to others |
| **Consumption layer** | `@Inject ServiceDiscovery` / `ObjectStorage` / `SecretStore` / `Metrics` / `Tracer`, plus `binder.bind(Api.class).to(...)` | What this service uses from others |

## Service Registry and Discovery

The same seam (`ServiceRegistry` / `ServiceDiscovery`), four production backends; switching = changing the module + changing the keys,
with zero changes to business code:

| Backend | Placement | Infrastructure |
|---|---|---|
| Static | `CloudModule` + `StaticModule` | None — fixed endpoint list or VIP, no membership; no write side |
| DNS | `CloudModule` + `DnsModule` | A DNS server answering `A`/`AAAA` (cluster DNS/CoreDNS, internal DNS); no membership, no write side |
| Fleet | `CloudModule` + `MemberModule` + ext `FleetModule` | Self-hosted Fleet server nodes |
| Consul | `CloudModule` + `MemberModule` + ext `ConsulModule` | Consul cluster |

There is no public in-process registry module. Tests use a test-scope registry
fixture (`TestDiscoveryModule` in `freeway-cloud`'s test sources); it is not
shipped and not a production backend.

Membership metrics are recorded by `MemberHook` / `MemberState` through the
optional `Metrics` SPI: `cloud.member.register.*`, `cloud.member.renew.*`,
`cloud.member.readiness.gate.*` and `cloud.member.drain.started`. Fleet and
Consul remain transport adapters and do not own these counters.

Static discovery needs no registry process: `StaticDiscovery` (or
`StaticModule`) binds a service-id → instance-list map. Several
instances let the client-side load balancer distribute per call; a single
endpoint is the VIP form, where an external load balancer owns selection. It
never registers or renews, so it is the right mode for fixed endpoints and the
wrong mode for membership that changes without a deployment.

### Config-driven static discovery

Use `StaticModule.of(...)` when the map is already a typed value in the
composition root. Use `new StaticModule()` when the topology comes from the
configuration cascade:

```java
FreewayApp.run(new HttpModule(), new RpcModule(), new StaticModule());
```

The required key is:

```properties
freeway.cloud.discovery.static.services=catalog=http://catalog.default.svc.cluster.local:8080,orders=https://orders.internal:443
```

Each comma-separated entry has the form:

```text
serviceId=scheme://host:port[/basePath]
```

Single VIP:

```properties
freeway.cloud.discovery.static.services=orders=https://orders.internal:443
```

Several instances of one service: repeat the service ID. The client-side
`LoadBalancerDefault` receives both entries.

```properties
freeway.cloud.discovery.static.services=orders=https://orders-a.internal:443,orders=https://orders-b.internal:443
```

The same value can be supplied through JSON, environment variables or CLI:

```json
{
  "freeway": {
    "cloud": {
      "discovery": {
        "static": {
          "services": [
            "catalog=http://catalog.default.svc.cluster.local:8080",
            "orders=https://orders.internal:443"
          ]
        }
      }
    }
  }
}
```

```bash
FREEWAY_CLOUD_DISCOVERY_STATIC_SERVICES='catalog=http://catalog.default.svc.cluster.local:8080,orders=https://orders.internal:443'
```

```bash
--freeway.cloud.discovery.static.services=catalog=http://catalog.default.svc.cluster.local:8080,orders=https://orders.internal:443
```

Format and validation rules:

- `serviceId` must be non-blank and must not contain `,` or `=`
- URI must include `scheme`, `host` and an explicit `port`
- optional base path is supported, for example `http://catalog:8080/api`
- userinfo, query and fragment are rejected
- repeated service IDs add instances in input order
- the instance ID is the normalized endpoint URI
- duplicate endpoint entries for one service fail startup
- an entry with an invalid URI fails startup and names the key and entry number

`new StaticModule()` requires a non-empty `freeway.cloud.discovery.static.services`
value. If an intentionally empty static map is needed, use the explicit
programmatic form:

```java
StaticModule.of(Map.of())
```

The configuration is read once at startup. It has no membership, heartbeat,
readiness propagation, eviction or hot reload. Configuration-driven entries do
not carry weight/zone/canary metadata in this version; use
`StaticModule.of(...)` when those metadata values are required.

### DNS discovery

Use `DnsModule` when the *name* is stable and the members behind it change
without a redeploy: a Kubernetes headless Service (one `A` record per ready
pod), an internal DNS name round-robining a fleet, any DNS answer with several
addresses. Static discovery sees such a name as **one** endpoint; `DnsModule`
expands it into one instance per resolved address. It binds no
`ServiceRegistry`, so `MemberModule` does not apply — nothing is registered,
renewed or drained.

```java
FreewayApp.run(new HttpModule(), new RpcModule(), new DnsModule());
```

The keys are:

```properties
freeway.cloud.discovery.dns.services=orders=http://orders.default.svc.cluster.local:8080,catalog=http://catalog.default.svc.cluster.local:8080/api
freeway.cloud.discovery.dns.refresh=30s
freeway.cloud.discovery.dns.family=any
```

| Key | Default | Meaning |
|---|---|---|
| `...dns.services` | required | Comma-separated `serviceId=scheme://host:port[/basePath]` entries — the same grammar as static discovery |
| `...dns.refresh` | `30s` | How long one answer is reused before re-resolving; one global value for every service. `0` = resolve on every call; a negative value fails startup |
| `...dns.family` | `any` | Which resolved addresses become instances: `any`, `ipv4` or `ipv6` |

The same value can be supplied through JSON, environment variables or CLI, as
with static discovery:

```bash
FREEWAY_CLOUD_DISCOVERY_DNS_SERVICES='orders=http://orders.default.svc.cluster.local:8080'
```

Two rules differ from static discovery on purpose:

- the host must be a **DNS name**; an IP literal fails startup and names
  `StaticModule` — a literal endpoint is a static entry
- a **repeated service id** fails startup: membership comes from the answer, so
  one service maps to one name

What one answer becomes: every resolved address is one instance whose endpoint
carries the literal address (no reverse lookup), the instance ID is
`serviceId@ip:port` (the default registration identity shape, so the event mesh
can exclude itself), and answers are filtered by `family`, deduplicated and
sorted into a stable snapshot. An unknown service ID answers empty. An answer
filtered entirely by `family` is an empty (successful) membership, not a
failure.

**Freshness and eviction.** A successful refresh replaces the whole list
atomically: an instance absent from the new answer is evicted at that moment —
no per-instance grace, no drain signal, no sticky state (an address that comes
back is the same instance ID). An attempt already running keeps the endpoint it
chose; retries re-resolve, so a just-evicted instance is never hit by a retry.

**A failing refresh never empties the topology.** When re-resolution throws,
the previous answer keeps serving while it is younger than
`max(10 × refresh, 30s)`, counted as `cloud.registry.cache.stale` with a WARN
naming the service; past that grace the failure propagates (RPC surfaces
`RpcException`, the event mesh keeps its previous peer set). Before the first
successful resolution there is nothing to serve, so the first call fails. The
JDK reports NODATA and NXDOMAIN the same way (`UnknownHostException`), so "the
service scaled to zero" and "DNS is unreachable" deliberately share this path.

**No drain, no health.** DNS says who a name points to now, not who is leaving
or sick. Kubernetes mitigates the drain side: a headless Service publishes only
ready endpoints, so readiness-gated termination removes an address before the
process stops — with a propagation delay of `max(refresh, the JVM's address
cache — about 30s positive by default)` plus the DNS servers' own caches.

**Startup posture.** A grammar error fails startup like static discovery; a
resolution failure never does — a service with no ready pods, or a resolver not
up yet, is a runtime state. One warm-up attempt per configured service reports
INFO (address count) or WARN (the failure), so a typo in a name is visible in
the log without being fatal. Counters: `cloud.registry.dns.resolve.ok` /
`.fail`; cache hits and stale answers stay under `cloud.registry.cache.*`.

**TLS.** Resolved endpoints carry IP literals and the client's default endpoint
identification is untouched, so `https` to a discovered address needs a
certificate with an IP SAN. In-cluster plaintext or mesh-mTLS workloads are
unaffected.

For a typed topology, use `DnsModule.of(Map.of("orders",
ServiceEndpoint.of("http", "orders.svc", 8080)))`, or construct `DnsDiscovery`
with a custom `HostResolver` and `AddressFamily` and pass it to
`DnsModule.of(discovery)`. Not in this version: SRV records, DNS record TTLs
(the fixed `refresh` is the knob), a raw DNS client, and configuration hot
reload.

`freeway.cloud.discovery.type=static` is optional declaration only. Installing
`StaticModule` is what selects static discovery; the type key never selects an
implementation automatically.

Fleet and Consul are optional adapters in **freeway-ext**. The service-membership
lifecycle remains here (`MemberModule`); the backend module supplies
`ServiceRegistry` / `ServiceDiscovery`.

Membership behavior is lifecycle-level: declared endpoints are registered,
renewed, marked ready, and deregistered on shutdown. Backend-specific behavior
(Fleet fan-out/protocol/cache, Consul sessions) is documented with that adapter
in **freeway-ext**.

**Readiness gate**: membership publication happens before the application can serve, so a process with `CloudHealthContributor`s registers as
"not ready" first (`ServiceHealth.starting()`: in the registry, not in discovery results), and flips to
ready only after **all** contributors pass — a slow-starting connection pool/migration no longer takes traffic it cannot handle. An application with no contributors is **ready on registration** (preserving the old behavior:
no wait added to an application that exposes no signal). The flip is immediate (`POST …/instances/{id}/ready`) and is also carried on the next heartbeat,
so old nodes lag by one beat instead of diverging.

## Remote Invocation

`RpcHttpClient.callAsync` returns a future whose `cancel(true)` is meaningful:
it interrupts the retry loop (backoff included) and asks the in-flight send to
cancel, so a cancelled call stops consuming attempts and worker time. This is
separate from a deadline — a deadline fails the call with a timeout, while
cancellation means the caller no longer wants the result. The transport is
best-effort: the JDK `HttpClient` owns the connection.

Cross-process method invocation: the provider declares which mappings can be called externally, and the consumer binds a typed client to that mapping.
Dispatch goes through `RpcHttpClient`, so discovery, load balancing, retries, circuit breaking, and context propagation all apply.

**The same interface serves in two forms** — business code always does `@Inject UserApi`; whether the implementation is local or over HTTP is, after all,
only a decision of the composition root:

```java
// monolith: built-in implementation
binder.bind(UserApi.class).to(UserApiImpl.class);

// split: typed remote client
binder.bind(UserApi.class).to(container -> RemoteProxyFactory
    .of(container.get(RemoteCaller.class))
    .withServiceId("user-service")      // the provider's discovery id
    .withMapping("user")                // the call-topic prefix the provider exports
    .build(UserApi.class));
```

**Per-method bounds.** A client method (or the whole interface) may carry
`@RemotePolicy`:

```java
public interface ReportApi {
    @RemotePolicy(timeoutMillis = 30_000)                  // heavy read
    Report generate(ReportRequest request);

    @RemotePolicy(timeoutMillis = 2_000, maxAttempts = 1)  // write: never replay
    void publish(Report report);
}
```

`timeoutMillis` is the end-to-end deadline for all attempts (`0` inherits the
factory timeout); `maxAttempts` is total attempts including the first (`0`
inherits the client Retryer, `1` disables retry). Replay of ambiguous outcomes
still requires `@Idempotent`; the annotation selects bounds only, not a second
set of thresholds.

**Provider — declaring an export**:

```java
binder.bind(UserHandlers.class);                                   // container-managed
binder.contribute(RpcExport.class)
    .add(RpcExport.of("user", UserHandlers.class));               // the whole export declaration
```

An export is a **declaration**, not an assembly: the framework resolves the handler from the container and serves exactly one wildcard route
`POST /rpc/{mapping}/{method}`. An undeclared mapping is rejected before it reaches any handler; exporting the
same mapping twice, exporting a type that is not bound, a handler with overloaded methods — these three assembly errors **fail at startup** instead of
becoming a 404 later.

**Consumer — direct call or typed client**:

```java
@Inject RemoteCaller caller;

// 1. direct call
Greeting g = caller.invoke("user-service", "user", "greet", List.of("bob"), Greeting.class);

// 2. typed client (bound once in the composition root); a per-call budget is set here too
UserApi api = RemoteProxyFactory.of(caller)
    .withServiceId("user-service").withMapping("user")
    .withTimeout(Duration.ofSeconds(5))          // end-to-end, including retries
    .build(UserApi.class);
```

**Error model — two kinds of failure, two intuitions**:

| Failure | Thrown | Replayable? |
|---------|-----------|---------|
| Connection refused / connect timeout | `RpcException`, `retryable()==true`, `outcomeUnknown()==false` | Yes — the request never left this process |
| Timeout, mid-flight I/O, 5xx | `RpcException`, `retryable()==true`, `outcomeUnknown()==true` | **Idempotent operations only** — the peer may already have applied it |
| Remote handler throws | `RpcException` (4xx), cause is `RemoteInvocationException` | **No** — deterministic failure |

A retry must pass two gates: failure category + idempotency. `RpcHttpClient` derives idempotency from the verb (GET/PUT/DELETE are
replayable, POST/PATCH are not), overridable with `RpcRequest.withIdempotent(true)`; remote invocation uses POST, so
mark `@Idempotent` on a method of the consuming interface or on the whole interface to declare it safe to replay. Remote exceptions are rebuilt from class name + message only,
and the original type is not restored reflectively. A cross-process call is **not inside any local transaction** — post-commit side effects go to the EventBus (Defer
buffering), not to RPC.

## Event Mesh

The cloud-native broadcast plane: publish and receive CloudEvents 1.0 frames over a WebSocket mesh — **explicitly**. The in-process
`EventBus` stays in-process; loading the event module changes no local publishing behavior.

Configuration (`freeway.cloud.event.*`) — presence-driven activation:

```properties
# static peers: a non-empty list activates the mesh — no enabled needed
freeway.cloud.event.peers=10.0.0.11:8080,10.0.0.12:8080
# freeway.cloud.event.enabled=true            # discovery-fed mesh: no static peers
# freeway.cloud.event.discovery-feed-ms=10000 # interval-based peer lookup from the bound discovery (whole replacement)
# freeway.cloud.event.enabled=false           # master switch — suppresses even configured peers
freeway.cloud.event.token=mesh-secret         # empty = no peer authentication (with a warning); required in production
```

- `peers` — the nodes to dial; a non-empty list **is itself the activation**. The endpoint hangs off the existing HTTP service at `/cloud/event`;
  IPv6 literals work with or without brackets.
- `discovery-feed-ms` — feed the mesh from the discovery backend: at each interval, query the bound `ServiceDiscovery` for this service's instances,
  hand the whole snapshot to the mesh (this node excluded): instances that appear are dialed, ones that leave are disconnected and stop being redialed, and **a failed query keeps the
  previous set**. Static `peers` and the discovery set are a union, so an empty poll does not cancel the static list; setting the key with no discovery to ask
  = startup failure naming the key.
- `token` — the shared secret in the hello frame, compared in constant time; a mismatch closes with WS `1008`. Required for multi-node production,
  injected via `FREEWAY_CLOUD_EVENT_TOKEN`, rotated by rolling restart.

**Interest is not configuration**: a subscription contribution drives the hello pull prefixes, the inbound gate, and delivery at once — one declaration, effective in three places.

**Publishing across the boundary — by name**: the routing key is a topic string chosen at the call site; Java class names never go on the wire:

```java
@Inject MeshEventBus mesh;

mesh.publish("order.created", new OrderCreated("order-42"));
mesh.publish("metrics.tick", report, "tenant-7");   // optional subject (ordering hint)
```

`publish` is Defer-aware: inside a transaction it buffers until commit and only then leaves the process; a rollback discards it — an uncommitted fact never leaves the JVM.

**Receiving declared types — a declaration is the whitelist**:

```java
binder.contribute(MeshSubscription.class)
      .add("orders",
          MeshSubscription.of("order.", OrderCreated.class, this::handle));
```

A frame whose topic matches no subscription is **dropped without deserialization** — undeclared classes are never instantiated. There is deliberately no runtime
subscribe: an interest the peer cannot see during the handshake is an interest that cannot be honored.

**Local and remote deliberately mirror each other**: the two planes do not feed each other — a remote fact does not automatically become a local fact. When needed, mirror it in the handler
with: `MeshSubscription.of("order.", OrderCreated.class, event -> bus.publishEvent(event))`.

**Who receives what**: only the subscription prefixes a node itself declares; there is no second filter. Per-node deployment checklist: module placement +
(peers or `enabled=true`) + subscription declarations + `token` — missing any one is a silent partition rather than an error;
`stats().droppedNoSubscriber()` and `framesQueued()` / `framesDropped()` make it observable after the fact on the sending side.

**Half-open connections**: with `freeway.cloud.event.keepalive-ms` set, the mesh sends a WebSocket ping to each peer every beat; at any moment
only one probe is in flight, the pong budget is derived as `max(3 × interval, 1 s)`, and the connection is closed only when it is exhausted, handing off to the existing reconnect (outbound)
and deregistration (inbound) paths. What this catches is "TCP is still alive, the peer no longer reads" — a NAT/LB silently dropping a connection is exactly this shape, and relying on a send
failure would only discover it at the next publish; the 1 s floor keeps a JVM pause or scheduling delay from misjudging a healthy link as half-open.
Off by default: liveness probing is traffic, and deployments already using TCP keepalive or a service mesh need not pay for a second opinion.

**Delivery semantics**: at-most-once, real time. A peer that is offline at publish time misses the event (no replay queue) — for durable,
per-key ordered delivery use the Kafka plane (`freeway-mq-kafka`). Inbound frames can carry interceptors
(`contribute(MeshInterceptor.class)`); a throwing handler is isolated and counted; the mesh has
no redelivery by design, and legacy class-channel frames are parsed and then dropped with a reason, counted in `stats().droppedLegacy()`.

## Health and Readiness

`contribute(CloudHealthContributor.class)` adds a readiness check for your own dependencies. The built-in membership check reports the state the heartbeat has
**verified** — registered / draining / consecutive heartbeat failures — not a constant; `/health/live` and
`/health/ready` are served by the framework. An external backend adapter may **append** its own connectivity check; it does not replace the core membership
state check.

## Observability, Resilience, Secrets, Storage, Context

| Capability | Application side | Keys / notes |
|------------|------------------|--------------|
| Load balancing | Weighted round-robin over the local zone when `freeway.cloud.discovery.local-zone` is set and instances declare `zone`; falls back to the full list when no local candidate exists. Canary stays a weight/custom-policy input. `.primary()` swaps the strategy | `ServiceInstance.weight()/zone()/version()/isCanary()` are the strategy's inputs |
| Observability | `@Inject Metrics` (counters/timers/gauges, bounded labels; HTTP/RPC durations are fixed-bucket histograms), `@Inject Tracer` (`start(name)`), `@Inject MetricsSnapshot`; every request runs in a server-side span and logs carry `traceId`; span names use the declared route template (`GET /users/{id}`) and misses are `GET unmatched`; probes and `/metrics` are not traced; failure classification and HTTP mapping are in [the failure taxonomy](../reference/failures.md) | `GET /metrics` |
| Resilience | Bound by default and used by RPC clients; `@Inject Retryer` / `RetryBudget` / `CircuitBreaker` / `RateLimiter`, or replace with `.primary()`; the per-service retry budget caps replay pressure. The client also **ejects a discovered instance** after 5 consecutive instance-level failures — 30s at first, doubling to 300s, at most half the current answer, never probing: only the instance's own connect/timeout/mid-flight/5xx failures count, and all-ejected falls back to the full answer. State is per client node | `freeway.cloud.rpc.resilience=auto\|off` and the per-capability keys (`rpc.ejection.*`) |
| Context propagation | `InvocationContext.current()` reads baggage/principal/trace; zero wiring | inbound filter + outbound header injection |
| Secrets | `@Inject SecretStore`; secrets can also be resolved as symbols (`@Symbol("db.password")`). Files are re-read when size/mtime changes (rotation without restart) | `freeway.cloud.secret.*` (system properties only) |
| Object storage | `@Inject ObjectStorage`; `ObjectStoredEvent` / `ObjectDeletedEvent` are published to the event bus | `freeway.cloud.storage.*` |

Runtime capabilities have built-in defaults (env/file secrets, filesystem storage, built-in metrics registry); discovery does not:
static (`StaticModule`) is the zero-infrastructure choice, while dynamic membership needs Fleet or an ext adapter.

**`freeway.cloud.*.type` declares intent; it does not choose an implementation.** The framework never loads by class name (no scanning, no config reflection);
the selector is the binding: an adapter binds the role with `.primary()`, and the built-in default steps aside. The `type` key is a declaration for humans and adapters;
when an external backend is declared but no adapter takes over, startup warns once (`BackendTypeGuard`), so the
declaration does not look like it took effect.

## Online Rotation of Outbound TLS

By default `TransportSecurity`'s `SSLContext` is resolved once, and changing keystore/truststore requires a restart; with
`freeway.cloud.rpc.tls.reload-interval` set, KeyManager/TrustManager delegate to **rotatable material** on every handshake
(size/mtime + throttling, keeping the old material on read failure), effective at the next handshake — **without rebuilding the client**, because the JDK
`HttpClient` captures the context at construction. The inbound side is a different mechanism (the engine reads the current context on every handshake,
`freeway.http.ssl.reload-interval`); only both sides together make complete certificate rotation.

## When Keys Are Read, and Shutdown Order

**Cloud keys are read once, when the component that owns them is constructed.** A hot update changes the value the symbol chain resolves from then on, not the value an already-constructed
client captured — changing `freeway.cloud.rpc.request-timeout` takes effect only after a restart (per-call values passed to
`RemoteCaller`/`RemoteProxyFactory` are the exception; secret files are re-read on size/mtime change).
Read at the point of use only where a "live value" is genuinely needed.

**Shutdown is ordered, deliberately so.** The membership hook stops before the HTTP server: set readiness to `draining`,
deregister first, then wait for `freeway.cloud.member.shutdown-drain` (default `auto`, with the registry backend answering its own
propagation window — the bound adapter answers its own number), and only then does the server stop accepting. In-flight outbound calls have
`freeway.cloud.rpc.shutdown-grace` (default `5s`) to finish; event mesh peers receive a `1001 going away`
close frame rather than an RST.

## Related Documentation

- Design trade-offs and boundaries: [cloud design](../design/cloud.md)
- All configuration keys: [configuration.md](../configuration.md)
- Third-party adapters (Consul, Kafka plane, etc.): [freeway-ext](https://github.com/dzb/freeway-ext)
