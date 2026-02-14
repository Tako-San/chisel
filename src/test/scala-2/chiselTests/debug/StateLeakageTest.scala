package chiselTests.debug

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StateLeakageTest extends AnyFlatSpec with Matchers {

  "Global state" should "not leak between compilations" in {
    class Module1 extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val reg = RegInit(0.U(8.W))
      reg := io.in
      io.out := reg
    }

    class Module2 extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val reg2 = RegInit(0.U(8.W))
      reg2 := io.in
      io.out := reg2
    }

    val chirrtl1 = ChiselStage.emitCHIRRTL(
      new Module1,
      Array("--capture-debug", "true")
    )
    chirrtl1 should include("intrinsic(chisel.debug.port_info")

    val chirrtl2 = ChiselStage.emitCHIRRTL(
      new Module2,
      Array.empty[String]
    )
    (chirrtl2 should not).include("intrinsic(chisel.debug.port_info")
  }

  "Global state" should "not leak from enabled to disabled compilation" in {
    class Module3 extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val x = RegInit(0.U(8.W))
      x := io.in
      io.out := x
    }

    class Module4 extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val y = RegInit(0.U(8.W))
      y := io.in
      io.out := y
    }

    for (i <- 1 to 3) {
      val withDebug = ChiselStage.emitCHIRRTL(
        new Module3,
        Array("--capture-debug", "true")
      )
      withDebug should include("intrinsic(chisel.debug.port_info")

      val withoutDebug = ChiselStage.emitCHIRRTL(
        new Module4,
        Array.empty[String]
      )
      (withoutDebug should not).include("intrinsic(chisel.debug.port_info")
    }
  }
}
