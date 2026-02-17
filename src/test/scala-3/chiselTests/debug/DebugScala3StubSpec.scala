package chiselTests.debug
import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DebugScala3StubSpec extends AnyFlatSpec with Matchers {
  "DebugCapture in Scala 3".should("emit warning but not crash").in {
    class MyMod extends Module {
      val io = IO(new Bundle { val in = Input(UInt(8.W)) })
    }
    noException.should(be).thrownBy {
      ChiselStage.emitCHIRRTL(new MyMod, Array("--capture-debug", "true"))
    }
  }
}
