// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import chisel3.internal.DebugMetaTestUtils.extractPayloads
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Test cases for compiler plugin-based ctorParams capture.
  * These tests require -P:chiselplugin:emitDebugTypeInfo at compile time.
  * See build.mill: chisel.pluginTest module.
  */
class DebugMetaPluginSpec extends AnyFlatSpec with Matchers {

  // -- Test modules for plugin-based ctorParams capture ---------------------

  class NoArgModule extends RawModule {
    val in = IO(Input(UInt(8.W)))
  }

  class IntModule(intParam: Int) extends RawModule {
    val in = IO(Input(UInt(8.W)))
  }

  class StringModule(strParam: String) extends RawModule {
    val in = IO(Input(UInt(8.W)))
  }

  class BoolModule(boolParam: Boolean) extends RawModule {
    val in = IO(Input(UInt(8.W)))
  }

  class MultiParamModule(intParam: Int, strParam: String, boolParam: Boolean) extends RawModule {
    val in = IO(Input(UInt(8.W)))
  }

  // Nested module to test stack semantics
  class InnerModule(innerInt: Int) extends RawModule {
    val in = IO(Input(UInt(8.W)))
  }

  class OuterModule extends RawModule {
    val in = IO(Input(UInt(8.W)))
    val inner = Module(new InnerModule(123)) // Inner module has nested ctor params
  }

  // -- Test instantiations with Module() wrapper ---------------------------
  // The plugin's wrapModuleWithCtorParams only intercepts Module() macro patterns,
  // not direct `new` instantiations.

  class IntModuleTest extends RawModule {
    val in = IO(Input(UInt(8.W)))
    val m = Module(new IntModule(42))
  }

  class StringModuleTest extends RawModule {
    val in = IO(Input(UInt(8.W)))
    val m = Module(new StringModule("hello"))
  }

  class BoolModuleTest extends RawModule {
    val in = IO(Input(UInt(8.W)))
    val m = Module(new BoolModule(true))
  }

  class MultiParamModuleTest extends RawModule {
    val in = IO(Input(UInt(8.W)))
    val m = Module(new MultiParamModule(42, "hello", true))
  }

  class IntModuleTest2 extends RawModule {
    val in = IO(Input(UInt(8.W)))
    val m1 = Module(new IntModule(1))
    val m2 = Module(new IntModule(2))
    val m3 = Module(new IntModule(3))
  }

  // -- H-3: import-alias and symbol-based isModuleSym test ------------------
  class AliasTarget2(val n: Int) extends RawModule

  class AliasUser extends RawModule {
    val m = Module(new AliasTarget2(99))
  }

  // -- H-4: curried constructor - known limitation ---------------------------
  class CurriedMod(a: Int)(b: String) extends RawModule

  class CurriedUser extends RawModule {
    val m = Module(new CurriedMod(10)("hello"))
  }

  // -- P1-6: emoji/surrogate pair truncation test -----------------------------
  class EmojiModule(val label: String) extends RawModule {
    val in = IO(Input(UInt(8.W)))
  }

  class InnerWithParam(val n: Int) extends RawModule {
    val p = IO(Input(UInt(8.W)))
  }

  class OuterWithParam(val m: Int) extends RawModule {
    val p = IO(Input(UInt(8.W)))
    val ch = Module(new InnerWithParam(42))
  }

  class BothParamsContainer extends RawModule {
    val top = Module(new OuterWithParam(99))
  }

  /** Module WITH constructor params whose body throws before generateComponent()
    * runs. The compiler plugin generates:
    *   withCtorParams(Some("{\"arg0\":99}")) { Module(new ThrowingWithParam(99)) }
    * Execution: pushPendingCtorParams -> ctor throws -> popPendingCtorParams
    * never called -> pendingCtorParamsStack: [Some("{\"arg0\":99}")] (unpopped).
    * The stale entry is abandoned with the DynamicContext by Builder.build().
    */
  class ThrowingWithParam(val x: Int) extends RawModule {
    throw new Exception("simulated failure inside ThrowingWithParam ctor")
  }

  class ContainerForThrowingWithParam extends RawModule {
    val p = IO(Input(UInt(8.W)))
    val bad = Module(new ThrowingWithParam(99))
  }

  // -- Helper ----------------------------------------------------------------

  private def emitWithDebug(gen: => RawModule): String = {
    ChiselStage.emitCHIRRTL(gen, args = Array("--emit-debug-type-info"))
  }

  // -- Tests ----------------------------------------------------------------

  it should "NOT emit ctorParams key for module with no constructor args" in {
    val chirrtl = emitWithDebug(new NoArgModule)
    val payloads = extractPayloads(chirrtl, "circt_debug_moduleinfo")
    payloads.foreach { json =>
      json.obj.keySet should not contain "ctorParams"
    }
  }

  it should "serialize Int ctor param as JSON number (no quotes)" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    val chirrtl = emitWithDebug(new IntModuleTest)
    val payloads = extractPayloads(chirrtl, "circt_debug_moduleinfo")
    val withParams = payloads.filter(_.obj.contains("ctorParams"))
    withParams should not be empty
    val params = withParams.head("ctorParams").obj
    params("arg0").num shouldBe 42.0 // Should be number, not "42"
  }

  it should "serialize String ctor param as JSON string (with quotes)" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    val chirrtl = emitWithDebug(new StringModuleTest)
    val payloads = extractPayloads(chirrtl, "circt_debug_moduleinfo")
    val withParams = payloads.filter(_.obj.contains("ctorParams"))
    withParams should not be empty
    val params = withParams.head("ctorParams").obj
    params("arg0").str shouldBe "hello"
  }

  it should "serialize Boolean ctor param as JSON boolean (no quotes)" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    val chirrtl = emitWithDebug(new BoolModuleTest)
    val payloads = extractPayloads(chirrtl, "circt_debug_moduleinfo")
    val withParams = payloads.filter(_.obj.contains("ctorParams"))
    withParams should not be empty
    val params = withParams.head("ctorParams").obj
    params("arg0").bool shouldBe true
  }

  it should "serialize Int and Boolean ctor params as JSON primitives, not strings" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    val chirrtl = emitWithDebug(new MultiParamModuleTest)
    val modInfos = extractPayloads(chirrtl, "circt_debug_moduleinfo")
    // Find the MultiParamModule info
    val multiModuleInfos = modInfos.filter(_("name").str == "MultiParamModule")
    multiModuleInfos should have size 1
    val params = multiModuleInfos.head("ctorParams").obj

    // Verify types: Int -> number, String -> string, Boolean -> boolean
    params("arg0").num shouldBe 42.0 // Int  -> number, not "42"
    params("arg1").str shouldBe "hello" // String -> string
    params("arg2").bool shouldBe true // Bool -> boolean, not "true"
  }

  it should "handle nested modules with correct ctor params on each" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    val chirrtl = emitWithDebug(new OuterModule)
    val modInfos = extractPayloads(chirrtl, "circt_debug_moduleinfo")

    // Should have moduleinfo for both OuterModule and InnerModule
    modInfos should have size 2

    val innerInfo = modInfos.find(_("name").str == "InnerModule")
    innerInfo should be(defined)

    // Inner module should have ctorParams with arg0=123
    val innerParams = innerInfo.get.obj.get("ctorParams")
    innerParams should be(defined)
    innerParams.get.obj("arg0").num shouldBe 123.0

    // OuterModule should NOT have ctorParams (no args)
    val outerInfo = modInfos.find(_("name").str == "OuterModule")
    outerInfo should be(defined)
    outerInfo.get.obj.get("ctorParams") should be(empty)
  }

  it should "ensure stack is cleaned up after elaboration" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    // Verify that after multiple elaborations, the stack doesn't leak
    val chirrtl = emitWithDebug(new IntModuleTest2)

    // All three IntModules should have ctorParams with correct values
    val modInfos = extractPayloads(chirrtl, "circt_debug_moduleinfo")

    // Filter for IntModules (by className) that have ctorParams
    // Chisel's unique naming adds suffixes like _1, _2, etc.
    val intModules = modInfos.filter(_.obj.get("className").exists(_.str == "IntModule"))

    // Some IntModules may not have ctorParams if they were created separately,
    // so we filter further for those with ctorParams
    val intModulesWithCtorParams = intModules.filter(_.obj.contains("ctorParams"))

    intModulesWithCtorParams should have size 3

    val ctors = intModulesWithCtorParams.map(_("ctorParams").obj("arg0").num.toDouble).toSet
    ctors shouldBe Set(1.0, 2.0, 3.0)
  }

  it should "capture ctorParams for modules with constructor parameters (plugin active)" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    // Verifies end-to-end plugin ctorParams injection for a module with a val constructor param.
    // The symbol-based isModuleSym fix (H-3) is exercised here via the bySymbol path on a
    // normal Module() call. The import-alias scenario (import chisel3.{Module => M}) cannot
    // be covered in this compilation unit - it requires a separate source file.
    val chirrtl = emitWithDebug(new AliasUser)
    val infos = extractPayloads(chirrtl, "circt_debug_moduleinfo")
      .filter(_.obj.contains("ctorParams"))
    infos should not be empty
    val targetInfo = infos.find(_("className").str == "AliasTarget2")
    targetInfo should be(defined)
    targetInfo.get("ctorParams")("arg0").num shouldBe 99.0
  }

  it should "capture only first arg list for curried constructors (known limitation)" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    // H-4: documents the known limitation in findNewArgs - only first arg list is captured.
    val chirrtl = emitWithDebug(new CurriedUser)
    val infos = extractPayloads(chirrtl, "circt_debug_moduleinfo")
      .filter(_.obj.contains("ctorParams"))
    infos should not be empty
    // Known limitation: second arg list ("hello") is silently dropped.
    infos.head("ctorParams").obj.keys.toSet shouldBe Set("arg0")
    infos.head("ctorParams")("arg0").num shouldBe 10.0
  }

  it should "preserve ctorParams for BOTH outer and inner when both have constructor params" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams capture requires Scala 2 compiler plugin"
    )
    val chirrtl = emitWithDebug(new BothParamsContainer)
    val infos = extractPayloads(chirrtl, "circt_debug_moduleinfo")

    val innerInfo = infos.find(_("name").str == "InnerWithParam")
    innerInfo shouldBe defined
    innerInfo.get("ctorParams")("arg0").num shouldBe 42.0

    val outerInfo = infos.find(_("name").str == "OuterWithParam")
    outerInfo shouldBe defined
    withClue(
      "OuterWithParam lost ctorParams - likely double-pop bug in withCtorParams.finally"
    ) {
      outerInfo.get.obj.get("ctorParams") shouldBe defined
      outerInfo.get("ctorParams")("arg0").num shouldBe 99.0
    }
  }

  it should "discard unpopped pendingCtorParamsStack entries with the DynamicContext after failed elaboration" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "requires Scala 2 compiler plugin"
    )

    intercept[Exception] {
      emitWithDebug(new ContainerForThrowingWithParam)
    }

    val chirrtl = emitWithDebug(new BothParamsContainer)
    val infos = extractPayloads(chirrtl, "circt_debug_moduleinfo")

    val containerInfo = infos.find(_("name").str == "BothParamsContainer")
    containerInfo shouldBe defined
    withClue(
      "BothParamsContainer got spurious ctorParams - pendingCtorParamsStack leaked from failed elaboration"
    ) {
      containerInfo.get.obj.get("ctorParams") shouldBe None
    }

    infos.find(_("name").str == "InnerWithParam").get("ctorParams")("arg0").num shouldBe 42.0
    infos.find(_("name").str == "OuterWithParam").get("ctorParams")("arg0").num shouldBe 99.0
  }
}
