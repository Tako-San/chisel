// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import circt.stage.ChiselStage
import ujson.Obj

class AnonymousBundleSpec extends AnyFlatSpec with Matchers {
  behavior.of("AnonymousBundle naming in debug metadata")

  "AnonymousBundle debug metadata" should "have className = 'AnonymousBundle' not empty string" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val x = Input(UInt(4.W))
        val y = Output(Vec(2, Bool()))
      })
      io.y(0) := io.x(0)
      io.y(1) := io.x(1)
    }

    val output = ChiselStage.emitCHIRRTL(new TestModule, args = Array("--emit-debug-type-info"))

    val intrinsics: Seq[Obj] = DebugMetaTestUtils
      .extractPayloads(output, "circt_debug_typetag")
      .map(_.asInstanceOf[Obj])

    val ioIntrinsicJson = intrinsics.find(_.obj.contains("fields")).get
    val className = ioIntrinsicJson("className").str

    className should not be ""
    className shouldBe "AnonymousBundle"
  }

  "NamedBundle debug metadata" should "preserve the actual class name" in {
    class MyBundle extends Bundle {
      val a = Input(UInt(8.W))
      val b = Output(Bool())
    }

    class TestModule extends Module {
      val io = IO(new MyBundle)
      val reg = RegInit(0.U(8.W))
      reg := io.a
      io.b := reg(0)
    }

    val output = ChiselStage.emitCHIRRTL(new TestModule, args = Array("--emit-debug-type-info"))

    val intrinsics: Seq[Obj] = DebugMetaTestUtils
      .extractPayloads(output, "circt_debug_typetag")
      .map(_.asInstanceOf[Obj])

    val ioIntrinsic = intrinsics.find(json => json.obj.contains("fields") && json("className").str == "MyBundle")
    ioIntrinsic should not be None
    ioIntrinsic.get("className").str shouldBe "MyBundle"
  }
}
