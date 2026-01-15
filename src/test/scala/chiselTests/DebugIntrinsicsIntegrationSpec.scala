// SPDX-License-Identifier: Apache-2.0

package chiselTests

import chisel3._
import chisel3.util._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Integration tests for ComponentDebugIntrinsics plugin E2E workflow.
  * 
  * Tests realistic Chisel modules with NO manual DebugInfo calls,
  * verifying automatic instrumentation works end-to-end.
  */
class DebugIntrinsicsIntegrationSpec extends AnyFlatSpec with Matchers {
  
  behavior of "Plugin E2E Workflow"
  
  it should "instrument realistic CPU module automatically" in {
    // Enable plugin and debug mode
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    // Realistic example: simple CPU state machine
    object State extends ChiselEnum {
      val FETCH, DECODE, EXECUTE, WRITEBACK = Value
    }
    
    class MemoryInterface extends Bundle {
      val addr = Output(UInt(32.W))
      val data = Input(UInt(32.W))
      val valid = Output(Bool())
    }
    
    class SimpleCPU extends Module {
      val io = IO(new MemoryInterface)
      
      val pc = RegInit(0.U(32.W))
      val state = RegInit(State.FETCH)
      val registers = Mem(32, UInt(32.W))
      val tempData = Wire(UInt(32.W))
      val instrReg = RegInit(0.U(32.W))
      
      // Default connections
      tempData := io.data
      io.addr := pc
      io.valid := false.B
      
      // State machine
      switch (state) {
        is (State.FETCH) {
          io.valid := true.B
          instrReg := io.data
          state := State.DECODE
        }
        is (State.DECODE) {
          state := State.EXECUTE
        }
        is (State.EXECUTE) {
          state := State.WRITEBACK
        }
        is (State.WRITEBACK) {
          pc := pc + 4.U
          state := State.FETCH
        }
      }
      
      // NO DebugInfo.annotate() calls anywhere!
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new SimpleCPU)
    
    // Verify ALL signals instrumented automatically
    firrtl should include regex "target = \"io\""
    firrtl should include regex "target = \"pc\""
    firrtl should include regex "target = \"state\""
    firrtl should include regex "target = \"registers\""
    firrtl should include regex "target = \"tempData\""
    firrtl should include regex "target = \"instrReg\""
    
    // Verify binding types correct
    firrtl should include regex "target = \"pc\".*binding = \"Reg\""
    firrtl should include regex "target = \"state\".*binding = \"Reg\""
    firrtl should include regex "target = \"tempData\".*binding = \"Wire\""
    firrtl should include regex "target = \"registers\".*binding = \"Mem\""
    firrtl should include regex "target = \"instrReg\".*binding = \"Reg\""
    
    // Verify enum metadata
    firrtl should include regex "typeName = \"State\""
    firrtl should include("enumDef")
    firrtl should include("FETCH")
    firrtl should include("DECODE")
    
    // Verify Bundle metadata
    firrtl should include regex "typeName = \"MemoryInterface\""
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  it should "work with nested Bundles" in {
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    class InnerBundle extends Bundle {
      val x = UInt(8.W)
      val y = UInt(8.W)
    }
    
    class OuterBundle extends Bundle {
      val inner = new InnerBundle
      val flag = Bool()
    }
    
    class TestModule extends Module {
      val io = IO(new OuterBundle)
      val state = RegInit(0.U.asTypeOf(new OuterBundle))
      
      // Connect
      state.inner.x := io.inner.x
      state.inner.y := io.inner.y
      state.flag := io.flag
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    
    // Verify hierarchical names (plugin should handle Bundle traversal)
    firrtl should include regex "target = \"io\""
    firrtl should include regex "target = \"state\""
    
    // Verify type information
    firrtl should include regex "typeName = \"OuterBundle\""
    firrtl should include regex "typeName = \"InnerBundle\""
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  it should "handle Vec types" in {
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    class TestModule extends Module {
      val io = IO(new Bundle {
        val inputs = Input(Vec(4, UInt(8.W)))
        val outputs = Output(Vec(4, UInt(8.W)))
      })
      
      val pipeline = Reg(Vec(4, UInt(8.W)))
      
      // Pipeline stages
      pipeline(0) := io.inputs(0)
      for (i <- 1 until 4) {
        pipeline(i) := pipeline(i-1)
      }
      
      io.outputs := pipeline
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    
    // Verify Vec instrumented
    firrtl should include regex "target = \"pipeline\""
    firrtl should include regex "typeName = \"Vec\""
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  it should "have zero overhead when disabled" in {
    // Disable plugin (default state)
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
    
    class TestModule extends Module {
      val io = IO(new Bundle { val in = Input(UInt(8.W)) })
      val state = RegInit(0.U(8.W))
      state := io.in
    }
    
    val firrtlWithoutPlugin = ChiselStage.emitCHIRRTL(new TestModule)
    
    // Should be identical to manual code (no overhead)
    firrtlWithoutPlugin should not include "circt_debug_typeinfo"
    firrtlWithoutPlugin should not include "Probe<"
    firrtlWithoutPlugin should not include "define("
  }
  
  it should "work with multiple modules" in {
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    class SubModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val reg = RegInit(0.U(8.W))
      reg := io.in
      io.out := reg
    }
    
    class TopModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      
      val sub1 = Module(new SubModule)
      val sub2 = Module(new SubModule)
      
      sub1.io.in := io.in
      sub2.io.in := sub1.io.out
      io.out := sub2.io.out
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TopModule)
    
    // Verify all modules instrumented
    firrtl should include regex "target = \"io\""  // TopModule IO
    
    // Count intrinsics (should have multiple)
    val intrinsicCount = "circt_debug_typeinfo".r.findAllMatchIn(firrtl).length
    intrinsicCount should be >= 3  // TopModule + 2 SubModules
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  it should "preserve existing manual annotations" in {
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    
    class TestModule extends Module {
      val io = IO(new Bundle { val in = Input(UInt(8.W)) })
      val autoReg = RegInit(0.U(8.W))  // Auto-instrumented by plugin
      
      val manualWire = Wire(UInt(8.W))
      // Manual annotation should take precedence
      chisel3.util.circt.DebugInfo.annotate(manualWire, "custom_name")
      
      manualWire := io.in
      autoReg := manualWire
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new TestModule)
    
    // Both should be present
    firrtl should include regex "target = \"autoReg\""  // Plugin
    firrtl should include regex "target = \"custom_name\""  // Manual
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
  
  behavior of "Plugin Performance"
  
  it should "have minimal compile-time overhead" in {
    // Warm-up JVM
    (1 to 5).foreach { _ =>
      class Warmup extends Module {
        val io = IO(new Bundle { val x = Input(UInt(8.W)) })
        val r = RegInit(0.U(8.W))
        r := io.x
      }
      ChiselStage.emitCHIRRTL(new Warmup)
    }
    
    // Benchmark: Plugin disabled
    sys.props.remove("chisel.plugin.debugintrinsics")
    val startBaseline = System.nanoTime()
    (1 to 20).foreach { _ =>
      class TestModule extends Module {
        val io = IO(new Bundle { val in = Input(UInt(8.W)) })
        val state = RegInit(0.U(8.W))
        state := io.in
      }
      ChiselStage.emitCHIRRTL(new TestModule)
    }
    val baselineTime = (System.nanoTime() - startBaseline) / 1e6  // ms
    
    // Benchmark: Plugin enabled
    sys.props("chisel.plugin.debugintrinsics") = "true"
    sys.props("chisel.debug") = "true"
    val startWithPlugin = System.nanoTime()
    (1 to 20).foreach { _ =>
      class TestModule extends Module {
        val io = IO(new Bundle { val in = Input(UInt(8.W)) })
        val state = RegInit(0.U(8.W))
        state := io.in
      }
      ChiselStage.emitCHIRRTL(new TestModule)
    }
    val pluginTime = (System.nanoTime() - startWithPlugin) / 1e6  // ms
    
    val overhead = ((pluginTime - baselineTime) / baselineTime) * 100
    
    info(s"Baseline: ${baselineTime}ms")
    info(s"With Plugin: ${pluginTime}ms")
    info(s"Overhead: ${overhead}%")
    
    // Should be < 20% overhead (reasonable for AST transformation)
    overhead should be < 20.0
    
    sys.props.remove("chisel.plugin.debugintrinsics")
    sys.props.remove("chisel.debug")
  }
}
