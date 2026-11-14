# freeway-flow migration change notes

> **Historical document**: plan record from the v1/v2 era; see [graph-v3.md](graph-v3.md) for the current specification.
>
> Subject: `freeway-flow`
>
> Background: the module's **v1 definition format (`GraphSpec`)** was ported from `solon-flow` to the Freeway architecture. This document records the actual changes and compatibility strategy after the port.
>
> The **v2 format** is a Freeway-native design, not from solon-flow — carried by `GraphSpec` under the
> `version=2` marker (the early class name `GraphSpec2` was merged into `GraphSpec` starting with 1.3.6) — see [graph-v2.md](graph-v2.md).

## 1. Migration goals

The goal of `freeway-flow` is not to rewrite a new flow engine, but to retain the original graph orchestration semantics in Freeway while changing the dependency approach, runtime boundaries, and extension access to follow the Freeway style.

Core principles:

1. Existing graph definitions remain runnable.
2. Runtime state and model state are separated.
3. No additional framework dependencies are introduced.
4. All integration points are explicit, with no classpath scanning.

## 2. Completed migration changes

### 2.1 `FlowModule`

- Changed from Solon-style component discovery to explicit module binding in Freeway IoC.
- Register `FlowEngine` through `ModuleEx` / `Binder`.
- Map the Freeway container to the flow-side component resolution interface through `IocContainerAdapter`.
- Retain typed registration of `TaskComponent`, so migrated task handlers can continue to integrate directly.

### 2.2 `FlowEngineDefault`

- Retain node traversal, conditional branches, subgraph calls, and the interceptor chain.
- Driver resolution changed to explicit lookup by the graph's `driver` name.
- The default driver still serves as a fallback and does not rely on container scanning.
- Control bits such as pause, stop, and rollback remain execution-time state and are not written into the graph model.

### 2.3 `FlowContextImpl`

- The context converges into a per-flow-execution object.
- `exchanger`, `eventBus`, and `trace` exist only as runtime data.
- The `context` self-reference is excluded during serialization.
- `trace` is compatible with the object format and the old string format, so historical data loads smoothly.

### 2.4 `FlowEventBus`

- Migrated from global `DamiBus` semantics to a local pub/sub bound to `FlowContext`.
- A subscription callback exception is isolated to the current subscriber only and does not affect other subscribers on the same topic.
- The scope is limited to a single flow execution and no longer carries application-level event bus responsibilities.

### 2.5 `GraphSpec`

> **This section is obsolete (1.5.2)**: the v1 graph definition, along with legacy field compatibility, was deleted. The following describes the historical strategy of the v1→v2 transition period
> and is no longer the current state; the current state is the sole `version=2` gate (`GraphSpec.fromText`), and v1 documents report errors directly.

- ~~Retain legacy field compatibility: `layout` / `nodes`, `when` / `condition`, multiple forms of `link`.~~
- Serialization output converges uniformly to the current `nodes` + `links` structure.
- ~~Retain start-node inference during parsing, ensuring old graphs can continue to run.~~ (entry must be explicitly declared)

### 2.6 `FlowExchanger`

- Centralize the execution state, context, and step counter shared by subgraph calls into the runtime object.
- `reverting`, `stopped`, and `interrupted` represent only control signals during execution.
- `copy()` is used only to reuse execution state in subgraph jump scenarios.

### 2.7 `NodeType`

- Unknown types still fall back to `ACTIVITY`.
- This fallback exists to be compatible with existing graphs and default graph definitions.
- New graphs are advised to declare types explicitly.

## 3. Explanatory changes added today

Today's work is not a functional logic change; it adds the migration declarations to the code so that the work does not stop at abstract descriptions:

1. Added notes on runtime boundaries, serialization compatibility, and self-reference exclusion to the `FlowContextImpl` class comment.
2. Added to the `FlowEventBus` class comment the reason for migrating from a global bus to a context-local event bus.
3. Added to the `FlowEngineDefault` class comment notes on runtime state convergence, explicit driver lookup, and control-bit ownership.
4. Added to the `FlowModule` class comment the reasons for Freeway IoC explicit binding and container adaptation.
5. Added to the `GraphSpec` class comment the reasons for legacy field compatibility and unified output structure.
6. Added to the `FlowExchanger` class comment notes on centralized execution-state management and `copy()` semantics.
7. Added to the `NodeType` class comment the reason for the unknown-type fallback strategy.

The purpose of these notes is to make the porting traces visible in the code, so that later readers of the source can directly understand why the change was made rather than seeing only the result.

## 4. Compatibility boundary

- Being compatible with old graphs does not mean the old spelling is the recommended spelling.
- Runtime objects should not be treated as part of a stable protocol.
- The local event bus serves only a single flow execution and does not replace the application-level event system.
- The fallback strategy is only for the migration period; future new graphs should declare things explicitly wherever possible.

## 5. Notes

If another structural optimization is done later, it is recommended to continue converging along these two directions:

1. Further compress cross-references between runtime state and model state.
2. Localize old-format compatibility logic as much as possible, so compatibility branches do not scatter across multiple classes.
