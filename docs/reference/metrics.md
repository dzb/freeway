# Metrics Reference

Freeway exposes metrics through the `Metrics` SPI (`counter`, `timer`,
`gauge`). The default registry renders Prometheus text at `GET /metrics`; a
backend can replace the SPI without changing the names below.

## Instrumenting

Inject `Metrics` and use the framework conventions:

```java
@Inject Metrics metrics;

metrics.counter("orders.created").increment();                    // cumulative
metrics.timer("export.duration").record(duration);                // count + average
metrics.histogram("orders.duration",
    Map.of("route", "/orders/{id}")).record(duration);            // fixed buckets
```

Use `counter` for facts, `timer` only when count/average is enough, and
`histogram` for latency the fleet must alert or compare. Labels are bounded
dimensions — a small fixed vocabulary, never raw paths, URLs, request ids or
tenant input. Labeled/histogram overloads default to the name-only methods, so a
backend without those features records the aggregate instead of losing the call;
a backend that models them overrides the overloads.

For backend implementations: honor the framework's fixed bucket list
(`Metrics.DEFAULT_LATENCY_BUCKETS_NANOS`, plus `+Inf`), and do not invent a
second bucket set per call site. The default registry keeps one process's
buckets; cross-instance quantiles are the backend's aggregation job.

## Naming Grammar

- **Dot-separated lowercase segments**: `freeway.http.requests.total`,
  `cloud.rpc.duration`. Segments start with a letter and may contain digits
  (`responses.4xx`); no underscores, hyphens or uppercase.
- **Namespace first**: `freeway.http.*` for the HTTP engine, `eventbus.*` for
  the in-process bus, `cloud.*` for cloud capabilities.
- **Names are static**: a metric name never carries a service id, route path,
  URL, node id or other unbounded value. Per-request/per-instance breakdowns
  belong in a backend's label model, not in the name.
- **One rendered series per name**: dots and hyphens both render as
  underscores in Prometheus exposition, so `a.b` and `a-b` would collide. The
  default registry rejects such a collision at registration.
- **Timers record duration**: the exposition exposes a count and a seconds
  total (`_count`, `_seconds_total`); the Java side records nanoseconds.

## Labels

A metric name can carry bounded dimensions without changing the name:

```java
metrics.counter("freeway.http.requests.total", Map.of("method", "GET", "route", "/users/{id}"));
metrics.timer("freeway.http.requests.duration",
    Map.of("method", "GET", "route", "/users/{id}", "status", "2xx"));
metrics.counter("cloud.rpc.calls", Map.of("service", "orders"));
```

The default registry renders them as `name{key="value",...}` in the Prometheus
text (labels sorted, values escaped). A backend that does not model dimensions
keeps the aggregate: the `Metrics` SPI's labeled overloads delegate to the
name-only methods, so instrumentation never branches on backend type.

| Metric | Labels |
|---|---|
| `freeway.http.requests.total` | `method` — GET/HEAD/POST/PUT/PATCH/DELETE/OPTIONS/TRACE/CONNECT, otherwise `OTHER`; `route` — the declared template |
| `freeway.http.requests.duration` | `method` as above, `route` as above |
| `freeway.http.responses.4xx`, `freeway.http.responses.5xx` | `method`, `route` |
| `cloud.rpc.calls`, `cloud.rpc.failures`, `cloud.rpc.duration`, `cloud.rpc.retry.budget.denied` | `service` — the RPC service id (deployment-bounded, never user input) |
| all other metrics | no labels |

The `route` label is the **declared template** — `/users/{id}`, never the
request path `/users/42`. Non-route outcomes use bounded categories:
`unmatched` (404/405 and other misses), `static`, `spa`, `health` and
`cors-preflight`. Labels are dimensions, not a place for raw paths, URLs,
request ids or other unbounded values. The `method`/`status` labels are bounded
by construction; `route` is bounded by the registered route table plus five
categories; `service` is bounded by the number of deployed services.

## Series Budget

The default registry admits at most **10,000 distinct name+label meter sets**.
Registering a new set beyond that throws `IllegalArgumentException` at the
instrumentation site — it does not silently drop samples or grow until the JVM
runs out of memory. Re-registering an admitted set (a gauge refresh, another
sample on the same counter) is not a new series.

This is the guardrail behind the label rules: `method`, `status` and `route`
are bounded, and `service` is bounded by the deployment. If your dimensions are
not bounded, aggregate before recording or use a backend with its own
cardinality policy; do not push raw ids into labels.

## Histograms

`freeway.http.requests.duration` and `cloud.rpc.duration` are histograms with
fixed buckets (seconds): `0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5,
5, 10`, plus the implicit `+Inf`. The exposition is the standard
`_bucket{le="..."}`, `_count` and `_sum` family; no arbitrary bucket list is
accepted from callers.

```promql
# p95 per route (5-minute rate)
histogram_quantile(0.95,
  sum by (le, route) (rate(freeway_http_requests_duration_bucket[5m])))
```

Quantiles are approximate and bucket-bound; a single scrape is not a
time-series query. The default registry keeps buckets in memory for one
process; cross-instance aggregation is the backend's job. `Timer` remains for
custom metrics that only need count/average.

## Catalog

| Name | Type | Unit | Meaning |
|---|---|---|---|
| `freeway.http.connections.total` | counter | connections | Accepted TCP connections |
| `freeway.http.connections.rejected` | counter | connections | Connections refused at the configured limit |
| `freeway.http.connections.active` | gauge | connections | Open connections right now |
| `freeway.http.requests.total` | counter | requests | Parsed HTTP requests |
| `freeway.http.requests.active` | gauge | requests | Requests currently being handled |
| `freeway.http.requests.duration` | histogram | seconds | Request handling duration (bucket distribution) |
| `freeway.http.responses.4xx` | counter | responses | Responses with a 4xx status |
| `freeway.http.responses.5xx` | counter | responses | Responses with a 5xx status |
| `freeway.http.h2.connections` | counter | connections | HTTP/2 connections established |
| `freeway.http.websocket.connections` | counter | connections | WebSocket upgrades accepted |
| `freeway.http.sendfile.transfers` | counter | transfers | Static-file transfers served |
| `eventbus.published` | counter | events | Events published on the in-process bus |
| `eventbus.delivered` | counter | deliveries | Subscriber deliveries completed |
| `eventbus.subscriber.failures` | counter | failures | Subscriber callbacks that threw |
| `eventbus.dead.events` | counter | events | Events with no matching subscriber |
| `eventbus.stream.drops` | counter | events | Stream events dropped under backpressure |
| `cloud.rpc.calls` | counter | calls | Logical RPC calls started |
| `cloud.rpc.failures` | counter | failures | Logical RPC calls that failed |
| `cloud.rpc.retry.budget.denied` | counter | retries | Retries refused by the per-service retry budget |
| `cloud.rpc.duration` | histogram | seconds | Logical RPC call duration (all attempts) |
| `cloud.event.published` | gauge | events | Events published through the mesh bus |
| `cloud.event.frames.queued` | gauge | frames | Outbound frames waiting to send |
| `cloud.event.frames.dropped` | gauge | frames | Outbound frames dropped |
| `cloud.event.send.failures` | gauge | failures | Outbound frame send failures |
| `cloud.event.inbound.delivered` | gauge | events | Inbound events delivered to subscribers |
| `cloud.event.dropped.legacy` | gauge | events | Events dropped for a legacy frame |
| `cloud.event.dropped.no.subscriber` | gauge | events | Events dropped with no subscriber |
| `cloud.event.peers` | gauge | peers | Connected mesh peers |

The defaults are intentionally coarse: one global series per fact. Adding a
route-, method-, status- or peer-labelled series is a backend/label decision,
not a name change; keep labels bounded (status class and route *pattern* are
bounded, raw paths and service ids are not).
