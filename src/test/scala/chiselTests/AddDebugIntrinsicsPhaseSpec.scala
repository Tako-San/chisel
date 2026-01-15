// SPDX-License-Identifier: Apache-2.0

package chiselTests

import chisel3._
import chisel3.stage.phases.{AddDebugIntrinsicsPhase, EnableDebugAnnotation}
import chisel3.stage.ChiselCircuitAnnotation
import chisel3.util.circt.DebugInfo
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
    sys.props("chisel.debug") = "true"
    
    class TestModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      io.out := io.in
      
      // Explicit annotation required
      DebugInfo.annotate(io.in, "io.in")
    }
    
    // Phase should trigger with system property
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    
    // Should process module with explicit annotations
    firrtl should include("TestModule")
    firrtl should include("circt_debug_typeinfo")
    
    sys.props.remove("chisel.debug")
  }
  
  it should "skip execution when debug is disabled" in {
    sys.props.remove("chisel.debug")
    
    class SimpleModule extends Module {
      val io = IO(new Bundle {
        val data = Output(UInt(8.W))
      })
      io.data := 0.U
      
      // Try to annotate, but flag is off
      DebugInfo.annotate(io.data, "data")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new SimpleModule)
    
    // No intrinsics without debug flag
    firrtl should not include "circt_debug_typeinfo"
  }
  
  it should "process explicitly annotated signals" in {
    sys.props("chisel.debug") = "true"
    
    class ExplicitModule extends Module {
      val io = IO(new Bundle {
        val a = Input(UInt(8.W))
        val b = Input(UInt(8.W))
        val sum = Output(UInt(9.W))
      })
      
      io.sum := io.a +& io.b
      
      // Explicit DebugInfo.annotate() calls
      DebugInfo.annotate(io.a, "io.a")
      DebugInfo.annotate(io.sum, "io.sum")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new ExplicitModule)
    
    // Phase should process explicit annotations
    firrtl should include("circt_debug_typeinfo")
    firrtl should include("target = \"io.a\"")
    firrtl should include("target = \"io.sum\"")
    
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
      DebugInfo.annotate(io.in, "subIn")
    }
    
    class TopModule extends Module {
      val io = IO(new Bundle {
        val data = Input(UInt(4.W))
        val result = Output(UInt(4.W))
      })
      
      val sub = Module(new SubModule)
      sub.io.in := io.data
      io.result := sub.io.out
      
      DebugInfo.annotate(io.data, "topData")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TopModule)
    
    // Should process both TopModule and SubModule
    firrtl should include("TopModule")
    firrtl should include("SubModule")
    firrtl should include("target = \"topData\"")
    firrtl should include("target = \"subIn\"")
    
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
  
  behavior of "Phase with explicit annotations"
  
  it should "process nested IO bundles with explicit annotation" in {
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
      
      // Explicit recursive annotation
      DebugInfo.annotateRecursive(io, "io")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new NestedIOModule)
    
    // Should generate intrinsics for nested structure
    firrtl should include("target = \"io\"")
    firrtl should include("target = \"io.ctrl\"")
    firrtl should include("target = \"io.ctrl.valid\"")
    
    sys.props.remove("chisel.debug")
  }
  
  it should "handle IO with Vecs when explicitly annotated" in {
    sys.props("chisel.debug") = "true"
    
    class VecIOModule extends Module {
      val io = IO(new Bundle {
        val vecIn = Input(Vec(4, UInt(8.W)))
        val vecOut = Output(Vec(4, UInt(8.W)))
      })
      
      io.vecOut := io.vecIn
      
      // Explicit annotation
      DebugInfo.annotate(io.vecIn, "io.vecIn")
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
      
      // Explicit annotation
      DebugInfo.annotate(io.data, "io.data")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new SourceLocModule)
    
    // Should include source file/line parameters
    firrtl should include("sourceFile")
    firrtl should include("sourceLine")
    
    sys.props.remove("chisel.debug")
  }
}
