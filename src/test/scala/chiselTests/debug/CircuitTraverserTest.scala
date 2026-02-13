package chiselTests.debug

import chisel3._
import chisel3.experimental.debug.DebugCapture
import circt.stage.ChiselStage
import logger.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CircuitTraverserTest extends AnyFlatSpec with Matchers with LazyLogging {
  "DebugCapture" should "generate port intrinsics" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
      })
      DebugCapture.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)
    logger.info(chirrtl)

    chirrtl should include("chisel.debug.port_info")
    chirrtl should include("direction = \"INPUT\"")
  }

  "DebugCapture" should "annotate registers" in {
    class TestModule extends Module {
      val reg = RegInit(0.U(8.W))
      DebugCapture.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)
    chirrtl should include("chisel.debug.source_info")
    chirrtl should include("field_name = \"reg\"")
  }

  "DebugCapture" should "annotate wires" in {
    class TestModule extends Module {
      val wire = Wire(UInt(8.W))
      wire := 0.U
      DebugCapture.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)
    chirrtl should include("chisel.debug.source_info")
    chirrtl should include("field_name = \"wire\"")
  }

  "DebugCapture" should "annotate bundle wire elements" in {
    class MyBundle extends Bundle {
      val a = UInt(8.W)
      val b = UInt(4.W)
    }
    class TestModule extends Module {
      val bundleWire = Wire(new MyBundle)
      bundleWire.a := 1.U
      bundleWire.b := 2.U
      DebugCapture.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)
    chirrtl should include("chisel.debug.source_info")
    chirrtl should include("field_name = \"bundleWire.a\"")
    chirrtl should include("field_name = \"bundleWire.b\"")
  }
}
