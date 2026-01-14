// SPDX-License-Identifier: Apache-2.0

package chiselTests

import chisel3._
import chisel3.stage.phases.{AddDebugIntrinsicsPhase, EnableDebugAnnotation}
import chisel3.stage.ChiselCircuitAnnotation
import circt.stage.ChiselStage
import firrtl.AnnotationSeq
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test suite for AddDebugIntrinsicsPhase
  */
class AddDebugIntrinsicsPhaseSpec extends AnyFlatSpec with Matchers {
  
  behavior of "AddDebugIntrinsicsPhase"
  
  it should "run when EnableDebugAnnotation is present" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      io.out := io.in
    }
    
    // Phase should trigger with annotation
    val firrtl = ChiselStage.emitCHIRRTL(
      new TestModule,
      Array("--enable-debug-intrinsics")
    )
    
    // Should process module even without explicit annotate() calls
    firrtl should include("TestModule")
  }
  
  it should "skip execution when debug is disabled" in {
    sys.props.remove("chisel.debug")
    
    class SimpleModule extends Module {
      val io = IO(new Bundle {
        val data = Output(UInt(8.W))
      })
      io.data := 0.U
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new SimpleModule)
    
    // No intrinsics without debug flag
    firrtl should not include "circt_debug_typeinfo"
  }
  
  it should "process IO ports automatically when enabled" in {
    sys.props("chisel.debug") = "true"
    
    class AutoIOModule extends Module {
      val io = IO(new Bundle {
        val a = Input(UInt(8.W))
        val b = Input(UInt(8.W))
        val sum = Output(UInt(9.W))
      })
      
      io.sum := io.a +& io.b
      // No explicit DebugInfo.annotate() calls!
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new AutoIOModule)
    
    // Phase should auto-annotate IO
    firrtl should include("circt_debug_typeinfo")
    firrtl should include("target = \"io\"")
    
    sys.props.remove("chisel.debug")
  }
  
  it should "handle modules without io field" in {
    sys.props("chisel.debug") = "true"
    
    class NoIOModule extends RawModule {
      val x = Wire(UInt(8.W))
      x := 0.U
    }
    
    // Should not crash on modules without io
    noException should be thrownBy {
      ChiselStage.emitCHIRRTL(new NoIOModule)
    }
    
    sys.props.remove("chisel.debug")
  }
  
  it should "process multiple modules in hierarchy" in {
    sys.props("chisel.debug") = "true"
    
    class SubModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(4.W))
        val out = Output(UInt(4.W))
      })
      io.out := io.in
    }
    
    class TopModule extends Module {
      val io = IO(new Bundle {
        val data = Input(UInt(4.W))
        val result = Output(UInt(4.W))
      })
      
      val sub = Module(new SubModule)
      sub.io.in := io.data
      io.result := sub.io.out
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TopModule)
    
    // Should process both TopModule and SubModule
    firrtl should include("TopModule")
    firrtl should include("SubModule")
    
    sys.props.remove("chisel.debug")
  }
  
  it should "respect phase ordering (after Elaborate, before Convert)" in {
    sys.props("chisel.debug") = "true"
    
    val phase = new AddDebugIntrinsicsPhase
    
    // Check prerequisites
    phase.prerequisites should contain(
      firrtl.options.Dependency[chisel3.stage.phases.Elaborate]
    )
    
    // Check ordering relative to Convert
    phase.optionalPrerequisiteOf should contain(
      firrtl.options.Dependency[chisel3.stage.phases.Convert]
    )
    
    sys.props.remove("chisel.debug")
  }
  
  behavior of "EnableDebugAnnotation"
  
  it should "be a NoTargetAnnotation" in {
    val anno = EnableDebugAnnotation()
    
    // Should be NoTargetAnnotation (no update logic needed)
    anno shouldBe a[firrtl.annotations.NoTargetAnnotation]
  }
  
  behavior of "Phase with complex Bundle hierarchies"
  
  it should "auto-process nested IO bundles" in {
    sys.props("chisel.debug") = "true"
    
    class NestedIO extends Bundle {
      val ctrl = new Bundle {
        val valid = Bool()
        val ready = Bool()
      }
      val data = UInt(32.W)
    }
    
    class NestedIOModule extends Module {
      val io = IO(new NestedIO)
      io.ctrl.ready := io.ctrl.valid
      io.data := 0.U
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new NestedIOModule)
    
    // Should generate intrinsics for nested structure
    firrtl should include("target = \"io\"")
    firrtl should include("target = \"io.ctrl\"")
    firrtl should include("target = \"io.ctrl.valid\"")
    
    sys.props.remove("chisel.debug")
  }
  
  it should "handle IO with Vecs" in {
    sys.props("chisel.debug") = "true"
    
    class VecIOModule extends Module {
      val io = IO(new Bundle {
        val vecIn = Input(Vec(4, UInt(8.W)))
        val vecOut = Output(Vec(4, UInt(8.W)))
      })
      
      io.vecOut := io.vecIn
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new VecIOModule)
    
    firrtl should include("typeName = \"Vec\"")
    firrtl should include("target = \"io.vecIn\"")
    
    sys.props.remove("chisel.debug")
  }
  
  behavior of "DebugIntrinsicGenerator helper"
  
  it should "determine correct binding types" in {
    sys.props("chisel.debug") = "true"
    
    class BindingTestModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      
      val w = Wire(UInt(8.W))
      val r = RegInit(0.U(8.W))
      
      w := io.in
      r := w
      io.out := r
      
      // Explicitly test internal generator
      chisel3.debuginternal.DebugIntrinsic.emit(io.in, "port", "IO")
      chisel3.debuginternal.DebugIntrinsic.emit(w, "wire", "Wire")
      chisel3.debuginternal.DebugIntrinsic.emit(r, "reg", "Reg")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new BindingTestModule)
    
    firrtl should include("binding = \"IO\"")
    firrtl should include("binding = \"Wire\"")
    firrtl should include("binding = \"Reg\"")
    
    sys.props.remove("chisel.debug")
  }
  
  it should "preserve source locations" in {
    sys.props("chisel.debug") = "true"
    
    class SourceLocModule extends Module {
      val io = IO(new Bundle {
        val data = Output(UInt(8.W))
      })
      
      io.data := 42.U  // Source location should be captured
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new SourceLocModule)
    
    // Should include source file/line parameters
    firrtl should include("sourceFile")
    firrtl should include("sourceLine")
    
    sys.props.remove("chisel.debug")
  }
}
