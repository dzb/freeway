# Commons Reference

Commons provides the small, shared runtime utilities used across Freeway.

## Stable API

- `JsonCodec`
- `JsonCodecDefault`
- `JsonUtils`
- `Defer`
- `ScopedCache`
- `BeanValidator`
- `Coercer`
- `CoerceRule`
- `LoggerSource`

## Scoped Primitives

`Defer` buffers side effects until the enclosing scope commits. `ScopedCache` memoizes values for the lifetime of a scope and runs cleanup on exit.

### Use `Defer` When

- work should happen only after the current unit of work succeeds
- side effects must stay ordered until commit time
- the agent sees a success boundary and the body is side effects, not state reuse

### Use `ScopedCache` When

- a value should be created once per scope and reused inside that scope
- the value must be cleaned up when the scope exits
- the agent sees repeated resolution of the same value in one boundary

### Do Not Use Them When

- cleanup must happen even on failure
- work must cross threads without losing context
- the value should outlive the current scope

For more details, see:

- [Defer summary](../freeway-defer-summary.md)
- [DB usage guide](../freeway-db-how-to-use.md)

## JSON

Use `JsonUtils` for direct parse/serialize helpers and `JsonCodec` when you want an injectable codec.

## Coercion

Use `Coercer` for string-to-type conversion. Add `CoerceRule` when you need a custom target type.

## Validation

Use `BeanValidator` for annotation-driven validation. Keep validation close to request or config boundaries.

