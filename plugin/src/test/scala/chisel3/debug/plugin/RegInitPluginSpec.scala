// SPDX-License-Identifier: Apache-2.0

package chisel3.debug.plugin

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * MVP test suite for compiler plugin.
  * 
  * Tests automatic instrumentation of RegInit without manual annotations.
  */
class RegInitPluginSpec extends AnyFlatSpec with Matchers {
  
  behavior of "Compiler Plugin (RegInit MVP)"
  
  it should "automatically instrument RegInit with ZERO code changes" in {
    // Enable plugin via system property
    sys.props("chisel.debug") = "true"
    
    // ========================================
    // USER CODE - NO MANUAL ANNOTATIONS!
    // ========================================
    class AutoInstrumentedModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      
      // NO DebugInfo.annotate() calls!
      val state = RegInit(0.U(8.W))
      val counter = RegInit(0.U(16.W))
      
      when(io.in > 0.U) {
        state := io.in
        counter := counter + 1.U
      }
      
      io.out := state
    }
    // ========================================
    
    // Compile to FIRRTL
    val firrtl = ChiselStage.emitCHIRRTL(new AutoInstrumentedModule)
    
    // CRITICAL: Verify intrinsics generated WITHOUT manual annotation!
    withClue("Missing intrinsic for 'state' - plugin not working!") {
      firrtl should include("circt_debug_typeinfo")
      firrtl should include("target = \"state\"")
      firrtl should include("binding = \"Reg\"")
    }
    
    withClue("Missing intrinsic for 'counter' - plugin not working!") {
      firrtl should include("target = \"counter\"")
      firrtl should include("binding = \"Reg\"")
    }
    
    // Verify Probe API is used
    firrtl should include regex "Probe<.*>"
    firrtl should include("probe(")
    firrtl should include("read(")
    
    sys.props.remove("chisel.debug")
  }
  
  it should "NOT generate intrinsics when plugin disabled" in {
    sys.props.remove("chisel.debug")
    
    class NoInstrumentModule extends Module {
      val state = RegInit(0.U(8.W))
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new NoInstrumentModule)
    
    // Should NOT contain debug intrinsics
    firrtl should not include "circt_debug_typeinfo"
  }
  
  it should "handle multiple RegInit in same module" in {
    sys.props("chisel.debug") = "true"
    
    class MultiRegModule extends Module {
      val io = IO(new Bundle {
        val out1 = Output(UInt(8.W))
        val out2 = Output(UInt(8.W))
        val out3 = Output(UInt(8.W))
      })
      
      val reg1 = RegInit(1.U(8.W))
      val reg2 = RegInit(2.U(8.W))
      val reg3 = RegInit(3.U(8.W))
      
      io.out1 := reg1
      io.out2 := reg2
      io.out3 := reg3
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new MultiRegModule)
    
    // All three should be instrumented
    firrtl should include("target = \"reg1\"")
    firrtl should include("target = \"reg2\"")
    firrtl should include("target = \"reg3\"")
    
    // Count intrinsics (should be at least 3)
    val intrinsicCount = "circt_debug_typeinfo".r.findAllMatchIn(firrtl).length
    intrinsicCount should be >= 3
    
    sys.props.remove("chisel.debug")
  }
  
  it should "preserve original module behavior (no-op transformation)" in {
    sys.props("chisel.debug") = "true"
    
    class BehaviorTestModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      
      val state = RegInit(42.U(8.W))
      when(io.in === 0.U) {
        state := 0.U
      }.otherwise {
        state := state + io.in
      }
      io.out := state
    }
    
    // Should compile without errors
    noException should be thrownBy {
      ChiselStage.emitCHIRRTL(new BehaviorTestModule)
    }
    
    sys.props.remove("chisel.debug")
  }
  
  it should "handle RegInit with type parameters" in {
    sys.props("chisel.debug") = "true"
    
    class ParametricBundle(val width: Int) extends Bundle {
      val value = UInt(width.W)
      val valid = Bool()
    }
    
    class TypeParamModule extends Module {
      val io = IO(new Bundle {
        val out = Output(new ParametricBundle(32))
      })
      
      val state = RegInit(0.U.asTypeOf(new ParametricBundle(32)))
      io.out := state
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TypeParamModule)
    
    firrtl should include("target = \"state\"")
    firrtl should include("binding = \"Reg\"")
    
    sys.props.remove("chisel.debug")
  }
}
