// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.sys.process._
import java.nio.file._
import java.nio.charset.StandardCharsets

class FirtoolCompatibilitySpec extends AnyFlatSpec with Matchers {

  // ── Module definitions ──
  class TestModule extends RawModule {
    val in = IO(Input(UInt(8.W)))
    val out = IO(Output(UInt(8.W)))
    out := in
  }

  // ── Test: Compatibility with firtool using --allow-unrecognized-intrinsic ──
  it should "accept CHIRRTL with debug intrinsics via --allow-unrecognized-intrinsic" in {
    // Use ChiselStage to emit CHIRRTL with debug type info
    val chirrtl = ChiselStage.emitCHIRRTL(
      gen = new TestModule,
      args = Array("--emit-debug-type-info")
    )

    // Verify the emitted CHIRRTL contains the debug intrinsic
    chirrtl should include("FIRRTL version")
    chirrtl should include("module")
    chirrtl should include("circt_debug_typetag")

    // Write the emitted IR to a temporary file
    val tempDir = Files.createTempDirectory("firtool-roundtrip-")
    val chirrtlFile = tempDir.resolve("test.chirrtl")
    Files.write(chirrtlFile, chirrtl.getBytes(StandardCharsets.UTF_8))

    try {
      // Execute firtool on the file with --allow-unrecognized-intrinsic flag
      val outputDir = tempDir.resolve("output")
      Files.createDirectories(outputDir)

      // Build the firtool command
      val firtoolCmd = Seq(
        "firtool",
        chirrtlFile.toString,
        "--allow-unrecognized-intrinsic",
        "-o",
        outputDir.resolve("output.ir").toString
      )

      // Execute the command and capture the exit code
      val stderrBuf = new StringBuilder
      val processLogger = ProcessLogger(
        stdout => (), // Ignore stdout
        stderr => stderrBuf.append(stderr).append("\n")
      )

      val exitCode: Int =
        try {
          Process(firtoolCmd).!(processLogger)
        } catch {
          case _: java.io.IOException =>
            // firtool command not found, assume unsupported
            assume(false, "firtool not found in PATH")
            -1
        }

      // If firtool returns a non-zero exit code indicating the flag is unsupported,
      // use assume(false, ...) to skip the test
      if (exitCode != 0) {
        info(s"firtool stderr:\n$stderrBuf")
        // The flag may not be supported; check if the error message indicates this
        val errorOutput =
          try {
            Process(Seq("firtool", "--help")).!!.toLowerCase
          } catch {
            case _: Exception => ""
          }

        if (!errorOutput.contains("allow-unrecognized-intrinsic")) {
          assume(false, "firtool does not support --allow-unrecognized-intrinsic")
        }
      }

      // Assert that the exit code is 0, meaning the round‑trip succeeded
      exitCode shouldBe 0
    } finally {
      // Clean up temporary files
      try {
        Files.walkFileTree(
          tempDir,
          new SimpleFileVisitor[Path] {
            override def visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes) = {
              Files.delete(file)
              FileVisitResult.CONTINUE
            }
            override def postVisitDirectory(dir: Path, exc: java.io.IOException) = {
              Files.delete(dir)
              FileVisitResult.CONTINUE
            }
          }
        )
      } catch {
        case ex: Exception =>
          // Log cleanup errors but don't fail the test
          System.err.println(
            s"[FirtoolRoundTripSpec] Warning: Failed to cleanup temp directory $tempDir: ${ex.getMessage}"
          )
      }
    }
  }
}
