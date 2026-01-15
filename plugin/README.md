# Chisel Debug Intrinsics Compiler Plugin

**Automatic debug metadata instrumentation for Chisel hardware.**

## Overview

Automatically instruments Chisel signals with debug metadata using Probe API for strong binding.

### Architecture

```
User Code → Plugin (AST transform) → DebugIntrinsic.emit() → Probe API → FIRRTL → CIRCT
```

**Unified Implementation:**
- Single source of truth: `DebugIntrinsic.emit()` with Probe API
- Used by: Plugin (auto), User API (manual), No FIRRTL transform needed
- Strong binding: Survives DCE/CSE/inlining

## Usage

### Enable in Build

```scala
scalacOptions += "-P:chisel:add-debug-intrinsics"
```

### Runtime Control

```bash
export CHISEL_DEBUG=true
sbt run
```

## Supported Constructs

| Construct | Binding | Example |
|-----------|---------|----------|
| `RegInit(...)` | `Reg` | `val state = RegInit(0.U)` |
| `Wire(...)` | `Wire` | `val data = Wire(UInt(8.W))` |
| `IO(...)` | `IO` | `val io = IO(new Bundle {...})` |
| `Mem(...)` | `Mem` | `val mem = Mem(16, UInt(8.W))` |

All generate Probe-based FIRRTL:

```firrtl
wire _probe : Probe<UInt<8>>
define(_probe, probe(signal))
node _dbg = intrinsic(circt_debug_typeinfo<...>, read(_probe))
```

## Configuration

| Option | Description |
|--------|-------------|
| `-P:chisel:add-debug-intrinsics` | Enable plugin transformation |
| `-P:chisel:verbose` | Verbose compilation logging |
| `CHISEL_DEBUG=true` | Runtime flag (enable intrinsics) |
| `chisel.debug.verbose=true` | Verbose debug output |

## Technical Details

### Plugin Transformation

**Input:**
```scala
val state = RegInit(0.U(8.W))
```

**Output:**
```scala
val state = {
  val _tmp = RegInit(0.U(8.W))
  if (DebugIntrinsic.isEnabled) DebugIntrinsic.emit(_tmp, "state", "Reg")
  _tmp
}
```

### Core Implementation

`DebugIntrinsic.emit()` uses Probe API:

```scala
val probe = Wire(Probe(data.cloneType))
define(probe, ProbeValue(data))
Intrinsic("circt_debug_typeinfo", ...)(read(probe))
```

**Why Probe API?**
1. Survives FIRRTL optimizations (DCE/CSE/inlining)
2. Type-safe at Scala compilation
3. CIRCT compatible (standard lowering)

## Development

- **Plugin:** `chisel/plugin/src/main/scala/chisel3/internal/plugin/`
- **Core:** `chisel/core/src/main/scala/chisel3/debuginternal/DebugIntrinsic.scala`
- **User API:** `chisel/src/main/scala/chisel3/util/circt/DebugInfo.scala`

### Tests

```bash
sbt "testOnly chiselTests.DebugIntrinsicsPluginSpec"
sbt "testOnly chiselTests.DebugIntrinsicSpec"
sbt "testOnly chiselTests.DebugInfoIntegrationSpec"
```

## References

- [Chisel Probe API](https://www.chisel-lang.org/docs/explanations/probes)
- [CIRCT Debug Dialect](https://circt.llvm.org/docs/Dialects/Debug/)
- [Tywaves Viewer](https://github.com/rameloni/tywaves-chisel)
- [ChiselTrace](https://github.com/rameloni/chisel-trace)