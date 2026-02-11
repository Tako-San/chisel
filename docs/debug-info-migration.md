# Debug Information System - Migration from Annotations to Intrinsics

## Overview

This document describes the modernized debug information system for Chisel, migrated from the annotation-based approach in [PR #4224](https://github.com/chipsalliance/chisel/pull/4224) to use modern **Intrinsics API**.

### Key Changes from PR #4224

| Aspect | PR #4224 (Old) | This Implementation (New) |
|--------|---------------|---------------------------|
| **Metadata carrier** | FIRRTL Annotations | FIRRTL Intrinsics |
| **API** | `ChiselAnnotation` + `TywavesAnnotation` | `Intrinsic` API |
| **Output format** | Annotations in JSON sidecar file | Embedded in FIRRTL IR |
| **CIRCT integration** | Custom annotation lowering pass | Native `dbg` dialect support |
| **Chisel version** | Chisel 5.x (outdated) | Chisel 7.x (current) |

## Architecture

### Layer 1: Chisel (Scala Source)

**DebugIntrinsicEmitter** (`core/src/main/scala/chisel3/debuginfo/DebugIntrinsic.scala`):
- Traverses Chisel IR after elaboration
- Extracts Scala type metadata via runtime reflection
- Emits `circt_debug_type_info` intrinsics attached to Data/Module nodes

**Key data structure:**
```scala
case class DebugTypeInfo(
  targetName: String,       // Hardware element name
  typeName: String,         // Scala source type (e.g., "MyBundle")
  params: Option[Seq[TypeParam]]  // Constructor params with values
)

case class TypeParam(
  name: String,             // Parameter name
  typeName: String,         // Parameter type
  value: Option[String]     // Runtime value if available
)
```

**Intrinsic emission:**
```scala
Intrinsic("circt_debug_type_info",
  "type_name" -> Param("MyCustomBundle"),
  "params" -> Param("width:Int=32,depth:Int=8"),
  "target_name" -> Param("io_dataPath")
)(targetSignal)
```

### Layer 2: FIRRTL

Intrinsics are **first-class FIRRTL IR nodes**, not annotations:
```firrtl
intrinsic(circt_debug_type_info<type_name="MyBundle", params="w:Int=8">, io.data)
```

Advantages:
- Survives FIRRTL transforms that would drop annotations
- Type-checked by FIRRTL parser
- Visible in FIRRTL pretty-printer output

### Layer 3: CIRCT (MLIR)

**firtool lowering path:**
```
FIRRTL Intrinsic → FIRRTL Dialect (intrinsic op)
                 ↓
          LowerIntrinsics pass
                 ↓
          Debug Dialect ops:
          - dbg.variable (for signals)
          - dbg.moduleinfo (for modules)
          - dbg.scope (for hierarchy)
```

**Example CIRCT Debug IR:**
```mlir
%signal = firrtl.wire : !firrtl.uint<8>
dbg.variable "io.data", %signal : !firrtl.uint<8> {
  source_type = "MyCustomBundle",
  params = "width:Int=8"
}
```

### Layer 4: Output (Verilog + Metadata)

**firtool output:**
1. **Verilog RTL** - Standard synthesizable output
2. **hw-debug-info.json** - JSON schema linking Chisel types to RTL signals:

```json
{
  "version": "1.0",
  "modules": [
    {
      "name": "MyModule",
      "source_type": "MyModule",
      "params": ["dataWidth:Int=32"],
      "signals": [
        {
          "rtl_path": "MyModule.io_data",
          "chisel_name": "io.data",
          "source_type": "MyCustomBundle",
          "params": ["width:Int=8"]
        }
      ]
    }
  ]
}
```

## Integration with Tywaves

**Tywaves waveform viewer** consumes `hw-debug-info.json` + VCD:

```
VCD file (simulator output) ──┐
                              ├──> Tywaves Viewer
hw-debug-info.json ───────────┘     (typed waveforms)
```

Viewer features enabled:
- Display signals with Chisel source types (not just `logic [7:0]`)
- Show Bundle field hierarchy
- Display constructor parameter values
- Navigate between Chisel source and waveform

## Usage

### 1. Enable in ChiselStage

```scala
import chisel3.stage.ChiselStage
import chisel3.stage.phases.{EmitDebugInfoAnnotation}

(new ChiselStage).execute(
  Array("--target-dir", "generated", "--emit-debug-info"),
  Seq(ChiselGeneratorAnnotation(() => new MyModule))
)
```

Or programmatically:
```scala
import chisel3.stage.phases.EmitDebugInfoAnnotation

val annos = Seq(
  ChiselGeneratorAnnotation(() => new MyModule),
  EmitDebugInfoAnnotation()
)

(new ChiselStage).execute(Array(), annos)
```

### 2. Compile with firtool

```bash
firtool generated/MyModule.fir \
  --format=fir \
  --emit-debug-info \
  --export-module-hierarchy \
  -o generated/
```

Output:
- `generated/MyModule.v` - Verilog
- `generated/hw-debug-info.json` - Debug metadata

### 3. Simulate and View

```bash
# Run simulation
verilator --trace generated/MyModule.v
./obj_dir/VMyModule

# View with Tywaves
tywaves generated/hw-debug-info.json dump.vcd
```

## Migration from PR #4224

### Step 1: Remove Annotations

**Old (PR #4224):**
```scala
case class TywavesAnnotation[T <: IsMember](
  target: T,
  typeName: String,
  params: Option[Seq[ClassParam]]
) extends SingleTargetAnnotation[T]

object TywavesChiselAnnotation {
  def createAnno(...): ChiselAnnotation = {
    new ChiselAnnotation {
      override def toFirrtl = TywavesAnnotation(...)
    }
  }
}
```

**New (Intrinsics):**
```scala
object DebugIntrinsicEmitter {
  def emitDebugInfo(target: Data, typeInfo: DebugTypeInfo): Unit = {
    Intrinsic("circt_debug_type_info", ...)(target)
  }
}
```

### Step 2: Update Phase Integration

**Old:**
```scala
class AddTywavesAnnotations extends Phase {
  def transform(annos: AnnotationSeq): AnnotationSeq = {
    annos ++ generatedAnnotations  // Append to annotation list
  }
}
```

**New:**
```scala
class EmitDebugIntrinsics extends Phase {
  def transform(annos: AnnotationSeq): AnnotationSeq = {
    DebugIntrinsicEmitter.generate(circuit)  // Mutate circuit IR in-place
    annos  // No annotation modifications
  }
}
```

### Step 3: No JSON File Generation in Chisel

**Old approach:** Chisel emitted `tywaves-metadata.json` alongside FIRRTL.

**New approach:** All metadata flows through FIRRTL intrinsics. CIRCT generates JSON as part of firtool compilation.

### Step 4: Reflection Logic - Mostly Unchanged

The Scala reflection code for extracting constructor parameters remains largely the same:

```scala
def extractConstructorParams(target: Any): Option[Seq[TypeParam]] = {
  import scala.reflect.runtime.universe._
  // ... same reflection logic as PR #4224 ...
}
```

## CIRCT Integration (Future Work)

### Current Status

✅ Chisel emits `circt_debug_type_info` intrinsic

🔄 **Next:** CIRCT lowering pass to consume intrinsic

```cpp
// In CIRCT: lib/Dialect/FIRRTL/Transforms/LowerIntrinsics.cpp

void lowerDebugTypeInfo(IntrinsicOp op) {
  auto typeName = op.getParameter("type_name");
  auto params = op.getParameter("params");
  
  // Create dbg.variable op
  auto debugVar = builder.create<dbg::VariableOp>(
    op.getLoc(),
    op.getOperand(0),  // Target signal
    typeName,
    params
  );
}
```

### Debug Dialect Extensions

**Proposed CIRCT additions** (to be upstreamed):

```mlir
// New ops in Debug dialect
dbg.enumdef @MyEnum { field @A = 0, field @B = 1 }
dbg.structdef @MyBundle { field @data : ui8, field @valid : ui1 }
dbg.moduleinfo @MyModule { params = ["width" = 32] }
```

See: [CIRCT Debug Dialect](https://circt.llvm.org/docs/Dialects/Debug/)

## Testing

### Unit Tests

```scala
class DebugIntrinsicSpec extends ChiselFlatSpec {
  "DebugIntrinsicEmitter" should "extract Bundle parameters" in {
    class MyBundle(val width: Int) extends Bundle {
      val data = UInt(width.W)
    }
    
    val params = DebugIntrinsicEmitter.extractConstructorParams(new MyBundle(8))
    params shouldBe Some(Seq(
      TypeParam("width", "Int", Some("8"))
    ))
  }
}
```

### Integration Tests

```scala
class DebugInfoIntegrationSpec extends ChiselFlatSpec {
  "EmitDebugIntrinsics phase" should "instrument circuit" in {
    val chirrtl = ChiselStage.emitCHIRRTL(
      new MyModule,
      Array("--emit-debug-info")
    )
    
    chirrtl should include("intrinsic(circt_debug_type_info")
  }
}
```

## FAQ

### Q: Why not use Annotations?

**A:** Annotations are deprecated in modern Chisel/CIRCT workflow:
- Harder to type-check
- Can be dropped by transforms
- Require custom CIRCT lowering passes
- Not visible in FIRRTL pretty-printer

Intrinsics are the **official** mechanism for custom metadata (see [Chisel Intrinsics Docs](https://www.chisel-lang.org/docs/explanations/intrinsics)).

### Q: Does this work with ChiselSim?

**A:** Partially. ChiselSim can preserve intrinsics through elaboration, but VPI-based debugging (HGDB integration) requires additional work.

### Q: How does this relate to ChiselTrace?

**ChiselTrace** (from Space instructions) uses **dynamic tracing**:
- Instruments VCD parsing to build CFG/PDG
- Tracks data dependencies at runtime
- Interactive Tauri UI

**This system** provides **static metadata**:
- No runtime overhead
- Type information available pre-simulation
- Consumed by Tywaves for typed waveform viewing

**They are complementary** - can be combined for comprehensive debugging.

### Q: What about HGDB integration?

**HGDB** (Hardware Graph Database) uses VPI for dynamic breakpoints.

**Integration path:**
1. This system exports `hw-debug-info.json`
2. HGDB runtime reads JSON to map Chisel names → RTL signals
3. VPI callbacks use `vpi_handle_by_name` to access signals
4. Breakpoints expressed in Chisel source coordinates

See: [HGDB on GitHub](https://github.com/Kuree/hgdb)

## References

- [Original PR #4224](https://github.com/chipsalliance/chisel/pull/4224) - Annotation-based approach
- [Chisel Intrinsics](https://www.chisel-lang.org/docs/explanations/intrinsics) - Official docs
- [CIRCT Debug Dialect](https://circt.llvm.org/docs/Dialects/Debug/) - MLIR target
- [Tywaves Project](https://github.com/rameloni/tywaves-chisel) - Waveform viewer
- [ChiselTrace](https://github.com/rameloni/chisel-trace) - Dynamic dependency tracing

## TODO

- [ ] Implement CIRCT lowering pass for `circt_debug_type_info` intrinsic
- [ ] Define JSON schema for `hw-debug-info.json` output
- [ ] Add support for Enum types (via `dbg.enumdef`)
- [ ] Handle nested Bundle/Vec hierarchies
- [ ] Integrate with ChiselSim instrumentation
- [ ] Add HGDB VPI runtime support
- [ ] Upstream Debug dialect extensions to CIRCT
- [ ] Write comprehensive test suite
- [ ] Performance benchmarking vs annotation approach

## License

Apache 2.0 (same as Chisel)
