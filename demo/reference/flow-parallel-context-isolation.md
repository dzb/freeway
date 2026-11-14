# freeway-flow Parallel Branch Context Isolation Plan

## Current State (2026-09, after the engine redesign)

The fork/overlay/join isolation model is implemented and has become the default semantics; for the schema see the `join` declaration in `freeway-flow/docs/graph-v3.md`.

- A `PARALLEL` node defaults to `join: "merge"`: each branch executes in a
  thread-local write buffer opened by `FlowContext.beginBranch()` — reads inside a branch are
  "buffer first, parent as fallback", so branches never see each other's partial work; after a branch completes cleanly
  `merge` folds it into the parent layer, and when the same key is written to different values by two branches based on the same base value, the merge reports a conflict with a `FlowException` and names that key
  (rather than silently picking one). If a branch aborts with an exception, its buffer is discarded entirely — partial work never reaches the parent.
- An explicit `join: "shared"` falls back to shared writes: no buffer, no conflict detection, and single-writer discipline is left to the graph itself to guarantee.
- Execution state (`ExecState` counters/loop stack/dead ends) is still shared across subgraph evaluation, but is confined to an engine-private (graph,node) key space,
  so a custom driver can no longer write into the same string bag; the `$for` `peek→hasNext→pop` preemption still completes atomically inside the stack monitor.

The following keeps the design argument from that time (why isolate, the goals, and the model tradeoffs) as a decision trail; the implementation lands in `FlowContextImpl`'s
Layer/merge and in `FlowEngineDefault.runBranch`.

## Background

`freeway-flow` currently shares one `FlowContext` across parallel branches. Although the underlying implementation uses concurrent containers, that only guarantees thread safety at the container level, not consistency at the flow-semantics level.

The real problem is not "will it crash", but:

- Branches A and B may write the same key at the same time, and the final result depends on thread scheduling.
- One branch may read an intermediate state that another branch has just written but not yet completed.
- Replay, troubleshooting, and testing are all hard to reproduce reliably.

If callers should never have to care about thread safety, design parallel branches as "isolated execution + join commit".

## Goals

- Callers do not need to handle thread safety.
- Parallel branches do not affect each other by default.
- Parallel execution results are predictable, replayable, and testable.
- For scenarios that genuinely need shared state, provide an explicit sharing mechanism instead of implicitly sharing the entire `FlowContext`.

## Proposed Model

### 1. Split Runtime State from Business Data

The context should be split into two layers:

- `FlowRunState`: shared execution state
- `FlowContext`: branch-level business data

The shared execution state should keep:

- `trace`
- `eventBus`
- `stopped` / `interrupted`
- `stepCount`
- `temporary`

Business data, by contrast, is not shared directly through a `ConcurrentHashMap`; instead each parallel branch gets its own view.

### 2. Branch Contexts Use fork / overlay

On entering a parallel branch, create a child context for each branch:

- on read, consult the branch's own overlay first
- fall back to the parent snapshot when it is not found
- writes land only in the branch's own overlay

This guarantees:

- isolation between branches by default
- stable read/write semantics inside a branch
- when debugging, exactly what each branch modified is visible

### 3. Explicit merge at join

After all parallel branches finish, the engine merges the results at the join point:

- a key modified by a single branch is merged straight back into the parent context
- a key modified by multiple branches reports a conflict by default
- if truly needed, a merge strategy can be configured for specific keys

The recommended default strategy is fail fast, not "the last write overwrites earlier writes".

## Compatibility Recommendations

### Stay Compatible with Existing Flow Control

The following objects should continue to be shared:

- `trace`
- `eventBus`
- `stopped` / `interrupted`
- `temporary`

The reason is that these objects describe "this run", not business data owned exclusively by some branch.

### Keep Explicit Sharing Capability

For data that genuinely needs cross-branch coordination, do not keep stuffing it into the main `FlowContext` data area.

A better approach is to expose a separate explicit shared object, for example:

- `SharedState`
- `BranchMailbox`
- `ConcurrentState`

That makes the semantics clearer and avoids dragging every scenario into a shared-memory model.

## Implementation Recommendations

### Phase 1

- Keep the existing API unchanged.
- Add a context fork mechanism for parallel branches.
- By default isolate only `data` and share the execution state.

### Phase 2

- Add join merge strategies.
- Support basic merge first, then conflict detection.

### Phase 3

- Introduce an explicit shared-state API.
- Provide dedicated interfaces for scenarios that need cross-branch collaboration.

## Testing Recommendations

The following regression tests should be added:

- Parallel branches write different keys, and all of them are preserved after join.
- Parallel branches write the same key, and a conflict is reported by default.
- A read inside a branch sees only its own writes and the parent snapshot.
- The result stays stable when the execution order of parallel branches changes.

## Conclusion

Sharing one `FlowContext` across parallel branches is not the optimal design.  
If the goal is for callers never to consider thread safety, the safest scheme is:

- shared execution state
- branch-isolated business data
- unified merge at join
- explicit conflict handling

This matches the developer experience of a framework product better than merely using concurrent containers.
