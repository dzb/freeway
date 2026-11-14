# Task Resolution

> **Deprecated/historical document**: this document describes the v1/v2 task resolution mechanism, which was deleted with v3;
> see the "Vocabulary" section of [graph-v3.md](graph-v3.md) for the current vocabulary.

freeway-flow's task resolution matches the graph's `task` field to the actual executor through four strategies.

## v1/v2 historical strategies (deleted)

| Priority | Strategy | Syntax | Description |
|--------|------|------|------|
| 1 | Inline component | `task.component()` | `TaskComponent` set directly in code |
| 2 | Marker matching | `!marker` | `FlowMarkerIndex` intersection matching, most specific wins |
| 3 | Subgraph call | `#graph` | Calls a named subgraph |
| 4 | Bean reference | `@bean` | Container `get(TaskComponent.class, id)` |
| 5 | Metadata | `$meta` | Takes a value from graph meta |

## @FlowMarker

`@FlowMarker` is a repeatable annotation that marks `TaskComponent` implementation classes:

```java
@FlowMarker("channel:email")
@FlowMarker("priority:high")
public class EmailSender implements TaskComponent {
    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        // Send email
    }
}
```

Graph definitions reference it via `!markerName`:

```json
{ "task": "!channel:email !priority:high" }
```

`FlowMarkerIndex.resolve()` matches by `containsAll` semantics — the handler's marker set must contain all markers declared by the node. When multiple handlers match, the handler with the most markers wins.

## History

> Early versions supported "typed tasks" — referencing `TaskComponent` implementation classes directly by fully qualified class name (e.g. `"com.example.StockCheckHandler"`). That mechanism was implemented through `FlowEngine.register(Class, TaskComponent)` and `typedTasks()`, and was removed in v1.3.0, replaced by `@FlowMarker` + `!markerName`.
