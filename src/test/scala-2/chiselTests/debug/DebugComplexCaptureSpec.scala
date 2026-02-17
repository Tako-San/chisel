package chiselTests.debug

import chisel3._
import chisel3.experimental.debug.{DebugCapture, DebugIntrinsics}
import circt.stage.ChiselStage
import logger.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ComplexDebugCaptureSpec extends AnyFlatSpec with Matchers with LazyLogging {

  // ===== CircuitTraverserTest tests =====

  "DebugCapture" should "generate port intrinsics" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
      })
      DebugCapture.capture(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)
    logger.info(chirrtl)

    chirrtl should include(DebugIntrinsics.PortInfo)
    chirrtl should include("direction = \"INPUT\"")
  }

  it should "annotate registers" in {
    class TestModule extends Module {
      val reg = RegInit(0.U(8.W))
      DebugCapture.capture(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)
    chirrtl should include(DebugIntrinsics.SourceInfo)
    chirrtl should include("field_name = \"reg\"")
  }

  it should "annotate wires" in {
    class TestModule extends Module {
      val wire = Wire(UInt(8.W))
      wire := 0.U
      DebugCapture.capture(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)
    chirrtl should include(DebugIntrinsics.SourceInfo)
    chirrtl should include("field_name = \"wire\"")
  }

  it should "annotate bundle wire elements" in {
    class MyBundle extends Bundle {
      val a = UInt(8.W)
      val b = UInt(4.W)
    }
    class TestModule extends Module {
      val bundleWire = Wire(new MyBundle)
      bundleWire.a := 1.U
      bundleWire.b := 2.U
      DebugCapture.capture(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)
    chirrtl should include(DebugIntrinsics.SourceInfo)
    chirrtl should include("field_name = \"bundleWire.a\"")
    chirrtl should include("field_name = \"bundleWire.b\"")
  }

  it should "not emit self-alias for internal aggregate" in {
    class TestModule extends Module {
      val in = IO(Input(UInt(8.W)))
      val out = IO(Output(UInt(8.W)))

      // Internal aggregate that should NOT produce self-alias
      val bundleWire = Wire(new Bundle {
        val field1 = UInt(8.W)
        val field2 = UInt(4.W)
      })
      bundleWire.field1 := in
      out := bundleWire.field2
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule, Array("--capture-debug", "true"))

    // Verify no self-alias exists (name == target)
    (chirrtl should not).include(
      s"""intrinsic(${DebugIntrinsics.AliasInfo}, name = "bundleWire", target = "bundleWire""""
    )
  }

  // ===== RecursiveDataTest tests =====

  it should "recursively annotate nested bundle ports" in {
    class InnerBundle extends Bundle {
      val x = Input(UInt(8.W))
      val y = Input(UInt(4.W))
    }

    class OuterBundle extends Bundle {
      val a = Input(UInt(16.W))
      val b = Input(new InnerBundle)
      val c = Output(Bool())
    }

    class TestModule extends Module {
      val io = IO(new OuterBundle)
      DebugCapture.capture(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    chirrtl should include(DebugIntrinsics.PortInfo)
    chirrtl should include("name = \"io.a\"")
    chirrtl should include("name = \"io.b.x\"")
    chirrtl should include("name = \"io.b.y\"")
    chirrtl should include("name = \"io.c\"")

    chirrtl should include("type = \"UInt<16>\"")
    chirrtl should include("type = \"UInt<8>\"")
    chirrtl should include("type = \"UInt<4>\"")
    chirrtl should include("type = \"Bool\"")

    chirrtl should include("direction = \"INPUT\"")
    chirrtl should include("direction = \"OUTPUT\"")
  }

  it should "recursively annotate vector ports" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val vecIn = Input(Vec(4, UInt(8.W)))
        val vecOut = Output(Vec(2, Bool()))
      })
      DebugCapture.capture(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    chirrtl should include(DebugIntrinsics.PortInfo)
    chirrtl should include("name = \"io.vecIn[0]\"")
    chirrtl should include("name = \"io.vecIn[1]\"")
    chirrtl should include("name = \"io.vecOut[0]\"")
    chirrtl should include("name = \"io.vecOut[1]\"")

    chirrtl should include("type = \"UInt<8>\"")
    chirrtl should include("type = \"Bool\"")
  }

  it should "recursively annotate nested bundle internal data" in {
    class InnerBundle extends Bundle {
      val x = UInt(8.W)
      val y = UInt(4.W)
    }

    class TestModule extends Module {
      val bundleWire = Wire(new InnerBundle)
      bundleWire.x := 1.U
      bundleWire.y := 2.U
      DebugCapture.capture(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    chirrtl should include(DebugIntrinsics.SourceInfo)
    chirrtl should include("field_name = \"bundleWire.x\"")
    chirrtl should include("field_name = \"bundleWire.y\"")
  }

  it should "recursively annotate vector internal data" in {
    class TestModule extends Module {
      val vecWire = Wire(Vec(3, UInt(8.W)))
      vecWire(0) := 0.U
      vecWire(1) := 1.U
      vecWire(2) := 2.U
      DebugCapture.capture(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    chirrtl should include(DebugIntrinsics.SourceInfo)
    chirrtl should include("field_name = \"vecWire[0]\"")
    chirrtl should include("field_name = \"vecWire[1]\"")
    chirrtl should include("field_name = \"vecWire[2]\"")
  }

  it should "recursively annotate deeply nested structures" in {
    class Inner extends Bundle {
      val a = UInt(4.W)
    }

    class Middle extends Bundle {
      val inner = new Inner
      val vecInner = Vec(2, new Inner)
    }

    class Outer extends Bundle {
      val middle = new Middle
      val vecMiddle = Vec(2, new Middle)
    }

    class TestModule extends Module {
      val io = IO(new Outer)
      DebugCapture.capture(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    chirrtl should include("name = \"io.middle.inner.a\"")
    chirrtl should include("name = \"io.middle.vecInner[0].a\"")
    chirrtl should include("name = \"io.vecMiddle[0].inner.a\"")
    chirrtl should include("name = \"io.vecMiddle[0].vecInner[0].a\"")
  }

  it should "not explode with large vectors" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val bigVec = Input(Vec(1100, UInt(1.W)))
      })
      DebugCapture.capture(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule)

    val (has1023, has1024) =
      (chirrtl.contains("name = \"io.bigVec[1023]\""), chirrtl.contains("name = \"io.bigVec[1024]\""))
    has1023 shouldBe true
    has1024 shouldBe false
  }

  // ===== MemorySpec tests =====

  it should "annotate SyncReadMem" in {
    class TestModule extends Module {
      val ram = SyncReadMem(1024, UInt(32.W))
    }
    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule, Array("--capture-debug", "true"))
    chirrtl should include(s"intrinsic(${DebugIntrinsics.Memory}")
    chirrtl should include("name = \"ram\"")
    chirrtl should include("depth = \"1024\"")
    chirrtl should include("type = \"UInt<32>\"")
  }

  it should "emit exactly one memory intrinsic per memory" in {
    class TestModule extends Module {
      val addr = IO(Input(UInt(8.W)))
      val dataIn = IO(Input(UInt(32.W)))
      val dataOut = IO(Output(UInt(32.W)))

      val mem = SyncReadMem(256, UInt(32.W))
      dataOut := mem.read(addr)
      when(true.B) {
        mem.write(addr, dataIn)
      }
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule, Array("--capture-debug", "true"))

    // Count memory intrinsics - should be exactly 1
    val count = s"intrinsic\\(${DebugIntrinsics.Memory}".r.findAllMatchIn(chirrtl).length
    count shouldBe 1
  }

  it should "include source location parameters in intrinsics" in {
    class TestModule extends Module {
      val in = IO(Input(UInt(8.W)))
      val out = IO(Output(UInt(8.W)))

      // Wire should have command.sourceInfo from DefWire command
      val myWire = Wire(UInt(8.W))
      myWire := in

      // Reg should have command.sourceInfo from DefReg command
      val myReg = RegInit(UInt(8.W), init = 0.U)
      myReg := myWire
      out := myReg

      // SyncReadMem should have command.sourceInfo from DefMemory command
      val mem = SyncReadMem(256, UInt(8.W))
      when(true.B) {
        mem.write(in, myWire)
      }
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule, Array("--capture-debug", "true"))

    // Verify source location params are present for Wire/Reg (source_info intrinsic)
    chirrtl should include("source_file")
    chirrtl should include("source_line")

    // Verify memory intrinsic also has location params
    val memoryWithSourceFile = s"intrinsic\\(${DebugIntrinsics.Memory}.*source_file".r.findFirstIn(chirrtl)
    val memoryWithSourceLine = s"intrinsic\\(${DebugIntrinsics.Memory}.*source_line".r.findFirstIn(chirrtl)

    memoryWithSourceFile.isDefined shouldBe true
    memoryWithSourceLine.isDefined shouldBe true
  }

  it should "propagate source location to aggregate fields" in {
    class BundleWithFields extends Bundle {
      val fieldA = UInt(8.W)
    }
    class TestModule extends Module {
      // This line should appear in the intrinsic for fieldA
      val myWire = Wire(new BundleWithFields)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new TestModule, Array("--capture-debug", "true"))

    // Look for intrinsic specifically for the field, not the wire itself
    // Check that field_name = "myWire.fieldA" has source_file/line parameters
    val fieldIntrinsicPattern =
      raw"""intrinsic\(chisel\.debug\.source_info.*field_name = "myWire\.fieldA".*source_line""".r

    fieldIntrinsicPattern.findFirstIn(chirrtl) should be(defined)
  }
}
