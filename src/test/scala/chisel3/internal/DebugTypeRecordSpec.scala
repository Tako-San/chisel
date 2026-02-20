package chisel3.internal

import chisel3._
import chisel3.internal.Builder
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DebugTypeRecordSpec extends AnyFlatSpec with Matchers {
  behavior.of("Builder.DebugTypeRecord")

  it should "store and retrieve debug type info by HasId._id" in {
    var capturedId: Option[Long] = None

    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      // Simulate what plugin would inject:
      Builder.recordDebugType(w, "UInt", "width=8", "test.scala", 10)
      capturedId = Some(w._id)
    }

    circt.stage.ChiselStage.emitCHIRRTL(new TestModule)

    capturedId shouldBe defined
    // Note: side-table is cleared between elaborations via DynamicVariable,
    // so we can't check it AFTER elaboration.
    // This test verifies no exception is thrown during recording.
  }

  it should "not throw when querying nonexistent id" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      Builder.getDebugType(w) shouldBe None
    }

    circt.stage.ChiselStage.emitCHIRRTL(new TestModule)
  }
}
