// See LICENSE for license details.

package chisel3.debug

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.regex.Pattern

class DebugIntegrationSpec extends AnyFlatSpec with Matchers {

  "Full Pipeline" should "emit correct debug variable intrinsic with all metadata" in {

    // Define a module with multiple instrumented signals
    class InnerModule extends RawModule {
      val sig = Wire(Bool()).suggestName("innerSig")
      sig.instrumentDebug()

      val vec = Wire(Vec(4, UInt(8.W))).suggestName("myVec")
      vec.instrumentDebug()
    }

    class TopModule extends RawModule {
      val inner = Module(new InnerModule)
    }

    // Use ChiselStage to emit the full CHIRRTL
    val chirrtlString = ChiselStage.emitCHIRRTL(new TopModule)

    // Verify the placeholder intrinsic is not in the output
    (chirrtlString should not).include("intrinsic(circt_dbg_placeholder)")

    // Verify the debug variable intrinsic is present
    chirrtlString should include("intrinsic(circt_dbg_variable")

    // Verify hierarchical path is present
    chirrtlString should include("TopModule.inner")

    // Count debug variable intrinsics in CHIRRTL output
    val count = chirrtlString.split(Pattern.quote("intrinsic(circt_dbg_variable")).length - 1
    count shouldBe 2
  }

  "Full Pipeline" should "emit correct type information for complex data types" in {

    class MyBundle extends Bundle {
      val a = UInt(8.W)
      val b = SInt(16.W)
      val c = Vec(4, Bool())
    }

    // Test with complex data types
    class TopModule extends RawModule {
      val myBundle = Wire(new MyBundle).suggestName("myBundle")
      myBundle.instrumentDebug()
    }

    // Emit CHIRRTL
    val chirrtlString = ChiselStage.emitCHIRRTL(new TopModule)

    // Verify the debug variable intrinsic is present
    chirrtlString should include("intrinsic(circt_dbg_variable")

    // Verify type information is present in the CHIRRTL output
    chirrtlString should include("type = \"MyBundle\"")
  }

  "Full Pipeline" should "handle parameter extraction for modules with parameters" in {

    class ParamModule(val width: Int, val depth: Int) extends RawModule {
      val data = Wire(UInt(width.W)).suggestName("paramData")
      data.instrumentDebug()
    }

    // Test with parameterized modules
    class TopModule extends RawModule {
      val inner = Module(new ParamModule(width = 8, depth = 16))
    }

    // Emit CHIRRTL
    val chirrtlString = ChiselStage.emitCHIRRTL(new TopModule)

    // Verify the debug variable intrinsic is present
    chirrtlString should include("intrinsic(circt_dbg_variable")

    // Verify the correct hierarchical path is present
    chirrtlString should include("TopModule.inner.paramData")

    // Verify the type field contains the width information
    chirrtlString should include("type = \"UInt<8>\"")

    chirrtlString should include("params =")
    // These assertions verify JSON content within the CHIRRTL output.
    // The escaped strings ("\\\"") are the correct escape for literal double quotes in Scala.
    chirrtlString should include("\\\"width\\\"")
    chirrtlString should include("\\\"depth\\\"")
  }

  "Full Pipeline" should "handle multiple signals in same module" in {

    // Test with multiple signals
    class TopModule extends RawModule {
      val sig1 = Wire(Bool()).suggestName("sig1")
      sig1.instrumentDebug()

      val sig2 = Wire(UInt(8.W)).suggestName("sig2")
      sig2.instrumentDebug()

      val sig3 = Wire(SInt(16.W)).suggestName("sig3")
      sig3.instrumentDebug()
    }

    // Emit CHIRRTL
    val chirrtlString = ChiselStage.emitCHIRRTL(new TopModule)

    // Verify all three signals have debug intrinsics
    val count = chirrtlString.split(Pattern.quote("intrinsic(circt_dbg_variable")).length - 1
    count shouldBe 3
  }

  "Full Pipeline" should "include path for hierarchical signals" in {
    class SubModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("subWire")
      w.instrumentDebug()
    }

    class TopModule extends RawModule {
      val sub = Module(new SubModule)
      val w = Wire(Bool())
      w.suggestName("topWire")
      w.instrumentDebug()
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TopModule)

    // Verify path parameter is present and includes hierarchy
    chirrtlString should include("path = \"TopModule.sub.subWire\"")
    chirrtlString should include("path = \"TopModule.topWire\"")
  }

  "Full Pipeline" should "handle nested module parameters" in {
    class InnerModule(depth: Int) extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("innerWire")
      w.instrumentDebug()
    }

    class MiddleModule(count: Int) extends RawModule {
      val inner = Module(new InnerModule(depth = count))
      val w = Wire(UInt(16.W))
      w.suggestName("middleWire")
      w.instrumentDebug()
    }

    class TopModule extends RawModule {
      val middle = Module(new MiddleModule(count = 10))
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TopModule)

    // Should have params for each module
    chirrtlString should include("params =")
    // Should have path information for nested modules
    chirrtlString should include("TopModule.middle")
  }

  "Full Pipeline" should "preserve type information for complex data types" in {
    class ComplexBundle extends Bundle {
      val a = UInt(8.W)
      val b = SInt(16.W)
      val c = Bool()
    }

    class ComplexModule extends RawModule {
      val myBundle = Wire(new ComplexBundle).suggestName("myBundle")
      myBundle.instrumentDebug()
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new ComplexModule)

    // Verify type is preserved for complex types
    chirrtlString should include("type = \"ComplexBundle\"")
  }

  "Full Pipeline" should "be valid JSON for all entries" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("testWire")
      w.instrumentDebug()

      val b = Wire(Bool())
      b.suggestName("testBool")
      b.instrumentDebug()

      val v = Wire(Vec(2, UInt(8.W)))
      v.suggestName("testVec")
      v.instrumentDebug()
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TestModule)

    // All entries should have valid JSON structure
    // Extract JSON strings from the output
    val jsonMatches = "params = \"([^\"]+)\"".r.findAllMatchIn(chirrtlString).toList
    jsonMatches should not be empty

    // Simple structural validation without deprecated JSON parsers
    jsonMatches.foreach { m =>
      val json = m.group(1)
      json should startWith("{")
      json should endWith("}")
      (json should not).include("}{") // no malformed nested blocks
    }
  }
}
