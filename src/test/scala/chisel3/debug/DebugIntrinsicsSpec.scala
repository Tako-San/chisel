// SPDX-License-Identifier: Apache-2.0
package chisel3.debug

import chisel3._
import chisel3.util.MixedVec
import chisel3.experimental.Analog
import circt.stage.ChiselStage
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

// ─── Circuits used by this spec ───────────────────────────────────────────────

private class DebugSimpleModule extends Module {
  val in = IO(Input(UInt(8.W)))
  val out = IO(Output(UInt(8.W)))
  out := in
}

private class DebugParamModule(val width: Int, val hasReset: Boolean) extends Module {
  override def desiredName = s"DebugParamModule_w${width}_r$hasReset"
  val in = IO(Input(UInt(width.W)))
  val out = IO(Output(UInt(width.W)))
  if (hasReset) { val reg = RegInit(0.U(width.W)); reg := in; out := reg }
  else out := in
}

private class DebugSubModule extends Module {
  val a = IO(Input(UInt(4.W)))
  val b = IO(Output(UInt(4.W)))
  b := a + 1.U
}

private class DebugTopModule extends Module {
  val in = IO(Input(UInt(4.W)))
  val out = IO(Output(UInt(4.W)))
  val sub = Module(new DebugSubModule)
  sub.a := in
  out := sub.b
}

private class DebugParamBundle(val n: Int) extends Bundle {
  val data = UInt(n.W)
}

private class DebugBundleModule(val width: Int) extends Module {
  val io = IO(new DebugParamBundle(width))
}

// ─── Spec ─────────────────────────────────────────────────────────────────────

class DebugIntrinsicsSpec extends AnyFunSpec with Matchers {
  import DebugTestUtils._

  def emit(gen: => RawModule): String =
    ChiselStage.emitCHIRRTL(gen, withDebug = true).replaceAll("\\s+", " ")

  describe("circt_debug_moduleinfo") {

    it("emits exactly one moduleinfo per module") {
      val chirrtl = emit(new DebugSimpleModule)
      countOccurrences(chirrtl, moduleInfoPattern("DebugSimpleModule")) should be(1)
    }

    it("emits moduleinfo with typeName for a parametrized module") {
      val chirrtl = emit(new DebugParamModule(8, false))
      countOccurrences(chirrtl, moduleInfoPattern("DebugParamModule_w8_rfalse")) should be(1)
    }

    it("emits moduleinfo for every module in a hierarchy") {
      val chirrtl = emit(new DebugTopModule)
      countOccurrences(chirrtl, moduleInfoPattern("DebugTopModule")) should be(1)
      countOccurrences(chirrtl, moduleInfoPattern("DebugSubModule")) should be(1)
    }

    it("emits moduleinfo with constructor params serialized in params field") {
      val chirrtl = emit(new DebugParamModule(5, true))
      val name = "DebugParamModule_w5_rtrue"
      countOccurrences(chirrtl, moduleInfoPattern(name)) should be(1)
      // params field must contain width and hasReset entries
      chirrtl should include("width")
      chirrtl should include("hasReset")
    }

    it("does not emit moduleinfo for blackboxes") {
      import DebugTestCircuits.ModuleCircuits._
      val chirrtl = emit(new TopCircuitBlackBox)
      countOccurrences(chirrtl, moduleInfoPattern("TopCircuitBlackBox")) should be(1)
      countOccurrences(chirrtl, moduleInfoPattern("MyBlackBox")) should be(0)
    }

    it("emits moduleinfo with params for a module whose port is a parametrized bundle") {
      val chirrtl = emit(new DebugBundleModule(16))
      countOccurrences(chirrtl, moduleInfoPattern("DebugBundleModule")) should be(1)
      chirrtl should include("width")
    }
  }
}
