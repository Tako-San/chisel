// See LICENSE for license details.

package chisel3.debug

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DebugConverterSpec extends AnyFlatSpec with Matchers {

  "Debug Converter" should "transform placeholder to full intrinsic" in {

    // Define a simple test module with an instrumented wire
    class TestMod extends RawModule {
      val sig = Wire(Bool()).suggestName("mySig")
      sig.instrumentDebug()
    }

    // Use ChiselStage to emit CHIRRTL (this runs the full pipeline)
    val chirrtlString = ChiselStage.emitCHIRRTL(new TestMod)

    // The placeholder should be gone
    (chirrtlString should not).include("intrinsic(circt_dbg_placeholder)")

    // The full debug variable intrinsic should be present
    chirrtlString should include("intrinsic(circt_dbg_variable")

    // The path should be in the CHIRRTL
    chirrtlString should include("path =")

    // The type should be in the CHIRRTL
    chirrtlString should include("type =")
  }
}
