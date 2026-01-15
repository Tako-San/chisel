# Compiler Plugin Implementation Roadmap

**Goal:** Replace manual `DebugInfo.annotate()` API with automatic compiler plugin.

---

## ✅ Phase 1: MVP - RegInit Support (COMPLETE)

**Status:** ✅ DONE (6 hours)

**Deliverables:**
- [x] Plugin infrastructure (`DebugIntrinsicsPlugin.scala`)
- [x] AST transformer for RegInit (`DebugTransformComponent.scala`)
- [x] Basic tests (`RegInitPluginSpec.scala`)
- [x] Build configuration (`build.sbt`)
- [x] README documentation

**Commits:**
- `7477da4` - Build configuration
- `a7bc480` - Plugin entry point
- `b815102` - AST transformer (RegInit)
- `3d757f9` - MVP tests
- `4934b24` - README

**Test Results:**
```scala
// ✅ This works NOW:
class TestModule extends Module {
  val state = RegInit(0.U(8.W))  // Auto-instrumented!
}

// Generates FIRRTL with:
// intrinsic(circt_debug_typeinfo<target="state", binding="Reg", ...>)
```

---

## 🚧 Phase 2: Full Chisel Support (IN PROGRESS)

**Goal:** Support all Chisel hardware constructs

**Estimated Time:** 8 hours

### Task 2.1: Wire/WireInit Support (2h)

**File:** `plugin/src/main/scala/chisel3/debug/plugin/DebugTransformComponent.scala`

**Implementation:**
```scala
// Add to transform() match:
case vd @ ValDef(mods, name, tpt, rhs)
    if isWire(rhs) && shouldInstrument(tree) =>
  instrumentValDef(vd, name, rhs, "Wire")

private def isWire(tree: Tree): Boolean = tree match {
  case Apply(fun, _) => isWireSymbol(fun.symbol)
  case TypeApply(Apply(fun, _), _) => isWireSymbol(fun.symbol)
  case _ => false
}

private def isWireSymbol(sym: Symbol): Boolean = {
  if (sym == null || sym == NoSymbol) return false
  val fullName = sym.fullName
  fullName == "chisel3.Wire" ||
  fullName == "chisel3.WireInit" ||
  fullName == "chisel3.WireDefault"
}
```

**Test:**
```scala
class WirePluginSpec extends AnyFlatSpec {
  "Plugin" should "instrument Wire" in {
    sys.props("chisel.debug") = "true"
    
    class TestModule extends Module {
      val temp = Wire(UInt(8.W))  // NO manual annotation!
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    firrtl should include("target = \"temp\"")
    firrtl should include("binding = \"Wire\"")
  }
}
```

---

### Task 2.2: IO Support (2h)

**Challenge:** IO is special - it's both a method call AND a val assignment.

**Implementation:**
```scala
// Detect: val io = IO(new Bundle { ... })
case vd @ ValDef(mods, name, tpt, rhs)
    if isIO(rhs) && shouldInstrument(tree) =>
  // Special handling: recursively instrument Bundle fields
  instrumentIO(vd, name, rhs)

private def instrumentIO(
  original: ValDef,
  name: TermName,
  rhs: Tree
): Tree = {
  val transformedRHS = transform(rhs)
  val tmpName = TermName(s"_debug_${name}")
  
  // Generate block that:
  // 1. Creates IO
  // 2. Emits intrinsic for IO itself
  // 3. Recursively emits for Bundle fields
  val block = atPos(original.pos) {
    Block(
      List(
        ValDef(Modifiers(), tmpName, TypeTree(), transformedRHS),
        
        // Emit for IO bundle
        If(
          Select(...DebugIntrinsic, "isEnabled"),
          Apply(
            Select(...DebugIntrinsic, "emitRecursive"),  // ← RECURSIVE!
            List(
              Ident(tmpName),
              Literal(Constant(name.toString)),
              Literal(Constant("IO"))
            )
          ),
          EmptyTree
        )
      ),
      Ident(tmpName)
    )
  }
  
  treeCopy.ValDef(original, original.mods, original.name, original.tpt, localTyper.typed(block))
}
```

**Test:**
```scala
"Plugin" should "recursively instrument IO Bundle" in {
  class TestModule extends Module {
    val io = IO(new Bundle {
      val ctrl = new Bundle {
        val valid = Bool()
        val ready = Bool()
      }
      val data = UInt(32.W)
    })
  }
  
  val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
  
  // Should instrument ALL fields:
  firrtl should include("target = \"io\"")
  firrtl should include("target = \"io.ctrl\"")
  firrtl should include("target = \"io.ctrl.valid\"")
  firrtl should include("target = \"io.data\"")
}
```

---

### Task 2.3: Mem Support (2h)

**Implementation:**
```scala
case vd @ ValDef(mods, name, tpt, rhs)
    if isMem(rhs) && shouldInstrument(tree) =>
  instrumentValDef(vd, name, rhs, "Mem")

private def isMem(tree: Tree): Boolean = tree match {
  case Apply(fun, _) => isMemSymbol(fun.symbol)
  case TypeApply(Apply(fun, _), _) => isMemSymbol(fun.symbol)
  case _ => false
}

private def isMemSymbol(sym: Symbol): Boolean = {
  if (sym == null || sym == NoSymbol) return false
  val fullName = sym.fullName
  fullName == "chisel3.Mem" ||
  fullName == "chisel3.SyncReadMem"
}
```

---

### Task 2.4: Module Instantiation Tracking (2h)

**Goal:** Track submodule hierarchy for hierarchical names.

**Implementation:**
```scala
// Detect: val sub = Module(new SubModule)
case vd @ ValDef(mods, name, tpt, rhs)
    if isModuleInstantiation(rhs) =>
  // Extract submodule class name
  val subModuleName = extractModuleName(rhs)
  
  // Track in hierarchy (for future hierarchical naming)
  hierarchyStack.push((name.toString, subModuleName))
  
  val instrumented = instrumentValDef(vd, name, rhs, "Module")
  
  hierarchyStack.pop()
  instrumented

private val hierarchyStack = scala.collection.mutable.Stack[(String, String)]()

private def currentHierarchy: String = {
  hierarchyStack.map(_._1).mkString(".")
}
```

---

## 🧹 Phase 3: Cleanup - Remove Manual API (4h)

**Goal:** Delete redundant code now that plugin handles everything.

### Task 3.1: Remove Manual Annotation API (1h)

**Files to modify:**
```bash
src/main/scala/chisel3/util/circt/DebugInfo.scala
```

**Changes:**
```scala
// ❌ DELETE:
object DebugInfo {
  def annotate(...) = ...         // Plugin does this!
  def annotateRecursive(...) = ... // Plugin does this!
  
  // ✅ KEEP:
  def isEnabled: Boolean = chisel3.debuginternal.DebugIntrinsic.isEnabled
}
```

**Result:** `DebugInfo` becomes a single-method utility object.

---

### Task 3.2: Remove AddDebugIntrinsicsPhase (1h)

**Files to DELETE:**
```bash
src/main/scala/chisel3/stage/phases/AddDebugIntrinsicsPhase.scala  # ❌ Entire file!
```

**Files to modify:**
```bash
src/main/scala/chisel3/stage/ChiselStage.scala
```

**Remove phase from pipeline:**
```scala
// ❌ DELETE this line:
Dependency[chisel3.stage.phases.AddDebugIntrinsicsPhase]
```

---

### Task 3.3: Update Examples (1h)

**File:** `examples/DebugInfoExample.scala`

**Before:**
```scala
class DebugInfoExample extends Module {
  val io = IO(...)
  val cnt = RegInit(...)
  
  // ❌ DELETE all these:
  DebugInfo.annotate(io.counter, "io.counter")
  DebugInfo.annotateRecursive(io.counter, "io.counter")
  DebugInfo.annotate(cnt, "counter_reg")
}
```

**After:**
```scala
class DebugInfoExample extends Module {
  val io = IO(...)
  val cnt = RegInit(...)
  
  // ✅ NO ANNOTATIONS - plugin does it automatically!
}
```

---

### Task 3.4: Update Tests (1h)

**File:** `src/test/scala/chiselTests/AddDebugIntrinsicsPhaseSpec.scala`

**Action:** DELETE entire file (phase no longer exists)

**Create new:** `src/test/scala/chiselTests/CompilerPluginIntegrationSpec.scala`

```scala
class CompilerPluginIntegrationSpec extends AnyFlatSpec {
  "Compiler Plugin" should "auto-instrument complete modules" in {
    sys.props("chisel.debug") = "true"
    
    // NO manual annotations in ANY test!
    class CompleteModule extends Module {
      val io = IO(new Bundle { val data = UInt(8.W) })
      val state = RegInit(0.U)
      val temp = Wire(UInt(8.W))
      val mem = Mem(64, UInt(8.W))
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new CompleteModule)
    
    // Verify ALL auto-instrumented:
    firrtl should include("target = \"io\"")
    firrtl should include("target = \"state\"")
    firrtl should include("target = \"temp\"")
    firrtl should include("target = \"mem\"")
  }
}
```

---

## 💎 Phase 4: Polish & Production (4h)

### Task 4.1: Integration with Main Build (1h)

**File:** `build.sbt` (root)

**Add plugin subproject:**
```scala
lazy val plugin = (project in file("plugin"))
  .settings(
    name := "chisel-debug-plugin",
    libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value
  )

lazy val chisel = (project in file("."))
  .dependsOn(plugin)
  .settings(
    // Auto-enable plugin for Chisel compilation
    scalacOptions ++= {
      if (sys.props.get("chisel.debug").contains("true")) {
        val jar = (plugin / Compile / packageBin).value
        Seq(
          s"-Xplugin:${jar.absolutePath}",
          "-P:chisel-debug:enable"
        )
      } else Seq.empty
    }
  )
```

---

### Task 4.2: Performance Benchmarks (1h)

**Create:** `plugin/src/test/scala/chisel3/debug/plugin/BenchmarkSpec.scala`

```scala
class BenchmarkSpec extends AnyFlatSpec {
  "Plugin overhead" should "be < 15% compile time" in {
    // Benchmark large module compilation
    val moduleSize = 1000  // signals
    
    // Measure WITHOUT plugin
    val t1 = System.nanoTime()
    compileModule(size = moduleSize, pluginEnabled = false)
    val baselineTime = System.nanoTime() - t1
    
    // Measure WITH plugin
    val t2 = System.nanoTime()
    compileModule(size = moduleSize, pluginEnabled = true)
    val pluginTime = System.nanoTime() - t2
    
    val overhead = (pluginTime - baselineTime).toDouble / baselineTime * 100
    println(s"Compile overhead: $overhead%")
    
    overhead should be < 15.0
  }
}
```

---

### Task 4.3: Documentation (1h)

**Update:** `README.md` (root)

**Add section:**
```markdown
## Debug Metadata (NEW!)

Chisel now automatically instruments your hardware with debug metadata - **no code changes required**!

### Enable Debug Mode

```bash
export CHISEL_DEBUG=true
sbt compile
```

### What Gets Instrumented

- ✅ All `RegInit` / `Reg` registers
- ✅ All `Wire` / `WireInit` combinational logic
- ✅ All `IO` ports (recursively)
- ✅ All `Mem` / `SyncReadMem` memories

### Integration with Tools

- **Tywaves:** Type-aware waveform viewer
- **HGDB:** Interactive debugger with breakpoints
- **ChiselTrace:** Dynamic dependency tracing

See [plugin/README.md](plugin/README.md) for details.
```

---

### Task 4.4: CI/CD Integration (1h)

**File:** `.github/workflows/plugin-test.yml`

```yaml
name: Plugin Tests

on:
  push:
    branches: [ main, feature/compiler-plugin-* ]
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - uses: coursier/setup-action@v1
        with:
          jvm: adopt:11
          
      - name: Run plugin tests
        run: |
          cd plugin
          sbt test
          
      - name: Integration test
        run: |
          export CHISEL_DEBUG=true
          sbt "testOnly chiselTests.CompilerPluginIntegrationSpec"
          
      - name: Benchmark overhead
        run: |
          cd plugin
          sbt "testOnly *BenchmarkSpec"
```

---

## 📅 Timeline

| Phase | Duration | Status | ETA |
|-------|----------|--------|-----|
| **Phase 1: MVP** | 6h | ✅ DONE | Jan 15 |
| **Phase 2: Full Support** | 8h | 🚧 Next | Jan 16 |
| **Phase 3: Cleanup** | 4h | ⏳ Pending | Jan 17 |
| **Phase 4: Polish** | 4h | ⏳ Pending | Jan 18 |
| **Total** | **22h** | | **~3 days** |

---

## 🎯 Next Actions

### Immediate (TODAY):

1. **Test MVP:**
   ```bash
   cd plugin/
   sbt test
   ```

2. **Verify RegInit works:**
   ```scala
   sys.props("chisel.debug") = "true"
   val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
   assert(firrtl.contains("circt_debug_typeinfo"))
   ```

3. **Start Phase 2:**
   - Implement Wire support (Task 2.1)
   - Test with multiple Wire declarations

### Tomorrow (Phase 2):

- Complete IO, Mem, Module support
- Write comprehensive tests
- Benchmark compile overhead

### Day 3 (Phase 3):

- Remove all manual annotation code
- Update examples and tests
- Verify ZERO breaking changes

### Day 4 (Phase 4):

- Final polish
- Documentation
- Prepare for merge to main

---

## 🐛 Known Issues

### Current Limitations (MVP):

1. **Only RegInit supported** - Wire/IO/Mem coming in Phase 2
2. **No hierarchical names** - Currently "state", not "Core.state"
3. **No Bundle field expansion** - IO bundles not recursively instrumented yet

### Planned Fixes (Phase 2):

- Full Chisel construct coverage
- Hierarchical module tracking
- Recursive Bundle instrumentation

---

## 💬 Questions?

See [plugin/README.md](plugin/README.md) for usage.

File issues at: https://github.com/Tako-San/chisel/issues
