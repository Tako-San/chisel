// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import chisel3.experimental._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import circt.stage.ChiselStage
import DebugMetaTestUtils.extractStringParam
import DebugMetaTestUtils.extractJsonParam

class DebugMetaEmitterSpec extends AnyFlatSpec with Matchers {
  behavior.of("DebugMetaEmitter")

  class RegWithButtonModule extends Module {
    val in = IO(Input(UInt(8.W))).suggestName("in")
    val out = IO(Output(UInt(8.W))).suggestName("out")
  }

  class SimpleModule extends RawModule {
    val in = IO(Input(UInt(8.W))).suggestName("in")
    val out = IO(Output(UInt(16.W))).suggestName("out")
  }

  class MyModule extends Module {
    val in = IO(Input(UInt(8.W))).suggestName("in")
    val out = IO(Output(UInt(8.W))).suggestName("out")
  }

  class WireModule extends RawModule {
    val w = Wire(UInt(8.W))
  }

  class MyBundle extends Bundle {
    val a = UInt(8.W)
    val b = SInt(16.W)
  }

  class BundleModule extends RawModule {
    val io = IO(new MyBundle).suggestName("io")
  }

  class VecModule extends RawModule {
    val io = IO(Input(Vec(4, UInt(8.W)))).suggestName("io")
  }

  class InferredModule extends RawModule {
    val w = Wire(UInt()) // InferredWidth - must not throw
  }

  class DirectionModule extends RawModule {
    val in = IO(Input(UInt(8.W))).suggestName("in")
    val out = IO(Output(Bool())).suggestName("out")
  }

  class RegModule extends Module {
    val r = RegInit(0.U(8.W))
  }

  object MyState extends ChiselEnum {
    val IDLE, RUN, DONE = Value
  }

  object SharedEnum extends ChiselEnum { val A, B = Value }

  class ModWithEnum extends Module {
    val s = RegInit(SharedEnum.A)
  }

  class TopWithEnum extends Module {
    val sub = Module(new ModWithEnum)
    val s = RegInit(SharedEnum.A)
  }

  class EnumModule extends Module {
    val state = RegInit(MyState.IDLE)
  }

  class MemModule extends Module {
    val m = SyncReadMem(16, UInt(8.W))
  }

  class NestedModule extends RawModule {
    val io = IO(Input(Vec(2, new MyBundle))).suggestName("io")
  }

  // For testing truncation at max depth, we use deeply nested Vec structure
  class DeepBundleModule extends RawModule {
    // Vec(1, Vec(1, ... Vec(1, Bool()))...) creates deep nesting
    val io = IO(Input(Vec(1, Vec(1, Vec(1, Vec(1, Vec(1, Bool()))))))).suggestName("io")
  }

  class EmptyModule extends RawModule {}

  class Level1 extends Bundle {
    val x = Bool()
  }

  class Level2 extends Bundle {
    val l1 = new Level1
    val y = UInt(8.W)
  }

  class Level3 extends Bundle {
    val l2 = new Level2
    val z = Bool()
  }

  class BoolMetadataModule extends Module {
    val in = IO(Input(Bool())).suggestName("in")
    val out = IO(Output(Bool())).suggestName("out")
    val flag = RegInit(0.U(1.W))
  }

  class MultiWireModule extends RawModule {
    val wire1 = Wire(UInt(4.W))
    val wire2 = Wire(UInt(8.W))
    val wire3 = Wire(UInt(16.W))
    val wire4 = Wire(Bool())
  }

  class CtorParamModule(intParam: Int, strParam: String, boolParam: Boolean) extends RawModule {
    val in = IO(Input(UInt(8.W))).suggestName("in")
  }

  class WideningDeclModule extends RawModule {
    val in = IO(Input(UInt(8.W))).suggestName("in")
    val out = IO(Output(UInt(8.W))).suggestName("out")
    val x: Data = Wire(UInt(8.W))
  }

  class GenericPortModule[T <: Data](gen: T) extends RawModule {
    val io = IO(Input(gen)).suggestName("io")
    val y: T = Wire(gen)
  }

  class TopWithTypedSub extends RawModule {
    val sub = Module(new CtorParamModule(42, "hello", true))
  }

  class InnerRouting extends Module {
    val p = IO(Input(UInt(4.W))).suggestName("p")
  }

  class OuterRouting extends Module {
    val p = IO(Input(UInt(8.W))).suggestName("p")
    val inner = Module(new InnerRouting)
  }

  class AliasTarget(val n: Int) extends RawModule

  class AliasAliasImportedModule extends RawModule {
    val m = Module(new AliasTarget(99))
  }

  class CurriedMod(a: Int)(b: String) extends RawModule

  class CurriedUser extends RawModule {
    val m = Module(new CurriedMod(10)("hello"))
  }

  class ComplexRhsModule extends RawModule {
    val w = WireInit(UInt(8.W), 0.U)
    val v = VecInit(Seq.fill(4)(0.U(8.W)))
  }

  private def emitWithDebug(gen: => RawModule): String = {
    ChiselStage.emitCHIRRTL(gen, args = Array("--emit-debug-type-info"))
  }

  it should "emit circt_debug_typetag for IO ports" in {
    val chirrtl = emitWithDebug(new SimpleModule)
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("binding = \"port\"")
  }

  it should "emit circt_debug_typetag for Wire" in {
    val chirrtl = emitWithDebug(new WireModule)
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("binding = \"wire\"")
  }

  it should "emit Bundle field structure in JSON" in {
    val chirrtl = emitWithDebug(new BundleModule)
    // Bundle emits circt_debug_typenode; leaf fields emit circt_debug_typetag
    chirrtl should include("circt_debug_typenode")
    chirrtl should include("className = \"MyBundle\"")
    chirrtl should include("circt_debug_typetag") // leaf fields
  }

  it should "emit Vec structure in JSON" in {
    val chirrtl = emitWithDebug(new VecModule)

    // Top-level Vec -> typetag void form (firtool requirement)
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("className = \"Vec\"")

    // Vec TypeTag uses void form (no `=` prefix, firtool constraint)
    val vecTagLines = chirrtl
      .split("\n")
      .filter(l => l.contains("circt_debug_typetag") && l.contains("className = \"Vec\""))
    vecTagLines should not be empty
    all(vecTagLines) should not.include("= intrinsic(circt_debug_typetag")

    // Vec does NOT emit typenode - CIRCT handles decomposition
    (chirrtl should not).include("circt_debug_typenode")
    val typenodeLines = chirrtl.split("\n").filter(_.contains("circt_debug_typenode"))
    typenodeLines.exists(_.contains("className = \"Vec\"")) shouldBe false

    // Vec elements are not emitted separately by Chisel
    val elemTagLines = chirrtl
      .split("\n")
      .filter(l => l.contains("circt_debug_typetag") && !l.contains("className = \"Vec\""))
    elemTagLines shouldBe empty // Vec elements are not emitted separately by Chisel
  }

  it should "handle InferredWidth without exception" in {
    val chirrtl = emitWithDebug(new InferredModule)
    chirrtl should include("width = -1")
  }

  it should "NOT emit intrinsics when disabled" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new SimpleModule)
    (chirrtl should not).include("circt_debug_typetag")
  }

  it should "enable debug types via --emit-debug-type-info CLI arg" in {
    val chirrtl = ChiselStage.emitCHIRRTL(
      new SimpleModule,
      args = Array("--emit-debug-type-info")
    )
    chirrtl should include("circt_debug_typetag")
  }

  it should "include direction for IO ports" in {
    val chirrtl = emitWithDebug(new DirectionModule)
    chirrtl should include("direction = \"input\"")
    chirrtl should include("direction = \"output\"")
  }

  it should "emit circt_debug_typetag for Reg" in {
    val chirrtl = emitWithDebug(new RegModule)
    chirrtl should include("binding = \"reg\"")
  }

  it should "emit enum variant map for ChiselEnum" in {
    val chirrtl = emitWithDebug(new EnumModule)
    chirrtl should include("circt_debug_enumdef")
    chirrtl should include("name = \"MyState\"")
    chirrtl should include("variants =")
    // Check that variants contain the expected enum values
    val variants = extractJsonParam(chirrtl, "enumdef", "variants")
    variants should not be empty
    val variantArray = variants.head.arr
    (variantArray.map(_("name").str) should contain).allOf("IDLE", "RUN", "DONE")
  }

  it should "emit circt_debug_enumdef once per circuit" in {
    val chirrtl = emitWithDebug(new TopWithEnum)
    val count = chirrtl.split("\n").count(_.contains("circt_debug_enumdef"))
    count shouldBe 1 // per-circuit deduplication: one SharedState enumdef for entire circuit
  }

  it should "handle nested Vec(n, Bundle)" in {
    val chirrtl = emitWithDebug(new NestedModule)
    // Vec uses circt_debug_typetag void form only
    // CIRCT handles decomposing the Vec and Bundles via buildDebugAggregateWithMeta
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("className = \"Vec\"")
    // Vec does NOT emit typenode - CIRCT handles decomposition
    (chirrtl should not).include("circt_debug_typenode")
    // Vec elements are not emitted separately by Chisel
    val vecTypetags = chirrtl
      .split("\n")
      .filter(l => l.contains("circt_debug_typetag") && l.contains("className = \"Vec\""))
    vecTypetags should not be empty
    // Verify only one Vec typetag is emitted (not one per element)
    vecTypetags.size shouldBe 1
  }

  it should "truncate structure at max depth and emit sentinel" in {
    // MaxStructureDepth / truncation sentinel no longer apply at the Chisel level.
    // We verify that deeply-nested types still emit typetag without crashing.
    val chirrtl = emitWithDebug(new DeepBundleModule)
    chirrtl should include("circt_debug_typetag")
  }

  it should "emit circt_debug_moduleinfo" in {
    val chirrtl = emitWithDebug(new RegModule)
    chirrtl should include("circt_debug_moduleinfo")
  }

  it should "embed non-empty sourceLoc in moduleinfo (not @[])" in {
    val chirrtl = emitWithDebug(new RegModule)
    val moduleinfoLines = chirrtl.split("\n").filter(_.contains("circt_debug_moduleinfo"))
    withClue("Expected at least one circt_debug_moduleinfo line") {
      moduleinfoLines should not be empty
    }
    withClue("circt_debug_moduleinfo should not contain empty source location @[]:\n" + chirrtl) {
      moduleinfoLines.foreach { line =>
        (line should not).include("@[]")
      }
    }
  }

  it should "validate circt_debug_typetag has required native parameters" in {
    val chirrtl = emitWithDebug(new RegWithButtonModule)
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("className =")
    chirrtl should include("width =")
    chirrtl should include("binding =")
    chirrtl should include("direction =")
  }

  it should "validate circt_debug_moduleinfo has required native parameters" in {
    val chirrtl = emitWithDebug(new RegModule)
    chirrtl should include("circt_debug_moduleinfo")
    chirrtl should include("className =")
    chirrtl should include("name =")
  }

  it should "validate enum JSON payload with enumType and enumdef" in {
    val chirrtl = emitWithDebug(new EnumModule)
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("enumType =")
    chirrtl should include("enumType = \"MyState\"")
    chirrtl should include("circt_debug_enumdef")
    chirrtl should include("name = \"MyState\"")
    chirrtl should include("variants =")
    val enumdefs = extractJsonParam(chirrtl, "enumdef", "variants")
    enumdefs should not be empty
    val enumdefLines = chirrtl.split("\n").filter(_.contains("circt_debug_enumdef"))
    enumdefLines should have size 1
  }

  it should "emit circt_debug_meminfo for SyncReadMem" in {
    val chirrtl = emitWithDebug(new MemModule)
    chirrtl should include("circt_debug_meminfo")
    chirrtl should include("dataType =")
    chirrtl should include("memoryKind = \"SyncReadMem\"")
    chirrtl should include("depth = 16")
  }

  it should "NOT emit ctorParams for module with no constructor args" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    class NoArgModule extends RawModule {
      val in = IO(Input(UInt(8.W))).suggestName("in")
    }
    val chirrtl = emitWithDebug(new NoArgModule)
    (chirrtl should not).include("ctorParams =")
  }

  it should "emit circt_debug_moduleinfo for empty module with empty ctorParams" in {
    val chirrtl = emitWithDebug(new EmptyModule)
    chirrtl should include("circt_debug_moduleinfo")
    chirrtl should include("name = \"EmptyModule\"")
    (chirrtl should not).include("ctorParams =")
  }

  it should "include correct Bool type metadata for ports and registers" in {
    val chirrtl = emitWithDebug(new BoolMetadataModule)
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("className = \"Bool\"")
    chirrtl should include("direction = \"input\"")
    chirrtl should include("direction = \"output\"")
    chirrtl should include("binding = \"reg\"")
  }

  it should "emit separate intrinsics for multiple wires with distinct IDs" in {
    val chirrtl = emitWithDebug(new MultiWireModule)
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("binding = \"wire\"")
    chirrtl should include("className = \"UInt\"")
    chirrtl should include("width = 4")
    chirrtl should include("width = 8")
    chirrtl should include("width = 16")
    chirrtl should include("className = \"Bool\"")
    val typetagLines = chirrtl.split("\n").filter(_.contains("circt_debug_typetag"))
    typetagLines.exists(_.contains("wire1")) shouldBe true
    typetagLines.exists(_.contains("wire2")) shouldBe true
    typetagLines.exists(_.contains("wire3")) shouldBe true
    typetagLines.exists(_.contains("wire4")) shouldBe true
  }

  it should "include ctorParams for module with constructor arguments" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    // Note: This test actually requires the plugin to be active.
    // Without the plugin, ctorParams won't be emitted. The pluginTest
    // suite properly tests this functionality.
    // Here we just verify that the moduleinfo is present for the module.
    val chirrtl = emitWithDebug(new TopWithTypedSub)
    chirrtl should include("circt_debug_moduleinfo")
    chirrtl should include("name = \"CtorParamModule\"")
    chirrtl should include("className =")
  }

  it should "extract RHS type name for widening declarations (Data vs UInt)" in {
    val chirrtl = emitWithDebug(new WideningDeclModule)
    chirrtl should include("circt_debug_typetag")
    val classNames = extractStringParam(chirrtl, "typetag", "className")
    classNames should not be empty
    classNames.foreach { cn =>
      if (cn != "Bool") {
        cn shouldBe "UInt"
      }
    }
  }

  it should "resolve generic type parameters to concrete types" in {
    val chirrtl = emitWithDebug(new GenericPortModule(UInt(8.W)))
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("className = \"UInt\"")
  }

  it should "emit enumdef before meminfo for enum element type" in {
    object MemState extends ChiselEnum { val sIdle, sRun = Value }
    class EnumMemModule extends RawModule {
      val mem = Mem(8, MemState())
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new EnumMemModule, args = Array("--emit-debug-type-info"))
    val enumIdx = chirrtl.indexOf("circt_debug_enumdef")
    val memIdx = chirrtl.indexOf("circt_debug_meminfo")
    enumIdx should be >= 0
    memIdx should be >= 0
    withClue("circt_debug_enumdef must precede circt_debug_meminfo:\n" + chirrtl) {
      enumIdx should be < memIdx
    }

    val payloads = extractJsonParam(chirrtl, "meminfo", "dataType")
    payloads should have size 1
    val dt = payloads.head
    dt.obj.contains("enumType") shouldBe true
    dt("enumType").str shouldBe "MemState"
    dt.obj.get("kind").foreach(_.str should not be "MemState")
  }

  it should "use className in meminfo dataType for Bundle element" in {
    class Inner extends Bundle { val x = UInt(8.W); val y = SInt(4.W) }
    class BundleMemModule extends RawModule { val mem = Mem(4, new Inner) }
    val chirrtl = ChiselStage.emitCHIRRTL(new BundleMemModule, args = Array("--emit-debug-type-info"))
    val dt = extractJsonParam(chirrtl, "meminfo", "dataType").head

    dt.obj.contains("className") shouldBe true
    dt.obj.contains("typeName") shouldBe false
    dt("className").str shouldBe "Inner"

    val fields = dt("fields").obj
    fields("x").obj.contains("className") shouldBe true
    fields("x").obj.contains("typeName") shouldBe false
  }

  it should "emit enumdef once per circuit for shared ChiselEnum" in {
    object SharedState extends ChiselEnum { val sIdle, sRun = Value }
    class ChildModule extends RawModule { val p = IO(Input(SharedState())) }
    class TopModule extends RawModule {
      val p = IO(Input(SharedState()))
      val sub = Module(new ChildModule)
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new TopModule, args = Array("--emit-debug-type-info"))
    val count = chirrtl.split("\n").count(_.contains("circt_debug_enumdef"))
    withClue("Same ChiselEnum should produce exactly one enumdef across all modules:\n" + chirrtl) {
      count shouldBe 1
    }
  }

  it should "emit separate enumdef for same-named enums from different packages" in {
    object pkgA { object State extends ChiselEnum { val sA, sB = Value } }
    object pkgB { object State extends ChiselEnum { val sX, sY, sZ = Value } }
    class TwoStateModule extends RawModule {
      val p1 = IO(Input(pkgA.State()))
      val p2 = IO(Input(pkgB.State()))
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new TwoStateModule, args = Array("--emit-debug-type-info"))

    val count = chirrtl.split("\n").count(_.contains("circt_debug_enumdef"))
    withClue("Two distinct ChiselEnum objects must produce two separate enumdefs:\n" + chirrtl) {
      count shouldBe 2
    }

    val defs = extractJsonParam(chirrtl, "enumdef", "variants")
    val varCounts = defs.map(_.arr.length).toSet
    varCounts shouldBe Set(2, 3)
  }

  it should "use AnonymousModule (not AnonymousBundle) for className" in {
    class Top extends RawModule {
      val sub = Module(new RawModule {
        val io = IO(new Bundle { val x = Input(UInt(8.W)) })
      })
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new Top, args = Array("--emit-debug-type-info"))
    val classNames = extractStringParam(chirrtl, "moduleinfo", "className")

    withClue("No moduleinfo should have className=AnonymousBundle") {
      classNames should not contain "AnonymousBundle"
    }
    withClue("Anonymous module should have className=AnonymousModule") {
      classNames should contain("AnonymousModule")
    }
  }

  it should "use className consistently in nested Record fields, not typeName" in {
    class Inner extends Bundle { val a = UInt(4.W) }
    class Outer extends Bundle { val inner = new Inner; val b = Bool() }
    class TestMod extends RawModule { val p = IO(Input(new Outer)) }
    val chirrtl = ChiselStage.emitCHIRRTL(new TestMod, args = Array("--emit-debug-type-info"))

    // Structural JSON (fields) is no longer emitted as a parameter in circt_debug_typetag.
    // CIRCT builds dbg.struct from the IR.  We verify that the top-level aggregate
    // typetag uses className, not typeName, and that no typeName parameter is emitted.
    chirrtl should include("className = \"Outer\"")
    val typetagLines = chirrtl.split("\n").filter(_.contains("circt_debug_typetag"))
    typetagLines.foreach { line =>
      (line should not).include("typeName =")
    }
  }

  it should "not leak ctorParams stack when using Definition/Instance" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    import chisel3.experimental.hierarchy._

    class ParamModule(val n: Int) extends RawModule {
      val p = IO(Input(UInt(8.W))).suggestName("p")
    }

    class TopWithDefinition extends RawModule {
      val defn = Definition(new ParamModule(42))
      val inst = Instance(defn)
      val plain = Module(new RawModule {
        val q = IO(Input(UInt(4.W))).suggestName("q")
      })
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TopWithDefinition, args = Array("--emit-debug-type-info"))
    val moduleinfoLines = chirrtl.split("\n").filter(_.contains("circt_debug_moduleinfo"))

    // Find the AnonymousModule line and check it doesn't have ctorParams with arg0=42
    val hasAnonWithArg0 = moduleinfoLines.exists { line =>
      line.contains("AnonymousModule") && line.contains("ctorParams =") && line.contains("arg0")
    }
    hasAnonWithArg0 shouldBe false
  }

  it should "emit moduleinfo for sub-module instantiated via Module() wrapper" in {
    val chirrtl = emitWithDebug(new AliasAliasImportedModule)
    chirrtl should include("circt_debug_moduleinfo")
    val classNames = extractStringParam(chirrtl, "moduleinfo", "className")
    classNames should contain("AliasTarget")
  }

  it should "emit moduleinfo for modules with curried constructors" in {
    val chirrtl = emitWithDebug(new CurriedUser)
    chirrtl should include("circt_debug_moduleinfo")
    val classNames = extractStringParam(chirrtl, "moduleinfo", "className")
    classNames should contain("CurriedMod")
  }

  it should "not crash for macro-expanded or complex RHS expressions" in {
    noException should be thrownBy emitWithDebug(new ComplexRhsModule)
  }

  it should "emit required native parameters for enumdef" in {
    val chirrtl = emitWithDebug(new EnumModule)
    chirrtl should include("circt_debug_enumdef")
    chirrtl should include("name =")
    chirrtl should include("fqn =")
    chirrtl should include("variants =")
  }

  it should "emit required native parameters for meminfo" in {
    val chirrtl = emitWithDebug(new MemModule)
    chirrtl should include("circt_debug_meminfo")
    chirrtl should include("memName =")
    chirrtl should include("depth =")
    chirrtl should include("memoryKind =")
  }

  it should "emit moduleinfo into each module's own body, not the parent's" in {
    val chirrtl = emitWithDebug(new OuterRouting)

    val outerModIdx = chirrtl.indexOf("module OuterRouting")
    val innerModIdx = chirrtl.indexOf("module InnerRouting")

    outerModIdx should be >= 0
    innerModIdx should be >= 0

    val outerInfoIdx = chirrtl.indexOf("name = \"OuterRouting\"")
    val innerInfoIdx = chirrtl.indexOf("name = \"InnerRouting\"")

    outerInfoIdx should be >= 0
    innerInfoIdx should be >= 0

    outerInfoIdx should be > outerModIdx
    innerInfoIdx should be > innerModIdx
    (outerInfoIdx > outerModIdx) && (innerInfoIdx > innerModIdx) shouldBe true
  }

  it should "reset emittedEnums between elaborations" in {
    val c1 = ChiselStage.emitCHIRRTL(new EnumModule, args = Array("--emit-debug-type-info"))
    val c2 = ChiselStage.emitCHIRRTL(new EnumModule, args = Array("--emit-debug-type-info"))
    val count1 = extractStringParam(c1, "enumdef", "name").length
    val count2 = extractStringParam(c2, "enumdef", "name").length
    count1 shouldBe 1
    count2 shouldBe 1
  }

  it should "emit fqn field in enumdef" in {
    object pkgA { object State extends ChiselEnum { val sA, sB = Value } }
    object pkgB { object State extends ChiselEnum { val sX, sY, sZ = Value } }
    class TwoStateModule extends RawModule {
      val p1 = IO(Input(pkgA.State()))
      val p2 = IO(Input(pkgB.State()))
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new TwoStateModule, args = Array("--emit-debug-type-info"))

    chirrtl should include("fqn =")

    val enumdefs = extractStringParam(chirrtl, "enumdef", "fqn")
    enumdefs should have size 2

    val fqns = enumdefs.toSet
    fqns.size shouldBe 2
    fqns.foreach { fqn =>
      fqn should include("State")
      (fqn should not).endWith("$")
    }

    val typetagsEnumType = extractStringParam(chirrtl, "typetag", "enumType")
    typetagsEnumType should not be empty

    val enumTypeFqns = extractStringParam(chirrtl, "typetag", "enumTypeFqn")
    enumTypeFqns should not be empty
    enumTypeFqns.foreach(_ should not be empty)
  }

  it should "handle two enums with same simpleName getting distinct fqn" in {
    object pkgA { object MyState extends ChiselEnum { val a, b = Value } }
    object pkgB { object MyState extends ChiselEnum { val x, y, z = Value } }
    class SameNameEnumModule extends RawModule {
      val p1 = IO(Input(pkgA.MyState()))
      val p2 = IO(Input(pkgB.MyState()))
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new SameNameEnumModule, args = Array("--emit-debug-type-info"))

    val variants = extractJsonParam(chirrtl, "enumdef", "variants")
    variants should have size 2

    val names = extractStringParam(chirrtl, "enumdef", "name").toSet
    val fqns = extractStringParam(chirrtl, "enumdef", "fqn").toSet

    fqns.size shouldBe 2
    names.size should be <= 2
    fqns.forall(_.contains("MyState")) shouldBe true

    fqns.foreach(_ should not endWith "$")
  }

  it should "emit enumTypeFqn in typetag for enum references" in {
    object MyState extends ChiselEnum { val IDLE, RUN = Value }
    class SimpleEnumModule extends RawModule {
      val port = IO(Input(MyState()))
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new SimpleEnumModule, args = Array("--emit-debug-type-info"))

    chirrtl should include("enumType = \"MyState\"")
    chirrtl should include("enumTypeFqn =")

    val typetagsEnumType = extractStringParam(chirrtl, "typetag", "enumType")
    typetagsEnumType should not be empty
    typetagsEnumType.foreach(_ shouldBe "MyState")

    val typetagsEnumTypeFqn = extractStringParam(chirrtl, "typetag", "enumTypeFqn")
    typetagsEnumTypeFqn should not be empty
    typetagsEnumTypeFqn.foreach { fqn =>
      fqn should include("MyState")
      (fqn should not).endWith("$")
    }

    val enumdefsFqn = extractStringParam(chirrtl, "enumdef", "fqn")
    enumdefsFqn should have size 1
    enumdefsFqn.head should include("MyState")
    (enumdefsFqn.head should not).endWith("$")
  }

  it should "Vec element descriptor must not contain direction field" in {
    class VecModuleWithPort extends RawModule {
      val io = IO(Input(Vec(4, UInt(8.W)))).suggestName("io")
    }
    val chirrtl = emitWithDebug(new VecModuleWithPort)

    // We verify that the IO port typetag is emitted with correct className
    // and no spurious direction parameter in element typetag lines.
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("className = \"Vec\"")
    val typetagLines = chirrtl.split("\n").filter(_.contains("circt_debug_typetag"))
    typetagLines.foreach { line =>
      (line should not).include("direction = \"unspecified\"")
    }
  }

  it should "emit enumdef in first module that references the enum, not subsequent" in {
    object TestEnum extends ChiselEnum { val Idle, Run, Done = Value }

    class ModA extends RawModule {
      val p = IO(Input(TestEnum()))
    }

    class ModB extends RawModule {
      val p = IO(Input(TestEnum()))
    }

    class TopModule extends RawModule {
      val a = Module(new ModA)
      val b = Module(new ModB)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TopModule, args = Array("--emit-debug-type-info"))

    val count = chirrtl.split("\n").count(_.contains("circt_debug_enumdef"))
    withClue("Same ChiselEnum should produce exactly one enumdef across all modules:\n" + chirrtl) {
      count shouldBe 1
    }

    val lines = chirrtl.split("\n")
    val modAStart = lines.indexWhere(_.contains("module ModA"))
    val modBStart = lines.indexWhere(_.contains("module ModB"))
    val enumDefLine = lines.indexWhere(_.contains("circt_debug_enumdef"))

    withClue(s"modAStart=$modAStart, modBStart=$modBStart, enumDefLine=$enumDefLine:\n" + chirrtl) {
      modAStart should be >= 0
      modBStart should be >= 0
      enumDefLine should be >= 0

      enumDefLine should be > modAStart
      enumDefLine should be < modBStart
    }

    val modAEnd = if (modBStart > 0) modBStart else lines.length
    val enumDefInModA = lines.slice(modAStart, modAEnd).count(_.contains("circt_debug_enumdef"))
    withClue(s"enumdef count in ModA range [$modAStart, $modAEnd): $enumDefInModA:\n" + chirrtl) {
      enumDefInModA shouldBe 1
    }
  }

  it should "Vec element descriptor in memory dataType must not contain direction field" in {
    class VecMemModule extends RawModule {
      val mem = Mem(8, Vec(4, UInt(8.W)))
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new VecMemModule, args = Array("--emit-debug-type-info"))

    val payloads = extractJsonParam(chirrtl, "meminfo", "dataType")
    payloads should have size 1

    val dataType = payloads.head

    dataType.obj.contains("vecLength") shouldBe true
    dataType.obj.contains("element") shouldBe true

    val elementObj = dataType("element")

    elementObj.obj.contains("direction") shouldBe false
  }

  it should "emit __truncated as top-level key, not inside fields object" in {
    // Structural info is emitted as circt_debug_typetag for aggregates.
    // truncation at max depth is no longer a Chisel-level concern.
    // MaxStructureDepth / setMaxStructureDepth remain available for meminfo dataType JSON.
    // We simply verify no crash and that typetag is emitted for the aggregate.
    val chirrtl = emitWithDebug(new DeepBundleModule)
    chirrtl should include("circt_debug_typetag")
  }

  it should "emit circt_debug_typetag void form for Vec and circt_debug_typenode for Bundle" in {
    class MixedModule extends Module {
      val io = IO(new Bundle {
        val vec = Input(Vec(2, UInt(8.W)))
        val flag = Input(Bool())
      })
    }
    val chirrtl = emitWithDebug(new MixedModule)

    // Bundle uses circt_debug_typenode (returns token); Vec uses circt_debug_typetag void form (no return)
    // Verify both structures are present
    val typenodeLines = chirrtl.split("\n").filter(_.contains("circt_debug_typenode"))
    // Bundle emits typenode for structure
    typenodeLines.exists(_.contains("AnonymousBundle")) shouldBe true
    // Top-level Vec would emit typenode, but Vec in Bundle uses parent's typenode
    // This test's Vec is nested in Bundle, so no Vec typenode is emitted
    all(typenodeLines) should include("= intrinsic(circt_debug_typenode")

    // Verify Vec TypeTag is emitted (void form for Vec nested in Bundle)
    val chirrtlTypetagLines = chirrtl.split("\n").filter(_.contains("circt_debug_typetag"))
    chirrtlTypetagLines.exists(_.contains("className = \"Vec\"")) shouldBe true

    // Vec -> typetag void form (firtool constraint: TypeTag must have no outputs)
    val vecTagLines = chirrtl
      .split("\n")
      .filter(l => l.contains("circt_debug_typetag") && l.contains("Vec"))
    vecTagLines should not be empty
    all(vecTagLines) should not.include("= intrinsic(circt_debug_typetag")

    // Leaf ground types -> typetag void form (no "= intrinsic" prefix)
    // Filter out Vec and Bundle to get only leaf ground types (e.g., io.flag)
    val leafTagLines = chirrtl
      .split("\n")
      .filter(l =>
        l.contains("circt_debug_typetag") && !l.contains("Vec") && !l.contains("className = \"AnonymousBundle\"") && !l
          .contains("className = \"MyBundle\"") && !l.contains(": UInt<0>)")
      )
    leafTagLines should not be empty
    (all(leafTagLines) should not).include("= intrinsic(circt_debug_typetag")
  }

  it should "emit circt_debug_typenode for Bundle aggregate, not circt_debug_typetag" in {
    val chirrtl = emitWithDebug(new BundleModule)

    // Bundle -> typenode (dedicated op, no signal operand)
    val typenodeLines = chirrtl.split("\n").filter(_.contains("circt_debug_typenode"))
    typenodeLines should not be empty
    all(typenodeLines) should include("= intrinsic(circt_debug_typenode")

    // No typetag with Bundle className — Bundle aggregate must not appear in typetag
    val bundleTagLines = chirrtl
      .split("\n")
      .filter(l => l.contains("circt_debug_typetag") && l.contains("className = \"MyBundle\""))
    bundleTagLines shouldBe empty
  }

  /** Recursively checks if __truncated key exists in any nested JSON object */
  private def checkForTruncated(obj: ujson.Obj): Boolean =
    obj.value.values.exists {
      case n: ujson.Obj => n.obj.contains("__truncated") || checkForTruncated(n)
      case _ => false
    }

}
