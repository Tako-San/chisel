// SPDX-License-Identifier: Apache-2.0

package circtTests.stage

import circt.stage.ChiselStage
import chisel3._
import chisel3.stage.{ChiselGeneratorAnnotation, CaptureDebugInfoAnnotation}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DebugCaptureSpec extends AnyFlatSpec with Matchers {

  "ChiselStage with CaptureDebugInfoAnnotation" should "support capture-debug=true option" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val x = Output(UInt(8.W))
      })
      io.x := 0.U
    }

    val result = (new ChiselStage).execute(
      Array("--target", "chirrtl", "--capture-debug", "true"),
      Seq(
        ChiselGeneratorAnnotation(() => new TestModule)
      )
    )

    // Should have the annotation in result
    result.collectFirst { case c: CaptureDebugInfoAnnotation =>
      c.enabled should be(true)
    }.getOrElse(fail("CaptureDebugInfoAnnotation not found in result"))
  }

  it should "support capture-debug=false option" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val x = Output(UInt(8.W))
      })
      io.x := 0.U
    }

    val result = (new ChiselStage).execute(
      Array("--target", "chirrtl", "--capture-debug", "false"),
      Seq(
        ChiselGeneratorAnnotation(() => new TestModule)
      )
    )

    // Should still have the annotation but disabled
    result.collectFirst { case c: CaptureDebugInfoAnnotation =>
      c.enabled should be(false)
    }.getOrElse(fail("CaptureDebugInfoAnnotation not found in result"))
  }

  it should "not include the annotation by default" in {
    class TestModule extends Module {
      val io = IO(new Bundle {
        val x = Output(UInt(8.W))
      })
      io.x := 0.U
    }

    val result = (new ChiselStage).execute(
      Array("--target", "chirrtl"),
      Seq(
        ChiselGeneratorAnnotation(() => new TestModule)
      )
    )

    // Should not have the annotation
    result.collectFirst { case _: CaptureDebugInfoAnnotation =>
      fail("CaptureDebugInfoAnnotation should not be present by default")
    }
  }
}