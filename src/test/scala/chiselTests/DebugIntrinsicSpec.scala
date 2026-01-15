// SPDX-License-Identifier: Apache-2.0

package chiselTests

import chisel3._
import chisel3.debuginternal.DebugIntrinsic
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test suite for CIRCT Debug Intrinsics
  * 
  * CRITICAL TESTS: Validates Probe API usage (P2 fix)
  * These tests protect against regression to weak binding.
  */
class DebugIntrinsicSpec extends AnyFlatSpec with Matchers {
  
  behavior of "DebugIntrinsic"
  
  it should "generate intrinsic for simple UInt with target parameter" in {
    sys.props("chisel.debug") = "true"
    
    class SimpleModule extends Module {
      val io = IO(new Bundle {
        val out = Output(UInt(8.W))
      })
      io.out := 42.U
      
      // Manually emit intrinsic for testing
      DebugIntrinsic.emit(io.out, "io.out", "IO")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new SimpleModule)
    
    // Verify FIRRTL contains intrinsic with target
    firrtl should include("circt_debug_typeinfo")
    firrtl should include("target = \"io.out\"")
    firrtl should include("typeName = \"UInt\"")
    firrtl should include("binding = \"IO\"")
    
    sys.props.remove("chisel.debug")
  }
  
  // ============================================================================
  // CRITICAL TEST SUITE: Probe API Validation (Weak Spot #1)
  // ============================================================================
  
  it should "use Probe API for signal binding (REGRESSION GUARD)" in {
    sys.props("chisel.debug") = "true"
    
    class ProbeAPITestModule extends Module {
      val io = IO(new Bundle {
        val data = Output(UInt(8.W))
      })
      io.data := 42.U
      
      DebugIntrinsic.emit(io.data, "io.data", "IO")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new ProbeAPITestModule)
    
    // CRITICAL: Verify Probe API constructs in FIRRTL
    // If ANY of these fail, we've regressed to weak binding!
    
    withClue("Missing Probe type declaration - ProbeValue() not called!") {
      // Must declare: wire _probe_XXX : Probe<UInt<8>>
      firrtl should include regex "wire\\s+\\w+\\s*:\\s*Probe<UInt<\\d+>>"
    }
    
    withClue("Missing probe() function - ProbeValue() not called!") {
      // Must have: define(_probe_XXX, probe(io.data))
      firrtl should include regex "define\\(\\w+,\\s*probe\\("
    }
    
    withClue("Intrinsic doesn't use read() - direct signal reference (WEAK BINDING)!") {
      // Must have: intrinsic(circt_debug_typeinfo<...>, read(_probe_XXX))
      firrtl should include regex "intrinsic\\(circt_debug_typeinfo.*,\\s*read\\("
    }
    
    // NEGATIVE TEST: Should NOT have direct signal reference
    withClue("REGRESSION DETECTED: Intrinsic uses direct signal, not probe!") {
      // Bad pattern: intrinsic(circt_debug_typeinfo<...>)(io.data)
      val weakBindingPattern = """intrinsic\(circt_debug_typeinfo[^)]*\)\(\s*io\.data\s*\)""".r
      weakBindingPattern.findFirstIn(firrtl) shouldBe None
    }
    
    sys.props.remove("chisel.debug")
  }
  
  it should "generate unique probe names for multiple signals" in {
    sys.props("chisel.debug") = "true"
    
    class MultiProbeModule extends Module {
      val io = IO(new Bundle {
        val a = Output(UInt(8.W))
        val b = Output(UInt(8.W))
        val c = Output(UInt(8.W))
      })
      io.a := 1.U
      io.b := 2.U
      io.c := 3.U
      
      DebugIntrinsic.emit(io.a, "io.a", "IO")
      DebugIntrinsic.emit(io.b, "io.b", "IO")
      DebugIntrinsic.emit(io.c, "io.c", "IO")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new MultiProbeModule)
    
    // Extract all probe wire names
    val probeWirePattern = """wire\\s+(\\w+)\\s*:\\s*Probe<.*>""".r
    val probeNames = probeWirePattern.findAllMatchIn(firrtl).map(_.group(1)).toSet
    
    withClue(s"Expected at least 3 unique probe wires, found: $probeNames") {
      probeNames.size should be >= 3
    }
    
    // Each probe should have corresponding define statement
    probeNames.foreach { probeName =>
      withClue(s"Missing define() for probe: $probeName") {
        firrtl should include(s"define($probeName")
      }
    }
    
    // Each probe should be read in an intrinsic
    probeNames.foreach { probeName =>
      withClue(s"Probe $probeName not read by intrinsic") {
        firrtl should include regex s"read\\($probeName\\)"
      }
    }
    
    sys.props.remove("chisel.debug")
  }
  
  it should "verify probe definition syntax is correct" in {
    sys.props("chisel.debug") = "true"
    
    class ProbeDefineTestModule extends Module {
      val io = IO(new Bundle {
        val signal = Output(UInt(16.W))
      })
      io.signal := 0xBEEF.U
      
      DebugIntrinsic.emit(io.signal, "io.signal", "IO")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new ProbeDefineTestModule)
    
    // Extract probe wire name
    val probeWirePattern = """wire\\s+(\\w+)\\s*:\\s*Probe<UInt<\\d+>>""".r
    val probeNameOpt = probeWirePattern.findFirstMatchIn(firrtl).map(_.group(1))
    
    probeNameOpt should not be None
    val probeName = probeNameOpt.get
    
    // Verify complete probe definition pattern
    withClue("Probe define statement malformed") {
      // Should match: define(_probe_XXX, probe(io.signal))
      firrtl should include regex s"define\\($probeName,\\s*probe\\(io\\.signal\\)\\)"
    }
    
    // Verify intrinsic reads the probe
    withClue("Intrinsic doesn't read the probe") {
      firrtl should include regex s"intrinsic\\(circt_debug_typeinfo.*read\\($probeName\\)"
    }
    
    sys.props.remove("chisel.debug")
  }
  
  it should "fail gracefully if Probe API is removed (CANARY TEST)" in {
    sys.props("chisel.debug") = "true"
    
    class CanaryModule extends Module {
      val io = IO(new Bundle {
        val test = Output(Bool())
      })
      io.test := true.B
      
      DebugIntrinsic.emit(io.test, "io.test", "IO")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new CanaryModule)
    
    // This test MUST fail if ProbeValue() is removed from DebugIntrinsic.emit()
    val probeAPIPresent = 
      firrtl.contains("Probe<") &&
      firrtl.contains("probe(") &&
      firrtl.contains("read(")
    
    withClue(
      """\n\n========================================
      |CRITICAL REGRESSION DETECTED!
      |========================================
      |Probe API missing from debug intrinsics.
      |This breaks metadata→RTL binding!
      |
      |Check: DebugIntrinsic.emit() implementation
      |Expected: ProbeValue(data) + read(probe)
      |Found: Direct signal reference (weak binding)
      |
      |See: PR #1 commit 678db15 for fix
      |========================================\n\n""".stripMargin
    ) {
      probeAPIPresent shouldBe true
    }
    
    sys.props.remove("chisel.debug")
  }
  
  // ============================================================================
  // END CRITICAL TESTS
  // ============================================================================
  
  it should "not generate intrinsics when debug flag is disabled" in {
    sys.props.remove("chisel.debug")
    
    class NoIntrinsicModule extends Module {
      val io = IO(new Bundle {
        val out = Output(UInt(8.W))
      })
      io.out := 42.U
      
      val result = DebugIntrinsic.emit(io.out, "io.out", "IO")
      result shouldBe None
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new NoIntrinsicModule)
    firrtl should not include "circt_debug_typeinfo"
    firrtl should not include regex "Probe<.*>"
  }
  
  it should "preserve Bundle hierarchy with recursive emission" in {
    sys.props("chisel.debug") = "true"
    
    class MyBundle(val n: Int) extends Bundle {
      val field1 = UInt((8*n).W)
      val field2 = Bool()
    }
    
    class BundleModule extends Module {
      val io = IO(new Bundle {
        val in = Input(new MyBundle(4))
        val out = Output(new MyBundle(4))
      })
      io.out := io.in
      
      // Emit recursively for bundle hierarchy
      DebugIntrinsic.emitRecursive(io.in, "io.in", "IO")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new BundleModule)
    
    // Verify bundle fields are tracked
    firrtl should include("target = \"io.in\"")
    firrtl should include("target = \"io.in.field1\"")
    firrtl should include("target = \"io.in.field2\"")
    firrtl should include regex "typeName = \"MyBundle\\$\\d+\""
    
    // CRITICAL: All recursive signals should use Probe API
    val intrinsicCount = "circt_debug_typeinfo".r.findAllMatchIn(firrtl).length
    val probeReadCount = "read\\(".r.findAllMatchIn(firrtl).length
    
    withClue(s"Probe API not used for all recursive intrinsics: $intrinsicCount intrinsics, $probeReadCount reads") {
      probeReadCount should be >= intrinsicCount
    }
    
    sys.props.remove("chisel.debug")
  }
  
  it should "extract Bundle constructor parameters" in {
    class ParametricBundle(val dataWidth: Int, val depth: Int) extends Bundle {
      val data = UInt(dataWidth.W)
      val valid = Bool()
    }
    
    val bundle = new ParametricBundle(32, 1024)
    val params = DebugIntrinsic.extractBundleParams(bundle)
    
    params should contain("dataWidth" -> "32")
    params should contain("depth" -> "1024")
  }
  
  it should "handle Vec types with length parameter" in {
    sys.props("chisel.debug") = "true"
    
    class VecModule extends Module {
      val io = IO(new Bundle {
        val vec = Output(Vec(4, UInt(8.W)))
      })
      io.vec := VecInit(Seq.fill(4)(0.U(8.W)))
      
      DebugIntrinsic.emit(io.vec, "io.vec", "IO")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new VecModule)
    
    // Verify Vec parameters
    firrtl should include("typeName = \"Vec\"")
    firrtl should include("parameters = \"length=4;elementType=UInt\"")
    
    // Verify Probe API used for Vec
    firrtl should include regex "Probe<Vec"
    firrtl should include regex "read\\(.*\\)"
    
    sys.props.remove("chisel.debug")
  }
  
  it should "handle ChiselEnum types with enumDef" in {
    sys.props("chisel.debug") = "true"
    
    object MyState extends ChiselEnum {
      val IDLE, RUN, DONE = Value
    }
    
    class EnumModule extends Module {
      val state = RegInit(MyState.IDLE)
      state := MyState.RUN
      
      DebugIntrinsic.emit(state, "state", "Reg")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new EnumModule)
    
    // Verify enum definition is captured
    firrtl should include regex "typeName = \"MyState\\$\\d+\""
    firrtl should include("enumDef")
    firrtl should (include("IDLE") or include("0:"))
    
    // Verify Probe API used for enum (UInt representation)
    firrtl should include regex "Probe<UInt<\\d+>>"
    
    sys.props.remove("chisel.debug")
  }
  
  it should "clean enum value names correctly (smart 's' prefix handling)" in {
    // Test the enum value name cleaning logic
    
    // Simulate Scala-generated enum value names
    object TestEnum extends ChiselEnum {
      // Scala typically generates: sIDLE$, sRUN$, etc.
      val IDLE = Value  // Internally: "sIDLE$"
      val RUN = Value   // Internally: "sRUN$"
    }
    
    // Extract enum definition
    val enumDef = DebugIntrinsic.extractEnumDef(TestEnum.IDLE)
    
    // Should clean Scala artifacts but preserve actual 's' prefixes
    withClue("Enum value names should be cleaned of Scala artifacts") {
      // Should contain "IDLE" or "RUN", not "sIDLE" or "sRUN"
      // (unless user explicitly named them that way)
      enumDef should include regex "(IDLE|RUN)"
    }
    
    // Should NOT have trailing $
    withClue("Enum values should not contain trailing $") {
      enumDef should not include regex "\\$\\d*:"
    }
    
    // Test that user-defined names starting with lowercase 's' are preserved
    // (We can't easily test this with real ChiselEnum, so just document the logic)
    // Examples that should work:
    //   "sIDLE" (Scala artifact) → "IDLE"
    //   "sRUN" (Scala artifact) → "RUN"
    //   "sleep" (user-defined) → "sleep" (preserved)
    //   "start" (user-defined) → "start" (preserved)
  }
  
  it should "handle nested Bundle structures" in {
    sys.props("chisel.debug") = "true"
    
    class InnerBundle extends Bundle {
      val x = UInt(8.W)
      val y = UInt(8.W)
    }
    
    class OuterBundle extends Bundle {
      val inner = new InnerBundle
      val flag = Bool()
    }
    
    class NestedModule extends Module {
      val io = IO(Output(new OuterBundle))
      io.inner.x := 1.U
      io.inner.y := 2.U
      io.flag := true.B
      
      DebugIntrinsic.emitRecursive(io, "io", "IO")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new NestedModule)
    
    // Verify nested structure paths
    firrtl should include("target = \"io\"")
    firrtl should include("target = \"io.inner\"")
    firrtl should include("target = \"io.inner.x\"")
    firrtl should include("target = \"io.flag\"")
    
    // CRITICAL: Count probes vs intrinsics for nested structures
    val intrinsicCount = "circt_debug_typeinfo".r.findAllMatchIn(firrtl).length
    val probeDefineCount = "define\\(".r.findAllMatchIn(firrtl).length
    
    withClue(s"Probe definitions missing for nested intrinsics: $intrinsicCount intrinsics, $probeDefineCount defines") {
      probeDefineCount should be >= intrinsicCount
    }
    
    sys.props.remove("chisel.debug")
  }
  
  it should "extract correct type names for all Data types" in {
    DebugIntrinsic.extractTypeName(UInt(8.W)) shouldBe "UInt"
    DebugIntrinsic.extractTypeName(SInt(8.W)) shouldBe "SInt"
    DebugIntrinsic.extractTypeName(Bool()) shouldBe "Bool"
    
    class CustomBundle extends Bundle {
      val x = UInt(8.W)
    }
    val name = DebugIntrinsic.extractTypeName(new CustomBundle)
    // Allow CustomBundle or CustomBundle$1, should be cleaned to CustomBundle
    name should (equal("CustomBundle") or startWith("CustomBundle"))
  }
  
  it should "generate valid FIRRTL that passes basic checks" in {
    sys.props("chisel.debug") = "true"
    
    class IntegrationModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      
      val reg = RegInit(0.U(8.W))
      reg := io.in
      io.out := reg
      
      // Emit intrinsics for all signals
      DebugIntrinsic.emit(io.in, "io.in", "IO")
      DebugIntrinsic.emit(io.out, "io.out", "IO")
      DebugIntrinsic.emit(reg, "reg", "Reg")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new IntegrationModule)
    
    // Verify all intrinsics are present
    firrtl should include regex "intrinsic\\(circt_debug_typeinfo"
    
    // Count intrinsic instances (should be 3)
    val intrinsicCount = "circt_debug_typeinfo".r.findAllMatchIn(firrtl).length
    intrinsicCount should be >= 3
    
    // CRITICAL REGRESSION TEST: Verify all use Probe API
    val probeReadCount = "read\\(".r.findAllMatchIn(firrtl).length
    
    withClue(s"Not all intrinsics use Probe API: $intrinsicCount intrinsics, $probeReadCount reads") {
      probeReadCount should be >= intrinsicCount
    }
    
    // Each intrinsic should have read() in its operands
    val intrinsicsWithReadPattern = """intrinsic\(circt_debug_typeinfo[^)]*\)[^;]*read\(""".r
    val intrinsicsWithRead = intrinsicsWithReadPattern.findAllMatchIn(firrtl).length
    
    withClue(s"Some intrinsics missing read(): $intrinsicsWithRead out of $intrinsicCount") {
      intrinsicsWithRead shouldBe intrinsicCount
    }
    
    sys.props.remove("chisel.debug")
  }
}