package chiselTests.debug

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AutoDebugTest extends AnyFlatSpec with Matchers {
  "Full pipeline" should "capture debug info automatically without manual calls" in {
    class MyDesign extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val reg = RegInit(0.U(8.W))
      reg := io.in
      io.out := reg
    }

    val chirrtl = ChiselStage.emitCHIRRTL(
      new MyDesign,
      Array("--capture-debug", "true")
    )

    chirrtl should include("intrinsic(chisel.debug.port_info")
    chirrtl should include("name = \"io.in\"")
    chirrtl should include("intrinsic(chisel.debug.source_info")
    chirrtl should include("name = \"reg\"")
  }
}
