// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import circt.stage.ChiselStage
import ujson.{read, Obj}

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

    // Parse the intrinsics to check className for anonymous Bundle
    val intrinsics: Seq[(String, Obj)] = output.linesIterator.collect {
      case line if line.contains("circt_debug_typetag") =>
        val jsonStr = line
          .replaceAll(".*circt_debug_typetag<info = \"", "")
          .replaceAll("\">.*", "")
          .replace("\\\"", "\"")
        val json = ujson.read(jsonStr).asInstanceOf[Obj]
        (line, json)
    }.toSeq

    // Find the intrinsic for the io port (anonymous Bundle)
    val ioIntrinsic = intrinsics.find { case (line, json) =>
      // The io port will have a "fields" key (it's a Bundle/Record)
      json.obj.contains("fields")
    }

    ioIntrinsic should not be None

    val (_, json) = ioIntrinsic.get
    val className = json("className").str

    // Before the fix, className would be ""
    // After the fix, className should be "AnonymousBundle"
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

    val intrinsics: Seq[Obj] = output.linesIterator.collect {
      case line if line.contains("circt_debug_typetag") =>
        val jsonStr = line
          .replaceAll(".*circt_debug_typetag<info = \"", "")
          .replaceAll("\">.*", "")
          .replace("\\\"", "\"")
        ujson.read(jsonStr).asInstanceOf[Obj]
    }.toSeq

    val ioIntrinsic = intrinsics.find(json => json.obj.contains("fields") && json("className").str == "MyBundle")

    ioIntrinsic should not be None

    val json = ioIntrinsic.get
    val className = json("className").str
    className shouldBe "MyBundle"
  }
}
