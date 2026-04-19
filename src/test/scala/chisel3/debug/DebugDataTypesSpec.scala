// SPDX-License-Identifier: Apache-2.0
package chisel3.debug

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class DebugDataTypesSpec extends AnyFunSpec with Matchers {

  import DebugTestCircuits.DataTypesCircuits._
  import DebugTestCircuits.{BindingChoice, PortBinding, RegBinding, WireBinding}
  import DebugTestUtils._

  def emit(gen: => RawModule): String =
    ChiselStage.emitCHIRRTL(gen, withDebug = true).replaceAll("\\s+", " ")

  def wrap(b: BindingChoice, t: String): String = b match {
    case PortBinding => s"IO[$t]"
    case WireBinding => s"Wire[$t]"
    case RegBinding  => s"Reg[$t]"
  }

  def clockResetFor(b: BindingChoice): Seq[(String, Int)] =
    if (b == RegBinding)
      Seq(
        (varPattern("IO[Clock]", "clock"), 1),
        (varPattern("IO[Reset]", "reset"), 1)
      )
    else Seq.empty

  def typeTests(b: BindingChoice): Unit = {

    it(s"[$b] should annotate ground types") {
      val chirrtl = emit(new TopCircuitGroundTypes(b))
      val analog =
        if (b != RegBinding)
          Seq((varPattern(wrap(b, "Analog<1>"), "analog"), 1))
        else Seq.empty[(String, Int)]
      checkIntrinsics(
        Seq(
          (varPattern(wrap(b, "UInt<8>"), "uint"), 1),
          (varPattern(wrap(b, "SInt<8>"), "sint"), 1),
          (varPattern(wrap(b, "Bool"), "bool"), 1),
          (varPattern(wrap(b, "UInt<8>"), "bits"), 1)
        ) ++ clockResetFor(b) ++ analog,
        chirrtl
      )
    }

    it(s"[$b] should annotate bundles") {
      val chirrtl = emit(new TopCircuitBundles(b))
      checkIntrinsics(
        Seq(
          (varPattern(wrap(b, "AnonymousBundle"), "a"), 1),
          (varPattern(wrap(b, "MyEmptyBundle"), "bnd"), 1),
          (varPattern(wrap(b, "MyBundle"), "c"), 1),
          (subfieldPattern(wrap(b, "UInt<8>"), "c.a", "c"), 1),
          (subfieldPattern(wrap(b, "SInt<8>"), "c.b", "c"), 1),
          (subfieldPattern(wrap(b, "Bool"), "c.c", "c"), 1)
        ) ++ clockResetFor(b),
        chirrtl
      )
    }

    it(s"[$b] should annotate nested bundles") {
      val chirrtl = emit(new TopCircuitBundlesNested(b))
      checkIntrinsics(
        Seq(
          (varPattern(wrap(b, "MyNestedBundle"), "a"), 1),
          (subfieldPattern(wrap(b, "Bool"), "a.a", "a"), 1),
          (subfieldPattern(wrap(b, "MyBundle"), "a.b", "a"), 1),
          (subfieldPattern(wrap(b, "UInt<8>"), "a.b.a", "a.b"), 1),
          (subfieldPattern(wrap(b, "SInt<8>"), "a.b.b", "a.b"), 1),
          (subfieldPattern(wrap(b, "Bool"), "a.b.c", "a.b"), 1),
          (subfieldPattern(wrap(b, "MyBundle"), "a.c", "a"), 1),
          (subfieldPattern(wrap(b, "UInt<8>"), "a.c.a", "a.c"), 1),
          (subfieldPattern(wrap(b, "SInt<8>"), "a.c.b", "a.c"), 1),
          (subfieldPattern(wrap(b, "Bool"), "a.c.c", "a.c"), 1)
        ) ++ clockResetFor(b),
        chirrtl
      )
    }

    it(s"[$b] should annotate vecs") {
      val chirrtl = emit(new TopCircuitVecs(b))
      checkIntrinsics(
        Seq(
          (varPattern(wrap(b, "SInt<23>[5]"), "a"), 1),
          (subfieldPattern(wrap(b, "SInt<23>"), "a[0]", "a"), 1),
          (varPattern(wrap(b, "SInt<23>[3][5]"), "bv"), 1),
          (subfieldPattern(wrap(b, "SInt<23>[3]"), "bv[0]", "bv"), 1),
          (subfieldPattern(wrap(b, "SInt<23>"), "bv[0][0]", "bv[0]"), 1),
          (varPattern(wrap(b, "AnonymousBundle[5]"), "c"), 1),
          (subfieldPattern(wrap(b, "AnonymousBundle"), "c[0]", "c"), 1),
          (subfieldPattern(wrap(b, "UInt<8>"), "c[0].x", "c[0]"), 1),
          (varPattern(wrap(b, "MixedVec"), "d"), 1),
          (subfieldPattern(wrap(b, "UInt<3>"), "d.0", "d"), 1),
          (subfieldPattern(wrap(b, "SInt<10>"), "d.1", "d"), 1)
        ) ++ clockResetFor(b),
        chirrtl
      )
    }

    it(s"[$b] should annotate bundle with vec") {
      val chirrtl = emit(new TopCircuitBundleWithVec(b))
      checkIntrinsics(
        Seq(
          (varPattern(wrap(b, "AnonymousBundle"), "a"), 1),
          (subfieldPattern(wrap(b, "UInt<8>[5]"), "a.vec", "a"), 1),
          (subfieldPattern(wrap(b, "UInt<8>"), "a.vec[0]", "a.vec"), 1)
        ) ++ clockResetFor(b),
        chirrtl
      )
    }
  }

  describe("Clock and Reset annotations") {
    it("should annotate explicit clock and reset types") {
      val chirrtl = emit(new TopCircuitClockReset)
      checkIntrinsics(
        Seq(
          (varPattern("IO[Clock]", "clock"), 1),
          (varPattern("IO[Bool]", "syncReset"), 1),
          (varPattern("IO[Reset]", "reset"), 1),
          (varPattern("IO[AsyncReset]", "asyncReset"), 1)
        ),
        chirrtl
      )
    }

    it("should annotate implicit clock and reset") {
      val chirrtl = emit(new TopCircuitImplicitClockReset)
      checkIntrinsics(
        Seq(
          (varPattern("IO[Clock]", "clock"), 1),
          (varPattern("IO[Bool]", "reset"), 1)
        ),
        chirrtl
      )
    }
  }

  describe("Port (IO) annotations") { typeTests(PortBinding) }
  describe("Wire annotations") { typeTests(WireBinding) }
  describe("Reg annotations") { typeTests(RegBinding) }

  describe("ChiselEnum annotations") {
    it("should emit enumdef once per enum type") {
      val chirrtl = emit(new TopCircuitEnumSimple)
      checkIntrinsics(
        Seq(
          (enumDefPattern("DebugTestEnum"), 1),
          (enumDefPattern("DebugTestEnum2"), 1)
        ),
        chirrtl
      )
    }

    it("should annotate enum port with enumTypeName") {
      val chirrtl = emit(new TopCircuitEnumSimple)
      checkIntrinsics(
        Seq(
          (varPattern("IO[DebugTestEnum]", "e", Some("DebugTestEnum")), 1),
          (varPattern("IO[DebugTestEnum2]", "e2", Some("DebugTestEnum2")), 1)
        ),
        chirrtl
      )
    }

    it("should annotate enum subfield with enumTypeName") {
      val chirrtl = emit(new TopCircuitEnumSimple)
      checkIntrinsics(
        Seq(
          (subfieldPattern("IO[DebugTestEnum]", "bnd.en", "bnd", Some("DebugTestEnum")), 1),
          (subfieldPattern("IO[DebugTestEnum2]", "bnd.en2", "bnd", Some("DebugTestEnum2")), 1),
          (subfieldPattern("IO[UInt<8>]", "bnd.x", "bnd", None), 1)
        ),
        chirrtl
      )
    }

    it("should annotate enum in vec with enumTypeName") {
      val chirrtl = emit(new TopCircuitEnumSimple)
      checkIntrinsics(
        Seq(
          (varPattern("IO[DebugTestEnum[3]]", "v"), 1),
          (subfieldPattern("IO[DebugTestEnum]", "v[0]", "v", Some("DebugTestEnum")), 1)
        ),
        chirrtl
      )
    }
  }

  describe("Memory annotations") {
    import DebugTestCircuits.MemCircuits._

    it("should annotate Mem as var without parent") {
      val chirrtl = emit(new TopCircuitMem(UInt(8.W), false))
      val memVar = s"""intrinsic\\(circt_debug_var<[^)]*name\\s*=\\s*"mem""""
      countOccurrences(chirrtl, memVar) should be(1)
      countOccurrences(chirrtl, """circt_debug_subfield""") should be(0)
    }

    it("should annotate SyncReadMem as var without parent") {
      val chirrtl = emit(new TopCircuitSyncMem(UInt(8.W), false))
      val memVar = s"""intrinsic\\(circt_debug_var<[^)]*name\\s*=\\s*"mem""""
      countOccurrences(chirrtl, memVar) should be(1)
    }
  }

  describe("Tmp values in when/else") {
    it("should annotate named vals inside when blocks") {
      val chirrtl = emit(new TopCircuitWhenElse)
      checkIntrinsics(
        Seq(
          (varPattern("OpResult[UInt<8>]", "evenSel"), 1),
          (varPattern("OpResult[UInt<8>]", "selIsOne"), 1),
          (varPattern("OpResult[UInt<8>]", "oddSel"), 1)
        ),
        chirrtl
      )
    }
  }
}
