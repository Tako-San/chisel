package chiselTests.debug

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.matching.Regex

class CircuitTraversalSpec extends AnyFlatSpec with Matchers {

  it should "capture all modules in hierarchy" in {
    class SubModule extends Module {
      val io = IO(new Bundle { val x = Input(UInt(8.W)) })
    }

    class TopModule extends Module {
      val sub1 = Module(new SubModule)
      val sub2 = Module(new SubModule)
      chisel3.experimental.debug.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TopModule)

    // Should have 3 module_info intrinsics (TopModule + 2x SubModule)
    chirrtl.split("chisel.debug.port_info").length should be >= 3
  }

  it should "annotate all ports and registers" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val reg = RegInit(0.U(8.W))
      chisel3.experimental.debug.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    chirrtl should include("chisel.debug.port_info")
    chirrtl should include("\"direction\"")
    chirrtl should include("chisel.debug.source_info") // for reg
  }

  it should "annotate memory with Bundle elements" in {
    class MemBundle extends Bundle {
      val data = UInt(32.W)
      val valid = Bool()
    }

    class MemModule extends Module {
      val mem = SyncReadMem(16, new MemBundle)
      val addr = IO(Input(UInt(4.W)))
      val wrData = IO(Input(new MemBundle))
      mem.write(addr, wrData)
      chisel3.experimental.debug.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new MemModule)

    chirrtl should include("chisel.debug.memory")
    chirrtl should include("\"inner_type\" = \"MemBundle\"")
    chirrtl should include("chisel.debug.memory_field")
    chirrtl should include("\"field\" = \"data\"")
  }

  it should "not duplicate annotations for same signal" in {
    class TestModule extends Module {
      val wire = Wire(UInt(8.W))
      chisel3.experimental.debug.captureCircuit(this) // First call
      chisel3.experimental.debug.captureCircuit(this) // Second call
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    // Count occurrences of intrinsic for this wire
    val intrinsicCount = "chisel\\.debug\\.source_info.*wire".r
      .findAllMatchIn(chirrtl)
      .size
    intrinsicCount should be(1)
  }
}
