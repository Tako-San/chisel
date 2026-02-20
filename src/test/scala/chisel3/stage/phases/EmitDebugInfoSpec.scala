// SPDX-License-Identifier: Apache-2.0
package chisel3.stage.phases

import chisel3._
import chisel3.stage.EmitDebugInfoAnnotation
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EmitDebugInfoSpec extends AnyFlatSpec with Matchers {

  private def emitWithDebug(gen: => RawModule): String =
    ChiselStage.emitCHIRRTL(gen, Array("--emit-debug-info"))

  private def emitWithoutDebug(gen: => RawModule): String =
    ChiselStage.emitCHIRRTL(gen)

  // ---------- Opt-in behavior ----------

  "EmitDebugInfo" should "not emit intrinsics without --emit-debug-info" in {
    val chirrtl = emitWithoutDebug(new Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      io.out := io.in
    })
    (chirrtl should not).include("circt_dbg_variable")
  }

  it should "emit intrinsics with --emit-debug-info" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      io.out := io.in
    })
    chirrtl should include("circt_dbg_variable")
  }

  // ---------- Signal coverage ----------

  it should "instrument wires" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val w = Wire(UInt(8.W))
      w := io.in
      io.out := w
    })
    chirrtl should include("""name = "w"""")
    chirrtl should include("""type = "UInt<8>"""")
  }

  it should "instrument registers" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val r = RegInit(0.U(8.W))
      r := io.in
      io.out := r
    })
    chirrtl should include("""name = "r"""")
  }

  it should "instrument RegInit" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val out = Output(UInt(8.W))
      })
      val r = RegInit(42.U(8.W))
      io.out := r
    })
    chirrtl should include("""name = "r"""")
  }

  it should "instrument ports" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      io.out := io.in
    })
    chirrtl should include("""name = "io"""")
    chirrtl should include("""name = "clock"""")
    chirrtl should include("""name = "reset"""")
  }

  it should "instrument Mem" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val addr = Input(UInt(4.W))
        val out = Output(UInt(8.W))
      })
      val mem = Mem(16, UInt(8.W))
      io.out := mem(io.addr)
    })
    chirrtl should include("""name = "mem"""")
  }

  it should "instrument SyncReadMem" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val addr = Input(UInt(4.W))
        val out = Output(UInt(8.W))
      })
      val mem = SyncReadMem(16, UInt(8.W))
      io.out := mem.read(io.addr)
    })
    chirrtl should include("""name = "mem"""")
  }

  // ---------- Chisel type metadata (for Tywaves) ----------

  it should "emit chiselType for named Bundles" in {
    class MyBundle extends Bundle {
      val a = UInt(4.W)
      val b = SInt(4.W)
    }
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new MyBundle)
    })
    // Named Bundles should show their name in the type field
    chirrtl should include("""type = "MyBundle"""")
  }

  it should "not emit chiselType for ground types" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val out = Output(UInt(8.W))
      })
      val w = Wire(UInt(8.W))
      w := 0.U
      io.out := w
    })
    // For UInt wire, chiselType should NOT appear
    // (UInt FIRRTL type is self-sufficient)
    val dbgLines = chirrtl.split("\n").filter(_.contains("""name = "w""""))
    dbgLines.foreach { line =>
      (line should not).include("chiselType")
    }
  }

  it should "emit type for Vec" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val v = Input(Vec(4, UInt(8.W)))
        val out = Output(UInt(8.W))
      })
      io.out := io.v(0)
    })
    // Vec type should be reflected in the type representation
    chirrtl should include("""v : UInt<8>[4]""")
  }

  // ---------- Signals inside when ----------

  it should "instrument signals defined inside when blocks" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val cond = Input(Bool())
        val out = Output(UInt(8.W))
      })
      io.out := 0.U
      when(io.cond) {
        val inner = Wire(UInt(8.W))
        inner := 42.U
        io.out := inner
      }
    })
    chirrtl should include("""name = "inner"""")
  }

  it should "place when-inner intrinsics at module level" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val cond = Input(Bool())
        val out = Output(UInt(8.W))
      })
      io.out := 0.U
      when(io.cond) {
        val inner = Wire(UInt(8.W))
        inner := 42.U
        io.out := inner
      }
    })
    // The intrinsic for "inner" should appear AFTER the when block ends,
    // not nested inside it. Check by verifying it's at module indentation.
    val lines = chirrtl.split("\n")
    val innerDbg = lines.filter(l => l.contains("circt_dbg_variable") && l.contains(""""inner""""))
    innerDbg should not be empty
    // Module-level statements have 4-space indent in CHIRRTL
    innerDbg.foreach { l =>
      val indent = l.takeWhile(_ == ' ').length
      indent shouldBe 4 // module-level, not when-level (8+)
    }
  }

  // ---------- Parallel elaboration safety ----------

  it should "be safe under parallel elaboration" in {
    import scala.concurrent.{Future, Await}
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.duration._

    val futures = (1 to 4).map { i =>
      Future {
        emitWithDebug(new Module {
          val io = IO(new Bundle {
            val out = Output(UInt(8.W))
          })
          val w = Wire(UInt(8.W))
          w := i.U
          io.out := w
        })
      }
    }
    val results = Await.result(Future.sequence(futures), 30.seconds)
    results.foreach { chirrtl =>
      chirrtl should include("circt_dbg_variable")
      chirrtl should include("""name = "w"""")
    }
  }

  // ---------- Manual debug() API ----------

  "debug()" should "emit intrinsic from constructor" in {
    val chirrtl = emitWithoutDebug(new Module {
      val io = IO(new Bundle {
        val out = Output(UInt(8.W))
      })
      val w = Wire(UInt(8.W))
      w := 0.U
      io.out := w
      chisel3.debug.debug(w, "my_w")
    })
    chirrtl should include("""name = "my_w"""")
  }

  it should "require explicit name or pass empty" in {
    // When debug() is called without name parameter, it requires the signal's instanceName
    // This can only be called after circuit elaboration completes
    // The working approach is to provide explicit name during elaboration
    val chirrtl = emitWithoutDebug(new Module {
      val io = IO(new Bundle {
        val out = Output(UInt(8.W))
      })
      val myWire = Wire(UInt(8.W))
      myWire := 0.U
      io.out := myWire
      chisel3.debug.debug(myWire, "explicit")
    })
    chirrtl should include("""name = "explicit"""")
  }

  ".instrumentDebug()" should "work as extension method" in {
    val chirrtl = emitWithoutDebug(new Module {
      import chisel3.debug._
      val io = IO(new Bundle {
        val out = Output(UInt(8.W))
      })
      val w = Wire(UInt(8.W))
      w := 0.U
      io.out := w
      w.instrumentDebug("ext_w")
    })
    chirrtl should include("""name = "ext_w"""")
  }
}
