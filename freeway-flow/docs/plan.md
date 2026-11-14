# Porting adaptation plan: solon-flow → freeway-flow

> **Historical document**: plan record from the v1/v2 era; see [graph-v3.md](graph-v3.md) for the current specification.
>
> Plan drafted: 2026-06-26  
> Implementation completed: 2026-06-26  
> Source baseline: solon-flow 4.0.2

---

## Plan overview

Extract the core graph orchestration engine from solon-flow (Graph → Node → Link model + FlowEngine execution engine + PlantUML export) and adapt it into freeway's `freeway-flow` module.

**Goal: zero new third-party dependencies**. It depends only on freeway-commons + freeway-ioc + slf4j (all already in the freeway framework).

## Module structure design

```
freeway-flow/
├── pom.xml
├── README.md
├── docs/
│   ├── assessment.md      ← Porting assessment
│   └── plan.md             ← This file
└── src/
    ├── main/java/com/jujin/freeway/flow/
    │   ├── package-info.java           ← Package-level notes (source project information)
    │   │
    │   ├── [Model layer — immutable runtime]
    │   │   ├── NodeType.java           enum: 7 node types
    │   │   ├── Graph.java              graph container + toPlantuml export
    │   │   ├── Node.java               node
    │   │   ├── Link.java               directed link
    │   │   ├── TaskDesc.java           task descriptor
    │   │   ├── ConditionDesc.java      condition descriptor
    │   │   ├── FlowTrace.java          execution trace
    │   │   └── NodeRecord.java         node visit record
    │   │
    │   ├── [Model layer — Builder/Spec]
    │   │   ├── GraphSpec.java          graph definition + JSON fromDom parsing
    │   │   ├── NodeSpec.java           node definition
    │   │   └── LinkSpec.java           link definition
    │   │
    │   ├── [Engine layer]
    │   │   ├── FlowEngine.java          engine interface + eval overloads
    │   │   └── FlowEngineDefault.java       core execution engine (traversal of 7 node types)
    │   │
    │   ├── [Runtime layer]
    │   │   ├── FlowExchanger.java       execution exchanger (internal state)
    │   │   ├── FlowContext.java         context interface
    │   │   ├── FlowContextImpl.java     context implementation
    │   │   ├── Temporary.java           gateway temporary state (stack/counters)
    │   │   └── FlowOptions.java         execution options (including interceptor list)
    │   │
    │   ├── [Driver layer]
    │   │   ├── FlowDriver.java          driver interface
    │   │   ├── SimpleFlowDriver.java    default driver (@bean/#graph/$meta resolution)
    │   │   ├── Container.java           component container interface
    │   │   ├── TaskComponent.java       task component @FunctionalInterface
    │   │   ├── ConditionComponent.java  condition component @FunctionalInterface
    │   │   └── NamedTaskComponent.java  named task component
    │   │
    │   ├── [Extension layer]
    │   │   ├── ExprEvaluator.java       minimal condition expression evaluator (recursive descent)
    │   │   ├── Stepper.java             numeric range iterator (used by LOOP nodes)
    │   │   ├── FlowEventBus.java        execution-level event bus (topic pub/sub)
    │   │   ├── FlowInterceptor.java     flow interceptor interface
    │   │   └── FlowInvocation.java      interceptor responsibility chain
    │   │
    │   ├── [PlantUML export]
    │   │   ├── PlantumlOptions.java      output options
    │   │   ├── PlantumlDisplayContext.java  display context
    │   │   └── PlantumlDisplayResult.java   display result
    │   │
    │   ├── [Exceptions]
    │   │   └── FlowException.java        flow exception
    │   │
    │   └── [Freeway integration]
    │       └── FlowModule.java           ModuleEx entry
    │
    └── test/java/com/jujin/freeway/flow/
        └── FlowEngineTest.java           12 test cases
```

**Final size: 35 Java files (including package-info), 12 test cases.**

## Implementation steps

### Step 1: Create the module skeleton

- Create the `freeway-flow/` directory structure
- Write `pom.xml` (dependencies freeway-commons + freeway-ioc + slf4j + junit)
- Register the module + dependencyManagement in the parent POM

### Step 2: Migrate base classes (zero internal dependencies)

Copy solon-flow source files directly, change the package + remove annotations:

| File | Change |
|---|---|
| `NodeType.java` | `org.noear.solon.flow` → `com.jujin.freeway.flow` |
| `FlowException.java` | Same as above |
| `Container.java` | Same as above |
| `TaskComponent.java` | Remove `@Preview`/`@FunctionalInterface` (keep semantics) |
| `ConditionComponent.java` | Same as above |
| `NamedTaskComponent.java` | Same as above |
| `Stepper.java` | Same as above (zero external dependencies) |
| `PlantumlOptions.java` | Same as above |
| `PlantumlDisplayResult.java` | Same as above |
| `PlantumlDisplayContext.java` | Same as above |
| `NodeRecord.java` | Remove the `@ONodeAttr` annotation |
| `Temporary.java` | Same as above (zero external dependencies, pure JDK) |

### Step 3: Add newly written classes

| File | Description |
|---|---|
| `ExprEvaluator.java` | ~280-line recursive-descent expression parser, supports `>`/`<`/`==`/`!=`/`>=`/`<=`/`&&`/`\|\|`/`!`/parentheses/variable paths |
| `FlowEventBus.java` | ~90-line topic pub/sub, `ConcurrentHashMap` + `CopyOnWriteArrayList` |

### Step 4: Migrate Spec/model classes (need rewriting)

**LinkSpec.java** / **NodeSpec.java** — pure Java; only needs `Utils.isEmpty()` replaced → inline null/empty check.

**ConditionDesc.java** / **TaskDesc.java** — same as above.

**Link.java** / **Node.java** — remove `transient final` (migrated to a non-serialized scenario), `Utils.isEmpty()` → inline.

**Graph.java** — core changes:
- `toJson()` / `toMap()` / `toYaml()` → remove snakeyaml, `ONode.serialize` → `JsonUtils.stringify`
- `toPlantuml()` and its helper methods — retained in full, with only `Utils.isEmpty()` replaced

**GraphSpec.java** — core rewrite (~80 lines):
- `fromDom(ONode dom)` → `fromDom(JsonObject dom)`, API mapping:

| snack4 API | freeway API |
|---|---|
| `dom.get("id").getString()` | `json.getString("id")` |
| `dom.get("layout").getArray()` | `json.getArray("layout")` |
| `dom.hasKey("key")` | `json.containsKey("key")` |
| `dom.get("meta").toBean(Map.class)` | `json.getObject("meta").toMap()` |
| `linkNode.isArray()` | `linkNode instanceof JsonArray` |
| `linkNode.isObject()` | `linkNode instanceof JsonObject` |
| `linkNode.isValue()` | `linkNode instanceof String\|Number\|Boolean` |
| `new ONode(OPTIONS).asObject()` | `JsonUtils.object()` |

### Step 5: Migrate context fields

**FlowTrace.java** — pure Java, zero external dependencies, migrated completely.

**FlowContext.java / FlowContextImpl.java** — core changes:
- Remove `DamiBus` in favor of `FlowEventBus` (self-written)
- Remove the `NonSerializable` marker interface
- In `toJson()`/`fromJson()`, `ONode` → `JsonObject`/`JsonUtils`
- Remove deprecated methods `vars()`/`serVars()`

### Step 6: Migrate the driver

**FlowDriver.java** — interface migration, remove `@Preview`/`@NonSerializable`.

**SimpleFlowDriver.java** — core changes:
- Remove the `AbstractFlowDriver` base class (inline its logic)
- Remove the `tryAsScriptTask()` branch — arbitrary script execution is no longer supported
- Condition evaluation is delegated to `ExprEvaluator`

### Step 7: Migrate the engine core

**FlowEngine.java** — interface migration, remove `ResourceUtil` (wildcard loading), remove `@Preview`.

**FlowEngineDefault.java** (formerly FlowEngineDefault) — core migration:
- All internal methods change from the `(exchanger, node, startNode)` signature to `(exchanger, options, node, startNode)` to pass options (including the interceptor list)
- `eval()` is wrapped with `FlowInvocation`
- `onNodeStart`/`onNodeEnd` notify all interceptors
- Replace `RankEntity` with the `FlowOptions.RankedInterceptor` record

### Step 8: Migrate the interceptors and event bus

> Note: step 5 of the original plan called for "cutting the interceptor chain", but in practice the user asked to keep it during implementation. The following is what was actually executed.

**FlowInterceptor.java** — interface migration, 3 methods (interceptFlow / onNodeStart / onNodeEnd), remove `@Preview`.

**FlowInvocation.java** — responsibility chain migration:
- Remove `RankEntity` and use the `FlowOptions.RankedInterceptor` record instead
- `(index, target)` → `(interceptor, index)` record implements `Comparable`

**FlowOptions.java** — originally an empty shell for the interceptor list, now implements:
- `RankedInterceptor` record (replaces solon's `RankEntity`)
- `interceptorAdd` / `interceptorList` management

### Step 9: Freeway integration

**FlowModule.java**:
- Implements `ModuleEx` and registers `FlowEngine` as a singleton
- Built-in `IocContainerAdapter`: adapts the freeway `Container` to the flow `Container`
- Fully qualified names handle the `Container` naming conflict

### Step 10: Testing

Write `FlowEngineTest.java`, covering:

| # | Test | Verification point |
|---|---|---|
| 1 | testLinearFlow | START→ACTIVITY→END linear flow |
| 2 | testExclusiveGateway | Exclusive gateway + conditional branches |
| 3 | testGraphFromJson | JSON definition parsing + execution |
| 4 | testExprEvaluator | Expression evaluator 14 assertions |
| 5 | testPlantuml | PlantUML export format verification |
| 6 | testSubGraph | #graphId subgraph call |
| 7 | testStop | ctx.stop() stops the flow |
| 8 | testEventBus | FlowEventBus standalone pub/sub |
| 9 | testEventBusInFlow | EventBus used inside Flow |
| 10 | testInterceptorChain | Interceptor order + node callbacks |
| 11 | testInterceptorStopFlow | Interceptor prevents flow execution |
| 12 | testMultipleInterceptors | Nested ordering of multiple interceptors |

## Differences from the original plan

During implementation, the following adjustments were made based on user feedback (all are **additions** to the original plan and do not affect compatibility):

| Original plan | Actual execution | Reason |
|---|---|---|
| Cut the EventBus | Kept; self-written `FlowEventBus` (~90 lines) | User request; feasible with zero dependencies |
| Cut the interceptor chain | Fully kept `FlowInterceptor` + `FlowInvocation` | User request; feasible with zero dependencies |
| Single evaluation round | Two evaluation rounds: EventBus → interceptor chain | User confirmed step by step |
| README only | Added `package-info.java` + `docs/` directory | User requested retaining complete documentation |

## Verification results

```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Original copyright

```
Original code copyright (c) 2017-2025 noear.org and authors
Licensed under the Apache License, Version 2.0

Ported and adapted to the freeway framework, retaining the original license terms.
```
