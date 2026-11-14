# Freeway Roadmap

Freeway 2.0 has no release-blocking feature work. This file records the
remaining evaluated work so it is not rediscovered from scratch; it is not a
promise of sequencing. Items that depend on a cloud platform, a third-party
SDK or an adapter belong in `freeway-ext`.

Statuses: **next** (evaluated, not scheduled), **later** (useful but not a
near-term priority), **delivered** (kept for context; details are in
[CHANGELOG.md](CHANGELOG.md)).

## Next

### Local-call resilience annotations

**Status:** evaluated, not scheduled.

**Goal.** Let an in-process service method carry `@Retry`, `@CircuitBreak` and
`@RateLimit`, reusing the existing `Retryer`, `CircuitBreaker` and `RateLimiter`
SPIs and their `freeway.cloud.rpc.*` thresholds. Today those SPIs govern only
the `RpcHttpClient` outbound path; an application calling a slow dependency
directly must write retry/circuit template code itself.

**Why it is not trivial.** It is not a second resilience implementation:

- annotations select an `Advisor`; the advice delegates to the same SPIs and
  configuration keys — no parallel thresholds, no second config vocabulary;
- method-level annotation falls back to class-level, read at proxy creation,
  with no classpath scanning;
- only interface proxies can be advised through the existing
  `Binding.advise`/`Advisor` seam; final classes and self-invocation stay out
  of scope and must be documented as such;
- composition order must be explicit and match the RPC policy:
  rate limit → circuit breaker → attempt, with retry outside the attempt;
- `@Retry` on a local call needs an explicit replay-safety decision; local
  exceptions do not carry the RPC outcome model, so an annotation that is absent
  or not idempotent must not be retried by default.

**Minimal closing action.** Add the three annotations in the cloud resilience
package, one `Advisor`-based advice per annotation, annotation lookup (method
then type), and tests proving: no annotation means no behavior change; retry
respects `max-attempts`; breaker opens; rate limit refuses; `resilience=off`
disables all three; local and remote paths share the same SPI instances.

**Acceptance tests.** No double-proxy, self-invocation accounted as unsupported,
configuration reuse, and a failure/normal path for each annotation. Metrics
reuse the existing `cloud.rpc.*` families with a `service`/`method` label only
if cardinality stays bounded.

## Later

### HTTP/2 stateful fuzz

Malformed frame and HPACK blocks are covered by fixed-seed fuzz tests. The next
layer is **valid-but-adversarial sequences**: SETTINGS changes, RST_STREAM
bursts, CONTINUATION interleaving, flow-control overflow, concurrent stream
limits, frames after GOAWAY. The assertion is the same: a protocol error closes
the connection; no hang, unchecked exception, leaked thread or unbounded
allocation.

### Canary traffic policy

`LoadBalancerDefault` prefers the local zone and uses `weight`; `canary` is a
metadata flag only. A default canary split is deliberately not implemented —
silently routing all traffic to a canary is worse than making the operator
choose. A future policy would be opt-in and header/version-based, with weighted
fallback, not an automatic promotion.

### Internal surface shrink

`api/internal-surface.txt` budgets the public types under `internal`. `Stepper`
was moved back to its owner package (40 → 39); the rest cross module/package
assembly boundaries and should be taken one at a time, each with a named reason,
not in a batch.

### Metrics export adapters (ext)

The core owns the in-memory registry, fixed histograms and `/metrics` text;
OTLP/Prometheus push adapters are ext work. Do not add an SDK to core.

## Delivered (2.0 highlights)

- context carrier + MDC propagation across EventBus/Flow/RPC;
- route-template metrics and trace span names; bounded labels; fixed-bucket
  latency histograms and a 10,000-series registry budget;
- failure taxonomy plus the `Failure` classification view;
- RPC async cancellation, per-service retry budget, zone-aware load balancing
  and per-method `@RemotePolicy`;
- HPACK/HTTP/1/HTTP/2 malformed-input fuzz baselines and property tests for
  JSON and SQL parameters;
- configuration hot-reload matrix and the hot-reload contract test.

See [CHANGELOG.md](CHANGELOG.md) for behavior details and
[docs/design/cloud.md](docs/design/cloud.md) for the design rationale.
