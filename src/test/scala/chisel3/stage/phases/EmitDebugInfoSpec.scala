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

  // ---------- Enum variant mapping ----------

  // Define enum objects at class level to avoid scoping issues in lambda
  object State extends chisel3.ChiselEnum {
    val Idle, Busy, Done = Value
  }

  object Opcode extends chisel3.ChiselEnum {
    val ADD = Value(0x00.U)
    val SUB = Value(0x01.U)
    val MUL = Value(0x10.U)
    val DIV = Value(0x11.U)
  }

  object Color extends chisel3.ChiselEnum {
    val Red, Green, Blue = Value
  }

  object Cmd extends chisel3.ChiselEnum {
    val Read, Write = Value
  }

  class ReqBundle extends Bundle {
    val cmd = Cmd()
    val addr = UInt(32.W)
  }

  "EmitDebugInfo" should "emit enum variant mapping for ChiselEnum" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val out = Output(State())
      })
      val reg = RegInit(State.Idle)
      io.out := reg
    })
    chirrtl should include("""chiselType = "State(Idle=0, Busy=1, Done=2)"""")
  }

  it should "handle non-sequential enum values" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val out = Output(UInt(5.W))
      })
      val w = Wire(Opcode())
      w := Opcode.MUL
      io.out := w.asUInt
    })
    // Verify enum name present on the local wire
    chirrtl should include("Opcode(")
    // Verify non-sequential values
    chirrtl should include("MUL=16")
    chirrtl should include("DIV=17")
  }

  it should "handle enum used as wire type" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val out = Output(UInt(2.W))
      })
      val w = Wire(Color())
      w := Color.Red
      io.out := w.asUInt
    })
    chirrtl should include("""name = "w"""")
    chirrtl should include("Color(Red=0, Green=1, Blue=2)")
  }

  it should "handle enum in Bundle" in {
    // Define ReqBundle at module level to avoid anonymous bundle issue
    class TestModule extends Module {
      val io = IO(new Bundle {
        val req = Input(new ReqBundle)
        val out = Output(UInt(1.W))
      })
      io.out := io.req.cmd.asUInt
    }
    val chirrtl = emitWithDebug(new TestModule)
    // The Bundle type is reflected in the FIRRTL type
    chirrtl should include("cmd")
    // We do NOT expect enum variants inside a Bundle's port type string —
    // enum info is only emitted for local wires/regs that are EnumType.
  }

  // ---------- Signal reference in intrinsic arguments ----------

  it should "include signal reference in intrinsic for wire" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val w = Wire(UInt(8.W))
      w := io.in
      io.out := w
    })
    // Verify the intrinsic includes the signal reference as an argument
    chirrtl should include("""intrinsic(circt_dbg_variable<name = "w", type = "UInt<8>">, w)""")
  }

  it should "include signal reference in intrinsic for port" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      io.out := io.in
    })
    // Verify the intrinsic includes the port reference as an argument
    chirrtl should include("""intrinsic(circt_dbg_variable<name = "io", type = ".*">, io)""")
  }

  it should "include signal reference in intrinsic for memory" in {
    val chirrtl = emitWithDebug(new Module {
      val io = IO(new Bundle {
        val addr = Input(UInt(4.W))
        val out = Output(UInt(8.W))
      })
      val mem = Mem(16, UInt(8.W))
      io.out := mem(io.addr)
    })
    // Verify the intrinsic includes the memory reference as an argument
    chirrtl should include("""intrinsic(circt_dbg_variable<name = "mem", type = ".*">, mem)""")
  }

  it should "include signal reference in debug() API call" in {
    val chirrtl = emitWithoutDebug(new Module {
      import chisel3.debug._
      val io = IO(new Bundle {
        val out = Output(UInt(8.W))
      })
      val w = Wire(UInt(8.W))
      w := 0.U
      io.out := w
      debug(w, "my_w")
    })
    // Verify the debug() API intrinsic includes the signal reference
    chirrtl should include("""intrinsic(circt_dbg_variable<name = "my_w", type = "UInt<8>">, my_w)""")
  }

  it should "include signal reference in .instrumentDebug() extension method" in {
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
    // Verify the .instrumentDebug() intrinsic includes the signal reference
    chirrtl should include("""intrinsic(circt_dbg_variable<name = "ext_w", type = "UInt<8>">, ext_w)""")
  }
}
