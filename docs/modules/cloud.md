# freeway-cloud Development Guide

This document is an overview of how to develop with `freeway-cloud`: how an application moves from single-node to cloud-native, how registry and discovery, remote invocation,
and the event mesh are each used and deployed, and where the boundaries are. For design trade-offs see
[cloud design](../design/cloud.md); for all configuration keys see
[configuration.md](../configuration.md).

The core promise is one sentence: **single-node and cloud-native are two placements of the same code**. Business code writes no registration, no addressing,
and no retries; "having neighbors" is expressed by the composition root (which modules are placed, which keys are configured).

## Modules and Composition

`CloudModule` is the cloud capability bundle: it declares 8 standard modules with `@SubModule` — context propagation, secrets,
**service registry and discovery**, remote invocation, observability, resilience, health, and object storage. Placing the bundle installs everything;
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
| **Environment layer** | Nothing — install the modules and it is there | Automatic instance registration (heartbeat renewal, deregistration on shutdown), `GET /health/live`, `/health/ready`, `/metrics`; every outbound call carries metrics/tracing/resilience/context propagation |
| **Declaration layer** | Data contributions: `binder.bind(Handlers.class)` + `contribute(RpcExport.class)`, `contribute(MeshSubscription.class)` (mesh), `contribute(CloudHealthContributor.class)` | What this service provides to others |
| **Consumption layer** | `@Inject ServiceDiscovery` / `ObjectStorage` / `SecretStore` / `Metrics` / `Tracer`, plus `binder.bind(Api.class).to(...)` | What this service uses from others |

## Service Registry and Discovery

The same seam (`ServiceRegistry` / `ServiceDiscovery`), three backends; switching = changing the module + changing the keys,
with zero changes to business code:

| Backend | Placement | Infrastructure |
|---|---|---|
| In-process (default) | `CloudModule` (or `DiscoveryModule`) | None — single instance / fixed topology |
| **freeway registry node** | `CloudModule` + `FleetRegistryModule` | Self-deployed registry nodes (1–N freeway applications) |
| Consul | `CloudModule` + ext `ConsulModule` | Consul cluster |

### Deploying Registry Nodes (freeway native)

A node is just an ordinary freeway application; assembly is all there is:

```java
public static void main(String[] args) {
    FreewayApp.run(Launch.of(args),
        new HttpModule(),              // HTTP face: six routes — register/renew/deregister/discover/ready/index
        new DiscoveryModule(),    // instance table (RegistryStore)
        new RegistryServerModule());   // publishes the instance table as the HTTP face
}
```

```properties
freeway.http.server.port=8060
freeway.cloud.registry.token=s3cret      # required off-machine: both reads and writes need this header
```

**Every business service** (registrar = caller = listener):

```java
FreewayApp.run(Launch.of(args),
    new HttpModule(),
    new CloudModule(),
    new FleetRegistryModule());    // binds the node fleet as this process's registry and discovery
```

```properties
freeway.cloud.registry.nodes=http://n1:8060,http://n2:8060
freeway.cloud.registry.token=s3cret
freeway.cloud.registry.service-id=order-service
```

**What happens automatically** (zero registration logic in business code):

| Timing | What happens automatically |
|---|---|
| Startup | Resolve identity (service-id + host:port) → POST registration to **every** node; a single node failure logs a warning and the others keep serving; startup fails only when **all** nodes reject it |
| Every 10 seconds | Heartbeat fan-out; a node returning 404 (it was restarted) → **refill it in place** with the instances this process has registered |
| Every `discovery-feed-ms` | Pull the same-service instance list once and feed the whole snapshot to the event mesh — new instances are dialed automatically, departed ones are disconnected automatically |
| Calling others | `RemoteProxyFactory` selects an instance from discovery results + zone-aware weighted round-robin |
| `GET /health/ready` | Reports real registration state: registered / draining / consecutive heartbeat failures |
| Shutdown | Deregister from all nodes first (drain window), then close the process |

**Reuse of discovery results**: `ServiceDiscovery` implementations cache answers internally (Eureka/Ribbon/Spring Cloud LB
are all the same shape; push-style ones such as the K8s informer need no TTL). The default is
`freeway.cloud.registry.cache-ttl=5s` (half a heartbeat, well within the 30s eviction window), overridable per service
(`…cache-ttl.checkout=1s`, `0` = do not cache that service). When the backend fails, the oldest answer keeps being used within the window
(grace = max(10×ttl, 30s)); only beyond the window does it throw.

**Policy** (a default the framework provides, not a mandate): a discovery implementation that goes over the network is wrapped with `CachingServiceDiscovery.around(delegate, ttl, metrics)`
— that is what the fleet does; the in-process store is **not wrapped** (a table lookup already costs 0, and caching would only make it slower);
adapters that bring their own push freshness (the K8s informer) are not wrapped either — they answer `TtlPolicy` with `0`. In other words: **a caching discovery implementation is
a default the framework provides; whether to wrap is the backend's decision**.

**Protocol and credential rotation**: the registry face has a protocol version — every client request carries `X-Registry-Proto`, and when incompatible the node answers
`426` (with the versions it supports); a node always accepts older protocols. `GET /registry` is readable without credentials and describes the protocol,
routes, and authentication method. Credential rotation needs no node downtime: put the current `token` value into
`freeway.cloud.registry.token-previous` and the new value into `token` so both coexist, then roll the
writers over, and finally clear the old-value key — secrets are resolved per request, so a config hot reload takes effect immediately.

**Readiness gate**: registration happens before the application can serve, so a process with `CloudHealthContributor`s registers as
"not ready" first (`ServiceHealth.starting()`: in the registry, not in discovery results), and flips to
ready only after **all** contributors pass — a slow-starting connection pool/migration no longer takes traffic it cannot handle. An application with no contributors is **ready on registration** (preserving the old behavior:
no wait added to an application that exposes no signal). The flip is immediate (`POST …/instances/{id}/ready`) and is also carried on the next heartbeat,
so old nodes lag by one beat instead of diverging.

**Operational semantics of nodes** (an explicit v1 choice): no persistence — the table is reconstructible state; after a node restart the table is empty, and each instance's
next heartbeat gets a 404 and **re-registers itself with that node in place**, restoring full membership within one heartbeat period; no consensus replication —
HA relies on client fan-out (write all nodes, read the union), so any surviving node keeps the whole fleet usable; nodes do not know each other and there is no leader/follower.
Capacity is estimated on a polling model: one heartbeat per instance every 10s plus one discovery query per feed interval.

### Consul

ext's `freeway-cloud-consul`: the same seam; `renew` self-healing, `drainWindow()`, and the `BackendTypeGuard`
warning are all driven by core, and the adapter implements only the transport. Nacos / K8s endpoints plug in the same way.

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
// monolith: local implementation
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
with: `MeshSubscription.of("order.", OrderCreated.class, event -> bus.publish(event))`.

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

`contribute(CloudHealthContributor.class)` adds a readiness check for your own dependencies. The built-in registration check reports the state the heartbeat has
**verified** — registered / draining / consecutive heartbeat failures — not a constant; `/health/live` and
`/health/ready` are served by the framework. An external backend adapter may **append** its own connectivity check; it does not replace the core registration
state check.

## Observability, Resilience, Secrets, Storage, Context

| Capability | Application side | Keys / notes |
|------------|------------------|--------------|
| Load balancing | Weighted round-robin over the local zone when `freeway.cloud.discovery.local-zone` is set and instances declare `zone`; falls back to the full list when no local candidate exists. Canary stays a weight/custom-policy input. `.primary()` swaps the strategy | `ServiceInstance.weight()/zone()/version()/isCanary()` are the strategy's inputs |
| Observability | `@Inject Metrics` (counters/timers/gauges, bounded labels; HTTP/RPC durations are fixed-bucket histograms), `@Inject Tracer` (`start(name)`), `@Inject MetricsSnapshot`; every request runs in a server-side span and logs carry `traceId`; span names use the declared route template (`GET /users/{id}`) and misses are `GET unmatched`; probes and `/metrics` are not traced; failure classification and HTTP mapping are in [the failure taxonomy](../reference/failures.md) | `GET /metrics` |
| Resilience | Bound by default and used by RPC clients; `@Inject Retryer` / `RetryBudget` / `CircuitBreaker` / `RateLimiter`, or replace with `.primary()`; the per-service retry budget caps replay pressure | `freeway.cloud.rpc.resilience=auto\|off` and the per-capability keys |
| Context propagation | `InvocationContext.current()` reads baggage/principal/trace; zero wiring | inbound filter + outbound header injection |
| Secrets | `@Inject SecretStore`; secrets can also be resolved as symbols (`@Symbol("db.password")`). Files are re-read when size/mtime changes (rotation without restart) | `freeway.cloud.secret.*` (system properties only) |
| Object storage | `@Inject ObjectStorage`; `ObjectStoredEvent` / `ObjectDeletedEvent` are published to the event bus | `freeway.cloud.storage.*` |

Every capability has an in-process default (local registry, env/file secrets, filesystem storage, built-in metrics registry) — **a single-node
deployment needs no backend at all**; an external backend arrives as an adapter, and binding it with `.primary()` takes over.

**`freeway.cloud.*.type` declares intent; it does not choose an implementation.** The framework never loads by class name (no scanning, no config reflection);
the selector is the binding: an adapter binds the role with `.primary()`, and the built-in default steps aside. The `type` key is a declaration for humans and adapters;
when an external backend is declared but no adapter takes over, the local implementation warns once at startup (`BackendTypeGuard`), so the
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

**Shutdown is ordered, deliberately so.** The registration hook stops before the HTTP server: set readiness to `draining`,
deregister first, then wait for `freeway.cloud.registry.shutdown-drain` (default `auto`, with the registry backend answering its own
propagation window — the in-process one answers 0, an adapter answers its own number), and only then does the server stop accepting. In-flight outbound calls have
`freeway.cloud.rpc.shutdown-grace` (default `5s`) to finish; event mesh peers receive a `1001 going away`
close frame rather than an RST.

## Related Documentation

- Design trade-offs and boundaries: [cloud design](../design/cloud.md)
- All configuration keys: [configuration.md](../configuration.md)
- Third-party adapters (Consul, Kafka plane, etc.): [freeway-ext](https://github.com/dzb/freeway-ext)
