# Freeway 1.5.1 Release Notes

> Version: 1.5.1 ｜ Highlight: **resilience with an off switch — an H2 reset breaker that only counts the asymmetric shape, event-driven TLS reload, and a migration lock that knows its owner**

1.5.1 is a hardening + modernization release on top of 1.5.0: a full audit
pass over all seven modules closed every confirmed defect (pool release,
migration lock races, CallBus null args, WebSocket digest truncation, a
silently disarming config watcher), gave HTTP/2 a tunable rapid-reset
breaker and TLS reload a WatchService fast path, and converged the codebase
on modern Java idioms — pattern switches, immutable factories, platform
thread builders, private locks — while documenting exactly where the old
forms stay on purpose (order-promising maps, live views, null-tolerant
views). ~1,880 tests green across all core modules.

## Added

- **H2 reset burst guard** (`freeway-http.engine.http2`) — more
  not-yet-answered inbound resets than `freeway.http.h2.reset-burst-limit`
  (default 200, `0` disables) inside `freeway.http.h2.reset-window`
  (default 10s) trips the connection with `GOAWAY(ENHANCE_YOUR_CALM)`, then
  tears it down; the peer retries on a fresh connection per RFC 7540 §6.8.
  Counting is shape-precise: only resets for live streams that never
  committed a response count (`Http2Stream.isResponseCommitted`), so
  post-response cancels and dead-stream resets can never trip it. The full
  chain is `HttpConfigKeys` → internal `HttpConfig` (`SymbolSpec`, single
  source) → `HttpServerConfig` (validation + Builder) → `Http2Session` →
  a new `Http2Connection` overload; the 6-arg constructor keeps its
  defaults.
- **Event-driven TLS reload** (`freeway-http.internal`) — the watcher thread
  now only signals: a debounced reschedule collapses CREATE+MODIFY bursts
  into one digest pass on the single scheduler thread, so checks never
  overlap and no interleaving needs reasoning. `check()` moved off its
  public monitor onto a private lock. The poll stays as the fallback for
  filesystems where watch events are unreliable — either layer alone drives
  the same snapshot comparison.
- **Regression coverage** — custom burst limits and the disabled guard,
  pre/post-response cancel accounting, GOAWAY last-id, sub-poll-interval
  watcher reload, stale-owner lock handover, foreign-pool release, `Defer`
  `Error` identity, null proxy arguments.

## Changed

- **GOAWAY carries `lastHandledStreamId`** (`freeway-http.engine.http2`) —
  refused streams still advance validation (`lastSeenStreamId`, so lower-id
  replays stay connection errors per RFC 7540 §5.1.1) but no longer leak
  into GOAWAY's last-stream-id, which previously claimed never-processed
  requests as "might have been processed" and discouraged their retry.
- **Private locks over public monitors** — `Extension` (including the inner
  `Entry.before/after`, which shares the monitor), `LazyValue`,
  `ScopedCache.Session`, `Defer`'s `DeferredSupplier`, `SslReloader`. The
  whole tree was verified free of `wait/notify` pairings first; behavior is
  identical, external synchronization can no longer interfere.
- **Idiom convergence, no behavior change** — type-to-value dispatches
  become pattern switches (`JsonLeaves`, the shutdown drain with
  compiler-checked arm order, `PeerHub` prefixes); `copyOf`/`of` replace
  copy-and-wrap where order and null semantics allow (order-promising maps,
  live views and null-tolerant views keep their forms, each with a comment
  saying why); thread creation unifies on `Thread.ofPlatform` builders;
  charsets are explicit; raw types and redundant qualifiers are gone.
- **`Extension.asMap` documents its snapshot semantics** — immutable,
  point-in-time, refreshed only by a fresh call. Deliberately not renamed:
  the name describes the shape, the docs describe the temporality.

## Fixed

- **Migration lock races** (`freeway-db.migration`) — stale takeover is a
  conditional delete on the observed `executed_at` (zero rows affected backs
  off instead of deleting a fresh lock), and every lock row carries an owner
  token in `description`, so a slow owner's late release removes zero rows
  instead of its successor's fresh lock.
- **Foreign pool release** (`freeway-db`) — a `PooledConnection` from another
  pool now fails with an actionable `SqlException`, not a `ClassCastException`.
- **`Defer.supply` swallowing `Error`** (`freeway-commons.scoped`) — `Error`
  propagates and rethrows as-is with exactly-once preserved; other failures
  keep the established wrapper shape.
- **Null proxy arguments** (`freeway-ioc`) — `CallBus` proxies forward null
  arguments like `RemoteProxyFactory` always did, instead of failing inside
  `List.of`.
- **Node sorts a copy** (`freeway-flow`) — constructing a node no longer
  reorders the caller's link list.
- **WebSocket accept-key truncation** (`freeway-http.engine.ws`) — digest
  input pinned to UTF-8 bytes; the old platform-charset `length()`-bounded
  update corrupted non-ASCII input.
- **Config watcher disarming** (`freeway-boot`) — `WatchKey.reset()` moved
  to `finally`, so a failed iteration can no longer silently retire its
  directory.
- **H2 preface decoding** (`freeway-http.engine.http2`) — US-ASCII instead
  of the platform default.
