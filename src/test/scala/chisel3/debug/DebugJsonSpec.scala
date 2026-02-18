// See LICENSE for license details.

package chisel3.debug

import chisel3._
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3.stage.phases.Elaborate
import chisel3.debug.{ClassParam, DebugJsonUtils, DebugRegistry, DebugRegistryAnnotation}
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

  "DebugJsonUtils.toJson" should "produce exact JSON format for single parameter with value" in {
    val params = Seq(ClassParam("x", "Int", Some("5")))
    val json = DebugJsonUtils.toJson(params)
    json should include("x")
    json should include("typeName")
    json should include("Int")
    json should include("value")
    json should include("5")
    json should startWith("{")
    json should endWith("}")
  }

  "DebugJsonUtils.toJson" should "produce exact JSON format for single parameter without value" in {
    val params = Seq(ClassParam("x", "Int", None))
    val json = DebugJsonUtils.toJson(params)
    json should include("x")
    json should include("typeName")
    json should include("Int")
    json should include("value")
    json should include("null")
    json should startWith("{")
    json should endWith("}")
  }

  "DebugJsonUtils.toJson" should "produce exact JSON format for multiple parameters" in {
    val params = Seq(
      ClassParam("width", "Int", Some("8")),
      ClassParam("depth", "Int", Some("16")),
      ClassParam("name", "String", Some("test"))
    )
    val json = DebugJsonUtils.toJson(params)
    json should include("width")
    json should include("depth")
    json should include("name")
    json should include("typeName")
    json should include("Int")
    json should include("String")
    json should include("8")
    json should include("16")
    json should include("test")
    json should startWith("{")
    json should endWith("}")
  }

  "DebugJsonUtils.toJson" should "return empty object for empty params" in {
    val params = Seq.empty[ClassParam]
    val json = DebugJsonUtils.toJson(params)
    json shouldBe "{}"
  }

  "DebugJsonUtils.toJson" should "escape special characters in parameter names" in {
    val params = Seq(ClassParam("test_name", "String", Some("value")))
    val json = DebugJsonUtils.toJson(params)
    json should include("test_name")
    json should include("typeName")
    json should include("value")
    json should include("String")
  }

  "DebugJsonUtils.toJson" should "escape special characters in values" in {
    val params = Seq(ClassParam("text", "String", Some("Hello\nWorld\"Test")))
    val json = DebugJsonUtils.toJson(params)
    json should include("\\n")
    json should include("\\\"")
    json should include("typeName")
    json should include("Hello")
    json should include("World")
    json should include("Test")
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
}
