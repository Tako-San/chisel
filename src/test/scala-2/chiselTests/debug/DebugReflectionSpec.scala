package chiselTests.debug

import chisel3._
import chisel3.experimental.debug.DebugIntrinsics
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MySimpleBundle(val dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val valid = Bool()
}

class ReflectionModule extends Module {
  val io = IO(new Bundle {
    val in = Input(new MySimpleBundle(16))
    val out = Output(new MySimpleBundle(32))
  })
}

class DebugReflectionSpec extends AnyFlatSpec with Matchers {
  it should "include constructor params in intrinsic" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new ReflectionModule, Array("--capture-debug", "true"))

    chirrtl should include(s"intrinsic(${DebugIntrinsics.PortInfo}")
    chirrtl should include("scalaClass")
    chirrtl should include("constructorParams")
    chirrtl should include("name = \"io.in\"")
    chirrtl should include("MySimpleBundle")
    // The constructorParams attribute should contain parameter info
    chirrtl should include("dataWidth")
  }
}
