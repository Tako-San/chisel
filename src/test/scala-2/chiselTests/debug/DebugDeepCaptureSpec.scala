package chiselTests.debug

import chisel3._
import chisel3.experimental.debug.DebugIntrinsics
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DebugDeepCaptureSpec extends AnyFlatSpec with Matchers {

  "DebugCapture" should "capture local wires inside methods" in {
    class TestModule extends Module {
      val io = IO(new Bundle { val out = Output(UInt(8.W)) })

      // Method creates wires that are NOT class fields
      def createLogic(): UInt = {
        val internalWire = Wire(UInt(8.W)) // Local variable
        internalWire := 42.U
        internalWire
      }

      io.out := createLogic()
    }

    // Compile with debug flag
    val chirrtl = ChiselStage.emitCHIRRTL(
      new TestModule,
      Array("--capture-debug", "true")
    )

    // Verification
    // Original version (Reflection) will NOT find "internalWire" as it is not a class field
    // New version (IR Traversal) will find it, as it is a DefWire in the command list
    chirrtl should include(s"intrinsic(${DebugIntrinsics.SourceInfo}")
    chirrtl should include("field_name = \"internalWire\"")
  }

  it should "capture logic created in methods but NOT duplicate if stored in val" in {
    class MethodModule extends Module {
      val io = IO(new Bundle { val out = Output(UInt(8.W)) })

      def makeWire(): UInt = {
        val w = Wire(UInt(8.W))
        w := 5.U
        w
      }

      val myVal = makeWire()
      io.out := myVal
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new MethodModule, Array("--capture-debug", "true"))
    chirrtl.split(s"intrinsic\\(${DebugIntrinsics.SourceInfo}").length should be(2)
  }
}
