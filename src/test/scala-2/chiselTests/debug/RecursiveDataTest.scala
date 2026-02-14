package chiselTests.debug

import chisel3._
import chisel3.experimental.debug.DebugCapture
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RecursiveDataTest extends AnyFlatSpec with Matchers {

  "DebugCapture" should "recursively annotate nested bundle ports" in {
    class InnerBundle extends Bundle {
      val x = Input(UInt(8.W))
      val y = Input(UInt(4.W))
    }

    class OuterBundle extends Bundle {
      val a = Input(UInt(16.W))
      val b = Input(new InnerBundle)
      val c = Output(Bool())
    }

    class TestModule extends Module {
      val io = IO(new OuterBundle)
      DebugCapture.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    chirrtl should include("chisel.debug.port_info")
    chirrtl should include("name = \"io.a\"")
    chirrtl should include("name = \"io.b.x\"")
    chirrtl should include("name = \"io.b.y\"")
    chirrtl should include("name = \"io.c\"")

    chirrtl should include("type = \"UInt<16>\"")
    chirrtl should include("type = \"UInt<8>\"")
    chirrtl should include("type = \"UInt<4>\"")
    chirrtl should include("type = \"Bool\"")

    chirrtl should include("direction = \"INPUT\"")
    chirrtl should include("direction = \"OUTPUT\"")
  }

  "DebugCapture" should "recursively annotate vector ports" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val vecIn = Input(Vec(4, UInt(8.W)))
        val vecOut = Output(Vec(2, Bool()))
      })
      DebugCapture.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    chirrtl should include("chisel.debug.port_info")
    chirrtl should include("name = \"io.vecIn[0]\"")
    chirrtl should include("name = \"io.vecIn[1]\"")
    chirrtl should include("name = \"io.vecOut[0]\"")
    chirrtl should include("name = \"io.vecOut[1]\"")

    chirrtl should include("type = \"UInt<8>\"")
    chirrtl should include("type = \"Bool\"")
  }

  "DebugCapture" should "recursively annotate nested bundle internal data" in {
    class InnerBundle extends Bundle {
      val x = UInt(8.W)
      val y = UInt(4.W)
    }

    class TestModule extends Module {
      val bundleWire = Wire(new InnerBundle)
      bundleWire.x := 1.U
      bundleWire.y := 2.U
      DebugCapture.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    chirrtl should include("chisel.debug.source_info")
    chirrtl should include("field_name = \"bundleWire.x\"")
    chirrtl should include("field_name = \"bundleWire.y\"")
  }

  "DebugCapture" should "recursively annotate vector internal data" in {
    class TestModule extends Module {
      val vecWire = Wire(Vec(3, UInt(8.W)))
      vecWire(0) := 0.U
      vecWire(1) := 1.U
      vecWire(2) := 2.U
      DebugCapture.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    chirrtl should include("chisel.debug.source_info")
    chirrtl should include("field_name = \"vecWire[0]\"")
    chirrtl should include("field_name = \"vecWire[1]\"")
    chirrtl should include("field_name = \"vecWire[2]\"")
  }

  "DebugCapture" should "recursively annotate deeply nested structures" in {
    class Inner extends Bundle {
      val a = UInt(4.W)
    }

    class Middle extends Bundle {
      val inner = new Inner
      val vecInner = Vec(2, new Inner)
    }

    class Outer extends Bundle {
      val middle = new Middle
      val vecMiddle = Vec(2, new Middle)
    }

    class TestModule extends Module {
      val io = IO(new Outer)
      DebugCapture.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    chirrtl should include("name = \"io.middle.inner.a\"")
    chirrtl should include("name = \"io.middle.vecInner[0].a\"")
    chirrtl should include("name = \"io.vecMiddle[0].inner.a\"")
    chirrtl should include("name = \"io.vecMiddle[0].vecInner[0].a\"")
  }

  "DebugCapture" should "not explode with large vectors" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val bigVec = Input(Vec(200, UInt(1.W)))
      })
      DebugCapture.captureCircuit(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    val (has99, has100) = (chirrtl.contains("name = \"io.bigVec[99]\""), chirrtl.contains("name = \"io.bigVec[100]\""))
    has99 shouldBe true
    has100 shouldBe false
  }
}
