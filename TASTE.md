# TASTE — Freeway's design taste

> This is neither API documentation nor a changelog. It records the aesthetic
> standards we actually use when making design judgments: which structures are
> worth keeping, which "conveniences" should be deleted, and which breaks are
> worth paying for.
>
> When the rules and intuition disagree, return to one sentence: **a fact may
> have only one owner, and the reader should never have to guess.**

---

## 1. One owner per fact

We are not short of abstractions; we are short of placing the same fact in only
one place.

### Response: ResponsePlan

A response has four kinds of facts: status and headers, where the body comes
from, how the body is encoded, and how many bytes it occupies on the wire.

They used to be scattered across HttpExchangeSupport, the default methods of
HttpTransport, and the built-in transport's overrides. Now support only plans
and transport only executes:

    interface HttpTransport {
        void writeResponse(ResponsePlan plan) throws IOException;
    }

    record ResponsePlan(ResponseHead head, ResponseBody body) {
        boolean gzip(); // read from the head's Content-Encoding only
    }

    sealed interface ResponseBody
        permits Empty, Bytes, Stream, File, Channel {}

- support selects the body, decides the encoding, and writes the known length.
- transport executes gzip, framing, HEAD, file/channel, and closes resources.
- gzip has exactly one declaration: the head's Content-Encoding; there is no
  second `boolean gzip`.
- HEAD/204/304 do not read the body source; the type and the call order together
  make that fact true.

See [ResponsePlan.java](freeway-http/src/main/java/com/jujin/freeway/http/ResponsePlan.java),
[ResponseBody.java](freeway-http/src/main/java/com/jujin/freeway/http/ResponseBody.java),
[HttpTransport.java](freeway-http/src/main/java/com/jujin/freeway/http/HttpTransport.java).

### Request: RequestHead

method, path, queryParams and headers are the four request facts. They should
not be written twice, in RequestView and HttpTransport, nor should two supports
each maintain their own header-casing rules.

    interface RequestHead { method, path, queryParams, headers }
    interface RequestView extends RequestHead { pathVar..., queryParam..., header... }
    interface HttpTransport extends RequestHead { framedBodyStream, TLS, writeResponse }

RequestViewSupport is the shared read implementation: HTTP reads a live snapshot
from the transport, WebSocket uses an immutable snapshot built at construction;
headers are lowercased uniformly, and path vars are written by the router.

### Errors: ErrorResponse + a pure ErrorMapper

An error mapper's job is to decide which response an exception corresponds to,
not to write the response on the way.

    record ErrorResponse(status, code, message, details, headers) {
        void write(HttpResponse response); // the only write point
    }

    @FunctionalInterface
    interface ErrorMapper {
        Optional<ErrorResponse> map(Exception failure);
    }

- a mapper holds no HttpResponse and cannot half-commit.
- when one mapper throws, the server keeps trying the next.
- 404/405/413/415/validation/500 share the error + code + details structure.
- the human-readable field remains `error`; the machine-readable field is a
  stable `code`.

### Health: HealthResult

A health check should not return Object and make every caller guess the JSON
shape.

    record HealthResult(boolean healthy, String detail) {}

HTTP `/healthz` and the cloud readiness contributor consume the same result
type: `healthy` 200, `unhealthy` 503. Paths may differ; the result model may
not.

---

## 2. Read interfaces expose no write capability

This is the most typical "types are more honest than runtime checks" move in
this round.

`Container.extension()` returns a read-only view; `add`/`seal` belong to the
composition-time store, not to consumers:

    interface Extension<T> {
        List<T> all();
        Map<String, T> asMap();
        void validateOrdering();
    }

    final class ExtensionStore<T> implements Extension<T> {
        Ordering add(String id, T value);
        void seal();
    }

- an application cannot modify an extension point through
  `container.extension(...).add(...)`.
- there is exactly one write path: `Binder.contribute(...)`.
- "adding after seal throws" moves from a runtime defence to something the
  compiler cannot express.
- the test changes from "assert a late add throws" to "this code does not
  compile at all".

See [Extension.java](freeway-ioc/src/main/java/com/jujin/freeway/ioc/extension/Extension.java),
[ExtensionStore.java](freeway-ioc/src/main/java/com/jujin/freeway/ioc/extension/ExtensionStore.java).

---

## 3. A name claims only what the holder can verify

### Scope.BOUNDARY, not Scope.THREAD

Scope.THREAD was never a ThreadLocal and never a thread lifetime; it was the
boundary lifetime of `ScopeBoundary.within(...)`. Under virtual threads and
asynchronous work that name would directly mislead the design.

So it is called BOUNDARY:

- one instance per boundary
- opened and closed by ScopeBoundary.within
- not "thread-local", and not "dies with the thread"
- the related test fixture also changed from ThreadScopedDep to
  BoundaryScopedDep

### framedBodyStream, not bodyStream

`HttpTransport.framedBodyStream()` is the raw framed stream; a handler's
`HttpRequest.bodyStream()` is a stream already wrapped in the maxBodySize
limit. They are different, so the names must differ.

### isCommitted, not isResponded

"the response is committed" is the one fact the transport knows. support
cannot guess it, and should not maintain a second `responded` flag.

### HealthResult, not Object

A health check's return type must state healthy/detail instead of making the
caller agree on an arbitrary Map.

---

## 4. Defaults are promises, not conveniences

We are gradually deleting defaults that "look convenient but actually make a
decision for the caller":

- `HttpRequest.isSecure()` no longer defaults to false: a TLS request must not
  become plaintext because an implementation forgot to override it.
- `HttpRequest.sslSession()` no longer defaults to null meaning "unknown".
- `HttpRequest.bodyStream()` no longer defaults to full buffering: streaming
  versus materialization must not be decided silently by the interface.
- `HttpTransport` no longer has default body strategies for byte[]/stream/file:
  an adapter must not obtain framing that differs from the built-in by
  inheriting a default.
- `FlowContextImpl` no longer hand-writes VarHandle lazy initialization; it uses
  the existing `LazyValue`.
- `FlowDriverDefault.Builder` no longer exists: two optional fields conflicted
  with "a builder holds no defaults of its own", and it had no callers.

The test:

> Is this default expressing an immutable fact, or making a decision for the
> caller that the caller may not know about?

The former may stay; the latter should be deleted.

---

## 5. Structure before checks

The best fix is not "one more `if`", but "make the erroneous state
inexpressible".

- one exchange per request: maxBodySize is a constructor input of
  HttpExchangeSupport, not a field with a reset that can be forgotten.
- ResponseBody is sealed: when a new body type appears, the compiler forces the
  transport to handle it.
- RequestHead and RequestView are layered: the transport need not implement path
  vars it knows nothing about.
- Extension is read-only: consumers need no runtime defence against late adds.
- H2Dialect no longer extends PostgresDialect: H2 mode is H2's syntax surface,
  not the identity of the target database; MERGE, INFORMATION_SCHEMA and DDL
  auto-commit all belong to H2 itself.

"Make the error inexpressible" outranks "add a check"; adding a check is the
last resort.

---

## 6. Do not unify for the sake of uniformity

Taste is also restraint.

We did not perform these mergers:

- we do not merge EventBus, MeshEventBus and FlowEventBus: they are the
  in-process, mesh and execution-local planes, with different semantics. Naming
  and the subscription model may converge; lifetimes may not.
- we do not remove HttpExchange's composite entry: a handler needs request +
  response + meta present at the same time.
- we do not generalize RouteIndex / WebSocketIndex into one Index: the
  WebSocket index already reuses RouteIndex; another abstraction would only let
  the two protocols contaminate each other.
- we do not merge HttpServerHandle into HttpServer: the engine handle and server
  orchestration are two lifetimes.
- we do not give ErrorMapper a "write the response on the way" default: a pure
  function and a side-effecting function cannot share one name.

The principle:

> Merge only when two things share the same fact; when they merely "look
> alike", keep them separate.

---

## 7. The requirements of the agentic era

If agents assemble, probe and repair systems in the future, API discoverability
and verifiability matter more than "writing a few fewer lines".

### Machine-readable

- errors have stable codes, not just natural-language messages.
- health has HealthResult, not arbitrary JSON.
- a dialect has capability flags; H2 has H2Mode.
- an HttpEngine's honor contract must be able to say which fields it honors; it
  cannot ignore them silently.

### No hidden state

- handlers no longer guess isSecure / bodyStream / whether the response is
  committed.
- mappers no longer write responses quietly on the exception path.
- `Map.put(null)` no longer means remove without telling the caller.
- the default implementation of `callAsync(deadline)` no longer drops the
  deadline silently.

### Structured context

- Scope.BOUNDARY is a boundary, not a thread.
- ContextExecutor propagates ScopedValue explicitly.
- the ThreadLocal layers of flow branches should converge on an explicit branch
  context / ScopedValue, so asynchronous handlers do not lose the branch view.

### A break is the migration path

- when a type changes, the compiler reports it and callers follow.
- no two names, two defaults, or two lifetimes are kept.
- a break is worth it only when it deletes a concept or a layer; a
  rename-only break is churn.

---

## 8. The taste ledger of the structural convergence round

| Decision | Taste |
|---|---|
| ResponsePlan + ResponseBody | drives "how to execute a body" out of interface defaults |
| RequestHead + RequestViewSupport | the four request facts are declared once |
| ErrorResponse + pure ErrorMapper | error shape is data, not a side effect |
| Shared HealthResult | a health result does not differ by module |
| Read-only Extension + writing ExtensionStore | a read interface carries no write capability |
| Scope.BOUNDARY | the name describes the lifetime, not the implementation detail |
| Container-built-in JsonCodec | a foundational capability does not belong to the HTTP module |
| Standalone H2Dialect + H2Mode | a compatibility mode is not a target dialect |
| Orm.findAll goes through Dialect | pagination syntax has exactly one owner |
| LazyValue replaces hand-written VarHandle | the correct primitive already exists; do not copy it |
| Delete FlowDriverDefault.Builder | a builder with no callers is a second set of defaults |

---

## 9. This round: names are structure too

This round added no business capability; all the work aligned names and
contracts on the public API. It is not cosmetic: it answers the same question:

> Can someone reading the name know what it is, what happens when a value is
> missing, and whether it reads or writes — without opening the implementation?

### 9.1 Domain words over mechanism words

- `HttpContext` → `HttpExchange`: the full lifetime of one request/response
  exchange; the collaborating types are already `ExchangeHandler`,
  `ExchangeMeta`, `HttpExchangeEvent`, and `context` was merely a generalized
  implementation word.
- `FlowEvaluation` → `FlowExecution`: it owns the graph, engine, driver, context
  and stop signal; it is one execution. `ExprEvaluator.eval()` stays `eval`,
  because expression evaluation and workflow execution are two actions.
- `DatabaseStats` → `PoolStats`: the metrics describe the pool, not the
  database.
- `Health` / `Endpoint` → `ServiceHealth` / `ServiceEndpoint`: in the discovery
  package, `service` is the necessary domain qualifier; a generic name would
  make the reader guess which endpoint.

### 9.2 The package already supplies the domain, so the class name does not repeat it

`cloud.rpc.RpcModule` has one fewer layer of noise than
`cloud.rpc.CloudRpcModule`; `cloud.event.MeshEventBus` is explicitly the mesh
plane while the local `EventBus` remains the local plane; the Fleet client,
registrar and protocol live in `com.jujin.freeway.fleet`, so the package carries
the domain and the class carries the role.

The principle is not "remove every prefix", but:

> `Cloud` appears only at umbrella/shared boundaries; in domain subpackages the
> package carries the domain and the class carries the role.

That is why `CloudModule`, `CloudHookIds` and `HttpClients` remain — they are
genuinely cross-domain names — and `CloudHealthContributor` remains as a role
name; only module types whose package already covered their semantics changed.

### 9.3 The return contract belongs in the method name

- `Container.get(...)` still means "throw when not found"; it is the container's
  primary lookup entry.
- `DatabaseRegistry.get(String)` → `find(String): Optional<Database>`;
  `FlowEngine.graph(String)` → `find(String): Optional<Graph>`;
  `graphOrThrow(...)` → `require(...)`.
- `find` no longer returns null; `require` throws. The name tells the caller to
  handle a missing value before reading the implementation.
- `Query.param(...)` → `bind(...)`: it is the act of writing a parameter, not a
  getter.
- `CloudRequest.idempotentWith(...)` → `withIdempotent(...)`;
  `RpcExport.propagateMessages()` → `withPropagatedMessages()`;
  `RemoteProxyFactory`'s `serviceId/mapping/timeout` →
  `withServiceId/withMapping/withTimeout`. `withX` means a new value is
  returned, so a reader does not mistake it for a mutation of the caller.
- `Container.isActiveBinding(...)` → `isActiveBindingMarked(...)`: it asks
  whether the selected binding carries these markers, not whether some binding
  with the markers exists; the name must state the actual predicate.

### 9.4 The response-body method family must be distinguishable at a glance

`HttpResponse`'s original `output`/`send` carried three differences at once:
body source, status code, and text/JSON. This round split them into a readable
family of actions:

    body(byte[])
    body(InputStream, long)
    text(String)
    json(Object)
    file(Path, long, long)
    file(FileChannel, long, long)
    sendText(int, String)
    sendJson(int, Object)

- `body` is content bytes and streams;
- `text` / `json` are content semantics;
- `file` is a sendfile/resource source;
- `sendText` / `sendJson` are "status code + content" shortcuts.

A method name must not only compile; a call site should not have to read the
parameter types to guess the behaviour.

### 9.5 Read interfaces expose no write capability

`FlowContext.stopped(boolean)` was removed from the public interface and moved
to the internal `FlowContextState.setStopped(boolean)`.

The public `FlowContext` keeps only:

    stop()
    isStopped()

Reading the stop signal is application API; writing it is an internal
permission of the engine/execution. This is the same taste as the read-only
`Extension` view: a read interface should not carry write capability.

### 9.6 A rename completes the whole family

This round did not rename only the "ugliest-looking" method; it aligned every
outlet of the same fact:

- `DatabaseRegistry.find`'s Optional contract, implementation, tests and docs
  changed together;
- `BatchQuery.rows/named` → `rowsRaw/rowsNamed`, with interface, implementation
  and tests;
- all `Row` convenience accessors unified as `as*`;
- `Sql.sql/setColumn` → `toSql/value`, with `Orm`, tests and docs;
- when `CloudHttpClient` was renamed, `CloudHttpClientDefault` and its `Wiring`
  kept the same type relationship;
- `CloudEventBus.CloudEventStats` and `EventBus.EventBusStats` both converged to
  `Stats`, so nested owner names no longer stutter.

No compatibility aliases are kept: an old wrong name that survives is a second
answer that survives.

### 9.7 Restraint is also taste

Names that were not selected for change and still express the right semantics
remain untouched: `ModuleEx`, `HttpPipeline`, `DbModule`, `HttpUtils`,
`ContextExecutor`, `RuntimeDescription`, `HttpRequest.param/pathVar`,
`HttpClients`, `CloudHealthContributor`.

The reason is not "later", but:

> a wide break is not worth producing the same number of public words; a rename
> is justified only when it deletes a second answer or makes a contract/boundary
> readable.

`CloudEvents 1.0` is a protocol proper noun and does not follow the `Cloud*`
class rename; `HttpClients` is a shared helper across RPC / Fleet / event,
not an RPC type. A name's ownership follows facts, not string prefixes.

## 10. The naming ledger of this round

| Decision | Taste |
|---|---|
| `HttpExchange` + `HttpExchangeSupport` | exchange describes the fact of request/response meeting, not a generalized context |
| `HttpResponse.body/text/json/file/sendText` | content source, content semantics and shortcut actions each have their own word |
| `isSecure` / `matchWebSocket` / `webSocketIndex` | predicates use `is`; WebSocket keeps two camelCase segments in Java identifiers |
| `PoolStats` / `DatabaseRegistry.find` / `databases()` | statistics, missing values and collection reads each have an accurate word |
| `Query.bind` / `Contribution.addDeferred` | read, write, immediate construction and deferred construction no longer share an ambiguous verb |
| `Row.asString/asInt/asLong` | the accessor family uses one syntax |
| `FlowExecution` / `FlowEngine.run` | workflow execution is separate from expression eval |
| `FlowContextState.setStopped` internal | the public read interface exposes no engine write capability |
| `metadata()/putMetadata()` | reading a map, reading one value and writing meta use consistent verbs |
| `EventBus.Stats` / `MeshEventBus.Stats` | nested types no longer repeat the owner name |
| `ScopedCache.computeIfAbsent` / `JsonUtils.toJson` / `JsonCodec.coerce` | side effects, serialization and type coercion each use an accurate verb |
| `MeshEventBus` / `MeshEnvelope` / `MeshSubscription` | the mesh plane has its own vocabulary and does not mix names with the local EventBus |
| `RpcRequest/RpcResponse/RpcException/RpcHttpClient` | within the RPC subdomain the package carries cloud and the class carries the role |
| `RpcModule/RegistrationModule/StaticModule/...` | module types no longer repeat the package name |
| `ServiceHealth/ServiceEndpoint` | discovery public types use the `service` qualifier |
| `freeway.fleet.*` | the Fleet client/server and its protocol state the same domain in every artifact |
| `CloudHttpClient` rename includes `CloudHttpClientDefault` | an interface and its default implementation are one type family; half a naming set is not kept |
| Keep `HttpClients` / `CloudHealthContributor` / `CloudEvents` | do not rename mechanically by prefix; rename by ownership of the fact |

## 11. Review checklist

Before submitting a "refactor/cleanup", ask in order:

1. Who owns this fact?
   If the answer is more than one, merge the owners first; do not add
   synchronization checks.

2. Is this default expressing a fact, or deciding for the caller?
   Delete the latter, or turn it into an explicit capability.

3. Does this read interface expose write capability?
   If so, split it into a read view + composition store.

4. Does this name claim something the holder cannot verify?
   THREAD / configured / responded are all counterexamples.

5. Does this structure need a long comment to prove it is safe?
   If so, change the structure; comments explain "why", they do not apologize
   for the structure.

6. Does this break delete a concept, or only rename?
   A rename-only break is not worth it; deleting a layer/default/second answer
   is.

7. Does the test pin behaviour, or describe the implementation?
   A test that stays green after the implementation is deleted usually did not
   protect the contract.

8. Can an agent know the next step from types and error codes alone?
   If not, add a stable contract, not more comments.

9. Does this method name state the missing-value behaviour?
   `get` throws, `find` returns Optional, `require` throws — do not make the
   caller guess from the implementation.

10. Does this name repeat in the class what the package already expresses?
    The package owns the domain, the class owns the role; outside
    umbrella/shared code, `Cloud*` / `*Module` should not stack layer on layer.

11. Did this rename complete the whole family?
    Renaming an `output`, a `Stats` or a Default must bring the interface,
    implementation, tests, docs and every companion type with it.

---

## 12. The boundary round: framework, optional control planes, and data planes

This round did not add a feature; it decided **where Freeway ends**. The taste
question is not "can this live in core?" but "does every application need it?".

### 12.1 Core is a framework, not a product bundle

The core reactor is now:

    commons / ioc / boot / http / db / flow / cloud

`freeway-fleet` (client/protocol) and `freeway-fleet-server` (control plane)
live in `freeway-ext`; so does the standalone Fleet demo. `freeway-cloud`
keeps the discovery contracts, static discovery, registration lifecycle and
load-balancer policy.

The rule:

> A module enters core only when the framework itself cannot work without it.
> A deployable control plane is an optional product, even when Freeway owns it.

Ext is not limited to third-party adapters. It may host complete optional
applications and tools — the benchmark suite was already the precedent, and
the Fleet server is the second. A separate repository is a later decision, not
a purity requirement; split when release cadence, ownership or operations
actually diverge.

### 12.2 A discovery backend is chosen by need, not by a default

| Need | Backend |
|---|---|
| Fixed endpoints / VIP, no registration | `StaticModule` |
| Dynamic membership, self-hosted control plane | Fleet (`freeway-ext`) |
| Existing Consul estate | `ConsulModule` (`freeway-ext`) |
| Tests of registration/readiness | test-scope registry fixture |

There is no production in-process registry. `LocalModule` did not have a
multi-replica production role, so keeping it in core would have been a false
default. Static discovery has no write side; it must not be forced to pretend
it is a registry.

> A backend that cannot express the deployment's lifecycle should not be the
> default just because it is convenient.

### 12.3 Registration is a runtime lifecycle; storage is the server's fact

`RegistrationModule` in `freeway-cloud` owns register, renew, readiness and
drain for **any** `ServiceRegistry`. It knows no Fleet, Consul or H2.

`LeaseStore` lives with the Fleet server, not in the client and not in cloud:

    LeaseStore
    ├── H2LeaseStore      embedded default, single node
    └── MemLeaseStore     tests / embedding

H2 is the first store because a Fleet node should run standalone without an
external database. It is not HA: two Fleet processes must not share one
embedded H2 file; a PostgreSQL implementation replaces it behind the same seam.
The server-only `freeway.fleet.db.*` keys belong to `FleetServerModule`, not to
the protocol or the client.

> Put a fact in the module whose lifecycle owns it: registration in cloud,
> leases in the server, the protocol in neither.

### 12.4 A contract shared by two modules is an artifact

The server must not depend on the client just to know the wire shape. So:

    freeway-fleet-protocol
    ├── FleetProtocol   routes, headers, version
    └── FleetKeys       shared fleet.* keys

    freeway-fleet        -> protocol + cloud
    freeway-fleet-server -> protocol + cloud/http

`FleetProtocol`, `FleetKeys`, the client and the server now say the same thing:

    module   freeway-fleet / freeway-fleet-server
    wire     /fleet, X-Fleet-*
    config   freeway.fleet.*
    protocol freeway-fleet-protocol

This is the protocol rule, not a naming preference:

> When two modules share a contract, the contract is its own compiled artifact.
> An `internal` import across a boundary is a missing public type, not a shortcut.

### 12.5 Public helpers beat `cloud.internal` imports

Ext used to import `cloud.internal` for three generic helpers. The fix was to
promote the smallest public contract, not to copy code:

- `CloudHttpClients` → `cloud.http.HttpClients`
- `ConstantTime` → `commons.util.ConstantTime`
- `OptionalBinding` → `ioc.OptionalBinding`

`cloud.internal` now contains only `BackendTypeGuard`, which no external module
needs. The rule:

> An out-of-module consumer turns an internal helper into a public contract or
> proves the helper belongs to a lower module. It never justifies widening
> `internal`.

### 12.6 A test fixture is not a production backend

The local registry became a test fixture:

- it lives in `freeway-cloud/src/test/java`;
- it binds with the `@Local` marker, not `.primary()`, so an application or
  adapter that binds a real registry still wins;
- it supports register/renew/markReady/unregister, but no heartbeat scheduler,
  TTL or drain;
- production startup fails loudly when no real backend is installed.

Tests must not force production to keep a backend just so they can run.
Conversely, a fixture must not silently outrank a configured primary.

### 12.7 Client-side LB is the default; a gateway is a product decision

Freeway already has client-side load balancing: a caller gets a candidate list
from static/Fleet discovery and chooses per attempt (weighted, zone-aware,
retry-aware). Fleet is a **control plane**: it publishes membership, not
traffic.

Server-side LB adds a hop and a data plane:

    client -> gateway/ingress -> instance

It is justified for north-south entry, non-Freeway clients, central TLS/auth,
or Fleet-aware dynamic upstreams. It is not justified merely because "load
balancing sounds like a server feature".

Current choice:

- internal service-to-service: client-side LB;
- north-south: external ingress/LB (Nginx, Envoy, Kubernetes);
- `freeway-gateway`: only after a concrete requirement; if built, an optional
  ext application that consumes the Fleet client, never a core module and never
  a Fleet-server responsibility.

> Do not build a data plane before the requirement. A control plane that also
> proxies traffic has become two products with one failure domain.

This is a confirmed non-goal for the current boundary, not a deferred backlog
item. Nginx, Envoy, HAProxy, Kubernetes Service/Ingress and cloud load balancers
already cover server-side LB; Freeway should not duplicate them. Revisit only if
a Freeway-specific requirement appears that those products cannot express:
Fleet-aware dynamic upstreams, Freeway context/trace propagation at the entry,
or coordinated SSE/WebSocket drain. Until then, server-side LB is an external
deployment choice.

### 12.8 One namespace, one migration

The Fleet upgrade was taken as a whole:

- `/registry` → `/fleet`;
- `X-Registry-*` → `X-Fleet-*`;
- `freeway.cloud.registry.*` → `freeway.fleet.*` for Fleet keys;
- route contribution ids → `freeway.fleet.*`;
- demo curl examples and docs moved with them.

No compatibility alias was kept. But the break was only acceptable because
every consumer moved in the same change: client, server, tests, demo, docs,
config vocabulary and KnownKeys.

> A no-shims rule is a promise to move the whole family, not a license to leave
> half the old name alive.

### 12.9 The ledger of this round

| Decision | Taste |
|---|---|
| Fleet + Fleet server in ext, core is seven modules | optional product stays out of the framework |
| Local backend deleted, test fixture added | a test seam is not a production backend |
| `RegistrationModule` backend-neutral | lifecycle has one owner, independent of backend |
| `LeaseStore` server-side; H2 default; PG later | storage belongs where its lifecycle lives |
| `freeway-fleet-protocol` artifact | a shared contract is compiled, not imported from `internal` |
| `/fleet`, `X-Fleet-*`, `freeway.fleet.*` | module, wire, config and protocol say one word |
| ext no longer imports `cloud.internal` | promote the smallest public contract |
| gateway is a non-goal; client LB + external ingress | mature data planes exist; duplicate only for a Freeway-specific need |
| Fleet stays in ext, no separate repo yet | split by real release/ownership pressure, not aesthetics |
| H2 embedded, not shared-file HA | document the limit instead of pretending it is a cluster |

## 13. One-sentence summary

**Freeway's aesthetic is not "less code", but "fewer structures that need an
explanation".**

One response has one plan, one error has one mapper, one extension point has one
read entry, one scope has one lifetime, one fact has one owner; one owner has one
name, and one public name allows only one contract.

When the structure is right, comments get shorter, tests get harder, and agents
and humans guess less.
