// See LICENSE for license details.

package chisel3.debug

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.regex.Pattern

class DebugIntegrationSpec extends AnyFlatSpec with Matchers {

  "Full Pipeline" should "emit correct debug variable intrinsic with all metadata" in {

    // Define a module with multiple instrumented signals
    class InnerModule extends RawModule {
      val sig = Wire(Bool()).suggestName("innerSig")
      sig.instrumentDebug()

      val vec = Wire(Vec(4, UInt(8.W))).suggestName("myVec")
      vec.instrumentDebug()
    }

    class TopModule extends RawModule {
      val inner = Module(new InnerModule)
    }

    // Use ChiselStage to emit the full CHIRRTL
    val chirrtlString = ChiselStage.emitCHIRRTL(new TopModule)

    // Verify the placeholder intrinsic is not in the output
    (chirrtlString should not).include("intrinsic(circt_dbg_placeholder)")

    // Verify the debug variable intrinsic is present
    chirrtlString should include("intrinsic(circt_dbg_variable")

    // Verify hierarchical path is present
    chirrtlString should include("TopModule.inner")

    // Count debug variable intrinsics in CHIRRTL output
    val count = chirrtlString.split(Pattern.quote("intrinsic(circt_dbg_variable")).length - 1
    count shouldBe 2

    // Registry will be cleared by afterEach
  }

  "Full Pipeline" should "emit correct type information for complex data types" in {

    class MyBundle extends Bundle {
      val a = UInt(8.W)
      val b = SInt(16.W)
      val c = Vec(4, Bool())
    }

    // Test with complex data types
    class TopModule extends RawModule {
      val myBundle = Wire(new MyBundle).suggestName("myBundle")
      myBundle.instrumentDebug()
    }

    // Emit CHIRRTL
    val chirrtlString = ChiselStage.emitCHIRRTL(new TopModule)

    // Verify the debug variable intrinsic is present
    chirrtlString should include("intrinsic(circt_dbg_variable")

    // Verify type information is present in the CHIRRTL output
    chirrtlString should include("type = \"MyBundle\"")

    // Registry will be cleared by afterEach
  }

  "Full Pipeline" should "handle parameter extraction for modules with parameters" in {

    class ParamModule(val width: Int, val depth: Int) extends RawModule {
      val data = Wire(UInt(width.W)).suggestName("paramData")
      data.instrumentDebug()
    }

    // Test with parameterized modules
    class TopModule extends RawModule {
      val inner = Module(new ParamModule(width = 8, depth = 16))
    }

    // Emit CHIRRTL
    val chirrtlString = ChiselStage.emitCHIRRTL(new TopModule)

    // Verify the debug variable intrinsic is present
    chirrtlString should include("intrinsic(circt_dbg_variable")

    // Verify the correct hierarchical path is present
    chirrtlString should include("TopModule.inner.paramData")

    // Verify the type field contains the width information
    chirrtlString should include("type = \"UInt<8>\"")

    chirrtlString should include("params =")
    // These assertions verify JSON content within the CHIRRTL output.
    // The escaped strings ("\\\"") are the correct escape for literal double quotes in Scala.
    chirrtlString should include("\\\"width\\\"")
    chirrtlString should include("\\\"depth\\\"")
  }

  "Full Pipeline" should "handle multiple signals in same module" in {

    // Test with multiple signals
    class TopModule extends RawModule {
      val sig1 = Wire(Bool()).suggestName("sig1")
      sig1.instrumentDebug()

      val sig2 = Wire(UInt(8.W)).suggestName("sig2")
      sig2.instrumentDebug()

      val sig3 = Wire(SInt(16.W)).suggestName("sig3")
      sig3.instrumentDebug()
    }

    // Emit CHIRRTL
    val chirrtlString = ChiselStage.emitCHIRRTL(new TopModule)

    // Verify all three signals have debug intrinsics
    val count = chirrtlString.split(Pattern.quote("intrinsic(circt_dbg_variable")).length - 1
    count shouldBe 3

    // Registry will be cleared by afterEach
  }
}
