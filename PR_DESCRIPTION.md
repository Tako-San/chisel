### Summary

Add opt-in debug instrumentation for Chisel designs via `--emit-debug-info` CLI option.

When enabled, emits `circt_dbg_variable` FIRRTL intrinsics for all signals (wires,
registers, memories, ports) carrying:
- `name`: signal's local name
- `type`: FIRRTL type string
- `chiselType`: Chisel class name (only for Bundle/Vec, omitted for ground types)
- Source location via standard `@[file line:col]`

This metadata enables typed waveform viewing (Tywaves) and source-level
hardware debugging (HGDB) without modifying user RTL code.

### Design decisions

- **Separate Phase** (`EmitDebugInfo`): runs between Elaborate and Convert,
  does not touch Elaborate.scala
- **Opt-in via annotation**: `EmitDebugInfoAnnotation` with `HasShellOptions`
  pattern (like `--throw-on-first-error`)
- **Module-level emission**: all intrinsics placed at module scope, not
  inside `when`/`layer` blocks, ensuring unconditional debug metadata
- **No Scala reflection**: type info extracted from Chisel `Data` API
  (`typeName`, `className`) — no `setAccessible`, no JDK version issues
- **`circt_dbg_variable` intrinsic**: aligned with CIRCT Debug Dialect
  roadmap (see llvm/circt#6816)

### CIRCT dependency

`circt_dbg_variable` is not yet registered in firtool. A companion CIRCT PR
is needed to lower it to `dbg.variable`. Until then, `emitCHIRRTL` output is
fully usable; `emitSystemVerilog` will require the CIRCT-side change.

### Type of Improvement
- Feature (or new API)

### Desired Merge Strategy
- Squash