// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import chisel3.experimental._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import circt.stage.ChiselStage

// ── Enum definition (must be at top level) ──
object MyState extends ChiselEnum {
  val IDLE, RUN, DONE = Value
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

  // ── Helper ──

  private def emitWithDebug(gen: => RawModule): String = {
    ChiselStage.emitCHIRRTL(gen, args = Array("--emit-debug-type-info"))
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
}
