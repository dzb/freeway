# Registry Server Demo

A single freeway process acting as an in-process **registry server** — the
`RegistryStore` it owns is exposed over HTTP so that **other** freeway
instances (running in the same or other JVMs) can register their own
instances and discover each other. It is a development helper: best-effort, no
auth, no TLS.

## What it demonstrates

| Capability | Where it shows |
|---|---|
| One `RegistryStore` behind both protocols | POST `/registry/.../instances` then GET that same name lists it |
| Dev registry usable from any freeway app | any instance can POST its `ServiceInstance` and get discovered |
| Same sync semantics as in-process | renew POST keeps `alive` true, DELETE removes, renew of a ghost is 404 |
| The process itself is also a registry client | on start it registers `serviceId=freeway-app` into the same store |

## Endpoints

```text
POST   /registry/services/{serviceId}/instances              register a ServiceInstance (JSON body)
GET    /registry/services/{serviceId}/instances               discover live instances
POST   /registry/services/{serviceId}/instances/{instanceId}/renew   renew (returns {"alive":true})
DELETE /registry/services/{serviceId}/instances/{instanceId}  unregister
```

## Prerequisites

- JDK 25
- matching freeway artifacts in the local Maven repo:
  ```bash
  cd freeway && mvn install -DskipTests
  ```

## Run

```bash
cd demo/registry-server
./run.sh            # builds the shaded jar and starts the server
```

Expected startup:

```
== registry-server starting on http://127.0.0.1:19090 ==
...
[registry-server] ready — listening until killed
```

Then, from another terminal:

```bash
curl -s -X POST localhost:19090/registry/services/order/instances \
  -H 'Content-Type: application/json' \
  -d '{"serviceId":"order","instanceId":"i7","endpoint":{"scheme":"http","host":"127.0.0.1","port":18090,"basePath":""},"metadata":{}}'
curl -s localhost:19090/registry/services/order/instances
curl -s -X POST localhost:19090/registry/services/order/instances/i7/renew
curl -s -X DELETE localhost:19090/registry/services/order/instances/i7
```

## Note on the split with production

For production, `CloudDiscoveryModule`'s `ServiceRegistry`/`ServiceDiscovery`
are meant to be replaced with adapters for Nacos/Kubernetes/... (an adapter
implementing the same two interfaces, `.primary()`-bound). This registry
server is the *development* stand-in: it exposes the same in-process store
over HTTP so multiple local freeway apps can register, discover, renew and
delete without any external backend. See `freeway-cloud`'s
`RegistryServerModule` for the module that wires the routes.
