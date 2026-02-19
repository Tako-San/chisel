// See LICENSE for license details.

package chisel3.debug

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.regex.Pattern

class AutoInstrumentSpec extends AnyFlatSpec with Matchers {

  "A simple wire" should "result in a circt_dbg_variable intrinsic in emitted CHIRRTL" in {
    class SimpleWireModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("myWire")
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new SimpleWireModule)

    // Verify debug variable intrinsic is present
    chirrtlString should include("intrinsic(circt_dbg_variable")

    // Verify placeholder is not present
    (chirrtlString should not).include("intrinsic(circt_dbg_placeholder)")
  }

  "A register" should "be instrumented with circt_dbg_variable" in {
    class RegisterModule extends RawModule {
      val r = RegInit(0.U(8.W))
      r.suggestName("myReg")
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new RegisterModule)

    // Verify debug variable intrinsic is present
    chirrtlString should include("intrinsic(circt_dbg_variable")

    // Verify placeholder is not present
    (chirrtlString should not).include("intrinsic(circt_dbg_placeholder)")
  }

  "Nested modules" should "be properly instrumented" in {
    class InnerModule extends RawModule {
      val innerWire = Wire(Bool())
      innerWire.suggestName("innerWire")
    }

    class OuterModule extends RawModule {
      val outerWire = Wire(UInt(8.W))
      outerWire.suggestName("outerWire")

      val inner = Module(new InnerModule)
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new OuterModule)

    // Verify debug variable intrinsics are present for both modules
    chirrtlString should include("intrinsic(circt_dbg_variable")

    // Count debug variable intrinsics - should have one for each signal
    val count = chirrtlString.split(Pattern.quote("intrinsic(circt_dbg_variable")).length - 1
    count should be >= 2 // Should have at least 2 debug variables (innerWire and outerWire)

    // Verify placeholder is not present
    (chirrtlString should not).include("intrinsic(circt_dbg_placeholder)")
  }

  "Signals inside a when block" should "be instrumented correctly" in {
    class WhenBlockModule extends RawModule {
      val condition = Wire(Bool())
      condition.suggestName("condition")

      val result = Wire(UInt(8.W))
      result.suggestName("result")

      when(condition) {
        val insideWire = Wire(UInt(8.W))
        insideWire.suggestName("insideWire")
        result := insideWire
      } .otherwise {
        result := 0.U
      }
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new WhenBlockModule)

    // Verify debug variable intrinsic is present
    chirrtlString should include("intrinsic(circt_dbg_variable")

    // Verify placeholder is not present
    (chirrtlString should not).include("intrinsic(circt_dbg_placeholder)")
  }

  "Manually instrumented signals" should "not have duplicate debug instrumentation" in {
    class ManualInstrumentModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("manualWire")

      // Manually instrument using the deprecated debug() API
      w.debug("manual_debug_name")
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new ManualInstrumentModule)

    // Verify debug variable intrinsic is present
    chirrtlString should include("intrinsic(circt_dbg_variable")

    // Verify placeholder is not present
    (chirrtlString should not).include("intrinsic(circt_dbg_placeholder")

    // Count debug variable intrinsics - should have only one, not duplicated
    val count = chirrtlString.split(Pattern.quote("intrinsic(circt_dbg_variable")).length - 1
    count shouldBe 1 // Should not be duplicated
  }

  "Parallel elaborations" should "produce instrumentation for each" in {
    class ParallelModule(n: Int) extends RawModule {
      val wires = VecInit(Seq.fill(n)(Wire(UInt(8.W))))
      for (i <- 0 until n) {
        wires(i).suggestName(s"wire_$i")
      }
    }

    // Elaborate modules in parallel (multiple generators)
    val results = Vector(
      ChiselStage.emitCHIRRTL(new ParallelModule(2)),
      ChiselStage.emitCHIRRTL(new ParallelModule(3)),
      ChiselStage.emitCHIRRTL(new ParallelModule(4))
    )

    // Each elaboration should produce debug instrumentation
    results.foreach { chirrtlString =>
      chirrtlString should include("intrinsic(circt_dbg_variable")
      (chirrtlString should not).include("intrinsic(circt_dbg_placeholder")
    }

    // Verify each result has the expected number of debug variables
    val counts = results.map { chirrtlString =>
      chirrtlString.split(Pattern.quote("intrinsic(circt_dbg_variable")).length - 1
    }
    counts shouldBe Vector(2, 3, 4)
  }
}