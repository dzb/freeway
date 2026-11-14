# Fleet (client)

`freeway-fleet` contains the discovery implementations: the local in-process
registry (`LocalDiscoveryModule`) and the Fleet client/protocol
(`FleetModule`). Both bind the process's `ServiceRegistry` /
`ServiceDiscovery`; the registry server itself lives in
`freeway-fleet-server`.

## When to use it

Use `freeway-fleet` when instance membership changes dynamically and the
deployment does not already provide discovery through its platform. For fixed
endpoints use `StaticServiceDiscovery` / `StaticDiscoveryModule` from
`freeway-cloud`; for a single process use `LocalDiscoveryModule` (also in `freeway-fleet`).

The module is a library, not a server: installing it does not require a Fleet
process until the application actually resolves discovery. It never starts a
listener.

## Assembly

```java
FreewayApp.run(
    new HttpModule(),
    new CloudModule(),      // runtime: health, metrics, RPC, ...
    new LocalDiscoveryModule(), // registration/heartbeat lifecycle + local fallback
    new FleetModule());     // overrides discovery/registry with the Fleet client
```

`FleetModule` must be installed alongside `LocalDiscoveryModule`: the lifecycle hook
that registers on start, renews, publishes readiness and deregisters on stop is
owned by `LocalDiscoveryModule`; `FleetModule` supplies the backend. Do not install
both `FleetModule` and a third-party discovery adapter with `.primary()` at the
same time.

## Configuration

The client uses the `freeway.cloud.registry.*` keys owned by
`CloudModule.ConfigKeys`:

| Key | Meaning |
|---|---|
| `freeway.cloud.registry.nodes` | Comma-separated Fleet node addresses (`host:port` or `http(s)://...`). Required once the module is active. |
| `freeway.cloud.registry.token` | Optional shared token sent as `X-Registry-Token`. |
| `freeway.cloud.registry.cache-ttl` | Discovery cache TTL; per-service overrides use the family prefix. |

An empty node list fails startup with a message naming the key. A running Fleet
that becomes unreachable degrades discovery to the cached membership; a failed
registration fails startup loudly.

## Protocol

`FleetProtocol` is the single owner of routes, the `X-Registry-Proto` version
and the token header. The client and the server share that class; the wire path
remains under `/registry` for compatibility with earlier Fleet clients.

Writes fan out to every configured node; reads union their answers and
deduplicate by instance id. A node that answers a heartbeat with 404 makes the
client report the lost lease so the lifecycle hook can re-register.

## Load balancing

`freeway-fleet` does not choose instances. `FleetServiceDiscovery` returns the
candidate list; `LoadBalancerDefault` in `freeway-cloud` chooses per RPC
attempt (weighted, zone-aware). A VIP-only deployment belongs in static
discovery, not here.
