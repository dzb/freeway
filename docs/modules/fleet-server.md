# Fleet Server

`freeway-fleet-server` is a standalone registry server module. Compose it with
`freeway-boot` and `freeway-http` to run a Fleet process:

```java
FreewayApp.run(new HttpModule(), new FleetServerModule());
```

It is a control plane: it owns the registry surface and a `LeaseStore` (currently `InMemoryLeaseStore`), and it
does not register itself into the Fleet it serves. Applications depend on
`freeway-fleet` (client), never on this module.

## Surface

`FleetProtocol` defines the shared wire contract:

| Operation | Route |
|---|---|
| register | `POST /registry/services/{serviceId}/instances` |
| renew | `POST /registry/services/{serviceId}/instances/{instanceId}/renew` |
| unregister | `DELETE /registry/services/{serviceId}/instances/{instanceId}` |
| discover | `GET /registry/services/{serviceId}/instances` |
| readiness | `POST /registry/services/{serviceId}/instances/{instanceId}/ready` |
| self-description | `GET /registry` |

Requests may carry `X-Registry-Proto`; a newer protocol is refused with `426`
rather than a misleading `404`. The self-description is open so a client can
learn the protocol before presenting credentials. `X-Registry-Token` is checked
when `freeway.cloud.registry.token` is set; `freeway.cloud.registry.token-previous`
supports a rotation window.

## Current maturity

The module is a deployable starting point, not a production control plane yet:

- the store is in-memory and at-most-once; a restart loses membership until
  clients re-register;
- no TLS and no admin UI/RBAC;
- no durable leases or HA coordination.

Those are server responsibilities and belong here, not in `freeway-cloud`.
The intended first production step is a PostgreSQL implementation of `LeaseStore`. Node
discovery for the Fleet itself must be independent of the Fleet (static seed
list, an external load balancer, or the shared database) — never self-registration.

## Multi-node behavior

The client fans writes out to every configured node and unions reads. Each node
holds an independent table; a dead node is repopulated by the next heartbeat of
each instance. This is the current availability model and its limit: it is not
a consensus protocol, and a node restart can briefly hide membership until the
clients' next renewal.
