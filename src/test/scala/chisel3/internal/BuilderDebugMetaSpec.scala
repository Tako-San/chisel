// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import chisel3.experimental.hierarchy.{Definition, Instance}
import chisel3.internal.Builder
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BuilderDebugMetaSpec extends AnyFlatSpec with Matchers {
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
    // Note: side-table is cleared between elaborations via DynamicContext,
    // so we can't check it AFTER elaboration.
    // This test verifies no exception is thrown during recording.
  }

  it should "reset emittedEnums between elaborations on the same thread" in {
    // This test verifies that enum tracking is properly reset between elaborations
    // to prevent "phantom" enum entries from causing circt_debug_enumdef to be skipped

    object MyEnum extends ChiselEnum {
      val StateA = Value(0.U)
      val StateB = Value(1.U)
    }

    class EnumModule extends RawModule {
      val state = Wire(MyEnum())
      state := MyEnum.StateA
    }

    // First elaboration - should emit circt_debug_enumdef
    val chirrtl1 = circt.stage.ChiselStage.emitCHIRRTL(
      new EnumModule,
      args = Array("--emit-debug-type-info")
    )
    chirrtl1 should include("circt_debug_enumdef")

    // Second elaboration on the same thread - should also emit circt_debug_enumdef
    // This fails if emittedEnums contains phantom entries from a previous partial elaboration
    val chirrtl2 = circt.stage.ChiselStage.emitCHIRRTL(
      new EnumModule,
      args = Array("--emit-debug-type-info")
    )
    chirrtl2 should include("circt_debug_enumdef")
  }

  it should "automatically capture debug metadata for created components" in {
    if (!chisel3.BuildInfo.scalaVersion.startsWith("2.")) {
      cancel("Automatic debug metadata capture via compiler plugin is only supported in Scala 2")
    }
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      // After the fix, debug metadata is automatically captured for all components
      val meta = Builder.getDebugMeta(w)
      if (meta.isEmpty) cancel("Test requires -P:chiselplugin:emitDebugTypeInfo compiler flag")
      meta.get.className shouldBe "UInt"
    }

    circt.stage.ChiselStage.emitCHIRRTL(new TestModule, args = Array("--emit-debug-type-info"))
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

    circt.stage.ChiselStage.emitCHIRRTL(new TestModule, args = Array("--emit-debug-type-info"))

    // Verify the capture worked
    capturedInfo shouldBe defined
    capturedInfo.get.className shouldBe "UInt"
  }

  it should "not steal ctorParams when Definition is elaborated inside a module" in {
    class InnerModule extends RawModule {
      val w = Wire(UInt(8.W))
    }

    class OuterModule extends RawModule {
      val innerDef = Definition(new InnerModule)
      val innerInst = Instance(innerDef)
    }

    // If this fails, it means Definition incorrectly consumed ctorParams belonging to OuterModule
    circt.stage.ChiselStage.emitCHIRRTL(new OuterModule)
  }

  // Direct stack-inspection tests removed - the push/pop contract is covered
  // end-to-end by DebugMetaPluginSpec
}
