# Design Decisions

Key architectural decisions and the reasoning behind them.

## Striped Lock for Singleton Concurrency

**Decision:** Replace `synchronized (targetCache)` with 64-way striped locking for singleton creation in `ServiceRuntime.realize()`.

**Why:** The global lock serialized all singleton creation, creating a bottleneck during startup (dense service creation) and lazy-load concurrency. Channeling contention to 64 stripes via `key.hashCode() & STRIPE_MASK` provides per-key serialization without global serialization.

**Key constraint:** `synchronized` (reentrant) over `ConcurrentHashMap.computeIfAbsent`. `computeIfAbsent` throws "Recursive update" on reentrant calls for the same key, which occurs with proxy-based lazy resolution in circular dependency scenarios. `synchronized` is inherently reentrant.

```java
Object lock = lockStripes[key.hashCode() & STRIPE_MASK];
synchronized (lock) {
    Object cached = targetCache.get(key);
    if (cached == null) {
        cached = binding.directInstance();
        targetCache.put(key, cached);
    }
    return binding.type().cast(cached);
}
```

**See also:** `ServiceRuntime.java:80-91` (`freeway-ioc`)

---

## Shutdown Exception Aggregation

**Decision:** Accumulate all shutdown-phase exceptions into a single `RuntimeException` with suppressed exceptions. Always print to stderr unconditionally, then rethrow.

**Why:** Shutdown involves multiple sequential steps (publish lifecycle event → stop hooks → close container → log). A failure in an earlier step should not prevent later steps from running. Previously, early shutdown exceptions silently swallowed later failures. The new approach:

- Each step wraps its exception via `accumulate()` (null-safe suppression chaining)
- After all steps, `failure.printStackTrace(System.err)` outputs everything (SLF4J may be unavailable during shutdown)
- Rethrows the aggregated failure so callers know shutdown was incomplete
- Sets `state = FAILED` on error

**See also:** `AppRuntimeDefault.close()`

---

## AST Caching for ExprEvaluator

**Decision:** Cache compiled expression ASTs in a `LinkedHashMap` LRU cache (max 512 entries) with `ReentrantReadWriteLock` for concurrent access.

**Why:** The flow engine evaluates the same condition expression repeatedly across graph traversals. Previously, every `evalCondition()` call re-parsed the expression from scratch. The cache stores the AST after first parse; subsequent evaluations skip parsing entirely.

```java
private static final Map<String, AstNode> CACHE = new LinkedHashMap<>(512, 0.75f, true) {
    protected boolean removeEldestEntry(Map.Entry<String, AstNode> eldest) {
        return size() > 512;
    }
};
```

Read-write lock allows concurrent reads (fast path) and serializes the rare write (first parse, cache miss). The `match()` method contains a fix where operator characters (like `!`) skip ident-boundary validation — `!isIdentStart(token.charAt(0))` prevents `!active` from being incorrectly rejected.

**See also:** `ExprEvaluator.java` (`freeway-flow`)

---

## HTTP Body/Multipart Size Limits

**Decision:** Add `maxBodySize` (default 10MB) to `HttpServerConfig` and `maxPartSize` to `MultipartForm.parse()`.

**Why:** Without size limits, large HTTP bodies or multipart uploads can cause OOM by consuming all available heap. The limit is applied at the read boundary: the server rejects bodies exceeding the configured maximum before buffering them entirely.

```java
public static final long DEFAULT_MAX_BODY_SIZE = 10 * 1024 * 1024L; // 10MB
```

When exceeded, a `BodyTooLargeException` is thrown and handled by the built-in `ErrorHandler` returning HTTP 413 Payload Too Large.

**See also:** `HttpServerConfig.java`, `MultipartForm.java` (`freeway-http`)

---

## Container API: Remove `inject()`, Add `create()`

**Decision:** `Container.inject(Object)` was removed. `Container.create(Class<T>)` added as the public API.

**Why:** `inject()` was a "half measure" encouraging `new X() + container.inject(x)` — an anti-pattern where the caller manages part of the lifecycle and the container manages the rest. `create()` provides a clean contract: constructor injection + field injection + `@PostConstruct`, caller owns the lifecycle, no caching, no container management. The name `create()` was chosen over `instantiate()` as more idiomatic.

Final Container API: `get(Class)`, `get(Class, String)`, `extension(Class)`, `create(Class)`, `close()`.

**See also:** `Container.java` (`freeway-ioc`)

---

## Route Record: Remove `handlerType` Field

**Decision:** The `Route` record went from 4 fields to 3 (`method`, `path`, `handler`). The `handlerType` field and its accessor were removed.

**Why:** The 4-field version created an awkward "either handler or handlerType" invariant that shifted validation complexity into every consumer. By moving class-based lazy resolution into `LazyHandler` (a `RouteHandler` wrapper), the record itself has no special cases — it always carries a resolved `RouteHandler`. Factory methods like `Route.get("/path", MyHandler.class)` internally create `new LazyHandler(MyHandler.class)`.

**See also:** `Route.java`, `LazyHandler.java` (`freeway-http`)

---

## RouteIndex: Remove Container Dependency

**Decision:** `RouteIndex` constructor no longer accepts a `Container` parameter. Container-dependent resolution of lazy handlers moved to `HttpModule`'s provider function.

**Why:** `RouteIndex` is a pure data structure (trie-based path matcher) — it should not know about IoC. The Container dependency was leaked into it because class-based handlers needed resolution during construction. The fix:

1. `HttpModule` binds `RouteIndex` via a provider function
2. The provider resolves all `LazyHandler` instances by calling `lh.resolve(container)` 
3. Passes the resolved handlers to `new RouteIndex(routes, groups)`

This keeps the IoC dependency boundary at the Module level, identical to the `freeway-db` pattern.

**See also:** `HttpModule.java:45-52`, `RouteIndex.java`, `LazyHandler.java`

---

## Class-Based Contribution Via `Contributions.add(Class)`

**Decision:** `binder.contribute(T).add(MyImpl.class)` defers instantiation and injection until after all bindings are registered.

**Why:** When `add(Class)` is called during `bind()`, the container's bindings are not yet fully registered — so `container.create(implClass)` would fail if `MyImpl` depends on bindings from the same module. The solution defers instantiation until after **every** module's `bind()` has run — a contributed class may depend on services declared by any module regardless of declaration order:

```java
List<Runnable> pendingCreates = new ArrayList<>();

// In add(Class):
pendingCreates.add(() -> {
    V instance = container.create(implClass);
    deferred.apply(ext.add(id, instance));
});

// Bindings are registered per module (flushPending), then after the last
// module's bind() the class contributions run together (flushPendingCreates):
for (var action : pendingCreates) action.run();
```

The auto-generated id uses snake_case from the class name (`MyHandler` → `"my_handler"`). `DeferredContribution` stores `before/after` ordering constraints during `add(Class)` and applies them to the real `Contribution` at flush time.

**See also:** `BinderImpl.java`, `Contributions.java` (`freeway-ioc`)

---

## Three-Layer Architecture

```
freeway-commons         zero deps
 ├─ freeway-ioc         depends on commons
 │   ├─ freeway-http    depends on ioc (+ commons transitive)
 │   └─ freeway-flow    depends on ioc + commons
 └─ freeway-db          depends on commons (ioc optional)
```

**Decision:** `freeway-db` makes IoC optional — it is independently usable with only `freeway-commons`. `DbModule` bridges it to the container.

**Why:** A database access layer should be testable standalone and usable in non-IoC contexts. The IoC integration is contained entirely in `DbModule` — a gateway module that wires standalone DB types to IoC scopes/configuration. `freeway-http` follows the same pattern: `HttpModule` is the sole IoC-aware entry point; the rest of `freeway-http` has no dependency on `freeway-ioc`.

---

## `.primary()` for Implementation Selection

**Decision:** Default implementations are bound without `.primary()`. Alternative implementations are bound with `.primary()` for automatic selection.

**Why:** Avoids config keys for engine/pool/dialect selection. Adding the extension module to the classpath is sufficient — the container resolves the `.primary()` binding automatically. Same pattern across:

- HTTP engine: `FreewayHttpEngine` (built-in, no `.primary()`) vs `UndertowEngine` (with `.primary()`)
- Connection pool: `PoolDefault` (built-in) vs `HikariPool` (with `.primary()`)
- DB dialect: `PostgresDialect` (default) vs custom (with `.primary()`)

---

## LazyHandler Resolution Strategy

**Decision:** `LazyHandler.resolve()` uses DCL (double-checked locking) with `volatile`. Resolution is eager in `HttpModule`'s provider (before `RouteIndex` construction) but the mechanism supports lazy resolution at match time.

```java
RouteHandler resolve(Container container) {
    RouteHandler h = resolved;
    if (h == null) {
        synchronized (this) {
            h = resolved;
            if (h == null) {
                resolved = h = container.get(handlerType);
            }
        }
    }
    return h;
}
```

**Why:** DCL provides thread-safe lazy initialization with minimal overhead on the fast path (a single volatile read). The `synchronized` block is package-private and only called during module wiring, not on the request path. The `handle()` method throws if `resolved` is null — this is a programming error guard (must call `resolve()` before handling) rather than a fallback.

**See also:** `LazyHandler.java`

---

## DeferredContribution: Bridge bind() → flush()

**Decision:** A lightweight `DeferredContribution` stores `before/after` ordering constraints during `bind()` and applies them post-instantiation.

**Why:** `add(Class)` defers instantiation until after all modules have bound (`flushPendingCreates()`), but the caller specifies ordering at `bind()` time. `DeferredContribution` captures those ordering constraints eagerly and replays them onto the real `Contribution` (from `ext.add(id, instance)`) once the instance is created. This matches `Extension.Entry`'s behavior (append-only `before`/`after` lists).

**See also:** `BinderImpl.DeferredContribution`

---

## Utility Naming Convention

**Decision:** Framework-provided implementations use the naming convention `XDefault` (e.g. `AppRuntimeDefault`, `JsonCodecDefault`, `RequestContextDefault`, `CoercerImpl`).

**Why:** `XDefault` keeps the interface name dominant in alphabetized lists and imports. The name reads as "the default X" rather than "a default of X." The alternative `DefaultX` was rejected because it submerges the interface name.

---

## Flow Single Canonical Graph Spec

**Decision:** `Graph` is built exclusively from the canonical `GraphSpec` blueprint (`id/title/driver/version/entry/meta/nodes/links`). The legacy solon-flow v1 `layout` format (per-node embedded `link`, implicit auto-linking, `when`/`condition` aliases) was removed; `Graph.fromText()` accepts only the canonical shape and `Graph.toMap()`/`toJson()` serialize it.

**Why:** v1's auto-linking and auto-generated ids made the graph topology a function of array order — the same definition could produce different graphs. The canonical `(V, E)` representation is order-independent, declares exactly one entry, validates every link reference, and computes BFS reachability at `create()` time. Removing the v1 adapter deleted ~560 lines and eliminated the dual-format surface.

```
JSON → GraphSpec.normalize() → new Graph
```

**See also:** `GraphSpec.java`, `NodeSpec.java`, `LinkSpec.java` (`freeway-flow`)

---

## Flow Driver Extension Point

**Decision:** `FlowDriver` is a contributed extension point (`binder.contribute(FlowDriver.class)`). `FlowModule` binds `FlowContainer` and creates `FlowDriverDefault` internally; custom drivers are contributed by id. Graphs select their driver via the `"driver"` field (null/"" → `"default"`). The engine receives a plain `Map<String, FlowDriver>`, assembled by `FlowModule` via `Extension.asMap()`.

**Why:** This keeps `FlowEngineImpl` IoC-free while giving users the standard Freeway extension mechanism. The `Extension.asMap()` bridge converts named contributions into a plain map at the module boundary. Custom drivers can be registered via `add("custom", driver)` or `add(MyDriver.class)` (auto-instantiated via `container.create()`).

**See also:** `FlowModule.java`, `FlowEngineImpl.java`, `Extension.asMap()` (`freeway-flow`, `freeway-ioc`)

---

## Entry Node Type Preservation

**Decision:** `Graph.doAddNode()` does NOT force the entry node to `NodeType.START`. The entry node keeps its original type; execution starts from that node regardless.

**Why:** The previous behavior (promote entry→START) silently dropped task/loop behavior when the entry pointed to a non-START node. With type preservation, `node_run()` dispatches to the entry's actual type — an entry ACTIVITY runs its task, an entry LOOP iterates. This was the root cause of `loopNodeViaV2` test returning 0 executions when 4 were expected.

**See also:** `Graph.java:doAddNode()` (`freeway-flow`)

---

## GraphSpec Cache Invalidation via owner.touch()

**Decision:** `NodeSpec` and `LinkSpec` carry a package-private `owner` reference back to `GraphSpec`. Every setter calls `touch()` → `owner.invalidate()`, which clears the cached BFS order. Normalization runs fresh on each `create()`/`toMap()` call.

**Why:** Extracting the inner classes broke the implicit cache invalidation (inner classes had direct access). The `touch()` pattern restores it without exposing internals — `owner` is package-private, `touch()` is private, `invalidate()` is package-private. This guarantees that `create()` after any mutation always sees the correct graph state.

**See also:** `GraphSpec.java`, `NodeSpec.java`, `LinkSpec.java` (`freeway-flow`)

---

## unreachable nodes in serialization

**Decision:** `nodesInCompileOrder()` returns all nodes — BFS-reachable first, then unreachable appended. `toMap()` and `toJson()` serialize the complete node set.

**Why:** The previous implementation only returned BFS-reachable nodes, causing unreachable nodes to silently disappear from JSON output. This broke round-trip fidelity — a graph loaded from JSON, modified, and re-serialized would lose nodes. The new approach preserves all nodes while keeping the compilation order optimized for reachable paths.

**See also:** `GraphSpec.java:nodesInCompileOrder()` (`freeway-flow`)

---

## Subgraph Driver Re-resolution

**Decision:** `FlowExchanger.runGraph()` calls `engine.getDriver(graph)` to resolve the subgraph's own driver, rather than reusing the parent's.

**Why:** Subgraphs define their own `"driver"` field. Blindly propagating the parent's driver meant subgraphs with custom drivers would silently use the wrong one. The fix creates a new `FlowExchanger` with the correctly resolved driver. The same `ExecState` instance is shared between parent and subgraph for parallel/inclusive node state tracking.

**See also:** `FlowExchanger.java:runGraph()` (`freeway-flow`)

---

## Exception Wrapping Strategy

**Decision:** `FlowEngineImpl.task_exec()` and `condition_test()` propagate `IllegalStateException` and `IllegalArgumentException` unwrapped. All other non-`FlowException` throwables are wrapped in `FlowException`.

**Why:** Configuration errors (missing FlowContainer, unknown driver id) carry clear diagnostic messages. Wrapping them in a generic `FlowException("The task handle failed: g / a")` buried the root cause. The stratification preserves diagnostic clarity for setup errors while still wrapping unexpected runtime failures with graph context.

**See also:** `FlowEngineImpl.java:task_exec()`, `FlowDriverDefault.java:getContainer()` (`freeway-flow`)

---

## FlowOptions Defensive Copy

**Decision:** `FlowEngineImpl.eval()` always creates a new `FlowOptions` instance. If a non-null `options` is passed, its interceptor list is copied to the new instance before adding engine-level interceptors.

**Why:** `FlowOptions.DEFAULT` is a public static singleton. The previous code mutating it via `options.interceptorAdd(interceptorList)` caused interceptor accumulation across multiple `eval()` calls sharing the same `DEFAULT` instance.

**See also:** `FlowEngineImpl.java:eval()` (`freeway-flow`)

---

## Gateway Dead-End Detection

**Decision:** A run that never reaches its END node now fails loudly. Three gateway dead ends — an EXCLUSIVE node that matched no condition and has no default link, and INCLUSIVE/PARALLEL joins that never received all their incoming branches — are recorded on `ExecState` and surfaced as a `FlowException` when the evaluation completes. Previously such runs reported "success" with execution silently stopped short of END.

**Why:** A graph that stops mid-way is a workflow defect; silently returning success hid it from callers and tests. The mechanism:

- `ExecState` keeps a concurrent `Set<DeadEnd>` keyed per `(graphId, nodeId)`. An EXCLUSIVE node records a dead end when it matches no condition and has no default link (still logged as a warning). Joins record a *provisional* dead end each time they are entered but cannot activate; when the final branch arrives, the join clears only its own entry (`deadEndClear`) so a dead end recorded by a sibling branch is not lost.
- The completion check in `eval()` throws `FlowException("Graph '...' did not complete: dead end at node '...'")`, naming the stuck node and graph. **Exemptions:** runs ended via `stop()` / `interrupt()` — including interceptor-blocked runs — are not dead ends (stopping is intentional); resume replay walks never mark, because `markDeadEnd` skips `isReverting()` and the replay is only a walk to the resume point.
- Sub-graph evals share the parent's `ExecState` (passed through `FlowExchanger.runGraph()`), so a dead end recorded inside a sub-graph propagates to the caller's completion check. No reset is needed at eval start — a fresh `ExecState` is created per top-level evaluation.

**See also:** `ExecState.java:DeadEnd`, `FlowEngineImpl.java:eval()` / `exclusive_run_out()` / `inclusive_run_in()` / `parallel_run_in()` (`freeway-flow`)

---

## Expression Short-Circuit Evaluation

**Decision:** `&&` / `||` (and `and` / `or`) evaluate the right operand lazily: `false && (x - 1)` yields `false` without evaluating `(x - 1)`.

**Why:** The previous implementation evaluated both operands eagerly before applying the boolean operator, so a guarded expression like `flag && data.count > 0` could throw on `null` data even when `flag` was false. Short-circuiting matches conventional boolean semantics and makes guards safe. All other operators stay eager.

**See also:** `ExprEvaluator.java:BinaryOp.eval()` (`freeway-flow`)

---

## Unary Minus Support

**Decision:** The expression grammar now supports unary minus (`-x`, `-(a+b)`, `--x`) and unary plus, in addition to `!` / `not`. Unary minus is type-preserving for the boxed numerics: `-5` stays an `Integer`, `-9223372036854775807` stays an exact `Long` (no double rounding), `-1.5` stays a `Double`. Non-numeric operands throw `FlowException("Cannot negate non-numeric value: ...")` — the same error family as binary subtraction.

**Why:** Signed values previously had to be encoded as `0 - x` or parenthesized expressions. Unary chains recurse without passing through `primary()`, so they count against the same nesting-depth budget (`MAX_NESTING_DEPTH`) as `!`, keeping the recursion guard intact.

**See also:** `ExprEvaluator.java:UnaryOp.eval()` / `negate()` (`freeway-flow`)

---

## Mixed Number/String Comparison

**Decision:** Comparing a `Number` with a `String` (e.g. a JSON value like `"score":"90"` against a numeric literal) compares numerically when the string parses as a number — `"10" > 9` is `true`, and `"10" == 10` holds. Non-numeric strings fall back to lexicographic `compareTo` ordering (and to plain equality for `==`), keeping the pure-string and pure-number paths untouched.

**Why:** JSON data arrives as strings; without this, `"10" > 9` compared lexicographically (where `"10" < "9"` is `true`), silently inverting the intended ordering. Parsing tries `Long` first (exact, no precision loss), then `Double`, and returns `null` for non-numeric strings so callers can fall back to the previous behavior.

**See also:** `ExprEvaluator.java:cmp()` / `eq()` / `parseNumericString()` (`freeway-flow`)

---

## Atomic $for LOOP Claim

**Decision:** A `$for` LOOP node is claimed atomically: the "is a sibling branch already running this loop?" check, the `$in` iterator acquisition and the stack push all happen under one stack monitor (`loop_run_claim`). Two PARALLEL branches reaching the same loop concurrently cannot both pass an empty-stack check — the first arrival runs the loop body (task + iterations), later arrivals skip the whole node.

**Why:** Previously, concurrent arrivals could each observe an empty/not-yet-pushed stack and both run the body — a $for loop re-entered from parallel branches double-executed its iteration side effects. Sequential re-entry is handled too: an exhausted iterator left by a completed run is popped first, so a later re-entry (e.g. the node inside another loop's body) re-arms the loop with a fresh iterator — "exhaust → pop → re-arm".

**See also:** `FlowEngineImpl.java:loop_run_claim()` / `loop_run_out()` (`freeway-flow`)

---

## Loop-Body Join Counter Reset

**Decision:** At the start of every `$for` iteration, the engine resets the INCLUSIVE/PARALLEL join counters (and their provisional dead-ends) for every join node inside the loop body. The join set is computed once per (graph, loop node) per evaluation and cached in `ExecState.loopBodyJoins`.

**Why:** Join counters are keyed per (graph, node) and only activation resets them. A fork-join inside a loop body that received fewer arrivals than expected in one iteration would carry that residue into the next — the next iteration could falsely activate early, or twice. Resetting per iteration makes each iteration start from a clean count and re-record a dead end only if the join is still short.

**See also:** `FlowEngineImpl.java:resetLoopBodyJoins()` / `loopBodyJoins()` (`freeway-flow`)

---

## Sub-Graph Per-Eval Interceptor Inheritance

**Decision:** Sub-graph evals inherit the caller's per-eval interceptors: `FlowExchanger.runGraph()` stores the raw per-eval `FlowOptions` on the parent exchanger (`evalOptions`) and re-passes it to the sub-eval, so per-eval interceptors (`interceptFlow` / `onNodeStart` / `onNodeEnd`) cover sub-graph nodes too.

**Why:** Previously a sub-graph call lost the caller's per-eval interceptors — nodes inside the sub-graph ran with only engine-level interceptors. Only the *raw* options are propagated: each eval merges the engine-level interceptor list itself, so nested evals never run engine-level interceptors twice.

**See also:** `FlowExchanger.java:runGraph()` / `evalOptions()`, `FlowEngineImpl.java:eval()` (`freeway-flow`)

---

## Graph.fromText v2 Version Gate

**Decision:** `Graph.fromText()` now routes through `GraphSpec.fromText()` so it shares the same version gate as `GraphSpec`: only canonical v2 documents (`version=2` with `nodes` and `links`) load; anything else fails with the same clear `IllegalArgumentException` (naming the version field and any missing keys).

**Why:** The previous path (`GraphSpec.fromDom` directly) skipped the version check, so `Graph.fromText()` could accept non-canonical documents that `GraphSpec` rejected — the two entry points disagreed about what "a valid graph definition" means.

**See also:** `Graph.java:fromText()`, `GraphSpec.java:fromText()` (`freeway-flow`)

---

## FlowContext.putAll Null Filtering

**Decision:** `FlowContext.putAll(map)` skips `null` values — consistent with `put()`, which does not store `null`. A `null` map argument still fails fast (NPE), exactly like the previous `data().putAll(null)`.

**Why:** `Map.putAll` semantics would store a `null` value for a key, creating entries that `put()` would never create and that downstream readers must defensively handle. Unifying the two paths makes batch and single-key population behave identically.

**See also:** `FlowContext.java:putAll()`, `FlowContextImpl.java:putAll()` (`freeway-flow`)

---

## Design Philosophy

A summary of the project's architectural values as established in this session:

- **Small explicit APIs over future-proof abstractions.** Start with what's needed. Don't abstract for hypothetical future requirements.
- **Module as the IoC boundary.** IoC dependencies should not leak into the module's main codebase — they belong in the Module integration class.
- **Constructor injection for framework internals**, field injection acceptable for app code and config values.
- **No classpath scanning, no bytecode weaving.** Everything is explicit in `bind()`.
- **Records for value types.** `Route`, `HttpServerConfig`, `HttpServerConfig.Ssl` are records with compact constructors for validation.
- **Fail early and noisily.** Validation exceptions during construction, `BodyTooLargeException` during oversized request handling, shutdown exceptions always surfaced.
