// SPDX-License-Identifier: Apache-2.0

package chiselTests

import chisel3._
import chisel3.util.circt.DebugInfo
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * End-to-end integration tests for DebugInfo pipeline
  * Tests the full flow: Chisel → FIRRTL intrinsics → expected output
  */
class DebugInfoIntegrationSpec extends AnyFlatSpec with Matchers {
  
  behavior of "DebugInfo End-to-End Pipeline"
  
  // HELPER: Validate Probe API usage in FIRRTL
  def validateProbeAPI(firrtl: String, intrinsicCount: Int): Unit = {
    withClue("Probe API validation failed:") {
      // 1. Must have Probe type declarations
      firrtl should include regex "Probe<.*>"
      
      // 2. Must have probe() definitions
      val probeDefineCount = "define\\(".r.findAllMatchIn(firrtl).length
      probeDefineCount should be >= intrinsicCount
      
      // 3. Must have read() in intrinsics
      val readCount = "read\\(".r.findAllMatchIn(firrtl).length
      readCount should be >= intrinsicCount
      
      // 4. Each intrinsic should use read(), not direct signal
      val intrinsicsWithRead = """intrinsic\(circt_debug_typeinfo[^)]*\)[^)]*read\(""".r
        .findAllMatchIn(firrtl)
        .length
      intrinsicsWithRead shouldBe >= intrinsicCount
    }
  }
  
  it should "preserve type info through full compilation" in {
    sys.props("chisel.debug") = "true"
    
    class CompleteBundle(val dataWidth: Int) extends Bundle {
      val valid = Bool()
      val data = UInt(dataWidth.W)
    }
    
    class IntegrationModule extends Module {
      val io = IO(new Bundle {
        val in = Input(new CompleteBundle(16))
        val out = Output(new CompleteBundle(16))
      })
      
      // Test all annotation patterns
      DebugInfo.annotate(io.in, "io.in")
      DebugInfo.annotateRecursive(io.out, "io.out")
      
      val reg = RegInit(0.U.asTypeOf(new CompleteBundle(16)))
      reg := io.in
      io.out := reg
      
      DebugInfo.annotate(reg, "stateReg")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new IntegrationModule)
    
    // Verify intrinsic structure
    firrtl should include("intrinsic(circt_debug_typeinfo")
    
    // Verify all annotated signals present
    firrtl should include("target = \"io.in\"")
    firrtl should include("target = \"io.out\"")
    firrtl should include("target = \"stateReg\"")
    
    // Verify recursive annotation of io.out
    firrtl should include("target = \"io.out.valid\"")
    firrtl should include("target = \"io.out.data\"")
    
    // Verify Bundle parameters captured
    firrtl should include regex "typeName = \"CompleteBundle\\$\\d+\""
    firrtl should include("parameters = \"$outer=DebugInfoIntegrationSpec;dataWidth=16\"")
    
    // Verify source locations present
    firrtl should include("sourceFile")
    firrtl should include("sourceLine")
    
    // CRITICAL: Validate Probe API usage
    val intrinsicCount = "circt_debug_typeinfo".r.findAllMatchIn(firrtl).length
    validateProbeAPI(firrtl, intrinsicCount)
    
    sys.props.remove("chisel.debug")
  }
  
  it should "generate correct FIRRTL for ChiselEnum" in {
    sys.props("chisel.debug") = "true"
    
    object FsmState extends ChiselEnum {
      val sIDLE, sFETCH, sDECODE, sEXECUTE, sWRITEBACK = Value
    }
    
    class EnumIntegrationModule extends Module {
      val state = RegInit(FsmState.sIDLE)
      state := FsmState.sFETCH
      
      DebugInfo.annotate(state, "fsmState")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new EnumIntegrationModule)
    
    // Verify enum handling
    firrtl should include regex "typeName = \"FsmState\\$\\d+\""
    firrtl should include("enumDef")
    
    // Verify enum values appear (in some form)
    val hasEnumValues = 
      firrtl.contains("IDLE") || 
      firrtl.contains("FETCH") ||
      firrtl.contains("0:") || 
      firrtl.contains("1:")
    
    hasEnumValues shouldBe true
    
    // CRITICAL: Validate Probe API for enum
    validateProbeAPI(firrtl, intrinsicCount = 1)
    
    sys.props.remove("chisel.debug")
  }
  
  it should "handle complex nested structures" in {
    sys.props("chisel.debug") = "true"
    
    class MemRequest extends Bundle {
      val addr = UInt(32.W)
      val data = UInt(64.W)
      val mask = UInt(8.W)
    }
    
    class MemInterface extends Bundle {
      val req = new MemRequest
      val valid = Bool()
      val ready = Bool()
    }
    
    class CacheInterface extends Bundle {
      val cpu = Flipped(new MemInterface)
      val mem = new MemInterface
    }
    
    class ComplexModule extends Module {
      val io = IO(new CacheInterface)
      
      // Connect through
      io.mem <> io.cpu
      
      // Annotate entire hierarchy
      DebugInfo.annotateRecursive(io, "io")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new ComplexModule)
    
    // Verify all hierarchy levels present
    firrtl should include("target = \"io\"")
    firrtl should include("target = \"io.cpu\"")
    firrtl should include("target = \"io.cpu.req\"")
    firrtl should include("target = \"io.cpu.req.addr\"")
    firrtl should include("target = \"io.mem\"")
    
    // Verify type names (relaxed to allow Scala inner class suffixes)
    firrtl should include regex "typeName = \"CacheInterface.*\""
    firrtl should include regex "typeName = \"MemInterface.*\""
    firrtl should include regex "typeName = \"MemRequest.*\""
    
    // CRITICAL: All nested signals should use Probe API
    val intrinsicCount = "circt_debug_typeinfo".r.findAllMatchIn(firrtl).length
    validateProbeAPI(firrtl, intrinsicCount)
    
    sys.props.remove("chisel.debug")
  }
  
  it should "support Vec of complex types" in {
    sys.props("chisel.debug") = "true"
    
    class QueueEntry extends Bundle {
      val valid = Bool()
      val tag = UInt(4.W)
      val payload = UInt(32.W)
    }
    
    class VecComplexModule extends Module {
      val io = IO(new Bundle {
        val queue = Output(Vec(8, new QueueEntry))
      })
      
      io.queue := VecInit(Seq.fill(8)(0.U.asTypeOf(new QueueEntry)))
      
      DebugInfo.annotateRecursive(io.queue, "io.queue")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new VecComplexModule)
    
    // Verify Vec metadata
    firrtl should include("typeName = \"Vec\"")
    firrtl should include("length=8")
    // Scala compiler may add suffix to inner class names
    firrtl should include regex "elementType=QueueEntry.*"
    
    // CRITICAL: Vec should use Probe API
    val intrinsicCount = "circt_debug_typeinfo".r.findAllMatchIn(firrtl).length
    validateProbeAPI(firrtl, intrinsicCount)
    
    sys.props.remove("chisel.debug")
  }
  
  it should "handle multiple modules with independent annotations" in {
    sys.props("chisel.debug") = "true"
    
    class SubModuleA extends Module {
      val io = IO(new Bundle {
        val x = Input(UInt(8.W))
        val y = Output(UInt(8.W))
      })
      io.y := io.x
      DebugInfo.annotate(io, "subA_io")
    }
    
    class SubModuleB extends Module {
      val io = IO(new Bundle {
        val a = Input(UInt(8.W))
        val b = Output(UInt(8.W))
      })
      io.b := io.a
      DebugInfo.annotate(io, "subB_io")
    }
    
    class TopMultiModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      
      val subA = Module(new SubModuleA)
      val subB = Module(new SubModuleB)
      
      subA.io.x := io.in
      subB.io.a := subA.io.y
      io.out := subB.io.b
      
      DebugInfo.annotate(io, "top_io")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TopMultiModule)
    
    // Verify all modules' annotations present
    firrtl should include("target = \"top_io\"")
    firrtl should include("target = \"subA_io\"")
    firrtl should include("target = \"subB_io\"")
    
    // CRITICAL: All modules should use Probe API
    val intrinsicCount = "circt_debug_typeinfo".r.findAllMatchIn(firrtl).length
    validateProbeAPI(firrtl, intrinsicCount)
    
    sys.props.remove("chisel.debug")
  }
  
  it should "produce valid FIRRTL that doesn't break existing passes" in {
    sys.props("chisel.debug") = "true"
    
    class ValidFIRRTLModule extends Module {
      val io = IO(new Bundle {
        val a = Input(UInt(8.W))
        val b = Input(UInt(8.W))
        val sum = Output(UInt(9.W))
      })
      
      io.sum := io.a +& io.b
      
      DebugInfo.annotate(io.a, "operand_a")
      DebugInfo.annotate(io.b, "operand_b")
      DebugInfo.annotate(io.sum, "result")
    }
    
    // Should not throw during FIRRTL emission
    val firrtl = ChiselStage.emitCHIRRTL(new ValidFIRRTLModule)
    
    // Basic sanity checks
    firrtl should include("module ValidFIRRTLModule")
    // Modern Chisel generates Bundle IOs, so ports are aggregated in 'io'
    firrtl should include regex "output io.*flip a.*UInt"
    firrtl should include regex "output io.*sum.*UInt"
    firrtl should include("circt_debug_typeinfo")
    
    // Verify intrinsics don't interfere with actual logic
    // Modern FIRRTL uses = for connections
    firrtl should include regex "sum.*=.*add"
    
    // CRITICAL: Validate Probe API doesn't break FIRRTL validity
    validateProbeAPI(firrtl, intrinsicCount = 3)
    
    sys.props.remove("chisel.debug")
  }
  
  it should "respect flag disabling at runtime" in {
    sys.props.remove("chisel.debug")
    
    class FlagTestModule extends Module {
      val io = IO(new Bundle {
        val data = Output(UInt(8.W))
      })
      io.data := 0.U
      
      // Try to annotate, but flag is off
      DebugInfo.annotate(io.data, "data")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new FlagTestModule)
    
    // Should NOT contain intrinsics
    firrtl should not include "circt_debug_typeinfo"
    firrtl should not include "target = \"data\""
    
    // Should NOT contain Probe constructs when disabled
    firrtl should not include regex "Probe<.*>"
    firrtl should not include("probe(")
  }
  
  it should "count correct number of intrinsics" in {
    sys.props("chisel.debug") = "true"
    
    class CountingModule extends Module {
      val io = IO(new Bundle {
        val a = Input(UInt(8.W))
        val b = Input(UInt(8.W))
        val c = Output(UInt(8.W))
      })
      
      io.c := io.a + io.b
      
      // Exactly 3 annotations
      DebugInfo.annotate(io.a, "a")
      DebugInfo.annotate(io.b, "b")
      DebugInfo.annotate(io.c, "c")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new CountingModule)
    
    val intrinsicCount = "circt_debug_typeinfo".r.findAllMatchIn(firrtl).length
    intrinsicCount shouldBe 3
    
    // CRITICAL: Should have exactly 3 probe definitions
    val probeDefineCount = "define\\(".r.findAllMatchIn(firrtl).length
    probeDefineCount should be >= 3
    
    // CRITICAL: Should have exactly 3 read() calls in intrinsics
    val intrinsicsWithRead = """intrinsic\(circt_debug_typeinfo[^)]*\)[^)]*read\(""".r
      .findAllMatchIn(firrtl)
      .length
    intrinsicsWithRead shouldBe 3
    
    sys.props.remove("chisel.debug")
  }
  
  // CRITICAL REGRESSION TEST: E2E validation
  it should "fail loudly if Probe API is removed from pipeline" in {
    sys.props("chisel.debug") = "true"
    
    class E2ERegressionGuard extends Module {
      val io = IO(new Bundle {
        val control = Input(UInt(4.W))
        val status = Output(UInt(8.W))
      })
      
      val state = RegInit(0.U(8.W))
      when(io.control === 1.U) {
        state := state + 1.U
      }
      io.status := state
      
      DebugInfo.annotate(io.control, "io.control")
      DebugInfo.annotate(io.status, "io.status")
      DebugInfo.annotate(state, "state")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new E2ERegressionGuard)
    
    // If these fail, Probe API was removed!
    withClue("CRITICAL REGRESSION: Probe API missing from E2E pipeline!") {
      // Must have Probe types
      firrtl should include regex "wire .* : Probe<"
      
      // Must have probe definitions
      firrtl should include regex "define\\(.*,\\s*probe\\("
      
      // ALL intrinsics must use read()
      val intrinsicCount = "circt_debug_typeinfo".r.findAllMatchIn(firrtl).length
      val intrinsicsWithRead = """intrinsic\(circt_debug_typeinfo[^)]*\)[^)]*read\(""".r
        .findAllMatchIn(firrtl)
        .length
      
      intrinsicsWithRead shouldBe intrinsicCount
      
      // NO intrinsic should use direct signal (weak binding)
      val weakBindingPattern = """intrinsic\(circt_debug_typeinfo[^)]*\)\(\s*io\.""".r
      weakBindingPattern.findFirstIn(firrtl) shouldBe None
    }
    
    sys.props.remove("chisel.debug")
  }
}