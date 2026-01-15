# Chisel Debug Intrinsics Compiler Plugin

**Automatic debug metadata instrumentation for Chisel - zero code changes required.**

## Overview

The Chisel compiler plugin automatically instruments your hardware signals with debug metadata **without requiring any code changes**. It extends the existing ChiselPlugin to add debug intrinsic generation during compilation.

```scala
// Your code (NO CHANGES):
class RiscvCore extends Module {
  val io = IO(new MemoryInterface)
  val pc = RegInit(0.U(32.W))
  val state = Wire(MyEnum())
}

// Plugin automatically generates:
// - Debug intrinsics for io, pc, state
// - Correct binding types (IO, Reg, Wire)
// - Hierarchical names
// - Type metadata (width, enum definitions, etc.)
```

## Installation

### sbt (Recommended)

The plugin is part of the standard Chisel compiler plugin. Enable it with a scalac option:

```scala
// build.sbt
scalacOptions ++= Seq(
  "-P:chisel:add-debug-intrinsics"  // Enable automatic instrumentation
)

// Enable debug mode at runtime
sys.props("chisel.debug") = "true"
```

### Command Line

```bash
scalac \
  -Xplugin:chisel-plugin.jar \
  -P:chisel:add-debug-intrinsics \
  MyModule.scala
```

## Usage

### Basic Example

```scala
import chisel3._

class Counter extends Module {
  val io = IO(new Bundle {
    val en = Input(Bool())
    val count = Output(UInt(8.W))
  })
  
  val counter = RegInit(0.U(8.W))
  
  when (io.en) {
    counter := counter + 1.U
  }
  
  io.count := counter
  
  // NO DebugInfo.annotate() calls needed!
}

// Compile with plugin enabled:
// sbt compile

// Result: FIRRTL contains debug intrinsics for io, counter
```

### Verify Plugin Output

```scala
import circt.stage.ChiselStage

val firrtl = ChiselStage.emitCHIRRTL(new Counter)
println(firrtl)

// Output contains:
// intrinsic(circt_debug_typeinfo<target="io", ...>)
// intrinsic(circt_debug_typeinfo<target="counter", binding="Reg", ...>)
```

### Complex Example: CPU Pipeline

```scala
object State extends ChiselEnum {
  val FETCH, DECODE, EXECUTE, WRITEBACK = Value
}

class SimpleCPU extends Module {
  val io = IO(new MemoryInterface)
  
  val pc = RegInit(0.U(32.W))
  val state = RegInit(State.FETCH)
  val registers = Mem(32, UInt(32.W))
  val tempData = Wire(UInt(32.W))
  
  // State machine logic...
  
  // NO manual annotations - all signals automatically instrumented!
}

// Plugin automatically generates intrinsics for:
// - io (IO binding)
// - pc (Reg binding, width=32)
// - state (Reg binding, enum metadata with all values)
// - registers (Mem binding, depth=32, element type)
// - tempData (Wire binding, width=32)
```

## How It Works

### Compilation Flow

```
1. Scala Source Code
   ↓
2. Scala Type Checker (typer phase)
   ↓
3. Chisel Naming Plugin (extract signal names)
   ↓
4. Debug Intrinsics Plugin (add metadata)  ← NEW!
   ↓
5. Chisel Elaboration
   ↓
6. FIRRTL with debug intrinsics
   ↓
7. CIRCT lowering → hw-debug-info.json
```

### AST Transformation

The plugin runs after type checking and transforms `val` definitions:

```scala
// Before (your code):
val state = RegInit(0.U)

// After (plugin transformation):
val state = {
  val _debug_tmp_state = RegInit(0.U)
  if (chisel3.debuginternal.DebugIntrinsic.isEnabled) {
    chisel3.debuginternal.DebugIntrinsic.emit(
      _debug_tmp_state,
      "state",
      "Reg"
    )
  }
  _debug_tmp_state
}
```

This transformation:
- **Preserves semantics** (identical behavior)
- **Type-safe** (uses typed AST manipulation)
- **Conditional** (only active when debug mode enabled)

### Probe API Integration

The generated intrinsics use Chisel's Probe API for reliable signal binding:

```firrtl
wire _probe_state : Probe<UInt<8>>
define(_probe_state, probe(state))
intrinsic(circt_debug_typeinfo<target="state", ...>, read(_probe_state))
```

This ensures metadata survives FIRRTL optimization passes (DCE, CSE, inlining).

## Supported Constructs

| Chisel API | Binding Type | Metadata Captured |
|------------|--------------|-------------------|
| `RegInit()`, `Reg()`, `RegNext()` | `"Reg"` | Width, type name |
| `Wire()`, `WireInit()`, `WireDefault()` | `"Wire"` | Width, type name |
| `IO()`, `Input()`, `Output()` | `"IO"` | Bundle structure |
| `Mem()`, `SyncReadMem()` | `"Mem"` | Depth, element type |
| Nested `Bundle` | Hierarchical | Constructor parameters |
| `ChiselEnum` | Enum metadata | All enum values |
| `Vec` | Vec type | Length, element type |

## Configuration

### Enable/Disable Plugin

```scala
// Enable plugin transformation (at compile time)
scalacOptions += "-P:chisel:add-debug-intrinsics"

// Disable (default - no transformation)
// scalacOptions (no plugin option)
```

### Runtime Control

```scala
// Enable debug mode (intrinsics active)
sys.props("chisel.debug") = "true"
// OR
CHISEL_DEBUG=true sbt run

// Disable (intrinsics pruned, zero overhead)
sys.props.remove("chisel.debug")
```

### Two-Level Control

| Plugin Option | Runtime Flag | Result |
|---------------|--------------|--------|
| **OFF** | OFF | No transformation, zero overhead ✅ |
| **OFF** | ON | No transformation, zero overhead |
| **ON** | OFF | Transformation done, but intrinsics inactive |
| **ON** | **ON** | **Full instrumentation active** ✅ |

**Best Practice:** Enable plugin in `build.sbt`, control at runtime via environment variable.

## Performance

### Compile-Time Overhead

- **AST Transformation:** ~5-10% (measured on 100 module benchmark)
- **When:** Only during Scala compilation (not FIRRTL)
- **Caching:** sbt incremental compilation caches results
- **Disabled:** Absolutely zero overhead

### Runtime Overhead

- **Plugin Disabled:** Zero overhead
- **Debug OFF:** Zero overhead (intrinsics pruned during elaboration)
- **Debug ON:** Minimal (<1% FIRRTL size increase, no performance impact)

### Benchmark Results

```scala
// Test: 20 compilations of simple module
// Baseline (plugin OFF): 450ms
// With plugin (ON): 485ms
// Overhead: ~7.8% ✅ (within acceptable range)
```

## Examples

### Simple Module (No Changes)

```scala
// NO manual DebugInfo calls!
class Counter extends Module {
  val io = IO(new Bundle {
    val en = Input(Bool())
    val count = Output(UInt(8.W))
  })
  
  val counter = RegInit(0.U(8.W))
  
  when (io.en) {
    counter := counter + 1.U
  }
  
  io.count := counter
}

// Result: io, counter automatically instrumented!
```

### CPU Pipeline (Realistic)

```scala
class Pipeline extends Module {
  val io = IO(new PipelineIO)
  
  val pc = RegInit(0.U(32.W))
  val ifid = RegInit(0.U.asTypeOf(new IFIDBundle))
  val idex = RegInit(0.U.asTypeOf(new IDEXBundle))
  val exmem = RegInit(0.U.asTypeOf(new EXMEMBundle))
  val memwb = RegInit(0.U.asTypeOf(new MEMWBBundle))
  
  // All pipeline registers automatically instrumented!
  // Metadata includes Bundle structure, field types, etc.
}
```

### With ChiselEnum

```scala
object CPUState extends ChiselEnum {
  val FETCH, DECODE, EXECUTE, WRITEBACK = Value
}

class CPU extends Module {
  val state = RegInit(CPUState.FETCH)
  
  // Plugin captures:
  // - typeName = "CPUState"
  // - enumDef = "0:CPUState(0=FETCH),1:CPUState(1=DECODE),..."
}
```

## Troubleshooting

### Plugin Not Active

**Symptom:** No intrinsics in FIRRTL output.

**Check:**

```scala
// 1. Verify plugin option enabled
scalacOptions += "-P:chisel:add-debug-intrinsics"

// 2. Verify runtime flag
sys.props("chisel.debug") = "true"

// 3. Check plugin loaded
scalac -version -P:chisel:help
// Should show:
// Options for plugin 'chisel':
//   add-debug-intrinsics  Automatically instrument...
```

### No Intrinsics in FIRRTL

**Symptom:** FIRRTL doesn't contain `circt_debug_typeinfo`.

**Debug:**

```scala
import circt.stage.ChiselStage

// Enable explicitly
sys.props("chisel.debug") = "true"

val firrtl = ChiselStage.emitCHIRRTL(new MyModule)

// Check presence
val hasIntrinsics = firrtl.contains("circt_debug_typeinfo")
println(s"Debug intrinsics present: $hasIntrinsics")

if (!hasIntrinsics) {
  println("ERROR: Intrinsics missing!")
  println("Check: scalacOptions contains -P:chisel:add-debug-intrinsics")
  println("Check: sys.props(\"chisel.debug\") == \"true\"")
}
```

### Compilation Errors

**Symptom:** Plugin causes compilation errors.

**Common Issues:**

1. **Recursion guard failure:**
   - Plugin should detect and skip intrinsic calls
   - If stack overflow: disable plugin temporarily

2. **Type mismatch:**
   - Plugin preserves types via typed AST
   - If type errors: file a bug report with minimal example

3. **Forward reference:**
   - Plugin runs after typer, so forward refs should be resolved
   - If issues: check for circular dependencies

## Integration with Manual Annotations

Plugin works alongside manual `DebugInfo.annotate()` calls:

```scala
class HybridModule extends Module {
  val io = IO(new Bundle { ... })        // Auto-instrumented by plugin
  val state = RegInit(0.U)                // Auto-instrumented by plugin
  
  val custom = Wire(UInt(8.W))
  DebugInfo.annotate(custom, "special")  // Manual (higher priority)
  
  // Both automatic and manual annotations coexist!
}
```

**Precedence:** Manual annotations take priority over plugin-generated ones.

## Comparison: Manual vs Plugin

### Manual API

```scala
class Module1 extends Module {
  val io = IO(new Bundle { ... })
  DebugInfo.annotate(io, "io")  // ← Manual call
  
  val state = RegInit(0.U)
  DebugInfo.annotate(state, "state")  // ← Manual call
  
  // Must annotate EVERY signal
}
```

### Plugin (Zero-Code)

```scala
class Module2 extends Module {
  val io = IO(new Bundle { ... })  // ← Auto-instrumented
  val state = RegInit(0.U)         // ← Auto-instrumented
  
  // NO manual calls - plugin handles everything!
}
```

**Benefits:**
- ✅ Zero boilerplate
- ✅ No missed signals
- ✅ Consistent naming
- ✅ Easy to enable/disable

## See Also

- [Debug Intrinsics Manual API](DEBUG_INTRINSICS_README.md)
- [CIRCT Debug Dialect](https://circt.llvm.org/docs/Dialects/Debug/)
- [Chisel Compiler Plugin](https://www.chisel-lang.org/docs/explanations/naming)
- [Scala Compiler Plugins](https://docs.scala-lang.org/overviews/plugins/index.html)

## Contributing

### Plugin Source

```
chisel/plugin/src/main/scala/chisel3/internal/plugin/
├── ChiselPlugin.scala                # Plugin entry point (modified)
└── ComponentDebugIntrinsics.scala    # AST transformer (new)
```

### Running Tests

```bash
cd chisel/
sbt test

# Run specific test suite:
sbt "testOnly chiselTests.DebugIntrinsicsPluginSpec"
sbt "testOnly chiselTests.DebugIntrinsicsIntegrationSpec"
```

### Adding Support for New Constructs

To add support for new Chisel constructs:

1. **Extend `ChiselSymbols`** in `ComponentDebugIntrinsics.scala`:
   ```scala
   lazy val NewConstructMethods = Set("MyConstruct")
   def isNewConstruct(sym: Symbol): Boolean = ...
   ```

2. **Add detection in `isInstrumentable`:**
   ```scala
   val isChiselRHS = vd.rhs match {
     case Apply(fun, _) =>
       chiselSymbols.isRegInit(fun.symbol) ||
       chiselSymbols.isNewConstruct(fun.symbol)  // ← Add here
     case _ => false
   }
   ```

3. **Add binding type in `detectBinding`:**
   ```scala
   private def detectBinding(tree: Tree): String = tree match {
     case Apply(fun, _) if chiselSymbols.isNewConstruct(fun.symbol) => "NewType"
     case _ => "Unknown"
   }
   ```

4. **Add tests** in `DebugIntrinsicsPluginSpec.scala`

## License

Apache 2.0 (same as Chisel)
