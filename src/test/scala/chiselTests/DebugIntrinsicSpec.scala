// SPDX-License-Identifier: Apache-2.0

package chiselTests

import chisel3._
import chisel3.debuginternal.DebugIntrinsic
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test suite for CIRCT Debug Intrinsics
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
  
  // CRITICAL TEST: Validate Probe API is used (P2 fix)
  it should "use Probe API for signal binding (not direct signal reference)" in {
    sys.props("chisel.debug") = "true"
    
    class ProbeTestModule extends Module {
      val io = IO(new Bundle {
        val data = Output(UInt(8.W))
      })
      io.data := 42.U
      
      DebugIntrinsic.emit(io.data, "io.data", "IO")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new ProbeTestModule)
    
    // CRITICAL: Check for Probe API constructs in FIRRTL
    // If these are missing, we've regressed to weak binding!
    
    // 1. Probe wire declaration
    firrtl should include regex "wire .* : Probe<.*>"
    
    // 2. Probe definition using probe()
    firrtl should include regex "define\\(.*,\\s*probe\\("
    
    // 3. Intrinsic reads probe with read()
    firrtl should include regex "intrinsic\\(circt_debug_typeinfo.*read\\("
    
    // 4. Should NOT have direct signal in intrinsic (weak binding)
    // This would indicate regression: intrinsic(..., io.data)
    val badPattern = """intrinsic\(circt_debug_typeinfo[^)]*\)\(\s*io\.data\s*\)""".r
    badPattern.findFirstIn(firrtl) shouldBe None
    
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
    
    // Should have 3 distinct probe wires
    val probeWires = """wire (\w+) : Probe<.*>""".r.findAllMatchIn(firrtl)
    val probeNames = probeWires.map(_.group(1)).toSet
    probeNames.size shouldBe >= 3  // At least 3 unique probe names
    
    // Each probe should have corresponding define
    probeNames.foreach { probeName =>
      firrtl should include(s"define($probeName")
    }
    
    sys.props.remove("chisel.debug")
  }
  
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
    // Fix: inner class name may have suffixes, use proper regex escaping
    firrtl should include regex "typeName = \"MyBundle\\$\\d+\""
    
    // CRITICAL: All signals should use Probe API
    val intrinsicCount = "circt_debug_typeinfo".r.findAllMatchIn(firrtl).length
    val probeReadCount = "read\\(".r.findAllMatchIn(firrtl).length
    // At least as many probe reads as intrinsics (may have extra probes for other purposes)
    probeReadCount should be >= intrinsicCount
    
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
    
    // Verify Probe API used
    firrtl should include regex "read\\(.*\\).*:.*Vec"
    
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
    // Fix: inner object name may have suffixes
    firrtl should include regex "typeName = \"MyState\\$\\d+\""
    firrtl should include("enumDef")
    firrtl should (include("IDLE") or include("0:"))
    
    // Verify Probe API used for enum
    firrtl should include regex "Probe<UInt<\\d+>>"
    
    sys.props.remove("chisel.debug")
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
    
    // Should have at least one probe definition per intrinsic
    probeDefineCount should be >= intrinsicCount
    
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
    // Allow CustomBundle or CustomBundle$1
    name should startWith ("CustomBundle")
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
    // Modern FIRRTL uses intrinsic(...) not inst
    firrtl should include regex "intrinsic\\(circt_debug_typeinfo"
    
    // Count intrinsic instances (should be 3)
    val intrinsicCount = "circt_debug_typeinfo".r.findAllMatchIn(firrtl).length
    intrinsicCount should be >= 3
    
    // CRITICAL REGRESSION TEST: Verify all use Probe API
    val probeReadCount = "read\\(".r.findAllMatchIn(firrtl).length
    probeReadCount should be >= intrinsicCount
    
    // Each intrinsic should have read() in its operands
    val intrinsicsWithRead = """intrinsic\(circt_debug_typeinfo[^)]*\)[^)]*read\(""".r
      .findAllMatchIn(firrtl)
      .length
    intrinsicsWithRead shouldBe intrinsicCount
    
    sys.props.remove("chisel.debug")
  }
  
  // REGRESSION GUARD: Detect if someone removes Probe API
  it should "fail if Probe API is removed (regression protection)" in {
    sys.props("chisel.debug") = "true"
    
    class RegressionGuardModule extends Module {
      val io = IO(new Bundle {
        val signal = Output(UInt(16.W))
      })
      io.signal := 0xCAFE.U
      
      DebugIntrinsic.emit(io.signal, "io.signal", "IO")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new RegressionGuardModule)
    
    // If this test fails, someone removed ProbeValue() from DebugIntrinsic.emit!
    withClue("REGRESSION: Probe API missing! Check DebugIntrinsic.emit() implementation.") {
      // Must have Probe type declaration
      firrtl should include regex "Probe<UInt<\\d+>>"
      
      // Must have probe() function call
      firrtl should include("probe(")
      
      // Must have read() in intrinsic context
      firrtl should include regex "circt_debug_typeinfo.*read\\("
    }
    
    sys.props.remove("chisel.debug")
  }
}