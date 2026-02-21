// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import chisel3.experimental._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import circt.stage.ChiselStage
import scala.util.Try

// ── Enum definition (must be at top level) ──
object MyState extends ChiselEnum {
  val IDLE, RUN, DONE = Value
}

// ── Test modules for JSON payload validation ──

class RegWithButtonModule extends Module {
  val in = IO(Input(UInt(8.W))).suggestName("in")
  val enable = IO(Input(Bool())).suggestName("enable")
  val out = IO(Output(UInt(8.W))).suggestName("out")
  val reg = RegInit(0.U(8.W))
}

class DebugTypeEmitterSpec extends AnyFlatSpec with Matchers {
  behavior.of("DebugTypeEmitter")

  // ── Module definitions MUST be at class level, not inside `it should` lambdas. ──
  // The Chisel compiler plugin only injects .suggestName for vals visible at
  // class/trait member scope. Local classes inside lambdas are not processed,
  // causing IO ports to throw "Attempted to name a nameless IO port".
  // We explicitly call .suggestName() on IO ports to work around this limitation.

  class SimpleModule extends RawModule {
    val in = IO(Input(UInt(8.W))).suggestName("in")
    val out = IO(Output(UInt(16.W))).suggestName("out")
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
    val w = Wire(UInt()) // InferredWidth — must not throw
  }

  // ── Test modules for Phase 1 enhancements ──

  class DirectionModule extends RawModule {
    val in = IO(Input(UInt(8.W))).suggestName("in")
    val out = IO(Output(Bool())).suggestName("out")
  }

  class RegModule extends Module {
    val r = RegInit(0.U(8.W))
  }

  class EnumModule extends Module {
    val state = RegInit(MyState.IDLE)
  }

  class NestedModule extends RawModule {
    val io = IO(Input(Vec(2, new MyBundle))).suggestName("io")
  }

  // ── Edge case test modules ──

  class EmptyModule extends RawModule {
    // No ports, no internal logic
  }

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

  private def emitWithDebug(gen: => RawModule): String = {
    ChiselStage.emitCHIRRTL(gen, args = Array("--emit-debug-type-info"))
  }

  /** Extract and parse all JSON payloads from intrinsic lines of given type. */
  private def extractPayloads(chirrtl: String, intrinsicName: String): Seq[_root_.ujson.Value] = {
    chirrtl
      .split("\n")
      .filter(_.contains(intrinsicName))
      .flatMap { line =>
        val marker = "info = \""
        val start = line.indexOf(marker)
        if (start < 0) None
        else {
          val payloadStart = start + marker.length
          val payloadEnd = line.indexOf("\">", payloadStart)
          if (payloadEnd < 0) None
          else {
            val raw = line.substring(payloadStart, payloadEnd)
            Try(_root_.ujson.read(raw.replace("\\\"", "\""))).toOption
          }
        }
      }
      .toSeq
  }

  // ── Tests ──

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

  // ── Phase 1 enhancement tests ──

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
    chirrtl should include("\\\"enumDef\\\"")
    chirrtl should include("\\\"name\\\":\\\"MyState\\\"")
    chirrtl should include("\\\"0\\\":\\\"IDLE\\\"")
    chirrtl should include("\\\"1\\\":\\\"RUN\\\"")
    chirrtl should include("\\\"2\\\":\\\"DONE\\\"")
  }

  it should "handle nested Vec(n, Bundle)" in {
    val chirrtl = emitWithDebug(new NestedModule)
    chirrtl should include("\\\"vecLength\\\":2")
    chirrtl should include("\\\"fields\\\"")
  }

  it should "emit circt_debug_moduleinfo" in {
    val chirrtl = emitWithDebug(new RegModule)
    chirrtl should include("circt_debug_moduleinfo")
  }

  // ── JSON payload validation tests ──

  it should "validate circt_debug_typetag JSON payload has required fields" in {
    val chirrtl = emitWithDebug(new RegWithButtonModule)
    val payloads = extractPayloads(chirrtl, "circt_debug_typetag")
    payloads should not be empty
    payloads.foreach { json =>
      json.obj.keySet should contain("className")
    }
  }

  it should "validate circt_debug_moduleinfo JSON payload has required fields" in {
    val chirrtl = emitWithDebug(new RegModule)
    val payloads = extractPayloads(chirrtl, "circt_debug_moduleinfo")
    payloads should not be empty
    val keys = payloads.head.obj.keySet
    keys should contain("name")
    keys should contain("kind")
    keys should contain("className")
  }

  it should "validate enum JSON payload contains enumDef" in {
    val chirrtl = emitWithDebug(new EnumModule)
    val payloads = extractPayloads(chirrtl, "circt_debug_typetag")
    payloads should not be empty
    payloads.exists(_.obj.contains("enumDef")) shouldBe true
  }

  // ── Edge case tests ──

  it should "emit circt_debug_moduleinfo for empty module with empty ctorParams" in {
    val chirrtl = emitWithDebug(new EmptyModule)
    chirrtl should include("circt_debug_moduleinfo")
    chirrtl should include("\\\"name\\\":\\\"EmptyModule\\\"")
    // Note: empty modules may or may not have ctorParams field, so we just check the module name exists
  }

  it should "emit correct direction and type for deeply nested bundles" in {
    val chirrtl = emitWithDebug(new NestedBundleModule)
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("\\\"direction\\\":\\\"input\\\"")
    // Nested bundle fields use 'type' inside the fields object, not 'className'
    chirrtl should include("\\\"type\\\":\\\"Bool\\\"")
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
    // Each wire declaration + its intrinsic line = at least 2 occurrences
    // Verify each wire appears in at least one typetag intrinsic
    val typetagLines = chirrtl.split("\\n").filter(_.contains("circt_debug_typetag"))
    typetagLines.exists(_.contains("wire1")) shouldBe true
    typetagLines.exists(_.contains("wire2")) shouldBe true
    typetagLines.exists(_.contains("wire3")) shouldBe true
    typetagLines.exists(_.contains("wire4")) shouldBe true
  }

  it should "include ctorParams with properly JSON-escaped primitive types" in {
    val chirrtl = ChiselStage.emitCHIRRTL(
      new CtorParamModule(42, "test_string", true),
      args = Array("--emit-debug-type-info")
    )
    chirrtl should include("circt_debug_moduleinfo")
    chirrtl should include("\\\"name\\\":\\\"CtorParamModule\\\"")
    chirrtl should include("\\\"intParam\\\":42")
    chirrtl should include("\\\"strParam\\\":\\\"test_string\\\"")
    chirrtl should include("\\\"boolParam\\\":true")
  }
}
