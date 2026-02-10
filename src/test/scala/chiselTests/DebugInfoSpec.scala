// SPDX-License-Identifier: Apache-2.0

package chiselTests

import chisel3._
import chisel3.util.circt.DebugInfo
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test suite for user-facing DebugInfo API
  */
class DebugInfoSpec extends AnyFlatSpec with Matchers {
  
  behavior of "DebugInfo.annotate"
  
  it should "annotate signals via public API" in {
    DebugTestHelpers.withDebugMode {
      class PublicAPIModule extends Module {
        val io = IO(new Bundle {
          val in = Input(UInt(8.W))
          val out = Output(UInt(8.W))
        })
        
        // Use public API instead of internal DebugIntrinsic
        DebugInfo.annotate(io.in, "io.in")
        DebugInfo.annotate(io.out, "io.out")
        
        io.out := io.in
      }
      
      val firrtl = ChiselStage.emitCHIRRTL(new PublicAPIModule)
      
      firrtl should include("circt_debug_typeinfo")
      firrtl should include("target = \"io.in\"")
      firrtl should include("target = \"io.out\"")
    }
  }
  
  it should "support chaining (returns original signal)" in {
    DebugTestHelpers.withDebugMode {
      class ChainModule extends Module {
        val io = IO(new Bundle {
          val in = Input(UInt(8.W))
          val out = Output(UInt(8.W))
        })
        
        // Annotate and use in same expression
        io.out := DebugInfo.annotate(io.in, "io.in") + 1.U
      }
      
      val firrtl = ChiselStage.emitCHIRRTL(new ChainModule)
      firrtl should include("circt_debug_typeinfo")
    }
  }
  
  it should "handle default name parameter" in {
    DebugTestHelpers.withDebugMode {
      class DefaultNameModule extends Module {
        val io = IO(new Bundle {
          val data = Output(UInt(8.W))
        })
        
        // No explicit name - uses "signal" as default
        DebugInfo.annotate(io.data)
        
        io.data := 42.U
      }
      
      val firrtl = ChiselStage.emitCHIRRTL(new DefaultNameModule)
      firrtl should include("target = \"autogen_signal_0\"")
    }
  }
  
  behavior of "DebugInfo.annotateRecursive"
  
  it should "annotate deeply nested bundles" in {
    DebugTestHelpers.withDebugMode {
      class Level3 extends Bundle {
        val value = UInt(8.W)
      }
      
      class Level2 extends Bundle {
        val l3 = new Level3
      }
      
      class Level1 extends Bundle {
        val l2 = new Level2
      }
      
      class DeepNestModule extends Module {
        val io = IO(Output(new Level1))
        io.l2.l3.value := 0.U
        
        DebugInfo.annotateRecursive(io, "io")
      }
      
      val firrtl = ChiselStage.emitCHIRRTL(new DeepNestModule)
      
      firrtl should include("target = \"io\"")
      firrtl should include("target = \"io.l2\"")
      firrtl should include("target = \"io.l2.l3\"")
      firrtl should include("target = \"io.l2.l3.value\"")
    }
  }
  
  it should "handle Vec of Bundles" in {
    DebugTestHelpers.withDebugMode {
      class DataBundle extends Bundle {
        val valid = Bool()
        val bits = UInt(8.W)
      }
      
      class VecOfBundlesModule extends Module {
        val io = IO(new Bundle {
          val vec = Output(Vec(2, new DataBundle))
        })
        
        io.vec := VecInit(Seq.fill(2)(0.U.asTypeOf(new DataBundle)))
        
        DebugInfo.annotateRecursive(io.vec, "io.vec")
      }
      
      val firrtl = ChiselStage.emitCHIRRTL(new VecOfBundlesModule)
      
      firrtl should include("typeName = \"Vec\"")
      firrtl should include("target = \"io.vec\"")
    }
  }
  
  behavior of "DebugInfo.isEnabled"
  
  it should "respect CHISEL_DEBUG environment variable" in {
    sys.props.remove("chisel.debug")
    sys.env.get("CHISEL_DEBUG") match {
      case Some("true") => DebugInfo.isEnabled shouldBe true
      case _ => DebugInfo.isEnabled shouldBe false
    }
  }
  
  it should "respect chisel.debug system property" in {
    // Note: CHISEL_DEBUG env var takes precedence if set
    val hasEnvOverride = sys.env.get("CHISEL_DEBUG").exists(_.toLowerCase == "true")
    
    if (!hasEnvOverride) {
      // Only test if env var not set
      sys.props("chisel.debug") = "true"
      DebugInfo.isEnabled shouldBe true
      
      sys.props("chisel.debug") = "false"
      DebugInfo.isEnabled shouldBe false
      
      sys.props.remove("chisel.debug")
    } else {
      // Env var is set, so property can't disable
      sys.props("chisel.debug") = "true"
      DebugInfo.isEnabled shouldBe true
      
      sys.props("chisel.debug") = "false"
      DebugInfo.isEnabled shouldBe true  // Env var overrides!
      
      sys.props.remove("chisel.debug")
      DebugInfo.isEnabled shouldBe true  // Still true from env
    }
  }
  
  behavior of "DebugInfo with different signal types"
  
  it should "annotate Wires" in {
    DebugTestHelpers.withDebugMode {
      class WireModule extends Module {
        val w = Wire(UInt(8.W))
        w := 42.U
        
        DebugInfo.annotate(w, "myWire")
      }
      
      val firrtl = ChiselStage.emitCHIRRTL(new WireModule)
      firrtl should include("target = \"myWire\"")
    }
  }
  
  it should "annotate Regs" in {
    DebugTestHelpers.withDebugMode {
      class RegModule extends Module {
        val r = RegInit(0.U(8.W))
        r := 1.U
        
        DebugInfo.annotate(r, "myReg")
      }
      
      val firrtl = ChiselStage.emitCHIRRTL(new RegModule)
      firrtl should include("target = \"myReg\"")
    }
  }
  
  it should "annotate Mems" in {
    DebugTestHelpers.withDebugMode {
      class MemModule extends Module {
        val mem = Mem(16, UInt(8.W))
        val readData = mem.read(0.U)
        
        DebugInfo.annotate(readData, "memRead")
      }
      
      val firrtl = ChiselStage.emitCHIRRTL(new MemModule)
      firrtl should include("circt_debug_typeinfo")
    }
  }
  
  behavior of "DebugInfo edge cases"
  
  it should "handle empty Bundles" in {
    DebugTestHelpers.withDebugMode {
      class EmptyBundle extends Bundle {}
      
      class EmptyBundleModule extends Module {
        val io = IO(Output(new EmptyBundle))
        DebugInfo.annotate(io, "emptyIO")
      }
      
      val firrtl = ChiselStage.emitCHIRRTL(new EmptyBundleModule)
      firrtl should include("typeName = \"EmptyBundle\"")
    }
  }
  
  it should "handle Clock and Reset types" in {
    DebugTestHelpers.withDebugMode {
      class ClockResetModule extends Module {
        val customClock = IO(Input(Clock()))
        val customReset = IO(Input(Reset()))
        
        DebugInfo.annotate(customClock, "clk")
        DebugInfo.annotate(customReset, "rst")
      }
      
      val firrtl = ChiselStage.emitCHIRRTL(new ClockResetModule)
      firrtl should include("typeName = \"Clock\"")
      firrtl should include("typeName = \"Reset\"")
    }
  }
  
  it should "handle SInt types with width" in {
    DebugTestHelpers.withDebugMode {
      class SIntModule extends Module {
        val io = IO(new Bundle {
          val signed = Output(SInt(16.W))
        })
        io.signed := (-42).S
        
        DebugInfo.annotate(io.signed, "signed_val")
      }
      
      val firrtl = ChiselStage.emitCHIRRTL(new SIntModule)
      firrtl should include("typeName = \"SInt\"")
      firrtl should include("parameters = \"width=16\"")
    }
  }
  
  it should "not crash on signals without source info" in {
    DebugTestHelpers.withDebugMode {
      class NoSourceInfoModule extends Module {
        val w = Wire(UInt(8.W))
        w := 0.U
        
        // Should handle missing source info gracefully
        DebugInfo.annotate(w, "noSource")
      }
      
      noException should be thrownBy {
        ChiselStage.emitCHIRRTL(new NoSourceInfoModule)
      }
    }
  }
}
