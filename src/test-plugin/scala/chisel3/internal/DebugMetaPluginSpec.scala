// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import chisel3.internal.DebugMetaTestUtils.{extractIntrinsicParams, extractJsonParam, splitIntrinsics}
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import logger.LazyLogging

/** Test cases for compiler plugin-based ctorParams capture.
  * These tests require -P:chiselplugin:emitDebugTypeInfo at compile time.
  * See build.mill: chisel.pluginTest module.
  */
class DebugMetaPluginSpec extends AnyFlatSpec with Matchers with LazyLogging {

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

  // Import-alias and symbol-based isModuleSym test ------------------
  class AliasTarget2(val n: Int) extends RawModule

  class AliasUser extends RawModule {
    val m = Module(new AliasTarget2(99))
  }

  // Curried constructor - known limitation ---------------------------
  class CurriedMod(a: Int)(b: String) extends RawModule

  class CurriedUser extends RawModule {
    val m = Module(new CurriedMod(10)("hello"))
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

  class LocalModuleShadow extends RawModule {
    // Local object that shadows chisel3.Module name
    object Module {
      def apply(value: Int): Int = value * 2
    }
    // Using the local Module here should NOT trigger the plugin's Module() wrapper
    val x = Module(5) // This is a local Module, should return 10, not instantiate a Chisel module
  }

  // -- Helper ----------------------------------------------------------------

  private def emitWithDebug(gen: => RawModule): String = {
    ChiselStage.emitCHIRRTL(gen, args = Array("--emit-debug-type-info"))
  }

  /** Extracts and parses all circt_debug_moduleinfo intrinsics into JSON objects.
    * Since the new implementation uses native parameters instead of the 'info' attribute,
    * we need to reconstruct the JSON object from the individual parameters.
    */
  private def extractModuleInfos(chirrtl: String): Seq[ujson.Obj] = {
    val intrinsics = splitIntrinsics(chirrtl).filter(_.contains("circt_debug_moduleinfo"))

    intrinsics.map { intrinsic =>
      val obj = ujson.Obj()
      val params = extractIntrinsicParams(intrinsic)

      // Extract className
      params.get("className").foreach {
        case Left(name) => obj("className") = ujson.Str(name)
        case _          =>
      }

      // Extract name
      params.get("name").foreach {
        case Left(name) => obj("name") = ujson.Str(name)
        case _          =>
      }

      // Extract sourceFile
      params.get("sourceFile").foreach {
        case Left(file) => obj("sourceFile") = ujson.Str(file)
        case _          =>
      }

      // Extract sourceLine
      params.get("sourceLine").foreach {
        case Right(num) => obj("sourceLine") = ujson.Num(num)
        case Left(s)    => obj("sourceLine") = ujson.Num(s.toDouble)
      }

      // Extract ctorParams if present (it's a JSON string)
      params.get("ctorParams").foreach {
        case Left(jsonStr) =>
          val unescaped = jsonStr.replaceAll("\\\\\"", "\"").replaceAll("\\\\\\\\", "\\\\")
          try {
            obj("ctorParams") = ujson.read(unescaped)
          } catch {
            case e: Exception =>
              // If JSON parsing fails, log and skip this param
              logger.warn(s"Failed to parse ctorParams JSON: ${e.getMessage}")
          }
        case _ =>
      }

      obj
    }.toSeq
  }

  // -- Tests ----------------------------------------------------------------

  it should "NOT emit ctorParams key for module with no constructor args" in {
    val chirrtl = emitWithDebug(new NoArgModule)
    val modInfos = extractModuleInfos(chirrtl)
    // NoArgModule should not have ctorParams
    val noArgInfo = modInfos.find(_("name").str == "NoArgModule")
    noArgInfo shouldBe defined
    noArgInfo.get.obj.get("ctorParams") shouldBe None
  }

  it should "serialize Int ctor param as JSON number (no quotes)" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    val chirrtl = emitWithDebug(new IntModuleTest)
    val modInfos = extractModuleInfos(chirrtl)
    val intModInfo = modInfos.find(_("className").str == "IntModule")
    intModInfo shouldBe defined
    intModInfo.get.obj.get("ctorParams") shouldBe defined
    val params = intModInfo.get("ctorParams").obj
    params("arg0").num shouldBe 42.0 // Should be number, not "42"
  }

  it should "serialize String ctor param as JSON string (with quotes)" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    val chirrtl = emitWithDebug(new StringModuleTest)
    val modInfos = extractModuleInfos(chirrtl)
    val stringModInfo = modInfos.find(_("className").str == "StringModule")
    stringModInfo shouldBe defined
    stringModInfo.get.obj.get("ctorParams") shouldBe defined
    val params = stringModInfo.get("ctorParams").obj
    params("arg0").str shouldBe "hello"
  }

  it should "serialize Boolean ctor param as JSON boolean (no quotes)" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    val chirrtl = emitWithDebug(new BoolModuleTest)
    val modInfos = extractModuleInfos(chirrtl)
    val boolModInfo = modInfos.find(_("className").str == "BoolModule")
    boolModInfo shouldBe defined
    boolModInfo.get.obj.get("ctorParams") shouldBe defined
    val params = boolModInfo.get("ctorParams").obj
    params("arg0").bool shouldBe true
  }

  it should "serialize Int and Boolean ctor params as JSON primitives, not strings" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    val chirrtl = emitWithDebug(new MultiParamModuleTest)
    val modInfos = extractModuleInfos(chirrtl)
    val multiModInfo = modInfos.find(_("name").str == "MultiParamModule")
    multiModInfo shouldBe defined
    multiModInfo.get.obj.get("ctorParams") shouldBe defined
    val params = multiModInfo.get("ctorParams").obj

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
    val modInfos = extractModuleInfos(chirrtl)

    // Should have moduleinfo for both OuterModule and InnerModule
    modInfos should have size 2

    val innerInfo = modInfos.find(_("name").str == "InnerModule")
    innerInfo shouldBe defined

    // Inner module should have ctorParams with arg0=123
    val innerParams = innerInfo.get.obj.get("ctorParams")
    innerParams should be(defined)
    innerParams.get.obj("arg0").num shouldBe 123.0

    // OuterModule should NOT have ctorParams (no args)
    val outerInfo = modInfos.find(_("name").str == "OuterModule")
    outerInfo shouldBe defined
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
    val modInfos = extractModuleInfos(chirrtl)

    // Filter for IntModules (by className) that have ctorParams
    val intModules = modInfos.filter { info =>
      info("className").str == "IntModule" && info.obj.contains("ctorParams")
    }

    intModules should have size 3

    val ctors = intModules.map(_("ctorParams").obj("arg0").num.toDouble).toSet
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
    val infos = extractModuleInfos(chirrtl)
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
    val infos = extractModuleInfos(chirrtl)
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
    val infos = extractModuleInfos(chirrtl)

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
    val infos = extractModuleInfos(chirrtl)

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

  it should "NOT treat local shadowed Module as chisel3.Module" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "Module shadowing requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    // H-6: Verifies that a local `object Module` that shadows `chisel3.Module`
    // is NOT incorrectly treated as a Chisel module. The fix in isModuleSym checks
    // the symbol's fully qualified name ("chisel3.Module") instead of falling back
    // to string matching on any identifier named "Module".
    val chirrtl = emitWithDebug(new LocalModuleShadow)

    // The local Module shadowing should NOT generate any circt_debug_moduleinfo
    // because it's not actually a chisel3.Module instantiation
    val modInfos = extractModuleInfos(chirrtl)

    // We should only have moduleinfo for LocalModuleShadow itself, not for any
    // "module" instantiated via the shadowed local Module identifier
    val shadowingInfo = modInfos.find(_("name").str == "Module")
    shadowingInfo shouldBe empty

    // LocalModuleShadow should still have its own moduleinfo
    val parentInfo = modInfos.find(_("name").str == "LocalModuleShadow")
    parentInfo shouldBe defined
  }
}
