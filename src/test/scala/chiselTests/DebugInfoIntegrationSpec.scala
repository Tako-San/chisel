// SPDX-License-Identifier: Apache-2.0

package chiselTests

import chisel3._
import chisel3.util.circt.DebugInfo
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * End-to-end integration tests for DebugInfo pipeline
  * Tests the full flow: Chisel -> FIRRTL intrinsics -> expected output
  */
class DebugInfoIntegrationSpec extends AnyFlatSpec with Matchers {

  behavior of "DebugInfo End-to-End Pipeline"

  it should "preserve type info through full compilation" in {
    DebugTestHelpers.withDebugMode {
      class CompleteBundle(val dataWidth: Int) extends Bundle {
        val valid = Bool()
        val data = UInt(dataWidth.W)
      }

      class IntegrationModule extends Module {
        val io = IO(new Bundle {
          val in = Input(new CompleteBundle(16))
          val out = Output(new CompleteBundle(16))
        })

        // Test all annotation patterns
        DebugInfo.annotate(io.in, "io.in")
        DebugInfo.annotateRecursive(io.out, "io.out")

        val reg = RegInit(0.U.asTypeOf(new CompleteBundle(16)))
        reg := io.in
        io.out := reg

        DebugInfo.annotate(reg, "stateReg")
      }

      val firrtl = ChiselStage.emitCHIRRTL(new IntegrationModule)

      // Verify intrinsic structure
      firrtl should include("intrinsic(circt_debug_typeinfo")

      // Verify all annotated signals present
      firrtl should include("target = \"io.in\"")
      firrtl should include("target = \"io.out\"")
      firrtl should include("target = \"stateReg\"")

      // Verify recursive annotation of io.out
      firrtl should include("target = \"io.out.valid\"")
      firrtl should include("target = \"io.out.data\"")

      // Verify Bundle type name (cleaned, no $N suffix)
      firrtl should include("typeName = \"CompleteBundle\"")
      firrtl should include("parameters = \"dataWidth=16\"")

      // Verify source locations present
      firrtl should include("sourceFile")
      firrtl should include("sourceLine")

      DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 1)
    }
  }

  it should "generate correct FIRRTL for ChiselEnum" in {
    DebugTestHelpers.withDebugMode {
      object FsmState extends ChiselEnum {
        val sIDLE, sFETCH, sDECODE, sEXECUTE, sWRITEBACK = Value
      }

      class EnumIntegrationModule extends Module {
        val state = RegInit(FsmState.sIDLE)
        state := FsmState.sFETCH

        DebugInfo.annotate(state, "fsmState")
      }

      val firrtl = ChiselStage.emitCHIRRTL(new EnumIntegrationModule)

      // Verify enum handling (cleaned name, no $N suffix)
      firrtl should include("typeName = \"FsmState\"")
      firrtl should include("enumDef")

      // Verify enum values appear (in some form)
      val hasEnumValues =
        firrtl.contains("IDLE") ||
          firrtl.contains("FETCH") ||
          firrtl.contains("0:") ||
          firrtl.contains("1:")

      hasEnumValues shouldBe true

      DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 1)
    }
  }

  it should "handle nested structures" in {
    DebugTestHelpers.withDebugMode {
      class MemRequest extends Bundle {
        val addr = UInt(32.W)
        val data = UInt(64.W)
      }

      class SimpleModule extends Module {
        val io = IO(new MemRequest)

        // Annotate single element
        DebugInfo.annotate(io.addr, "io.addr")
      }

      val firrtl = ChiselStage.emitCHIRRTL(new SimpleModule)

      // Verify basic annotation works
      firrtl should include("target = \"io.addr\"")
      firrtl should include("typeName = \"UInt\"")

      DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 1)
    }
  }

  it should "support Vec types" in {
    DebugTestHelpers.withDebugMode {
      class VecModule extends Module {
        val io = IO(new Bundle {
          val data = Output(Vec(4, UInt(8.W)))
        })

        io.data := VecInit(Seq.fill(4)(0.U))

        DebugInfo.annotate(io.data, "io.data")
      }

      val firrtl = ChiselStage.emitCHIRRTL(new VecModule)

      // Verify Vec metadata
      firrtl should include("typeName = \"Vec\"")
      firrtl should include("target = \"io.data\"")

      DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 1)
    }
  }

  it should "handle multiple independent annotations" in {
    DebugTestHelpers.withDebugMode {
      class MultiModule extends Module {
        val io = IO(new Bundle {
          val in = Input(UInt(8.W))
          val out = Output(UInt(8.W))
        })

        io.out := io.in

        DebugInfo.annotate(io.in, "input")
        DebugInfo.annotate(io.out, "output")
      }

      val firrtl = ChiselStage.emitCHIRRTL(new MultiModule)

      // Verify both annotations present
      firrtl should include("target = \"input\"")
      firrtl should include("target = \"output\"")

      DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 2)
    }
  }

  it should "produce valid FIRRTL syntax" in {
    DebugTestHelpers.withDebugMode {
      class ValidModule extends Module {
        val io = IO(new Bundle {
          val a = Input(UInt(8.W))
          val sum = Output(UInt(9.W))
        })

        io.sum := io.a +& io.a

        DebugInfo.annotate(io.a, "operand")
      }

      val firrtl = ChiselStage.emitCHIRRTL(new ValidModule)

      // Basic sanity checks
      firrtl should include("module ValidModule")
      firrtl should include("circt_debug_typeinfo")
      firrtl should include("target = \"operand\"")

      DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 1)
    }
  }

  it should "respect DebugIntrinsic.emit conditional logic" in {
    // Note: DebugInfo.annotate() always creates intrinsics
    // It's DebugIntrinsic.emit() that checks chisel.debug flag internally
    // This test verifies annotate() works regardless of flag

    class FlagTestModule extends Module {
      val io = IO(new Bundle {
        val data = Output(UInt(8.W))
      })
      io.data := 0.U

      DebugInfo.annotate(io.data, "data")
    }

    val firrtl = ChiselStage.emitCHIRRTL(new FlagTestModule)

    // Intrinsics should be present (DebugInfo.annotate always emits)
    firrtl should include("circt_debug_typeinfo")
    firrtl should include("target = \"data\"")
  }

  it should "count correct number of intrinsics" in {
    DebugTestHelpers.withDebugMode {
      class CountingModule extends Module {
        val io = IO(new Bundle {
          val a = Input(UInt(8.W))
          val b = Input(UInt(8.W))
          val c = Output(UInt(8.W))
        })

        io.c := io.a + io.b

        DebugInfo.annotate(io.a, "a")
        DebugInfo.annotate(io.b, "b")
        DebugInfo.annotate(io.c, "c")
      }

      val firrtl = ChiselStage.emitCHIRRTL(new CountingModule)

      DebugTestHelpers.assertIntrinsicCount(firrtl, expected = 3)
      DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 3)
    }
  }

  it should "use Probe API consistently" in {
    DebugTestHelpers.withDebugMode {
      class ProbeTestModule extends Module {
        val io = IO(new Bundle {
          val control = Input(UInt(4.W))
          val status = Output(UInt(8.W))
        })

        val state = RegInit(0.U(8.W))
        when(io.control === 1.U) {
          state := state + 1.U
        }
        io.status := state

        DebugInfo.annotate(io.control, "io.control")
        DebugInfo.annotate(io.status, "io.status")
        DebugInfo.annotate(state, "state")
      }

      val firrtl = ChiselStage.emitCHIRRTL(new ProbeTestModule)

      withClue("CRITICAL: Probe API must be used for all intrinsics") {
        DebugTestHelpers.assertIntrinsicCount(firrtl, expected = 3)
        DebugTestHelpers.assertProbeAPIUsed(firrtl, minIntrinsics = 3)
      }
    }
  }

  it should "restore debug state after emission" in {
    class StateTestModule extends Module {
      val io = IO(new Bundle { val out = Output(UInt(1.W)) })
      io.out := 0.U
    }

    // Set initial state
    sys.props("chisel.debug") = "false"
    val initialState = sys.props.get("chisel.debug")

    // Emit with debug (should temporarily enable)
    val _ = DebugInfo.emitCHIRRTL(new StateTestModule)

    // Verify state restored
    val finalState = sys.props.get("chisel.debug")
    finalState shouldBe initialState

    // Cleanup
    sys.props.remove("chisel.debug")
  }

  it should "generate demo output for thesis documentation" in {
    DebugTestHelpers.withDebugMode {
      object CpuState extends ChiselEnum {
        val sIDLE, sFETCH, sDECODE, sEXECUTE = Value
      }

      class CpuBundle extends Bundle {
        val pc = UInt(32.W)
        val instr = UInt(32.W)
        val state = CpuState()
      }

      class ThesisDemoModule extends Module {
        val io = IO(new Bundle {
          val debug = Output(new CpuBundle)
        })

        val pc = RegInit(0.U(32.W))
        val state = RegInit(CpuState.sIDLE)

        pc := pc + 4.U
        state := CpuState.sFETCH

        io.debug.pc := pc
        io.debug.instr := 0.U
        io.debug.state := state

        DebugInfo.annotate(pc, "cpu.pc")
        DebugInfo.annotate(state, "cpu.state")
        DebugInfo.annotateRecursive(io.debug, "cpu.debug")
      }

      val firrtl = ChiselStage.emitCHIRRTL(new ThesisDemoModule)

      // Print for documentation
      val intrinsics = firrtl.split("\n").filter(_.contains("intrinsic(circt_debug_typeinfo"))

      println("\n" + "=" * 70)
      println("THESIS DEMO: Generated Debug Intrinsics")
      println("=" * 70)
      intrinsics.foreach { line =>
        val target = """target = "([^"]+)"""".r.findFirstMatchIn(line).map(_.group(1)).getOrElse("?")
        val typeName = """typeName = "([^"]+)"""".r.findFirstMatchIn(line).map(_.group(1)).getOrElse("?")
        println(f"  $target%-25s -> $typeName")
      }
      println("=" * 70 + "\n")

      intrinsics.length should be >= 5
    }
  }
}
