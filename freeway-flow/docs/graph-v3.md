# freeway-flow v3 graph schema (current specification)

> This document describes the **only current** graph format: `version=3`. The build recognizes only this shape —
> `version` is 3 and `nodes`/`links` are present; anything else throws `IllegalArgumentException` and states what is missing.
> v1 (`layout` dual spelling) was deleted in 1.5.2; v2 was upgraded to v3 in the 1.5.3 engine redesign
> (vocabulary convergence + `data` field + `join` declaration + build-time expression compilation), with no alias compatibility.
> For the historical design process, see `graph-v2.md` (proposal draft, including items not adopted) and `migration-notes.md`.

## 1. Principles

1. **Separate definition, compilation, and execution**: the definition expresses only the business graph; `create()` is compilation (validation + read-only conversion);
   execution walks only the iterative frontier and never goes back to compensate for structure.
2. **Everything statically knowable is known at boot**: cycles, entry, link references, task vocabulary, condition expressions,
   `join` declarations and values, `data` keys — failures at build time are never encountered at runtime.
3. **Closed vocabulary**: the legal forms of task and when are an enumeration rather than heuristics; unrecognized spellings report an error and point the way,
   never "guess the closest one".
4. **Runtime state is not written back to the model**: context, stop signals, join counts, and dead-end markers all belong to a single evaluation and do not enter the graph definition.
5. **Explicit isolation**: write isolation and merge strategy for parallel branches are a declaration on the graph (`join`), not an apology in javadoc.

## 2. Shape

```json
{
  "id": "order-flow",
  "title": "Order Flow",
  "driver": "default",
  "version": 3,
  "entry": "start",
  "meta": { "biz": "order" },
  "nodes": [
    { "id": "start",    "type": "START" },
    { "id": "validate", "type": "ACTIVITY", "task": "@validateOrder" },
    { "id": "gw",       "type": "EXCLUSIVE" },
    { "id": "fork",     "type": "PARALLEL", "meta": { "join": "merge" } },
    { "id": "notify",   "type": "ACTIVITY", "task": "#notification" },
    { "id": "archive",  "type": "ACTIVITY", "data": { "verdict": "archived" } },
    { "id": "end",      "type": "END" }
  ],
  "links": [
    { "from": "start", "to": "validate" },
    { "from": "validate", "to": "gw", "when": "order.status == \"paid\"" },
    { "from": "gw", "to": "fork" },
    { "from": "fork", "to": "notify" },
    { "from": "fork", "to": "archive" },
    { "from": "notify", "to": "end" },
    { "from": "archive", "to": "end" }
  ]
}
```

### 2.1 Graph fields

| Field | Description |
|---|---|
| `id` | Graph id (required, non-empty) |
| `title` | Display name, optional |
| `driver` | Driver id; absent/blank/`"default"` uses the default driver |
| `version` | Fixed at `3` — the gate field |
| `entry` | Explicit entry node id; when absent, exactly one START is required |
| `meta` | Graph-level metadata |
| `nodes` / `links` | See below |

### 2.2 Node fields

| Field | Description |
|---|---|
| `id` | Unique within the graph; also used as a PlantUML identifier (restricted to `[A-Za-z_][A-Za-z0-9_]*`) |
| `type` | Required: `START` / `END` / `ACTIVITY` / `EXCLUSIVE` / `INCLUSIVE` / `PARALLEL` / `LOOP` (case-insensitive; any other value reports an error and lists the legal values) |
| `title` | Display name |
| `task` | Vocabulary: `@name` / `#graphId`; absent = no task (empty activity) |
| `data` | Static value object; when the node executes (after `when` matches and before the task) each key is written into context — replacement for the v2 `$meta` task |
| `when` | Node-level condition (expression or `@name`); if it does not match, the task is skipped but outgoing links are still evaluated |
| `meta` | Node metadata; reserved keys: `join` (PARALLEL only; value `merge`\|`shared`), `$for`/`$in` (LOOP only) |

### 2.3 Link fields

| Field | Description |
|---|---|
| `from` / `to` | Must reference declared nodes |
| `when` | Link condition (expression or `@name`), empty = unconditional |
| `priority` | Branch order for EXCLUSIVE (larger value first) and general outgoing-link ordering |
| `title` / `meta` | For display |

### 2.4 Vocabulary

**task**:
- `@name` → `container.get(TaskHandler.class, name)` (the condition slot uses `ConditionHandler`);
  when unbound, reports an error and gives both the bind and contribute spellings.
- `#graphId` → subgraph call: shares this evaluation's join counts and dead-end report; if the subgraph does not reach END, throws at the call site.
- inline handler (programmatic) executes directly.
- everything else is a build-time error (`$x` → points to `data`; `!x` → points to a contribution with an id).

**when (link or node)**:
- `@name` → condition handler reference (resolved at runtime);
- otherwise compiled **at build time** according to the `ExprEvaluator` grammar: comparisons, `&& || ! and or`, parentheses, arithmetic
  `+ - * / %`, literals, `a.b.c` paths, list indexing; numeric strings originating from JSON are compared as numbers.

## 3. LOOP

`meta.$for` = bound variable name, `meta.$in` = list / context key / `"start...end"` / `"start:end:step"`.
The body iterates sequentially (no back links; the graph must be a DAG); each round resets the fork-join counts in the body; the iteration count is capped at
100,000 (configuration errors must be loud, not idle spinning). Multiple branches may converge on the same LOOP: exactly one branch claims it
(the check + iterator acquisition is atomic), and all other branches skip the entire node.

## 4. Execution model (v3)

- **Iterative frontier**: no path-length stack limit (a 20k-node chain has the same depth as 10 nodes); only subgraph nesting/nested
  parallelism is constrained by authoring depth.
- **Stop**: `ctx.stop()` = deliberate early completion, legal, and does not trigger a dead-end error. An interceptor that does not call `proceed()`
  vetoes the entire evaluation, which is equally legal.
- **Loud dead ends**: EXCLUSIVE with no match and no default, or a join not fully collected → the evaluation ends with `FlowException` naming the node and the graph.
- **Pairing invariant**: every `onNodeStart` has exactly one `onNodeEnd` (including failure paths).
- **Branch isolation**: `merge` branch write buffering + clean merge + a conflict is an error; `shared` is an explicit opt-out.
- **Failure classification**: configuration errors (`IllegalArgumentException`/`IllegalStateException`) are rethrown as-is;
  execution failures are wrapped as `FlowException` (with the `TASK_FAILED` prefix).

## 5. Compatibility boundary

No more historical aliases are introduced. The v2→v3 breaking points (`$meta`, `!marker`, pause/resume/steps,
`FlowOptions`/`FlowInvocation`/`FlowContainer`) are recorded in the CHANGELOG; when old documents or old configurations
hit these shapes, the build-time error is the migration path.
