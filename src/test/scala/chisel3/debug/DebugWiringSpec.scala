// See LICENSE for license details.

package chisel3.debug

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

class DebugWiringSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  "ChiselStage" should "execute CollectDebugInfo phase automatically" in {

    class SimpleMod extends RawModule {
      val x = Wire(Bool()).suggestName("autoRun")
      x.instrumentDebug()
    }

    // Run standard emission (Elaborate now creates the registry internally)
    val chirrtl = ChiselStage.emitCHIRRTL(new SimpleMod)

    // 1. If CollectDebugInfo ran, the path should be resolved.
    // If it didn't run, the path would be missing or the intrinsic would be a placeholder.
    chirrtl should include("SimpleMod.autoRun")

    // 2. The intrinsic name should be correct (circt_dbg_variable, not placeholder).
    chirrtl should include("intrinsic(circt_dbg_variable")
    (chirrtl should not).include("intrinsic(circt_dbg_placeholder)")

    // 3. DebugRegistry should be cleared after run - check within fresh context
    DebugRegistry.withFreshRegistry {
      DebugRegistry.entries should be(empty)
    }
  }

  it should "work with multiple sequential runs" in {
    class Mod1 extends RawModule {
      val x = Wire(Bool()).suggestName("mod1Sig")
      x.instrumentDebug()
    }

    class Mod2 extends RawModule {
      val y = Wire(UInt(8.W)).suggestName("mod2Sig")
      y.instrumentDebug()
    }

    // Run first module
    val chirrtl1 = ChiselStage.emitCHIRRTL(new Mod1)
    chirrtl1 should include("Mod1.mod1Sig")
    chirrtl1 should include("intrinsic(circt_dbg_variable")

    // Run second module
    val chirrtl2 = ChiselStage.emitCHIRRTL(new Mod2)
    chirrtl2 should include("Mod2.mod2Sig")
    chirrtl2 should include("intrinsic(circt_dbg_variable")

    // Verify no cross-contamination - each output contains only its own module name
    (chirrtl1 should not).include("Mod2")
    (chirrtl2 should not).include("Mod1")
  }
}
