# Chisel Debug Intrinsics Compiler Plugin

**Automatic debug metadata instrumentation for Chisel - ZERO code changes required!**

---

## 🎯 What It Does

### Before (Manual Annotation - OLD WAY)
```scala
import chisel3.util.circt.DebugInfo  // ❌ Extra import

class RiscvCore extends Module {
  val pc = RegInit(0.U(32.W))
  DebugInfo.annotate(pc, "pc")  // ❌ Manual call for EVERY signal
  
  val state = RegInit(State.IDLE)
  DebugInfo.annotate(state, "state")  // ❌ Repetitive boilerplate
}
```

### After (Compiler Plugin - NEW WAY)
```scala
// NO IMPORTS NEEDED!
// NO ANNOTATIONS NEEDED!

class RiscvCore extends Module {
  val pc = RegInit(0.U(32.W))      // ✅ Automatically instrumented!
  val state = RegInit(State.IDLE)  // ✅ Automatically instrumented!
}
```

**Result:** Same FIRRTL output with `circt_debug_typeinfo` intrinsics, but **ZERO code changes**!

---

## 🚀 Quick Start

### Installation

Add to `project/plugins.sbt`:
```scala
addCompilerPlugin("org.chipsalliance" %% "chisel-debug-plugin" % "0.1.0-SNAPSHOT")
```

### Enable Plugin

Option 1: Environment variable
```bash
export CHISEL_DEBUG=true
sbt compile
```

Option 2: System property
```bash
sbt -Dchisel.debug=true compile
```

Option 3: Build configuration
```scala
// build.sbt
scalacOptions += "-P:chisel-debug:enable"
```

### Verify It Works

```scala
import chisel3._
import circt.stage.ChiselStage

class TestModule extends Module {
  val state = RegInit(0.U(8.W))  // NO manual annotation!
}

object Main extends App {
  sys.props("chisel.debug") = "true"
  val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
  
  // Verify intrinsics generated:
  assert(firrtl.contains("circt_debug_typeinfo"))
  assert(firrtl.contains("target = \"state\""))
  println("✅ Plugin working!")
}
```

---

## 📚 Usage

### Supported Constructs (Phase 1 MVP)

| Chisel Code | Binding | Auto-Instrumented |
|-------------|---------|-------------------|
| `val x = RegInit(0.U)` | `"Reg"` | ✅ Yes |
| `val w = Wire(UInt(8.W))` | `"Wire"` | ⏳ Phase 2 |
| `val io = IO(new Bundle {...})` | `"IO"` | ⏳ Phase 2 |
| `val mem = Mem(64, UInt(8.W))` | `"Mem"` | ⏳ Phase 2 |

### Configuration

**Verbose output:**
```bash
scalac -P:chisel-debug:enable -P:chisel-debug:verbose
```

**Whitelist modules:**
```bash
scalac -P:chisel-debug:enable -P:chisel-debug:whitelist:RiscvCore,CacheModule
```

**Blacklist modules:**
```bash
scalac -P:chisel-debug:enable -P:chisel-debug:blacklist:TestHarness
```

---

## 🔬 How It Works

### Compile-Time AST Transformation

The plugin runs after Scala's `typer` phase and transforms:

```scala
// Your code:
val state = RegInit(0.U(8.W))
```

Into:

```scala
// Generated code:
val state = {
  val _debug_state = RegInit(0.U(8.W))
  if (chisel3.debuginternal.DebugIntrinsic.isEnabled) {
    chisel3.debuginternal.DebugIntrinsic.emit(
      _debug_state,
      "state",
      "Reg"
    )
  }
  _debug_state
}
```

### Zero Runtime Overhead

- **When disabled:** No transformation applied (zero overhead)
- **When enabled:** `DebugIntrinsic.emit()` only runs during elaboration
- **After FIRRTL:** Intrinsics pruned, no RTL impact

---

## 🧪 Testing

```bash
cd plugin/
sbt test
```

**Test coverage:**
- ✅ RegInit auto-instrumentation
- ✅ Multiple signals in same module
- ✅ Type parameters (Bundle, Vec)
- ✅ Enable/disable flag
- ✅ Behavior preservation (no-op semantics)

---

## 📊 Status

### Phase 1: MVP ✅ (Current)
- [x] Plugin infrastructure
- [x] RegInit auto-instrumentation
- [x] Basic tests
- [x] README

### Phase 2: Full Support (Next)
- [ ] Wire, WireInit, WireDefault
- [ ] IO, Input, Output
- [ ] Mem, SyncReadMem
- [ ] Module (submodule tracking)
- [ ] Comprehensive tests

### Phase 3: Production (Week 2)
- [ ] Remove manual DebugInfo API
- [ ] Update all examples
- [ ] Migrate existing tests
- [ ] Performance benchmarks

### Phase 4: Upstream (Week 3)
- [ ] Integration with Chisel main repo
- [ ] CI/CD pipeline
- [ ] Documentation site
- [ ] Release 1.0.0

---

## 🐛 Troubleshooting

**Q: Plugin not loading?**
```bash
# Verify plugin is in classpath:
sbt "show Compile/scalacOptions"
# Should contain: -Xplugin:/.../chisel-debug-plugin.jar
```

**Q: No intrinsics generated?**
```bash
# Check plugin is enabled:
sbt "show Compile/scalacOptions" | grep chisel-debug
# Should contain: -P:chisel-debug:enable

# Or use environment:
export CHISEL_DEBUG=true
```

**Q: Compile errors after enabling plugin?**
```bash
# Enable verbose mode to debug:
scalac -P:chisel-debug:enable -P:chisel-debug:verbose
```

---

## 🤝 Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md)

---

## 📄 License

Apache-2.0 (same as Chisel)
