# Fleet and local discovery

`freeway-fleet` contains the discovery implementations:

- `LocalDiscoveryModule` — in-process lease store, `LocalRegistrar`,
  `LocalDiscovery` and the default client-side load balancer;
- `FleetDiscoveryModule` — Fleet client, `FleetDiscovery`, `FleetRegistrar`
  and the wire protocol;
- `LeaseStore` / `InMemoryLeaseStore` — the storage seam shared by the local
  backend and the Fleet Server.

The registration lifecycle is not in this module. Install
`RegistrationModule` from `freeway-cloud` to register the HTTP endpoint, renew
it and publish readiness. Static discovery (`StaticDiscoveryModule`) does not
use that lifecycle.

## Assembly

Fleet backend:

```java
FreewayApp.run(
    new HttpModule(),
    new CloudModule(),          // runtime: health, metrics, RPC, ...
    new RegistrationModule(),   // register, renew, readiness, drain
    new FleetDiscoveryModule()); // Fleet client + client-side load balancer
```

Local single-process backend:

```java
FreewayApp.run(
    new HttpModule(),
    new CloudModule(),
    new RegistrationModule(),
    new LocalDiscoveryModule());
```

Applications depend on `freeway-fleet`; they never depend on
`freeway-fleet-server`. The client module is inert until installed: a dependency
alone does not require a Fleet process. Once `FleetDiscoveryModule` is placed,
`freeway.cloud.registry.nodes` must name at least one node, or startup fails
with the key in the message.

## Configuration

`FleetDiscoveryModule.ConfigKeys` owns the client keys:

| Key | Meaning |
|---|---|
| `freeway.cloud.registry.nodes` | Comma-separated Fleet addresses (`host:port` or `http(s)://...`). Required when the module is active. |
| `freeway.cloud.registry.token` | Optional shared token sent as `X-Registry-Token`. |
| `freeway.cloud.registry.cache-ttl` | Discovery cache TTL; per-service overrides use the family prefix. |

`RegistrationModule.ConfigKeys` owns the endpoint identity and drain keys
(`service-id`, `service-host`, `service-scheme`, `service-port`,
`service-instance-id`, `shutdown-drain`). `LocalDiscoveryModule.ConfigKeys` owns
`freeway.cloud.discovery.local-zone`.

An empty node list fails startup naming the key. A running Fleet that becomes
unreachable degrades discovery to the cached membership; a failed registration
fails startup loudly.

## Protocol

`FleetProtocol` is the single owner of routes, the `X-Registry-Proto` version
and the token header. The client and the server share that class; the wire path
remains under `/registry` for compatibility with earlier Fleet clients.

Writes fan out to every configured node; reads union their answers and
deduplicate by instance id. A node that answers a heartbeat with 404 makes the
client report the lost lease so `RegistrationLifecycleHook` can re-register.

## Load balancing

`freeway-fleet` does not choose instances. `FleetDiscovery` returns the
candidate list; `LoadBalancerDefault` in `freeway-cloud` chooses per RPC
attempt (weighted, zone-aware). A VIP-only deployment belongs in static
discovery, not here.
