package chisel3.internal

import chisel3._
import chisel3.experimental.UnlocatableSourceInfo
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DebugTypeEmitterSpec extends AnyFlatSpec with Matchers {
  behavior.of("DebugTypeEmitter")

  /** Helper: elaborate with debug emitter enabled and return CHIRRTL string. */
  private def emitWithDebug(gen: => RawModule): String = {
    var result = ""
    Builder.debugTypeEmitterEnabled.withValue(true) {
      result = circt.stage.ChiselStage.emitCHIRRTL(gen)
    }
    result
  }

  it should "emit circt_debug_typetag for IO ports" in {
    class SimpleModule extends RawModule {
      val in = IO(Input(UInt(8.W)))
      val out = IO(Output(UInt(16.W)))
    }
    val chirrtl = emitWithDebug(new SimpleModule)
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("\\\"binding\\\":\\\"port\\\"")
  }

  it should "emit circt_debug_typetag for Wire" in {
    class WireModule extends RawModule {
      val w = Wire(UInt(8.W))
    }
    val chirrtl = emitWithDebug(new WireModule)
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("\\\"binding\\\":\\\"wire\\\"")
  }

  it should "emit Bundle field structure in JSON" in {
    class MyBundle extends Bundle {
      val a = UInt(8.W)
      val b = SInt(16.W)
    }
    class BundleModule extends RawModule {
      val io = IO(new MyBundle)
    }
    val chirrtl = emitWithDebug(new BundleModule)
    chirrtl should include("\\\"fields\\\"")
    chirrtl should include("\\\"a\\\"")
    chirrtl should include("\\\"b\\\"")
  }

  it should "emit Vec structure in JSON" in {
    class VecModule extends RawModule {
      val io = IO(Input(Vec(4, UInt(8.W))))
    }
    val chirrtl = emitWithDebug(new VecModule)
    chirrtl should include("\\\"vecLength\\\":4")
  }

  it should "handle InferredWidth without exception" in {
    class InferredModule extends RawModule {
      val w = Wire(UInt()) // InferredWidth!
    }
    val chirrtl = emitWithDebug(new InferredModule)
    chirrtl should include("\\\"width\\\":\\\"inferred\\\"")
  }

  it should "NOT emit intrinsics when disabled" in {
    class SimpleModule extends RawModule {
      val in = IO(Input(UInt(8.W)))
    }
    val chirrtl = circt.stage.ChiselStage.emitCHIRRTL(new SimpleModule)
    (chirrtl should not).include("circt_debug_typetag")
  }
}
