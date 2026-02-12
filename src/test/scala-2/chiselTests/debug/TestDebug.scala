package chiselTests.debug

import chisel3._
import chisel3.experimental._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import chisel3.experimental.debug._

class TestDebug extends AnyFlatSpec with Matchers {

  it should "capture ports and registers" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val reg = RegInit(0.U(8.W))
      chisel3.experimental.debug.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)
    println("=== CHIRRTL OUTPUT ===")
    println(chirrtl)
    println("=== END CHIRRTL ===")

    chirrtl should include("chisel.debug.port_info")
    chirrtl should include("\"direction\"")
    chirrtl should include("chisel.debug.source_info") // for reg
  }
}
