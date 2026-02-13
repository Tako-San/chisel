package chiselTests.debug

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MemorySpec extends AnyFlatSpec with Matchers {
  "DebugCapture" should "annotate SyncReadMem" in {
    class TestModule extends Module {
      val ram = SyncReadMem(1024, UInt(32.W))
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule, Array("--capture-debug", "true"))
    chirrtl should include("intrinsic(chisel.debug.memory")
    chirrtl should include("name = \"ram\"")
    chirrtl should include("depth = \"1024\"")
    chirrtl should include("type = \"UInt<32>\"")
  }
}
