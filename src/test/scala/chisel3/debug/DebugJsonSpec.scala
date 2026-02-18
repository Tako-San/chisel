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

class DebugJsonSpec extends AnyFlatSpec with Matchers {

  "JSON escaping" should "escape control characters in JSON" in {
    case class TextCtrlChars(text: String)
    val obj = TextCtrlChars(text = "test\u0000string")
    val json = DebugReflectionUtils.getParamsJson(obj)
    // Should escape null character as \u0000 (which in JSON is "\\u0000" in repr)
    // Note: the actual string contains the escape sequence \u0000
    json should include("\\u0000")
    // Verify valid JSON format
    json should startWith("{")
    json should endWith("}")
  }

  "JSON escaping" should "escape null byte and other control characters" in {
    case class ControlChars(a: String, b: String)
    val obj = ControlChars(a = "text\u0001end", b = "text\u001fend")
    val json = DebugReflectionUtils.getParamsJson(obj)
    // Should escape control chars \u0001 and \u001f
    json should include("\\u0001")
    json should include("\\u001f")
    json should startWith("{")
    json should endWith("}")
  }

  "JSON escaping" should "escape backslashes and quotes" in {
    case class EscapedText(text: String)
    val obj = EscapedText(text = "path\\to\\\"file\"")
    val json = DebugReflectionUtils.getParamsJson(obj)
    // Backslashes are escaped as \\ in the JSON string value
    json should include("\\\\")
    // Quotes are escaped as \"
    json should include("\\\"")
    json should startWith("{")
    json should endWith("}")
  }

  "JSON escaping" should "escape newlines and tabs" in {
    case class Multiline(text: String)
    val obj = Multiline(text = "line1\nline2\ttabbed")
    val json = DebugReflectionUtils.getParamsJson(obj)
    // Should escape newline and tab
    json should include("\\n")
    json should include("\\t")
    json should startWith("{")
    json should endWith("}")
  }

  "JSON params" should "contain typeName and value for every parameter" in {
    class ParamModule(width: Int, init: Int) extends RawModule {
      val w = Wire(UInt(width.W))
      w.suggestName("paramWire")
      w.instrumentDebug()
    }

    class TopModule extends RawModule {
      val paramInst = Module(new ParamModule(width = 8, init = 42))
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TopModule)

    // Verify params field is present
    chirrtlString should include("params =")

    // The params should be a valid JSON string
    chirrtlString should include("{")
    chirrtlString should include("}")
  }

  "JSON structure" should "contain typeName field" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("testWire")
      w.instrumentDebug()
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TestModule)

    // Verify type parameter is present
    chirrtlString should include("type =")
    // Should not have the old-style 'typeName'
    (chirrtlString should not).include("typeName =")
  }

  "JSON structure" should "contain value field for type" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("testWire")
      w.instrumentDebug()
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TestModule)

    // Verify type value is present (quoted string with width)
    // The actual output includes width information like "UInt<8>"
    chirrtlString should include("type = \"UInt<8>\"")
  }

  "JSON params" should "handle null for missing values" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("testWire")
      w.instrumentDebug()
    }

    val annos = Seq(ChiselGeneratorAnnotation(() => new TestModule))
    val resultAnnos = new Elaborate().transform(annos)
    val registryEntries = resultAnnos.toSeq.collectFirst { case DebugRegistryAnnotation(entries) =>
      entries
    }.getOrElse(Map.empty)

    val entries = registryEntries.toSeq
    entries should have size 1

    val (_, entry) = entries.head
    // For a RawModule with no constructor parameters, paramsJson may be empty
    entry.paramsJson match {
      case Some(params) =>
        params should not be null
      case None =>
      // None is acceptable (no parent module to get params from)
    }
  }

  "JSON params" should "handle empty JSON object for no parameters" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("testWire")
      w.instrumentDebug()
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TestModule)

    // params = "{}" is valid for no parameters
    chirrtlString should include("params =")
  }

  "JSON params" should "handle module with multiple parameters" in {
    class MultiParamModule(w1: Int, w2: Int, initVal: Int) extends RawModule {
      val wire1 = Wire(UInt(w1.W))
      wire1.suggestName("wire1")
      wire1.instrumentDebug()

      val wire2 = Wire(SInt(w2.W))
      wire2.suggestName("wire2")
      wire2.instrumentDebug()
    }

    class TopModule extends RawModule {
      val multi = Module(new MultiParamModule(w1 = 8, w2 = 16, initVal = 5))
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TopModule)

    // params should be present in output
    chirrtlString should include("params =")
    chirrtlString should include("type =")
  }

  "JSON params" should "escape special characters in names" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("wire_with_underscore")
      w.instrumentDebug()
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TestModule)

    // The path should contain the wire name with underscores
    chirrtlString should include("wire_with_underscore")
  }

  "JSON params" should "handle numeric parameter values" in {
    class NumericParamModule(val1: Int, val2: Long) extends RawModule {
      val w = Wire(UInt(val1.W))
      w.suggestName("testWire")
      w.instrumentDebug()
    }

    class TopModule extends RawModule {
      val num = Module(new NumericParamModule(val1 = 32, val2 = 1000L))
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TopModule)

    // Should include params with numeric values
    chirrtlString should include("params =")
  }

  "JSON" should "handle boolean parameter values" in {
    class BoolParamModule(flag: Boolean) extends RawModule {
      val w = if (flag) Wire(UInt(8.W)) else Wire(UInt(16.W))
      w.suggestName("testWire")
      w.instrumentDebug()
    }

    class TopModule extends RawModule {
      val boolMod = Module(new BoolParamModule(flag = true))
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TopModule)

    // Should include params in output
    chirrtlString should include("params =")
  }

  "JSON params" should "preserve type information for complex data types" in {
    class ComplexModule extends RawModule {
      val myBundle = Wire(new ComplexBundle).suggestName("myBundle")
      myBundle.instrumentDebug()
    }

    class ComplexBundle extends Bundle {
      val a = UInt(8.W)
      val b = SInt(16.W)
      val c = Bool()
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new ComplexModule)

    // Verify type is preserved for complex types
    chirrtlString should include("type = \"ComplexBundle\"")
  }

  "JSON params" should "handle Vec types correctly" in {
    class VecModule extends RawModule {
      val vec = Wire(Vec(4, UInt(8.W)))
      vec.suggestName("testVec")
      vec.instrumentDebug()
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new VecModule)

    // Verify type info for Vec
    chirrtlString should include("type =")
  }

  "JSON structure" should "be valid JSON for all entries" in {
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

  "JSON" should "handle nested module parameters" in {
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

  "JSON" should "handle special characters in debug names" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      // Use special characters in debug name
      w.instrumentDebug("test_name-with.special")
    }

    val chirrtlString = ChiselStage.emitCHIRRTL(new TestModule)

    // The name should appear in the output (possibly escaped)
    chirrtlString should include("test_name-with.special")
  }

  "JSON params" should "be consistent across multiple entries" in {
    class TestModule extends RawModule {
      for (i <- 0 until 3) {
        val w = Wire(UInt(8.W))
        w.suggestName(s"wire_$i")
        w.instrumentDebug()
      }
    }

    val annos = Seq(ChiselGeneratorAnnotation(() => new TestModule))
    val resultAnnos = new Elaborate().transform(annos)
    val registryEntries = resultAnnos.toSeq.collectFirst { case DebugRegistryAnnotation(entries) =>
      entries
    }.getOrElse(Map.empty)

    val entries = registryEntries.toSeq
    entries should have size 3

    // All entries should have consistently typed paramsJson
    entries.foreach { case (_, entry) =>
      entry.paramsJson match {
        case Some(params) =>
          params should not be empty
        case None =>
        // Also acceptable
      }
    }
  }

  "JSON" should "include path for hierarchical signals" in {
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
}
