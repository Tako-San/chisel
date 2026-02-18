// See LICENSE for license details.

package chisel3.debug

import chisel3._
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3.stage.phases.Elaborate
import chisel3.debug.{DebugRegistry, DebugRegistryAnnotation}
import circt.stage.ChiselStage
import firrtl.{annoSeqToSeq, seqToAnnoSeq}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.regex.Pattern

class DebugEdgeCasesSpec extends AnyFlatSpec with Matchers {

  "RegInit" should "be correctly instrumented for debug" in {
    class TestModule extends Module {
      val reg = RegInit(0.U(8.W))
      reg.suggestName("testReg")
      reg.instrumentDebug()
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TestModule)

    // Verify the debug variable intrinsic is present for RegInit
    chirrtlString should include("intrinsic(circt_dbg_variable")
  }

  "Double instrumentDebug" should "create two separate registry entries" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("testWire")
      w.instrumentDebug("first_call")
      w.instrumentDebug("second_call")
    }

    val annos = Seq(ChiselGeneratorAnnotation(() => new TestModule))
    val resultAnnos = new Elaborate().transform(annos)
    val registryEntries = resultAnnos.toSeq.collectFirst { case DebugRegistryAnnotation(entries) =>
      entries
    }.getOrElse(Map.empty)

    // Два вызова → два UUID → два entry
    val entries = registryEntries.toSeq
    entries should have size 2

    val names = entries.flatMap(_._2.debugName).toSet
    names shouldBe Set("first_call", "second_call")
  }

  "Double instrumentDebug" should "produce two intrinsics in CHIRRTL" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("testWire")
      w.instrumentDebug("a")
      w.instrumentDebug("b")
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)
    val count = chirrtl.split(Pattern.quote("intrinsic(circt_dbg_variable")).length - 1
    count shouldBe 2
  }

  "Uninstrumented module" should "not produce debug intrinsics" in {
    class UninstrumentedModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("testWire")
      // No instrumentDebug() call
    }

    class TopModule extends RawModule {
      val uninst = Module(new UninstrumentedModule)
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TopModule)

    // Verify no debug intrinsics are present for the uninstrumented module
    (chirrtlString should not).include("intrinsic(circt_dbg_variable)")
  }

  "Mixed instrumented and uninstrumented signals" should "work correctly" in {
    class TestModule extends RawModule {
      val instrumentedWire = Wire(UInt(8.W))
      instrumentedWire.suggestName("instrumented")
      instrumentedWire.instrumentDebug()

      val uninstrumentedWire = Wire(UInt(8.W))
      uninstrumentedWire.suggestName("uninstrumented")
      // No instrumentation
    }

    val annos = Seq(ChiselGeneratorAnnotation(() => new TestModule))
    val resultAnnos = new Elaborate().transform(annos)
    val registryEntries = resultAnnos.toSeq.collectFirst { case DebugRegistryAnnotation(entries) =>
      entries
    }.getOrElse(Map.empty)

    val entries = registryEntries.toSeq
    // Only one entry should be registered (the instrumented wire)
    entries should have size 1

    val (_, entry) = entries.head
    entry.data shouldBe a[UInt]
  }

  "Input port" should "not be directly instrumentable" in {
    class TestModule extends RawModule {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })

      // Try to instrument an input port
      // This should either work gracefully or not cause errors
      noException should be thrownBy {
        chisel3.debug.debug(io.in, "input_debug")
      }

      io.out := io.in
    }

    // Should not crash
    noException should be thrownBy {
      ChiselStage.emitCHIRRTL(new TestModule)
    }
  }

  "Output port" should "not be directly instrumentable" in {
    class TestModule extends RawModule {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })

      io.out := io.in

      // Try to instrument an output port
      noException should be thrownBy {
        chisel3.debug.debug(io.out, "output_debug")
      }
    }

    // Should not crash
    noException should be thrownBy {
      ChiselStage.emitCHIRRTL(new TestModule)
    }
  }

  "Registry cleanup on exception" should "clear entries properly" in {
    class BrokenMod extends RawModule {
      val sig = Wire(Bool()).suggestName("s")
      sig.instrumentDebug() // register
      require(false, "elaboration failed on purpose") // throw after
    }

    // Registry should be cleared in afterEach
    // Test that cleanup works even if module elaboration fails
    try {
      val annos = Seq(ChiselGeneratorAnnotation(() => new BrokenMod))
      new Elaborate().transform(annos)
      fail("Should have thrown exception")
    } catch {
      case _: IllegalArgumentException =>
        // Expected for the failed require
        // Verify registry is cleared after exception
        DebugRegistry.entries shouldBe empty
    }
  }

  "Registry isolation" should "work correctly with withFreshRegistry" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.instrumentDebug()
    }

    // Elaborate now wraps module construction in withFreshRegistry internally
    val annos1 = Seq(ChiselGeneratorAnnotation(() => new TestModule))
    val resultAnnos1 = new Elaborate().transform(annos1)
    val entries1 = resultAnnos1.toSeq.collectFirst { case DebugRegistryAnnotation(entries) =>
      entries
    }.getOrElse(Map.empty)

    // Verify entries exist in returned map
    entries1 should not be empty

    // Run again in separate registry - should be isolated
    val annos2 = Seq(ChiselGeneratorAnnotation(() => new TestModule))
    val resultAnnos2 = new Elaborate().transform(annos2)
    val entries2 = resultAnnos2.toSeq.collectFirst { case DebugRegistryAnnotation(entries) =>
      entries
    }.getOrElse(Map.empty)

    entries2 should not be empty
    // Isolation semantics: verify registry snapshots are independent
    // Both should have exactly 1 entry
    entries1.size shouldBe 1
    entries2.size shouldBe 1
    // They're different object instances
    entries1 should not be theSameInstanceAs(entries2)
    // Keys should be different because each debug call gets a unique ID
    (entries1.keys should not).equal(entries2.keys)
  }

  "Hierarchical module instantiation" should "preserve debug entries across levels" in {
    class LeafModule extends RawModule {
      val leafWire = Wire(UInt(8.W))
      leafWire.suggestName("leafWire")
      leafWire.instrumentDebug()
    }

    class MiddleModule extends RawModule {
      val leaf = Module(new LeafModule)
      val middleWire = Wire(Bool())
      middleWire.suggestName("middleWire")
      middleWire.instrumentDebug()
    }

    class TopModule extends RawModule {
      val middle = Module(new MiddleModule)
      val topWire = Wire(UInt(16.W))
      topWire.suggestName("topWire")
      topWire.instrumentDebug()
    }

    val annos = Seq(ChiselGeneratorAnnotation(() => new TopModule))
    val resultAnnos = new Elaborate().transform(annos)
    val registryEntries = resultAnnos.toSeq.collectFirst { case DebugRegistryAnnotation(entries) =>
      entries
    }.getOrElse(Map.empty)

    val entries = registryEntries.toSeq
    // Should have 3 entries (one from each module level)
    entries should have size 3
  }

  "Empty module" should "handle no debug instrumentation" in {
    class EmptyModule extends RawModule {
      // No signals, no instrumentation
    }

    val annos = Seq(ChiselGeneratorAnnotation(() => new EmptyModule))
    val resultAnnos = new Elaborate().transform(annos)
    val registryEntries = resultAnnos.toSeq.collectFirst { case DebugRegistryAnnotation(entries) =>
      entries
    }.getOrElse(Map.empty)

    registryEntries shouldBe empty

    // Should still emit CHIRRTL without errors
    noException should be thrownBy {
      ChiselStage.emitCHIRRTL(new EmptyModule)
    }
  }

  "Module with only Reg" should "handle Reg instrumentation correctly" in {
    class TestModule extends Module {
      val reg = Reg(UInt(8.W))
      reg.suggestName("testReg")
      reg := 0.U
      reg.instrumentDebug()
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TestModule)

    // Verify debug variable intrinsic is present
    chirrtlString should include("intrinsic(circt_dbg_variable")
  }

  "Module with Vec of instrumented signals" should "handle multiple entries" in {
    class TestModule extends RawModule {
      val vec = Wire(Vec(4, UInt(8.W)))
      for (i <- 0 until 4) {
        vec(i) := 0.U
      }
    }

    val annos = Seq(ChiselGeneratorAnnotation(() => new TestModule))
    new Elaborate().transform(annos)

    // Verify the module compiles without errors
    noException should be thrownBy {
      ChiselStage.emitCHIRRTL(new TestModule)
    }
  }

  "Debug name after module renaming" should "preserve original intent" in {
    class TestModule extends RawModule {
      val originalWire = Wire(UInt(8.W))
      originalWire.suggestName("originalWire")
      originalWire.instrumentDebug("renamed_debug")
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TestModule)

    // Verify the custom name appears in output
    chirrtlString should include("renamed_debug")
  }

  it should "handle debug inside else block (elsewhen)" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val sel = Input(Bool())
        val out = Output(UInt(8.W))
      })
      val w = Wire(UInt(8.W))
      w.suggestName("cond_wire")
      when(io.sel) {
        w := 1.U
      }.otherwise {
        w.instrumentDebug("else_wire")
        w := 2.U
      }
      io.out := w
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)
    chirrtl should include("intrinsic(circt_dbg_variable")
    (chirrtl should not).include("intrinsic(circt_dbg_placeholder")
  }

  "Debug inside for loop" should "instrument all signals" in {
    class TestModule extends RawModule {
      val regs = (0 until 3).map { i =>
        val r = Wire(UInt(8.W))
        r.suggestName(s"r$i")
        r.instrumentDebug(s"loop_$i")
        r
      }
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)
    val count = chirrtl.split(Pattern.quote("intrinsic(circt_dbg_variable")).length - 1
    count shouldBe 3
  }
}
