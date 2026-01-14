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
    firrtl should include("typeName = \"MyBundle\"")
    
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
    firrtl should include("typeName = \"MyState\"")
    firrtl should include("enumDef")
    firrtl should (include("IDLE") or include("0:"))
    
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
    
    sys.props.remove("chisel.debug")
  }
  
  it should "extract correct type names for all Data types" in {
    DebugIntrinsic.extractTypeName(UInt(8.W)) shouldBe "UInt"
    DebugIntrinsic.extractTypeName(SInt(8.W)) shouldBe "SInt"
    DebugIntrinsic.extractTypeName(Bool()) shouldBe "Bool"
    
    class CustomBundle extends Bundle {
      val x = UInt(8.W)
    }
    DebugIntrinsic.extractTypeName(new CustomBundle) shouldBe "CustomBundle"
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
    firrtl should include regex "inst.*circt_debug_typeinfo"
    
    // Count intrinsic instances (should be 3)
    val intrinsicCount = "circt_debug_typeinfo".r.findAllMatchIn(firrtl).length
    intrinsicCount should be >= 3
    
    sys.props.remove("chisel.debug")
  }
}
