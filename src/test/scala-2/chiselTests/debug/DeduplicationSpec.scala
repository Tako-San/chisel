package chiselTests.debug

import chisel3._
import chisel3.experimental.debug.DebugCapture
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DeduplicationSpec extends AnyFlatSpec with Matchers {

  "DebugCapture" should "emit alias_info for internal alias wires" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val out = Output(UInt(8.W))
      })

      val a_original = Wire(UInt(8.W))
      val b_alias = a_original

      a_original := 42.U
      io.out := b_alias

      DebugCapture.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    chirrtl should include("intrinsic(chisel.debug.source_info")
    chirrtl should include("field_name = \"a_original\"")

    chirrtl should include("intrinsic(chisel.debug.alias_info")
    chirrtl should include("name = \"b_alias\"")
    chirrtl should include("target = \"a_original\"")

    chirrtl should include("type = \"UInt<8>\"")
  }

  "DebugCapture" should "emit alias_info for port aliases" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })

      val myIn = io.in
      io.out := myIn

      DebugCapture.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    chirrtl should include("intrinsic(chisel.debug.port_info")
    chirrtl should include("name = \"io.in\"")
    chirrtl should include("direction = \"INPUT\"")

    chirrtl should include("intrinsic(chisel.debug.alias_info")
    chirrtl should include("name = \"myIn\"")
    chirrtl should include("target = \"io.in\"")

    chirrtl should include("type = \"UInt<8>\"")
  }
}
