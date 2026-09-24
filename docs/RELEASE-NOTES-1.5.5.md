# Freeway 1.5.5 Release Notes

> Version: 1.5.5 | Highlight: **The event planes are separated — the bus is in-process, the fabric is named. Assembly roots collapse to one derivation each.**

1.5.5 is a doctrine release. The bus-to-transport bridge that let a local
`publish` cross the JVM "by module loading" is **removed** — the RPC plane made
the same call when `CallBus` died (compile errors are the migration path, and
hiding *where a fact goes* is the same sin as hiding *where a service lives*);
this release installs the same gate over the broadcast domain. The bus knows
nothing about transports; cross-node broadcast is `CloudEventBus` in
freeway-cloud; durable per-key streams are `KafkaEvents` in freeway-ext — each
plane, one job, entered at the call site. Alongside it, the assembly surfaces
converge: one derivation for an HTTP server, one for a `Database`, no second
owner of any default, and a correctness round that fixed a data-loss bug and
five container-level defects. ~2077 core tests, ~154 ext tests green.

This is a **breaking release**: **compatibility is not a goal** — compile
errors are the migration path. The full replacement tables live in
[CHANGELOG.md](../CHANGELOG.md). Consumers must move together with it:
**ext 1.5.5 ships on the same train** (the kafka bridge shapes are gone),
and mesh fleets upgrade **fleet-wide, not half at a time** (in-flight legacy
frames decode and are dropped by reason; routing across old/new nodes is not
promised).

## Highlights

### Event planes separated: the fabric is entered by name (freeway-ioc / freeway-cloud)

`EventSink`, `EventBridge`, `EventBridgePolicy`, `EventBusInbound`,
`EventBus.Keyed` and `@Topic` are deleted — the bus is the in-process plane and
knows no other. `CloudEventBus` is the cloud-native broadcast plane:
`publish(topic, payload[, subject])` leaves the JVM (and nothing else does),
Defer-bound so an uncommitted fact never crosses a boundary; interest is
declared with `CloudEventSubscription` contributions — **one declaration drives
the hello pull-prefixes, the inbound gate, and delivery**, and the declared
`Class` is the allowlist (undeclared topics are dropped before deserialization,
nothing reaches `Class.forName`). There is deliberately no runtime subscribe and
no automatic mirror: a remote fact becomes a local one only where a handler says
so, in one visible line. The wire is untouched — CloudEvents 1.0, `fwchannel`/
`fworigin`, W3C trace, origin loop-guard, and the `X-Event-*` read-compat row all
carry over; the CE envelope and mesh machinery move into the new plane intact.
The "one event, several copies" machinery (shared ids, the dedup window,
`publishInbound`) is gone as a concept, not as a feature. See
[plan-event-plane-separation.md](plan-event-plane-separation.md) — seven review
rounds, and every claim in it pinned by tests.

### One assembly derivation each (freeway-http / freeway-db)

`WebServer` is `HttpServer`, and `HttpServer.create(engine, config, pipeline[,
eventSink])` is the single derivation of a server from its parts — the
standalone `WebServerBuilder` and the config snapshot `HttpModuleConfig` are
deleted (a second assembly root is a second answer to every default).
Request assembly is now `HttpPipeline` (`of(...).withRoutes/withFilters/...`,
wither-returns-new), engines are interchangeable behind `HttpEngine.secure()`,
h2 knobs live with the engine (`FreewayHttpEngine.Wiring.withH2Reset`), and
error-handler ordering is identical on both roads. Same shape for the
database: `DatabaseBuilder` dies; `Database.create` (plus `Wiring` withers) is
the standalone face, `DbModule` the container face, and URL-to-dialect naming
belongs to the dialect (`Dialect.of`). Keys have one owner each — value types
read their own config (`HttpServerConfig.from(symbols)`, `CorsFilter.from`, …).

### The contribution mechanism converges (freeway-ioc)

Contributions seal with composition (late mutation fails loudly; the new
`DeferredOrdering.applied` flag turned "silently dropped after drain" into a
startup error), the handle type is `Ordering` (one name for "where this sits in
the line"), and named contributions can now arrive as container factories —
`add(id, c -> …)` — mirroring `Binding.to` and deferred to first flush, so the
bus's subscribers and the cloud's fabric subscriptions resolve once, at
composition, and fail there, not at first event. The event vocabulary moved
into `ioc.event`; the root package is container-core again.

### Kafka is its own plane (freeway-ext)

`KafkaEvents.send(topic, payload[, key])` / `subscribe(prefix, Type.class,
handler)` — the routing topic and the wire topic are one (the bridge-topic
override and the `ce-fwtopic` remap die with the bridge); the subscription
table is the inbound allowlist; `send`'s key argument is the partition key and
`ce-subject`. Poison stays what it was — undecodable records (retry, skip,
DLQ); a throwing handler is a consumer bug, isolated and counted, never poison.

### Cross-JVM trace, before and after the separation

Sending threads carrying a trace stamp `traceparent`/`tracestate` onto mesh
frames and Kafka records; receiving planes restore them around delivery.
Absent or malformed traces run bare — a traceless frame never clears the
consumer thread's ambient. The async bus channels remain a documented gap
(single-listed, not hidden).

## Bug Fixes

- **inbound dedup swallowed MQ redeliveries after a rollback (event loss)** —
  the wire id was claimed at publish time; the claim moved inside the deferred
  dispatch, so a rolled-back batch leaves the id unclaimed and the broker's
  redelivery is accepted (the machine then retired with the bridge — the bug
  class cannot recur in the separated planes).
- `@Primary` on implementation classes was ineffective for by-type resolution.
- `SymbolSource.resolve(name, default)` read the default as an expression.
- The realize lock was JVM-global — one container's slow constructor blocked
  another's.
- `THREAD` scope leaked values across containers (process-level `ScopedCache`
  keyed by `(type, id)` alone).
- `Dialect.of` judged the URL scheme case-sensitively (locale-dependent misses).
- Cloud registration picked `http`/`https` from a verdict that ignored TLS
  reality; error-path access logs recorded 200s; the silent `freeway.web.*`
  config prefix from a v1.2.2 rename was finally named and deleted.
- Class-routed WebSocket endpoints that never resolved failed **at startup**,
  upgrade-callback failures warn with the endpoint named; applications can now
  remap framework-mapped exceptions (contributed `ErrorHandler`s run before
  the built-ins on both roads).

## Migration

Every renamed or deleted shape has a replacement row in the CHANGELOG tables —
compile errors point the way. The three that need knowing rather than grepping:
mesh upgrades are fleet-wide (same-version nodes only, legacy in-flight frames
decode but drop with a counted trace), framework lifecycle events no longer
ride any wire (they were bridge traffic; mirror them if you actually wanted
them on the fabric), and `EventBusStats` lost its `sinkFailures` component —
per-plane stats now, no merged view across planes with different delivery
promises.

## Numbers

1.5.4 was skipped (no artifacts under that number). Core: 2077 tests,
7 modules; ext: 154 tests (5 skipped, broker-gated); the event-plane audit trail
(spotted in seven rounds by outside review, absorbed, verified) is part of the
record.
