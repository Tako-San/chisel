// SPDX-License-Identifier: Apache-2.0

package chiselTests.plugin

import chisel3._
import chiselTests.DebugTestHelpers
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
  
  // Helper to enable plugin for a test block
  def withPlugin[T](block: => T): T = {
    val prevPlugin = sys.props.get("chisel.plugin.debugintrinsics")
    try {
      sys.props("chisel.plugin.debugintrinsics") = "true"
      block
    } finally {
      prevPlugin match {
        case Some(v) => sys.props("chisel.plugin.debugintrinsics") = v
        case None => sys.props.remove("chisel.plugin.debugintrinsics")
      }
    }
  }

  it should "instrument RegInit automatically" in {
    withPlugin {
      DebugTestHelpers.withDebugMode {
        class TestModule extends Module {
          val state = RegInit(0.U(8.W))
          // NO manual DebugInfo.annotate() call!
        }
        
        val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
        
        // Verify specific target info
        firrtl should include regex """target\s*=\s*"state""""
        firrtl should include regex """binding\s*=\s*"Reg""""
        
        // Validate Probe API usage (Redundancy check: replaces manual regex for intrinsic structure)
        DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 1)
      }
    }
  }
  
  it should "instrument Wire automatically" in {
    withPlugin {
      DebugTestHelpers.withDebugMode {
        class TestModule extends Module {
          val temp = Wire(UInt(8.W))
          temp := 0.U
        }
        
        val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
        
        firrtl should include regex """target\s*=\s*"temp""""
        firrtl should include regex """binding\s*=\s*"Wire""""
        
        DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 1)
      }
    }
  }
  
  it should "instrument IO automatically" in {
    withPlugin {
      DebugTestHelpers.withDebugMode {
        class TestModule extends Module {
          val io = IO(new Bundle {
            val in = Input(UInt(8.W))
          })
        }
        
        val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
        
        firrtl should include regex """target\s*=\s*"io""""
        firrtl should include regex """binding\s*=\s*"IO""""
        
        DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 1)
      }
    }
  }
  
  it should "not instrument when plugin disabled" in {
    sys.props.remove("chisel.plugin.debugintrinsics")  // Plugin OFF
    DebugTestHelpers.withDebugMode {
      class TestModule extends Module {
        val state = RegInit(0.U(8.W))
      }
      
      val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
      
      // Should NOT have intrinsics (plugin disabled)
      firrtl should not include "circt_debug_typeinfo"
      DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 0)
    }
  }
  
  it should "not emit intrinsics when debug mode disabled" in {
    withPlugin {
      sys.props.remove("chisel.debug")  // Debug mode OFF
      
      class TestModule extends Module {
        val state = RegInit(0.U(8.W))
      }
      
      val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
      
      // Transformation happens, but intrinsics inactive at runtime
      firrtl should not include "circt_debug_typeinfo"
      DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 0)
    }
  }
  
  it should "handle multiple signals" in {
    withPlugin {
      DebugTestHelpers.withDebugMode {
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
        firrtl should include regex """target\s*=\s*"io""""
        firrtl should include regex """target\s*=\s*"reg1""""
        firrtl should include regex """target\s*=\s*"reg2""""
        firrtl should include regex """target\s*=\s*"wire1""""
        
        DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 4)
      }
    }
  }
  
  it should "not create infinite recursion" in {
    withPlugin {
      DebugTestHelpers.withDebugMode {
        // This should compile without stack overflow
        class TestModule extends Module {
          val state = RegInit(0.U(8.W))
        }
        
        noException should be thrownBy {
          ChiselStage.emitCHIRRTL(new TestModule)
        }
      }
    }
  }
  
  it should "preserve type information" in {
    withPlugin {
      DebugTestHelpers.withDebugMode {
        class MyBundle extends Bundle {
          val x = UInt(8.W)
        }
        
        class TestModule extends Module {
          val io = IO(new MyBundle)
          val reg = RegInit(0.U(32.W))
        }
        
        val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
        
        // Verify type names preserved
        firrtl should include regex """typeName\s*=\s*"MyBundle""""
        firrtl should include regex """typeName\s*=\s*"UInt""""
        
        DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 1)
      }
    }
  }
  
  it should "work with WireInit" in {
    withPlugin {
      DebugTestHelpers.withDebugMode {
        class TestModule extends Module {
          val data = WireInit(42.U(8.W))
        }
        
        val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
        
        firrtl should include regex """target\s*=\s*"data""""
        firrtl should include regex """binding\s*=\s*"Wire""""
        
        DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 1)
      }
    }
  }
  
  it should "work with Reg (no init)" in {
    withPlugin {
      DebugTestHelpers.withDebugMode {
        class TestModule extends Module {
          val state = Reg(UInt(8.W))
          state := 0.U
        }
        
        val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
        
        firrtl should include regex """target\s*=\s*"state""""
        firrtl should include regex """binding\s*=\s*"Reg""""
        
        DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 1)
      }
    }
  }
  
  it should "work with RegNext" in {
    withPlugin {
      DebugTestHelpers.withDebugMode {
        class TestModule extends Module {
          val io = IO(new Bundle { val in = Input(UInt(8.W)) })
          val delayed = RegNext(io.in)
        }
        
        val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
        
        firrtl should include regex """target\s*=\s*"delayed""""
        firrtl should include regex """binding\s*=\s*"Reg""""
        
        DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 1)
      }
    }
  }
  
  it should "not instrument non-Chisel code" in {
    withPlugin {
      DebugTestHelpers.withDebugMode {
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
        firrtl should include regex """target\s*=\s*"chiselWire""""
        
        DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 1)
      }
    }
  }
  
  it should "handle nested Modules" in {
    withPlugin {
      DebugTestHelpers.withDebugMode {
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
        // Simple check that we see debug info
        firrtl should include regex """circt_debug_typeinfo"""
        
        // We expect at least one from Outer and one from Inner
        DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 2)
      }
    }
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
    
    DebugTestHelpers.assertProbeAPIUsed(firrtlWithoutPlugin, minIntrinsics = 0)
  }
}