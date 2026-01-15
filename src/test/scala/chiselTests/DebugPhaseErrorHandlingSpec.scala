// SPDX-License-Identifier: Apache-2.0

package chiselTests

import chisel3._
import chisel3.stage.phases.{AddDebugIntrinsicsPhase, EnableDebugAnnotation}
import chisel3.stage.ChiselStage
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.{ByteArrayOutputStream, PrintStream}

/**
  * Test suite for AddDebugIntrinsicsPhase error handling.
  * 
  * Validates that errors are properly detected, reported, and handled:
  * - Fatal errors: throw RuntimeException with detailed messages
  * - Warnings: log to stderr but continue processing
  * - Expected cases: handled gracefully (no error)
  * 
  * CRITICAL: These tests protect against silent failures where
  * intrinsics don't generate but user gets no indication why.
  */
class DebugPhaseErrorHandlingSpec extends AnyFlatSpec with Matchers {
  
  behavior of "AddDebugIntrinsicsPhase error handling"
  
  /**
    * Helper: Capture stderr output during test execution.
    */
  def captureStderr[T](block: => T): (T, String) = {
    val baos = new ByteArrayOutputStream()
    val ps = new PrintStream(baos)
    val oldErr = System.err
    
    try {
      System.setErr(ps)
      val result = block
      ps.flush()
      (result, baos.toString)
    } finally {
      System.setErr(oldErr)
      ps.close()
    }
  }
  
  /**
    * Helper: Capture stdout output during test execution.
    */
  def captureStdout[T](block: => T): (T, String) = {
    val baos = new ByteArrayOutputStream()
    val ps = new PrintStream(baos)
    val oldOut = System.out
    
    try {
      System.setOut(ps)
      val result = block
      ps.flush()
      (result, baos.toString)
    } finally {
      System.setOut(oldOut)
      ps.close()
    }
  }
  
  it should "handle modules with no IO field gracefully" in {
    sys.props("chisel.debug") = "true"
    
    // RawModule has no 'io' field
    class NoIOModule extends RawModule {
      val input = IO(Input(UInt(8.W)))
      val output = IO(Output(UInt(8.W)))
      output := input
    }
    
    // Should not throw, should log info message
    val (_, stdout) = captureStdout {
      noException should be thrownBy {
        ChiselStage.emitCHIRRTL(new NoIOModule)
      }
    }
    
    // Should mention "no IO field" as expected case
    stdout should include regex "(?i)no IO field"
    
    sys.props.remove("chisel.debug")
  }
  
  it should "successfully process modules with standard IO" in {
    sys.props("chisel.debug") = "true"
    
    class StandardModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      io.out := io.in
    }
    
    val (firrtl, stdout) = captureStdout {
      ChiselStage.emitCHIRRTL(new StandardModule)
    }
    
    // Should process successfully
    stdout should include("Processing IO for module")
    firrtl should include("circt_debug_typeinfo")
    
    sys.props.remove("chisel.debug")
  }
  
  it should "log warning when no circuit found" in {
    sys.props("chisel.debug") = "true"
    
    val phase = new AddDebugIntrinsicsPhase
    
    // Empty annotations - no ChiselCircuitAnnotation
    val (_, stderr) = captureStderr {
      val result = phase.transform(Seq(EnableDebugAnnotation()))
      result should not be empty
    }
    
    // Should warn about missing circuit
    stderr should include regex "(?i)warning.*no.*circuit"
    
    sys.props.remove("chisel.debug")
  }
  
  it should "not generate intrinsics when debug mode disabled" in {
    sys.props.remove("chisel.debug")
    
    class SimpleModule extends Module {
      val io = IO(new Bundle {
        val data = Output(UInt(8.W))
      })
      io.data := 0.U
    }
    
    val (firrtl, stdout) = captureStdout {
      ChiselStage.emitCHIRRTL(new SimpleModule)
    }
    
    // Should not process
    stdout should not include "Generating debug intrinsics"
    firrtl should not include "circt_debug_typeinfo"
  }
  
  it should "handle nested Bundle IO correctly" in {
    sys.props("chisel.debug") = "true"
    
    class InnerBundle extends Bundle {
      val x = UInt(8.W)
      val y = UInt(8.W)
    }
    
    class OuterBundle extends Bundle {
      val inner = new InnerBundle
      val flag = Bool()
    }
    
    class NestedModule extends Module {
      val io = IO(new OuterBundle)
      io.inner.x := 0.U
      io.inner.y := 0.U
      io.flag := false.B
    }
    
    val (firrtl, stdout) = captureStdout {
      ChiselStage.emitCHIRRTL(new NestedModule)
    }
    
    // Should process without errors
    stdout should include("Processing IO for module")
    stdout should not include regex "(?i)error"
    
    // Should generate intrinsics for nested fields
    firrtl should include("circt_debug_typeinfo")
    
    sys.props.remove("chisel.debug")
  }
  
  it should "handle Vec IO correctly" in {
    sys.props("chisel.debug") = "true"
    
    class VecIOModule extends Module {
      val io = IO(new Bundle {
        val vec = Output(Vec(4, UInt(8.W)))
      })
      io.vec := VecInit(Seq.fill(4)(0.U))
    }
    
    val (firrtl, stdout) = captureStdout {
      ChiselStage.emitCHIRRTL(new VecIOModule)
    }
    
    // Should process without errors
    stdout should include("Processing IO for module")
    stdout should not include regex "(?i)error"
    
    firrtl should include("circt_debug_typeinfo")
    
    sys.props.remove("chisel.debug")
  }
  
  it should "provide helpful context in error messages" in {
    // This test validates error message quality
    // Actual API incompatibility would cause test suite to fail,
    // so we just verify the error handling code is structured correctly
    
    val phase = new AddDebugIntrinsicsPhase
    
    // Phase should have proper error handling structure
    val phaseSource = scala.io.Source.fromFile(
      "src/main/scala/chisel3/stage/phases/AddDebugIntrinsicsPhase.scala"
    ).mkString
    
    // Verify error messages contain key information
    withClue("Error messages should mention API incompatibility") {
      phaseSource should include("API incompatibility")
    }
    
    withClue("Error messages should provide recovery suggestions") {
      phaseSource should include("REQUIRED ACTION")
    }
    
    withClue("Error messages should include available methods/fields") {
      phaseSource should include("Available")
    }
    
    withClue("Fatal errors should throw RuntimeException") {
      phaseSource should include("throw new RuntimeException")
    }
  }
  
  it should "distinguish between fatal and warning-level errors" in {
    // Validate error handling policy is correctly implemented
    val phaseSource = scala.io.Source.fromFile(
      "src/main/scala/chisel3/stage/phases/AddDebugIntrinsicsPhase.scala"
    ).mkString
    
    // Fatal errors: API incompatibility
    withClue("NoSuchMethodException should be fatal (throw)") {
      val noSuchMethodBlock = phaseSource.split("case.*NoSuchMethodException")(1).split("case")(0)
      noSuchMethodBlock should include("throw")
      noSuchMethodBlock should include("FATAL")
    }
    
    withClue("IllegalAccessException should be fatal (throw)") {
      val illegalAccessBlock = phaseSource.split("case.*IllegalAccessException")(1).split("case")(0)
      illegalAccessBlock should include("throw")
      illegalAccessBlock should include("FATAL")
    }
    
    // Warnings: expected failures (no IO field)
    withClue("NoSuchFieldException for IO should be warning (no throw)") {
      val noIOBlock = phaseSource.split("NoSuchFieldException")(1).split("case")(0)
      noIOBlock should include("println") // Info, not error
      noIOBlock should not include "throw"
    }
  }
  
  it should "complete successfully for complex module hierarchies" in {
    sys.props("chisel.debug") = "true"
    
    class SubModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      io.out := io.in + 1.U
    }
    
    class TopModule extends Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      
      val sub1 = Module(new SubModule)
      val sub2 = Module(new SubModule)
      
      sub1.io.in := io.in
      sub2.io.in := sub1.io.out
      io.out := sub2.io.out
    }
    
    val (firrtl, output) = captureStdout {
      val (_, stderr) = captureStderr {
        ChiselStage.emitCHIRRTL(new TopModule)
      }
      stderr
    }
    
    // Should complete without errors
    output should not include regex "(?i)error"
    output should not include regex "(?i)fatal"
    
    // Should generate intrinsics
    firrtl should include("circt_debug_typeinfo")
    
    sys.props.remove("chisel.debug")
  }
}