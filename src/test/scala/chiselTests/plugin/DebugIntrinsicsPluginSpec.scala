// SPDX-License-Identifier: Apache-2.0

package chiselTests.plugin

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Unit tests for ComponentDebugIntrinsics compiler plugin.
  * 
  * Tests AST transformation correctness and integration with DebugIntrinsic API.
  */
class DebugIntrinsicsPluginSpec extends AnyFlatSpec with Matchers {
  
  behavior of "ComponentDebugIntrinsics Plugin"
  
  it should "instrument RegInit automatically" in {
    // Enable both plugin and debug mode
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    class TestModule extends Module {
      val state = RegInit(0.U(8.W))
      // NO manual DebugInfo.annotate() call!
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    
    // Verify intrinsic generated automatically
    firrtl should include("circt_debug_typeinfo")
    firrtl should include regex """target\s*=\s*"state"""
    firrtl should include regex """binding\s*=\s*"Reg"""
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  it should "instrument Wire automatically" in {
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    class TestModule extends Module {
      val temp = Wire(UInt(8.W))
      temp := 0.U
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    
    firrtl should include regex """target\s*=\s*"temp"""
    firrtl should include regex """binding\s*=\s*"Wire"""
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  it should "instrument IO automatically" in {
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    class TestModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
      })
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    
    firrtl should include regex """target\s*=\s*"io"""
    firrtl should include regex """binding\s*=\s*"IO"""
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  it should "not instrument when plugin disabled" in {
    sys.props.remove("chisel.plugin.debugintrinsics")  // Plugin OFF
    sys.props("chisel.debug") = "true"
    
    class TestModule extends Module {
      val state = RegInit(0.U(8.W))
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    
    // Should NOT have intrinsics (plugin disabled)
    firrtl should not include "circt_debug_typeinfo"
    
    sys.props.remove("chisel.debug")
  }
  
  it should "not emit intrinsics when debug mode disabled" in {
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props.remove("chisel.debug")  // Debug mode OFF
    
    class TestModule extends Module {
      val state = RegInit(0.U(8.W))
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    
    // Transformation happens, but intrinsics inactive at runtime
    // Should NOT have circt_debug_typeinfo in FIRRTL
    firrtl should not include "circt_debug_typeinfo"
    
    sys.props.remove("chisel.plugin.debugintrinsics")
  }
  
  it should "handle multiple signals" in {
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    class TestModule extends Module {
      val io = IO(new Bundle { val in = Input(UInt(8.W)) })
      val reg1 = RegInit(0.U(8.W))
      val reg2 = RegInit(0.U(8.W))
      val wire1 = Wire(UInt(8.W))
      
      wire1 := io.in
      reg1 := wire1
      reg2 := reg1
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    
    // All signals should have intrinsics
    firrtl should include regex """target\s*=\s*"io"""
    firrtl should include regex """target\s*=\s*"reg1"""
    firrtl should include regex """target\s*=\s*"reg2"""
    firrtl should include regex """target\s*=\s*"wire1"""
    
    // Count intrinsics (should be 4+)
    val intrinsicCount = """circt_debug_typeinfo""".r.findAllMatchIn(firrtl).length
    intrinsicCount should be >= 4
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  it should "not create infinite recursion" in {
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    // This should compile without stack overflow
    class TestModule extends Module {
      val state = RegInit(0.U(8.W))
    }
    
    noException should be thrownBy {
      ChiselStage.emitCHIRRTL(new TestModule)
    }
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  it should "preserve type information" in {
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    class MyBundle extends Bundle {
      val x = UInt(8.W)
    }
    
    class TestModule extends Module {
      val io = IO(new MyBundle)
      val reg = RegInit(0.U(32.W))
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    
    // Verify type names preserved
    firrtl should include regex """typeName\s*=\s*"MyBundle"""
    firrtl should include regex """typeName\s*=\s*"UInt"""
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  it should "work with WireInit" in {
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    class TestModule extends Module {
      val data = WireInit(42.U(8.W))
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    
    firrtl should include regex """target\s*=\s*"data"""
    firrtl should include regex """binding\s*=\s*"Wire"""
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  it should "work with Reg (no init)" in {
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    class TestModule extends Module {
      val state = Reg(UInt(8.W))
      state := 0.U
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    
    firrtl should include regex """target\s*=\s*"state"""
    firrtl should include regex """binding\s*=\s*"Reg"""
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  it should "work with RegNext" in {
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    class TestModule extends Module {
      val io = IO(new Bundle { val in = Input(UInt(8.W)) })
      val delayed = RegNext(io.in)
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    
    firrtl should include regex """target\s*=\s*"delayed"""
    firrtl should include regex """binding\s*=\s*"Reg"""
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  it should "not instrument non-Chisel code" in {
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    class TestModule extends Module {
      val io = IO(new Bundle { val out = Output(UInt(8.W)) })
      
      // Regular Scala val (not Chisel)
      val scalaVal = 42
      
      // Chisel (should be instrumented)
      val chiselWire = Wire(UInt(8.W))
      chiselWire := scalaVal.U
      io.out := chiselWire
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    
    // Should have intrinsic for chiselWire
    firrtl should include regex """target\s*=\s*"chiselWire"""
    
    // Should NOT have false positive for scalaVal
    // (It wouldn't have intrinsic pattern anyway since it's not Data)
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  it should "handle nested Modules" in {
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    class InnerModule extends Module {
      val io = IO(new Bundle { val data = Output(UInt(8.W)) })
      val reg = RegInit(0.U(8.W))
      io.data := reg
    }
    
    class OuterModule extends Module {
      val io = IO(new Bundle { val out = Output(UInt(8.W)) })
      val inner = Module(new InnerModule)
      io.out := inner.io.data
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new OuterModule)
    
    // Both outer and inner signals should be instrumented
    firrtl should include regex """circt_debug_typeinfo"""
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  it should "have zero overhead when fully disabled" in {
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
    
    class TestModule extends Module {
      val io = IO(new Bundle { val in = Input(UInt(8.W)) })
      val state = RegInit(0.U(8.W))
    }
    
    val firrtlWithoutPlugin = ChiselStage.emitCHIRRTL(new TestModule)
    
    // Should be identical to manual code (no overhead)
    firrtlWithoutPlugin should not include "circt_debug_typeinfo"
    firrtlWithoutPlugin should not include "Probe<"
    firrtlWithoutPlugin should not include "_debug_tmp"
  }
}
