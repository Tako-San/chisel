// SPDX-License-Identifier: Apache-2.0

package chiselTests

import chisel3._
import chisel3.util.SRAM
import chisel3.util.MixedVec
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DebugMetaSpec extends AnyFlatSpec with Matchers {

  private def elaborate[T <: RawModule](gen: => T): String =
    ChiselStage.emitCHIRRTL(gen, args = Array("--emit-debug-type-info"))

  "DebugMetaEmitter" should "emit typetag for SramPortBinding" in {
    class SramPortModule extends Module {
      val sram = SRAM(size = 16, tpe = UInt(8.W), numReadPorts = 1, numWritePorts = 1, numReadwritePorts = 0)
    }
    val chirrtl = elaborate(new SramPortModule)
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("\\\"binding\\\":\\\"sramport\\\"")
  }

  it should "emit typetag for OpBinding (Mux result)" in {
    class OpBindingModule extends Module {
      val io = IO(new Bundle {
        val a = Input(UInt(8.W))
        val b = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val selected = Mux(io.a > io.b, io.a, io.b)
      io.out := selected
    }
    val chirrtl = elaborate(new OpBindingModule)
    chirrtl should include("\\\"binding\\\":\\\"node\\\"")
  }

  it should "emit kind=MixedVec for MixedVec wire" in {
    class MixedVecModule extends Module {
      val io = IO(new Bundle {
        val out = Output(MixedVec(Seq(UInt(8.W), UInt(16.W))))
      })
      io.out(0) := 0.U
      io.out(1) := 0.U
    }
    val chirrtl = elaborate(new MixedVecModule)
    chirrtl should include("\\\"kind\\\":\\\"MixedVec\\\"")
  }

  it should "emit kind=sram with depth and port counts for SRAMInterface" in {
    class SramMetaModule extends Module {
      val sram = SRAM(
        size = 256,
        tpe = UInt(32.W),
        numReadPorts = 2,
        numWritePorts = 1,
        numReadwritePorts = 0
      )
    }
    val chirrtl = elaborate(new SramMetaModule)
    // The JSON is embedded in the intrinsic command with escaped quotes
    chirrtl should include("\\\"kind\\\":\\\"sram\\\"")
    chirrtl should include("\\\"depth\\\":256")
    chirrtl should include("\\\"numReadPorts\\\":2")
    chirrtl should include("\\\"numWritePorts\\\":1")
    chirrtl should include("\\\"numRWPorts\\\":0")
  }

  it should "emit maskGranularity for masked SRAM" in {
    class MaskedSramModule extends Module {
      val sram = SRAM.masked(
        size = 64,
        tpe = Vec(4, UInt(8.W)),
        numReadPorts = 1,
        numWritePorts = 1,
        numReadwritePorts = 0
      )
    }
    val chirrtl = elaborate(new MaskedSramModule)
    chirrtl should include("\\\"masked\\\":true")
    chirrtl should include("\\\"maskGranularity\\\":8")
  }

  it should "emit typetag for named OpBinding val" in {
    class NamedOpModule extends Module {
      val io = IO(new Bundle {
        val a = Input(UInt(8.W))
        val b = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val sum = io.a + io.b
      io.out := sum
    }
    val chirrtl = elaborate(new NamedOpModule)
    chirrtl should include("\\\"binding\\\":\\\"node\\\"")
  }

  it should "NOT emit typetag for unnamed OpBinding intermediate" in {
    class UnnamedOpModule extends Module {
      val io = IO(new Bundle {
        val a = Input(UInt(8.W))
        val b = Input(UInt(8.W))
        val c = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      io.out := (io.a + io.b) + io.c
    }
    val chirrtl = elaborate(new UnnamedOpModule)
    val nodeTagCount =
      chirrtl.split("\n").count(l => l.contains("circt_debug_typetag") && l.contains("\\\"binding\\\":\\\"node\\\""))
    nodeTagCount shouldBe 0
  }

  it should "emit moduleinfo for ExtModule" in {
    class MyBB extends ExtModule {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
    }
    class BBWrapper extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val bb = Module(new MyBB)
      bb.io.in := io.in
      io.out := bb.io.out
    }
    val chirrtl = elaborate(new BBWrapper)
    chirrtl should include("circt_debug_moduleinfo")
    chirrtl should include("MyBB")
  }

  it should "emit enumType reference in typetag (not inline enumDef)" in {
    object MyState extends ChiselEnum {
      val Idle = Value
      val Running = Value
      val Stopped = Value
    }

    class EnumModule extends Module {
      val io = IO(new Bundle {
        val stateInput = Input(MyState())
        val stateOutput = Output(MyState())
      })
      io.stateOutput := io.stateInput
    }

    val chirrtl = elaborate(new EnumModule)
    // Should contain the separate enumdef intrinsic
    chirrtl should include("circt_debug_enumdef")
    // Enum name may have a suffix (e.g., MyState$1) in Scala 2 due to anonymous class naming
    chirrtl should include("\\\"name\\\":\\\"MyState")

    // Should have enumType reference(s) in the output
    // These are embedded in the bundle field structure JSON for enum type fields
    chirrtl should include("\\\"enumType\\\"")

    // Inline definition should NOT be in typetag
    val typetagLines = chirrtl.split("\n").filter(_.contains("circt_debug_typetag"))
    typetagLines.foreach { line =>
      (line should not).include("\"enumDef\"")
    }
  }
}
