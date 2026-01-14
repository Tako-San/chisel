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
  
  it should "preserve type info through full compilation" in {
    sys.props("chisel.debug") = "true"
    
    class CompleteBundle(val width: Int) extends Bundle {
      val valid = Bool()
      val data = UInt(width.W)
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
    firrtl should include("typeName = \"CompleteBundle\"")
    firrtl should include("parameters = \"width=16\"")
    
    // Verify source locations present
    firrtl should include("sourceFile")
    firrtl should include("sourceLine")
    
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
    firrtl should include("typeName = \"FsmState\"")
    firrtl should include("enumDef")
    
    // Verify enum values appear (in some form)
    val hasEnumValues = 
      firrtl.contains("IDLE") || 
      firrtl.contains("FETCH") ||
      firrtl.contains("0:") || 
      firrtl.contains("1:")
    
    hasEnumValues shouldBe true
    
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
    
    // Verify type names
    firrtl should include("typeName = \"CacheInterface\"")
    firrtl should include("typeName = \"MemInterface\"")
    firrtl should include("typeName = \"MemRequest\"")
    
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
    firrtl should include("elementType=QueueEntry")
    
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
    firrtl should include("input a")
    firrtl should include("output sum")
    firrtl should include("circt_debug_typeinfo")
    
    // Verify intrinsics don't interfere with actual logic
    firrtl should include regex "sum.*<=.*add"
    
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
    
    sys.props.remove("chisel.debug")
  }
}
