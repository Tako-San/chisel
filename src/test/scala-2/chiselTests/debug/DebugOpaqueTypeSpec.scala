// SPDX-License-Identifier: Apache-2.0

package chiselTests.debug

import chisel3._
import chisel3.experimental.OpaqueType
import chisel3.experimental.debug.DebugIntrinsics
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.immutable.SeqMap

class DebugOpaqueTypeSpec extends AnyFlatSpec with Matchers {

  behavior.of("OpaqueType with Debug Capture")

  class MyOpaqueType extends Record with OpaqueType {
    private val underlying = UInt(8.W)
    val elements = SeqMap("" -> underlying)
  }

  "DebugCapture" should "generate port intrinsics for OpaqueType IO ports" in {
    class OpaqueIOModule extends Module {
      val io = IO(new Bundle {
        val in = Input(new MyOpaqueType)
        val out = Output(new MyOpaqueType)
      })
      io.out := io.in
    }

    val chirrtl = ChiselStage.emitCHIRRTL(
      new OpaqueIOModule,
      Array("--capture-debug", "true")
    )

    chirrtl should include(s"intrinsic(${DebugIntrinsics.PortInfo}")
    chirrtl should include("name = \"io.in\"")
    chirrtl should include("name = \"io.out\"")

    chirrtl should include("type = \"MyOpaqueType\"")

    (chirrtl should not).include("name = \"io.in.\"")
    (chirrtl should not).include("name = \"io.out.\"")
  }

  it should "generate source intrinsics for internal OpaqueType fields" in {
    class OpaqueInternalModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val internal = Wire(new MyOpaqueType)
      io.out := io.in
    }

    val chirrtl = ChiselStage.emitCHIRRTL(
      new OpaqueInternalModule,
      Array("--capture-debug", "true")
    )

    chirrtl should include(s"intrinsic(${DebugIntrinsics.SourceInfo}")
    chirrtl should include("field_name = \"internal\"")
  }

  it should "handle OpaqueType nested in Bundle" in {
    class BundleWithOpaque extends Bundle {
      val regular = Input(UInt(4.W))
      val opaque = Input(new MyOpaqueType)
    }

    class NestedOpaqueModule extends Module {
      val io = IO(new BundleWithOpaque)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(
      new NestedOpaqueModule,
      Array("--capture-debug", "true")
    )

    chirrtl should include(s"intrinsic(${DebugIntrinsics.PortInfo}")
    chirrtl should include("name = \"io.regular\"")
    chirrtl should include("name = \"io.opaque\"")

    (chirrtl should not).include("name = \"io.opaque.\"")
  }

  it should "handle OpaqueType in DebugCapture directly" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val in = Input(new MyOpaqueType)
        val out = Output(new MyOpaqueType)
      })
      io.out := io.in
      val internal = Wire(new MyOpaqueType)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(
      new TestModule,
      Array("--capture-debug", "true")
    )

    chirrtl should include(s"intrinsic(${DebugIntrinsics.PortInfo}")
    chirrtl should include(s"intrinsic(${DebugIntrinsics.SourceInfo}")
  }

  it should "NOT recurse into OpaqueType internal elements" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val in = Input(new MyOpaqueType)
      })
      val wireOpaque = Wire(new MyOpaqueType)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(
      new TestModule,
      Array("--capture-debug", "true")
    )

    chirrtl should include("name = \"io.in\"")
    chirrtl should include("field_name = \"wireOpaque\"")

    (chirrtl should not).include("name = \"io.in.\"")
    (chirrtl should not).include("field_name = \"wireOpaque.\"")
  }
}
