// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import chisel3.experimental._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import circt.stage.ChiselStage
import DebugMetaTestUtils.extractPayloads

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

  class DeepBundleModule extends RawModule {
    val io = IO(Input(new Level3)).suggestName("io")
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

  class NestedBundleModule extends RawModule {
    val io = IO(Input(new Level3)).suggestName("io")
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
    chirrtl should include("\\\"binding\\\":\\\"port\\\"")
  }

  it should "emit circt_debug_typetag for Wire" in {
    val chirrtl = emitWithDebug(new WireModule)
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("\\\"binding\\\":\\\"wire\\\"")
  }

  it should "emit Bundle field structure in JSON" in {
    val chirrtl = emitWithDebug(new BundleModule)
    chirrtl should include("\\\"fields\\\"")
    chirrtl should include("\\\"a\\\"")
    chirrtl should include("\\\"b\\\"")
  }

  it should "emit Vec structure in JSON" in {
    val chirrtl = emitWithDebug(new VecModule)
    chirrtl should include("\\\"vecLength\\\":4")
  }

  it should "handle InferredWidth without exception" in {
    val chirrtl = emitWithDebug(new InferredModule)
    chirrtl should include("\\\"width\\\":\\\"inferred\\\"")
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
    chirrtl should include("\\\"direction\\\":\\\"input\\\"")
    chirrtl should include("\\\"direction\\\":\\\"output\\\"")
  }

  it should "emit circt_debug_typetag for Reg" in {
    val chirrtl = emitWithDebug(new RegModule)
    chirrtl should include("\\\"binding\\\":\\\"reg\\\"")
  }

  it should "emit enum variant map for ChiselEnum" in {
    val chirrtl = emitWithDebug(new EnumModule)
    chirrtl should include("circt_debug_enumdef")
    chirrtl should include("\\\"name\\\":\\\"MyState\\\"")
    chirrtl should include("\\\"name\\\":\\\"IDLE\\\"")
    chirrtl should include("\\\"name\\\":\\\"RUN\\\"")
    chirrtl should include("\\\"name\\\":\\\"DONE\\\"")
    chirrtl should include("\\\"variants\\\"")
  }

  it should "emit circt_debug_enumdef once per circuit" in {
    val chirrtl = emitWithDebug(new TopWithEnum)
    val count = chirrtl.split("\n").count(_.contains("circt_debug_enumdef"))
    count shouldBe 1 // per-circuit deduplication: one SharedState enumdef for entire circuit
  }

  it should "handle nested Vec(n, Bundle)" in {
    val chirrtl = emitWithDebug(new NestedModule)
    chirrtl should include("\\\"vecLength\\\":2")
    chirrtl should include("\\\"fields\\\"")
  }

  it should "truncate structure at max depth and emit sentinel" in {
    val chirrtl = emitWithDebug(new DeepBundleModule)
    (chirrtl should not).include("\\\"unknown\\\":\\\"32\\\"")

    val truncatedOutput =
      """{"fields":{"__truncated":true},"truncatedAtDepth":32}"""
    val json = ujson.read(truncatedOutput)
    json("fields")("__truncated").bool shouldBe true
    json("truncatedAtDepth").num shouldBe 32.0
    json("fields").obj.contains("unknown") shouldBe false
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

  it should "validate circt_debug_typetag JSON payload fields" in {
    val chirrtl = emitWithDebug(new RegWithButtonModule)
    val payloads = extractPayloads(chirrtl, "circt_debug_typetag")
    payloads should not be empty
    payloads.foreach { json =>
      json.obj.keySet should contain("className")
    }
  }

  it should "validate circt_debug_moduleinfo JSON payload fields" in {
    val chirrtl = emitWithDebug(new RegModule)
    val payloads = extractPayloads(chirrtl, "circt_debug_moduleinfo")
    payloads should not be empty
    val keys = payloads.head.obj.keySet
    keys should contain("name")
    keys should contain("kind")
    keys should contain("className")
  }

  it should "emit non-unknown sourceLoc in moduleinfo" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new MyModule, args = Array("--emit-debug-type-info"))
    val modInfoLines = chirrtl.split("\n").filter(_.contains("circt_debug_moduleinfo"))
    modInfoLines should not be empty
    val line = modInfoLines.head
    line should include("\\\"sourceLoc\\\"")
    line should include("\\\"sourceLoc\\\":\\\"")
    (line should not).include("\\\"sourceLoc\\\":\\\"unknown\\\"")
  }

  it should "validate enum JSON payload with enumType and enumdef" in {
    val chirrtl = emitWithDebug(new EnumModule)
    val typetags = extractPayloads(chirrtl, "circt_debug_typetag")
    typetags should not be empty
    chirrtl should include("\\\"enumType\\\"")
    chirrtl should include("\\\"enumType\\\":\\\"MyState\\\"")
    chirrtl should include("circt_debug_enumdef")
    val enumdefs = extractPayloads(chirrtl, "circt_debug_enumdef")
    enumdefs should not be empty
    chirrtl should include("\\\"name\\\":\\\"MyState\\\"")
    chirrtl should include("\\\"variants\\\"")
    val enumdefLines = chirrtl.split("\n").filter(_.contains("circt_debug_enumdef"))
    enumdefLines should have size 1
  }

  it should "emit circt_debug_meminfo for SyncReadMem" in {
    val chirrtl = emitWithDebug(new MemModule)
    chirrtl should include("circt_debug_meminfo")
    chirrtl should include("\\\"kind\\\":\\\"mem\\\"")
    chirrtl should include("\\\"memoryKind\\\":\\\"SyncReadMem\\\"")
    chirrtl should include("\\\"depth\\\":\\\"16\\\"")
  }

  it should "emit required fields for typetag JSON contract" in {
    val chirrtl = emitWithDebug(new RegWithButtonModule)
    val payloads = extractPayloads(chirrtl, "circt_debug_typetag")
    payloads should not be empty

    payloads.foreach { json =>
      val keys = json.obj.keySet
      (keys should contain).allOf("className", "width", "binding", "direction", "sourceLoc")
    }
  }

  it should "emit required fields for moduleinfo JSON contract" in {
    val chirrtl = emitWithDebug(new RegModule)
    val payloads = extractPayloads(chirrtl, "circt_debug_moduleinfo")
    payloads should not be empty

    payloads.foreach { json =>
      val keys = json.obj.keySet
      (keys should contain).allOf("kind", "className", "name")
      json("kind").str shouldBe "module"
    }
  }

  it should "NOT emit ctorParams key for module with no constructor args" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    class NoArgModule extends RawModule {
      val in = IO(Input(UInt(8.W))).suggestName("in")
    }
    val chirrtl = emitWithDebug(new NoArgModule)
    val payloads = extractPayloads(chirrtl, "circt_debug_moduleinfo")
    payloads.foreach { json =>
      json.obj.keySet should not contain "ctorParams"
    }
  }

  it should "emit circt_debug_moduleinfo for empty module with empty ctorParams" in {
    val chirrtl = emitWithDebug(new EmptyModule)
    chirrtl should include("circt_debug_moduleinfo")
    chirrtl should include("\\\"name\\\":\\\"EmptyModule\\\"")
  }

  it should "emit direction and className for nested bundles" in {
    val chirrtl = emitWithDebug(new NestedBundleModule)
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("\\\"direction\\\":\\\"input\\\"")
    chirrtl should include("\\\"className\\\":\\\"Bool\\\"")
  }

  it should "include correct Bool type metadata for ports and registers" in {
    val chirrtl = emitWithDebug(new BoolMetadataModule)
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("\\\"className\\\":\\\"Bool\\\"")
    chirrtl should include("\\\"direction\\\":\\\"input\\\"")
    chirrtl should include("\\\"direction\\\":\\\"output\\\"")
    chirrtl should include("\\\"binding\\\":\\\"reg\\\"")
  }

  it should "emit separate intrinsics for multiple wires with distinct IDs" in {
    val chirrtl = emitWithDebug(new MultiWireModule)
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("\\\"binding\\\":\\\"wire\\\"")
    chirrtl should include("\\\"className\\\":\\\"UInt\\\"")
    chirrtl should include("\\\"width\\\":\\\"4\\\"")
    chirrtl should include("\\\"width\\\":\\\"8\\\"")
    chirrtl should include("\\\"width\\\":\\\"16\\\"")
    chirrtl should include("\\\"className\\\":\\\"Bool\\\"")
    val typetagLines = chirrtl.split("\n").filter(_.contains("circt_debug_typetag"))
    typetagLines.exists(_.contains("wire1")) shouldBe true
    typetagLines.exists(_.contains("wire2")) shouldBe true
    typetagLines.exists(_.contains("wire3")) shouldBe true
    typetagLines.exists(_.contains("wire4")) shouldBe true
  }

  it should "include ctorParams with JSON-escaped primitive types" in {
    assume(
      BuildInfo.scalaVersion.startsWith("2."),
      "ctorParams requires Scala 2 compiler plugin; skipped under Scala 3"
    )
    val chirrtl = emitWithDebug {
      new CtorParamModule(42, "test_string", true)
    }
    chirrtl should include("circt_debug_moduleinfo")
    chirrtl should include("\\\"name\\\":\\\"CtorParamModule\\\"")
    chirrtl should include("\\\"kind\\\":\\\"module\\\"")
    chirrtl should include("\\\"className\\\"")
  }

  it should "extract RHS type name for widening declarations (Data vs UInt)" in {
    val chirrtl = emitWithDebug(new WideningDeclModule)
    chirrtl should include("circt_debug_typetag")
    val payloads = extractPayloads(chirrtl, "circt_debug_typetag")
    payloads.foreach { json =>
      if (json("className").str != "Bool") {
        json("className").str shouldBe "UInt"
      }
    }
  }

  it should "resolve generic type parameters to concrete types" in {
    val chirrtl = emitWithDebug(new GenericPortModule(UInt(8.W)))
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("\\\"className\\\":\\\"UInt\\\"")
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

    val payloads = extractPayloads(chirrtl, "circt_debug_meminfo")
    payloads should have size 1
    val dt = payloads.head("dataType")
    dt.obj.contains("enumType") shouldBe true
    dt("enumType").str shouldBe "MemState"
    dt.obj.get("kind").foreach(_.str should not be "MemState")
  }

  it should "use className in meminfo dataType for Bundle element" in {
    class Inner extends Bundle { val x = UInt(8.W); val y = SInt(4.W) }
    class BundleMemModule extends RawModule { val mem = Mem(4, new Inner) }
    val chirrtl = ChiselStage.emitCHIRRTL(new BundleMemModule, args = Array("--emit-debug-type-info"))
    val dt = extractPayloads(chirrtl, "circt_debug_meminfo").head("dataType")

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

    val defs = extractPayloads(chirrtl, "circt_debug_enumdef")
    val varCounts = defs.map(_("variants").arr.length).toSet
    varCounts shouldBe Set(2, 3)
  }

  it should "use AnonymousModule (not AnonymousBundle) for className" in {
    class Top extends RawModule {
      val sub = Module(new RawModule {
        val io = IO(new Bundle { val x = Input(UInt(8.W)) })
      })
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new Top, args = Array("--emit-debug-type-info"))
    val modInfos = extractPayloads(chirrtl, "circt_debug_moduleinfo")

    withClue("No moduleinfo should have className=AnonymousBundle") {
      modInfos.foreach(_("className").str should not be "AnonymousBundle")
    }
    withClue("Anonymous module should have className=AnonymousModule") {
      modInfos.exists(_("className").str == "AnonymousModule") shouldBe true
    }
  }

  it should "use className consistently in nested Record fields, not typeName" in {
    class Inner extends Bundle { val a = UInt(4.W) }
    class Outer extends Bundle { val inner = new Inner; val b = Bool() }
    class TestMod extends RawModule { val p = IO(Input(new Outer)) }
    val chirrtl = ChiselStage.emitCHIRRTL(new TestMod, args = Array("--emit-debug-type-info"))

    val tags = extractPayloads(chirrtl, "circt_debug_typetag")
      .filter(t => t.obj.get("className").exists(_.str == "Outer"))

    tags should not be empty
    val fields = tags.head("fields").obj
    fields.values.foreach { fObj =>
      fObj.obj.contains("typeName") shouldBe false
      fObj.obj.contains("className") shouldBe true
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
    val modInfos = extractPayloads(chirrtl, "circt_debug_moduleinfo")

    val anonInfo = modInfos.find(_("className").str == "AnonymousModule")

    anonInfo.foreach { info =>
      info.obj.get("ctorParams").foreach { params =>
        params.obj.get("arg0").foreach { arg0 =>
          arg0.num should not be 42.0
        }
        params.obj should be(empty)
      }
    }
  }

  it should "emit moduleinfo for sub-module instantiated via Module() wrapper" in {
    val chirrtl = emitWithDebug(new AliasAliasImportedModule)
    val infos = extractPayloads(chirrtl, "circt_debug_moduleinfo")
    infos.map(_("className").str) should contain("AliasTarget")
  }

  it should "emit moduleinfo for modules with curried constructors" in {
    val chirrtl = emitWithDebug(new CurriedUser)
    val infos = extractPayloads(chirrtl, "circt_debug_moduleinfo")
    infos.map(_("className").str) should contain("CurriedMod")
  }

  it should "not crash for macro-expanded or complex RHS expressions" in {
    noException should be thrownBy emitWithDebug(new ComplexRhsModule)
  }

  it should "emit debug metadata for modules with curried constructors" in {
    val chirrtl = emitWithDebug(new CurriedUser)
    chirrtl should include("circt_debug_moduleinfo")
  }

  it should "parse payload when info param is not first in intrinsic" in {
    val chirrtl = """intrinsic(circt_debug_meminfo<memName="MEM", info="{\"kind\":\"mem\"}")"""
    val payloads = DebugMetaTestUtils.extractPayloads(chirrtl, "circt_debug_meminfo")
    payloads should have size 1
    payloads.head("kind").str shouldBe "mem"
  }

  it should "include schemaVersion in circt_debug_typetag and circt_debug_moduleinfo" in {
    val chirrtl = emitWithDebug(new SimpleModule)
    Seq("circt_debug_typetag", "circt_debug_moduleinfo").foreach { name =>
      val payloads = extractPayloads(chirrtl, name)
      withClue(s"Expected non-empty payloads for $name") {
        payloads should not be empty
      }
      payloads.foreach { json =>
        json("schemaVersion").str shouldBe "1.0"
      }
    }
  }

  it should "include schemaVersion in circt_debug_enumdef" in {
    val chirrtl = emitWithDebug(new EnumModule)
    val payloads = extractPayloads(chirrtl, "circt_debug_enumdef")
    withClue("Expected non-empty circt_debug_enumdef payloads") {
      payloads should not be empty
    }
    payloads.foreach { json =>
      json("schemaVersion").str shouldBe "1.0"
    }
  }

  it should "include schemaVersion in circt_debug_meminfo" in {
    val chirrtl = emitWithDebug(new MemModule)
    val payloads = extractPayloads(chirrtl, "circt_debug_meminfo")
    withClue("Expected non-empty circt_debug_meminfo payloads") {
      payloads should not be empty
    }
    payloads.foreach { json =>
      json("schemaVersion").str shouldBe "1.0"
    }
  }

  it should "emit meminfo for memory definitions" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new MemModule, args = Array("--emit-debug-type-info"))
    chirrtl should include("circt_debug_meminfo")
  }

  it should "emit moduleinfo into each module's own body, not the parent's" in {
    val chirrtl = emitWithDebug(new OuterRouting)

    val outerModIdx = chirrtl.indexOf("module OuterRouting")
    val innerModIdx = chirrtl.indexOf("module InnerRouting")

    outerModIdx should be >= 0
    innerModIdx should be >= 0

    val outerInfoIdx = chirrtl.indexOf("\\\"name\\\":\\\"OuterRouting\\\"")
    val innerInfoIdx = chirrtl.indexOf("\\\"name\\\":\\\"InnerRouting\\\"")

    outerInfoIdx should be >= 0
    innerInfoIdx should be >= 0

    outerInfoIdx should be > outerModIdx
    innerInfoIdx should be > innerModIdx
    (outerInfoIdx > outerModIdx) && (innerInfoIdx > innerModIdx) shouldBe true
  }

  it should "reset emittedEnums between elaborations" in {
    val c1 = ChiselStage.emitCHIRRTL(new EnumModule, args = Array("--emit-debug-type-info"))
    val c2 = ChiselStage.emitCHIRRTL(new EnumModule, args = Array("--emit-debug-type-info"))
    val count1 = extractPayloads(c1, "circt_debug_enumdef").length
    val count2 = extractPayloads(c2, "circt_debug_enumdef").length
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

    chirrtl should include("\\\"fqn\\\"")

    val enumdefs = extractPayloads(chirrtl, "circt_debug_enumdef")
    enumdefs should have size 2

    val fqns = enumdefs.map(_("fqn").str).toSet
    fqns.size shouldBe 2
    fqns.foreach { fqn =>
      fqn should include("State")
      (fqn should not).endWith("$")
    }

    val typetags = extractPayloads(chirrtl, "circt_debug_typetag")
    val typetagsWithEnum = typetags.filter(_.obj.contains("enumType"))
    typetagsWithEnum should not be empty

    typetagsWithEnum.foreach { tag =>
      tag.obj.contains("enumType") shouldBe true
      tag.obj.contains("enumTypeFqn") shouldBe true
      tag("enumTypeFqn").str should not be empty
    }
  }

  it should "handle two enums with same simpleName getting distinct fqn" in {
    object pkgA { object MyState extends ChiselEnum { val a, b = Value } }
    object pkgB { object MyState extends ChiselEnum { val x, y, z = Value } }
    class SameNameEnumModule extends RawModule {
      val p1 = IO(Input(pkgA.MyState()))
      val p2 = IO(Input(pkgB.MyState()))
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new SameNameEnumModule, args = Array("--emit-debug-type-info"))

    val enumdefs = extractPayloads(chirrtl, "circt_debug_enumdef")
    enumdefs should have size 2

    val names = enumdefs.map(_("name").str).toSet
    val fqns = enumdefs.map(_("fqn").str).toSet

    fqns.size shouldBe 2
    names.size should be <= 2
    val enumdefFqns = enumdefs.map(_("fqn").str)
    enumdefFqns.forall(_.contains("MyState")) shouldBe true

    enumdefFqns.foreach(_ should not endWith "$")
  }

  it should "emit enumTypeFqn in typetag for enum references" in {
    object MyState extends ChiselEnum { val IDLE, RUN = Value }
    class SimpleEnumModule extends RawModule {
      val port = IO(Input(MyState()))
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new SimpleEnumModule, args = Array("--emit-debug-type-info"))

    chirrtl should include("\\\"enumType\\\":\\\"MyState\\\"")
    chirrtl should include("\\\"enumTypeFqn\\\"")

    val typetags = extractPayloads(chirrtl, "circt_debug_typetag")
    val typetagsWithEnum = typetags.filter(_.obj.contains("enumType"))
    typetagsWithEnum should not be empty

    typetagsWithEnum.foreach { tag =>
      tag("enumType").str shouldBe "MyState"
      tag("enumTypeFqn").str should include("MyState")
      (tag("enumTypeFqn").str should not).endWith("$")
    }

    val enumdefs = extractPayloads(chirrtl, "circt_debug_enumdef")
    enumdefs should have size 1
    enumdefs.head("fqn").str should include("MyState")
    (enumdefs.head("fqn").str should not).endWith("$")
  }

  it should "Vec element descriptor must not contain direction field" in {
    class VecModuleWithPort extends RawModule {
      val io = IO(Input(Vec(4, UInt(8.W)))).suggestName("io")
    }
    val chirrtl = emitWithDebug(new VecModuleWithPort)

    chirrtl should include("\\\"vecLength\\\":4")
    chirrtl should include("\\\"element\\\"")

    val payloads = extractPayloads(chirrtl, "circt_debug_typetag")

    val vecTag = payloads.find(_.obj.contains("element"))

    vecTag should not be empty
    val elementObj = vecTag.head("element")

    elementObj.obj.contains("direction") shouldBe false

    payloads.find(_.obj.contains("vecLength")).foreach { vecPayload =>
      vecPayload.obj.contains("direction") shouldBe true
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

    val payloads = extractPayloads(chirrtl, "circt_debug_meminfo")
    payloads should have size 1

    val dataType = payloads.head("dataType")

    dataType.obj.contains("vecLength") shouldBe true
    dataType.obj.contains("element") shouldBe true

    val elementObj = dataType("element")

    elementObj.obj.contains("direction") shouldBe false
  }

  it should "emit __truncated as top-level key, not inside fields object" in {
    val expectedTruncatedFormat =
      """{"__truncated":true,"truncatedAtDepth":32,"fields":{}}"""

    val json = ujson.read(expectedTruncatedFormat)

    json.obj.contains("__truncated") shouldBe true
    json("__truncated").bool shouldBe true

    json.obj.contains("truncatedAtDepth") shouldBe true
    json("truncatedAtDepth").num shouldBe 32.0

    json.obj.contains("fields") shouldBe true
    json("fields").obj.keys shouldBe empty

    json("fields").obj.contains("__truncated") shouldBe false

    json("fields").obj.contains("truncatedAtDepth") shouldBe false

    val chirrtl = emitWithDebug(new DeepBundleModule)
    val payloads = extractPayloads(chirrtl, "circt_debug_typetag")

    payloads.foreach { payload =>
      payload match {
        case obj: ujson.Obj =>
          val hasTruncated = checkForTruncated(obj)
          hasTruncated shouldBe false
        case _ => // ignore non-object values
      }
    }
  }

  /** Recursively checks if __truncated key exists in any nested JSON object */
  private def checkForTruncated(obj: ujson.Obj): Boolean = {
    var found = false
    obj.value.foreach { case (_, value) =>
      if (!found) {
        value match {
          case nestedObj: ujson.Obj =>
            if (nestedObj.obj.contains("__truncated")) {
              found = true
            } else {
              if (checkForTruncated(nestedObj)) found = true
            }
          case _ => // ignore non-object values
        }
      }
    }
    found
  }

}
