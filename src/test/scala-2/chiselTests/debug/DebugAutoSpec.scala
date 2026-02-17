package chiselTests.debug

import chisel3._
import chisel3.experimental.debug.DebugIntrinsics
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DebugAutoSpec extends AnyFlatSpec with Matchers {
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

    chirrtl should include(s"intrinsic(${DebugIntrinsics.PortInfo}")
    chirrtl should include(s"${DebugIntrinsics.ParamKeys.Name} = \"io.in\"")
    chirrtl should include(s"intrinsic(${DebugIntrinsics.SourceInfo}")
    chirrtl should include(s"${DebugIntrinsics.ParamKeys.FieldName} = \"reg\"")
  }

  it should "NOT emit debug intrinsics when flag is disabled" in {
    class Simple extends Module {
      val io = IO(new Bundle { val in = Input(UInt(8.W)) })
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new Simple) // default is false
    (chirrtl should not).include(s"intrinsic(${DebugIntrinsics.PortInfo}")
    (chirrtl should not).include(s"intrinsic(${DebugIntrinsics.SourceInfo}")
  }
}
