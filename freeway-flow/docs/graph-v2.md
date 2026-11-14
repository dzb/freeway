# freeway-flow v2 graph design (historical proposal draft)

> **Superseded**: see [graph-v3.md](graph-v3.md) for the current specification (`version=3`).
> This document is the design proposal from before v2 landed, retained as a decision trail — its "recommendations" (GraphCodec/
> GraphCompiler/CompiledGraph, dual-format transition compatibility) were not all adopted; do not write graphs based on it.
> The type names in this document (such as `FlowExchanger`) were renamed later (now `FlowEvaluation`),
> and the v2 format is rejected by the parser.

> Goal: define a canonical graph format suitable for Freeway. v2 is a Freeway-native design, not from solon-flow.
>
> Constraint (as of 1.5.2): **only v2 is recognized** — `version=2` + `nodes` + `links` is the sole gate; v1 is deleted,
> and there is no second reading. The rest of this document describes v2's fields and runtime model.

## 1. Design goals

The goal of the new graph is not to extend the old format, but to separate "definition", "compilation", and "execution".

1. The definition layer expresses only the business graph, not runtime state.
2. The compilation layer converts the definition into a read-only runtime graph.
3. The execution layer handles only the state transitions of a single flow.
4. The compatibility layer is responsible only for importing old graphs and does not enter the main design path.
5. The v2 runtime must be able to run both old and new graphs directly at the same time, as the unified entry point during the migration transition period.

## 2. New graph principles

### 2.1 Single field set

The new graph keeps only one field set, and no longer offers dual spellings such as `layout/nodes` and `when/condition`.

### 2.2 Explicit entry

The new graph must explicitly declare the entry node and no longer rely on inference rules such as "the first node without predecessors".

### 2.3 Conditions on links

Branch conditions should preferably be attached to the `link`, rather than scattered inside nodes.

### 2.4 Runtime state is not written back to the model

During execution, `context`, `trace`, `exchanger`, and stop flags are not part of the graph definition.

### 2.5 Read-only after compilation

Once a graph definition is compiled, the resulting runtime graph should be a read-only structure; nodes and links themselves are no longer rewritten at runtime.

## 3. Recommended model

### 3.1 Graph definition

```json
{
  "id": "order-flow",
  "title": "Order Flow",
  "driver": "default",
  "version": 2,
  "entry": "start",
  "meta": {
    "biz": "order"
  },
  "nodes": [
    { "id": "start", "type": "START" },
    { "id": "validate", "type": "ACTIVITY", "task": "validateOrder" },
    { "id": "approve", "type": "ACTIVITY", "task": "approveOrder" },
    { "id": "end", "type": "END" }
  ],
  "links": [
    { "from": "start", "to": "validate" },
    { "from": "validate", "to": "approve", "when": "${ok}" },
    { "from": "approve", "to": "end" }
  ]
}
```

### 3.2 Node fields

| Field | Description |
|---|---|
| `id` | Unique node identifier |
| `type` | Node type, must be explicitly declared |
| `title` | Display name, optional |
| `meta` | Node metadata, optional |
| `task` | Node task identifier or task name, optional |
| `when` | Node-level condition, used only when truly needed |

### 3.3 Link fields

| Field | Description |
|---|---|
| `from` | Source node id |
| `to` | Target node id |
| `priority` | Branch priority; a larger value takes precedence |
| `when` | Link condition |
| `meta` | Link metadata |

### 3.4 Graph fields

| Field | Description |
|---|---|
| `id` | Graph id |
| `title` | Graph title |
| `driver` | Driver name |
| `version` | Graph version; new graphs are recommended to fix it at `2` |
| `entry` | Explicit entry node id |
| `meta` | Graph-level metadata |

## 4. Java API recommendations

### 4.1 Definition layer

It is recommended to keep the current `GraphSpec` as the old-graph/compatibility entry, but add a separate new-graph API.

The new graph is named `GraphSpec2`.

### 4.2 Compilation layer

It is recommended to add a dedicated compilation object:

- `GraphCodec`: responsible for JSON/object conversion
- `GraphCompiler`: responsible for validation, completion, and topological compilation
- `CompiledGraph`: read-only runtime graph

Where:

- `GraphCodec` handles format.
- `GraphCompiler` handles rules.
- `CompiledGraph` handles execution.

### 4.3 Runtime layer

The runtime layer is recommended to continue to keep its existing responsibilities:

- `FlowEngine`: entry and registration
- `FlowContext`: single execution context
- `FlowExchanger`: single execution exchanger
- `FlowDriver`: concrete driver implementation

## 5. Compilation flow

It is recommended that a new graph go through compilation once before entering the engine:

1. Parse JSON or builder output.
2. Validate `id`, `entry`, node uniqueness, and link reference integrity.
3. Expand nodes and links, and build read-only indexes.
4. Derive the entry, outgoing-link order, and branch priority.
5. Generate an executable read-only graph object.

In this way, runtime only traverses the graph and no longer performs structural compensation.

## 6. Compatibility strategy

Old and new graphs should coexist in parallel, but with separate responsibilities. v2 first serves as the unified runtime entry, then gradually becomes the default entry for new graphs.

### 6.1 Old graph

The old graph retains the following compatibility points:

- `layout` / `nodes`
- `when` / `condition`
- `link` mixed single value/array/object
- entry inference

All this logic should live in the compatibility codec.

### 6.2 New graph

The new graph recognizes only the canonical schema:

- `version = 2`
- `entry` is explicitly present
- `nodes` / `links` are expressed structurally
- conditions, priorities, and metadata fields are fixed

### 6.3 Compatibility boundary

Do not add more historical aliases to the new graph.  
Once aliases keep being stuffed into the main format, later structural optimization will become complicated again.

### 6.4 v2 transition mode

v2's goal is "one runtime, two inputs":

1. Old applications continue to run with old graph configuration, but the v2 engine takes over execution.
2. New applications use `GraphSpec2` directly, also executed by the v2 engine.
3. After migration is complete, the old-graph compatibility branch can be removed from the compilation/parsing entry.

That is to say, v2 is not a temporary copy, but the formal layer carrying the transition.

## 7. Relationship with the existing implementation

In the current code, the following classes are more suitable as transitional implementations during the migration phase rather than the final form of the new graph:

- `GraphSpec`
- `NodeSpec`
- `LinkSpec`
- `FlowContextImpl`
- `FlowEventBus`
- `FlowExchanger`

They currently carry mixed responsibilities of construction, compatibility, and execution.  
The direction of the new graph design is to gradually split these responsibilities into:

- spec layer
- compilation layer
- runtime layer
- compatibility layer

## 8. Recommended implementation order

1. First extract `GraphCodec`, moving old graph parsing out of `GraphSpec`.
2. Then introduce `GraphSpec2`, expressing only the new graph.
3. Then add `GraphCompiler`, centralizing entry validation and topology checks.
4. Finally change the engine entry to accept the compiled read-only graph.

In this way the changes are progressive and will not tear apart the existing API all at once.

## 9. Conclusion

The core of the new graph is not "stronger expressiveness", but "clearer boundaries".

If summarized in one sentence:

- The old graph is responsible for compatibility with existing assets.
- The new graph is responsible for becoming the future default.
- Compatibility logic is responsible for the transition.
- Runtime logic handles execution only.
