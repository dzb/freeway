# freeway-flow

An in-process graph orchestration engine: loads a DAG and executes it with semantics that include conditions, gateways, and branch isolation. The graph format is
a **Freeway-native design** (`version=3`, explicit entry, separated nodes/links, full build-time validation); the engine semantics are
defined by Freeway itself and do not follow upstream behavior. 7 node types, a closed task vocabulary, and a validation posture that reports errors at boot,
in keeping with the rest of the framework.

> **Boundary**: this is an orchestrator, not a durable workflow engine — it provides no cross-process persistence or checkpoint recovery,
> and execution is synchronous and in-JVM.

## Sources and license

The engine lineage traces back to [opensolon/solon-flow](https://github.com/opensolon/solon-flow) 4.0.2
([Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)). What is retained is the node taxonomy and
several traversal invariants; the graph schema, task vocabulary, execution model (iterative frontier walk), join/loop semantics, and validation posture
are all Freeway rewrites. Zero new third-party dependencies:

| solon-flow dependency | Purpose | freeway-flow replacement |
|---|---|---|
| `snakeyaml` | YAML graph definitions | **Removed** — JSON only |
| `snack4` (ONode) | JSON serialization | `freeway-commons` JsonUtils |
| `dami2` (DamiBus) | Execution-level event bus | `FlowEventBus` (~90 lines) |
| `liquor-eval` (Scripts) | Script/task evaluation | **Removed** — tasks are only `@name` / `#graphId` / inline handler |
| `solon-expression` (SnelParser) | Condition expressions | `ExprEvaluator` (standalone recursive-descent implementation) |
| `RankEntity` | Interceptor ordering | Container extension chain (ordered at assembly time, immutable after load) |

## Node semantics

| Type | Semantics |
|---|---|
| `START` | Entry; advances along matching links |
| `END` | Marks the graph complete; a run that does not reach END and is not deliberately stopped fails |
| `ACTIVITY` | Writes `data` → runs task → and-fan-out along matching links |
| `EXCLUSIVE` | Chooses one: the first matching `when`, otherwise the default link, otherwise a **dead-end error** |
| `INCLUSIVE` | Joins first (each arriving predecessor branch counted once), then runs, then fans |
| `PARALLEL` | Joins first, then forks; with `join:"merge"` (default), branch write isolation + conflict detection; `"shared"` explicitly falls back to sharing |
| `LOOP` | `$for`/`$in` iterate the body (sequential, with join counts inside the body reset each iteration); without `$for` it is an ordinary gateway |

## Vocabulary (validated at build time)

- **task**: `@name` (the container resolves `TaskHandler`/`ConditionHandler` by id), `#graphId` (subgraph,
  errors at the call site if END is not reached), inline handler (programmatic). v1/v2 `$meta` and `!marker` are removed:
  use the node `data` field for static values, and contribute with an id + `@name` for marker matching — old forms error at
  **build time** with guidance.
- **when (condition)**: `ExprEvaluator` expression (`> < >= <= == != && || !`, parentheses, `a.b.c` paths,
  list indexing), or an `@name` handler reference. All expressions compile in `create()`; an invalid expression fails at startup.
- **join**: a reserved meta key on PARALLEL nodes only, `merge` (default) or `shared`.

## Quick start (v3)

```java
String json = """
{
  "version": 3,
  "id": "demo", "entry": "s",
  "nodes": [
    { "id": "s",    "type": "start" },
    { "id": "gw",   "type": "exclusive" },
    { "id": "high", "type": "activity", "task": "@handler" },
    { "id": "low",  "type": "activity", "data": { "verdict": "archived" }, "task": "@handler" },
    { "id": "e",    "type": "end" }
  ],
  "links": [
    { "from": "s",  "to": "gw" },
    { "from": "gw", "to": "high", "when": "score > 80" },
    { "from": "gw", "to": "low",  "when": "score <= 80" },
    { "from": "high", "to": "e" },
    { "from": "low",  "to": "e" }
  ]
}""";

// IoC assembly: tasks = container bindings with ids, interceptors = contributed extensions (chain fixed at load time, immutable afterward)
App app = Freeway.create(new FlowModule(), binder -> {
    binder.contribute(TaskHandler.class).add("handler", (ctx, node) ->
        System.out.println(ctx.get("verdict")));
    binder.contribute(FlowInterceptor.class).add("audit", new FlowInterceptor() {
        @Override public void onNodeStart(FlowContext ctx, Node node) {
            System.out.println("-> " + node.id());
        }
    });
});

Graph graph = Graph.fromText(json);      // build-time validation: cycles/entry/vocabulary/expressions/join
FlowContext ctx = FlowContext.of();
ctx.put("score", 95);
app.get(FlowEngine.class).eval(graph, ctx);

// Standalone use (no container): only inline handlers and #subgraphs are supported
FlowEngine standalone = FlowEngine.create();
```

Branch isolation: under `join: "merge"` (the default), each PARALLEL branch writes to a thread-local buffer and merges on clean completion;
if two branches change the same key from the same base value → the merge reports a conflict rather than silently choosing one.

## Module dependencies

`freeway-ioc` + `freeway-commons` + slf4j-api (in-framework modules, zero new third-party).

```
Original code copyright (c) 2017-2025 noear.org and authors,
licensed under the Apache License 2.0; the Freeway rewrite retains the original license terms.
```
