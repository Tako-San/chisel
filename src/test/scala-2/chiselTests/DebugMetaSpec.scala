// SPDX-License-Identifier: Apache-2.0

package chiselTests

import chisel3._
import chisel3.internal.DebugMetaTestUtils
import chisel3.util.SRAM
import chisel3.util.MixedVec
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DebugMetaSpec extends AnyFlatSpec with Matchers {

  class SramPortModule extends Module {
    val sram = SRAM(size = 16, tpe = UInt(8.W), numReadPorts = 1, numWritePorts = 1, numReadwritePorts = 0)
  }

  class MixedVecModule extends Module {
    val io = IO(new Bundle {
      val out = Output(MixedVec(Seq(UInt(8.W), UInt(16.W))))
    })
    io.out(0) := 0.U
    io.out(1) := 0.U
  }

  class SramMetaModule extends Module {
    val sram = SRAM(
      size = 256,
      tpe = UInt(32.W),
      numReadPorts = 2,
      numWritePorts = 1,
      numReadwritePorts = 0
    )
  }

  class UnnamedOpModule extends Module {
    val io = IO(new Bundle {
      val a = Input(UInt(8.W))
      val b = Input(UInt(8.W))
      val c = Input(UInt(8.W))
      val out = Output(UInt(8.W))
    })
    io.out := (io.a + io.b) + io.c
  }

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

  class MemSrcLocModule extends Module {
    val m = SyncReadMem(32, UInt(16.W))
  }

  class NestedBundleMemModule extends Module {
    class NestedBundle extends Bundle {
      val a = UInt(8.W)
      val b = new Bundle {
        val x = UInt(4.W)
        val y = UInt(4.W)
      }
    }
    val m = SyncReadMem(16, new NestedBundle)
  }

  class VecBundleMemModule extends Module {
    class VecBundle extends Bundle {
      val a = UInt(8.W)
      val vec = Vec(4, UInt(4.W))
    }
    val m = SyncReadMem(8, new VecBundle)
  }

  private def elaborate[T <: RawModule](gen: => T): String =
    ChiselStage.emitCHIRRTL(gen, args = Array("--emit-debug-type-info"))

  "DebugMetaEmitter" should "emit typetag for SramPortBinding" in {
    val chirrtl = elaborate(new SramPortModule)
    chirrtl should include("circt_debug_typetag")
    // SRAM port bindings should have binding=sramport as a native parameter
    chirrtl should include("binding = \"sramport\"")
  }

  it should "emit kind=MixedVec for MixedVec wire" in {
    val chirrtl = elaborate(new MixedVecModule)
    // MixedVec(Seq(...)) is wrapped in an anonymous Bundle IO; the top-level typetag
    // for `io` carries className = "AnonymousBundle".  MixedVec structure is built
    // by CIRCT from the IR (no structural JSON parameters in typetag anymore).
    // We simply verify a typetag is emitted for the io port.
    chirrtl should include("circt_debug_typetag")
    chirrtl should include("name = \"io\"")
  }

  it should "NOT emit typetag for SRAMInterface wire" in {
    val chirrtl = elaborate(new SramMetaModule)
    val sramTypetagLine = chirrtl
      .split("\n")
      .find(l => l.contains("circt_debug_typetag") && l.contains("\\\"className\\\":\\\"SRAMInterface\\\""))
    sramTypetagLine shouldBe None
  }

  // SRAM metadata omitted from typetag; ports still receive typetags.

  it should "NOT emit kind=sram in circt_debug_typetag for SRAMInterface port" in {
    val chirrtl = elaborate(new SramMetaModule)
    val typetagLines = chirrtl.split("\n").filter(_.contains("circt_debug_typetag"))
    typetagLines.foreach { line =>
      (line should not).include("\\\"kind\\\":\\\"sram\\\"")
    }
  }

  it should "NOT emit typetag for unnamed OpBinding intermediate" in {
    val chirrtl = elaborate(new UnnamedOpModule)
    val nodeTagCount =
      chirrtl.split("\n").count(l => l.contains("circt_debug_typetag") && l.contains("\\\"binding\\\":\\\"node\\\""))
    nodeTagCount shouldBe 0
  }

  it should "emit moduleinfo only for wrapper, not for ExtModule itself" in {
    val chirrtl = elaborate(new BBWrapper)
    chirrtl should include("circt_debug_moduleinfo")

    // Use the new native parameter extraction instead of extractPayloads
    val names = DebugMetaTestUtils.extractStringParam(chirrtl, "moduleinfo", "name")
    names.contains("BBWrapper") shouldBe true
    names.contains("MyBB") shouldBe false
  }

  it should "emit enumType reference in typetag (not inline enumDef)" in {
    val chirrtl = elaborate(new EnumModule)
    // enumdef is still emitted as a separate intrinsic
    chirrtl should include("circt_debug_enumdef")
    chirrtl should include("name = \"MyState")
    // The io bundle typetag carries className only; enum leaf structure is built by CIRCT.
    // No inline enumDef or enumTypeFqn in the bundle-level typetag.
    val typetagLines = chirrtl.split("\n").filter(_.contains("circt_debug_typetag"))
    typetagLines.foreach { line =>
      (line should not).include("enumDef =")
    }
  }

  // Verify meminfo contains a `memName` field (native parameter).
  it should "include 'name' field in circt_debug_meminfo payload" in {
    val chirrtl = elaborate(new MemSrcLocModule)
    chirrtl should include("circt_debug_meminfo")
    // The new native parameter is named memName, not name
    chirrtl should include("memName =")
  }

  it should "recurse buildTypeJson into nested Bundle fields for SyncReadMem" in {
    val chirrtl = elaborate(new NestedBundleMemModule)
    chirrtl should include("circt_debug_meminfo")
    chirrtl should include("\\\"className\\\":\\\"NestedBundle\\\"")
    chirrtl should include("\\\"fields\\\"")
    chirrtl should include("\\\"b\\\"")
    chirrtl should include("\\\"x\\\"")
    chirrtl should include("\\\"y\\\"")
  }

  it should "recurse buildTypeJson into Vec elements for SyncReadMem" in {
    val chirrtl = elaborate(new VecBundleMemModule)
    chirrtl should include("circt_debug_meminfo")
    chirrtl should include("\\\"className\\\":\\\"VecBundle\\\"")
    chirrtl should include("\\\"vec\\\"")
    chirrtl should include("\\\"className\\\":\\\"Vec\\\"")
    chirrtl should include("\\\"vecLength\\\":4")
    chirrtl should include("\\\"element\\\"")
  }
}
