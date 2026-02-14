package chiselTests.debug

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DeepCaptureTest extends AnyFlatSpec with Matchers {

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
    chirrtl should include("intrinsic(chisel.debug.source_info")
    chirrtl should include("field_name = \"internalWire\"")
  }
}
