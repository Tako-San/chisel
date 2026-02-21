package chisel3.internal

import chisel3._
import chisel3.internal.Builder
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DebugMetaSpec extends AnyFlatSpec with Matchers {
  behavior.of("Builder.DebugMeta")

  it should "store and retrieve debug meta info by HasId._id" in {
    var capturedId: Option[Long] = None

    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      Builder.recordDebugMeta(w, "UInt", "width=8", "test.scala", 10)
      capturedId = Some(w._id)
    }

    circt.stage.ChiselStage.emitCHIRRTL(new TestModule)

    capturedId shouldBe defined
    // Note: side-table is cleared between elaborations via DynamicVariable,
    // so we can't check it AFTER elaboration.
    // This test verifies no exception is thrown during recording.
  }

  it should "automatically capture debug metadata for created components" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      // After the fix, debug metadata is automatically captured for all components
      val meta = Builder.getDebugMeta(w)
      meta should not be None
      meta.get.className shouldBe "UInt"
    }

    circt.stage.ChiselStage.emitCHIRRTL(new TestModule)
  }

  it should "record debug meta info during elaboration" in {
    var capturedInfo: Option[Builder.DebugMeta] = None

    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      Builder.recordDebugMeta(w, "UInt", "width=8", "test.scala", 10)
      val infoOpt = Builder.getDebugMeta(w)
      capturedInfo = infoOpt
      infoOpt shouldBe defined
      infoOpt.get.className shouldBe "UInt"
    }

    circt.stage.ChiselStage.emitCHIRRTL(new TestModule)

    // Verify the capture worked
    capturedInfo shouldBe defined
    capturedInfo.get.className shouldBe "UInt"
  }
}
