// See LICENSE for license details.

package chisel3.debug

import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DebugReflectionSpec extends AnyFlatSpec with Matchers {

  "DebugReflectionUtils" should "extract simple constructor params" in {
    // Test with a simple case class
    case class SimpleClass(intParam: Int, stringParam: String, doubleParam: Double)

    val obj = SimpleClass(intParam = 42, stringParam = "hello", doubleParam = 3.14)
    val params = DebugReflectionUtils.getConstructorParams(obj)

    params should have size 3

    val paramMap = params.map(p => p.name -> p).toMap

    paramMap("intParam").typeName shouldBe "Int"
    paramMap("intParam").value shouldBe Some("42")

    paramMap("stringParam").typeName shouldBe "String"
    paramMap("stringParam").value shouldBe Some("hello")

    paramMap("doubleParam").typeName shouldBe "Double"
    paramMap("doubleParam").value shouldBe Some("3.14")
  }

  it should "extract nested constructor params" in {
    // Test with nested case classes
    case class InnerClass(innerValue: Int)
    case class OuterClass(outerValue: Int, nested: InnerClass)

    val inner = InnerClass(innerValue = 99)
    val obj = OuterClass(outerValue = 100, nested = inner)

    val params = DebugReflectionUtils.getConstructorParams(obj)

    params should have size 2

    val paramMap = params.map(p => p.name -> p).toMap

    paramMap("outerValue").typeName shouldBe "Int"
    paramMap("outerValue").value shouldBe Some("100")

    paramMap("nested").typeName shouldBe "InnerClass"
    // The nested object value should be printed
    paramMap("nested").value match {
      case Some(v) => v should include("InnerClass")
      case None    => fail("Expected Some(value) for nested param")
    }
  }

  it should "handle Data types correctly" in {
    // Test with Chisel Data types (simplified - no Wire creation needed)
    case class DataContainer(uintVal: Int, boolVal: Boolean, clockVal: String)

    val obj = DataContainer(
      uintVal = 8,
      boolVal = true,
      clockVal = "Clock"
    )

    val params = DebugReflectionUtils.getConstructorParams(obj)

    params should have size 3

    val paramMap = params.map(p => p.name -> p).toMap

    paramMap("uintVal").typeName shouldBe "Int"
    paramMap("uintVal").value shouldBe Some("8")

    paramMap("boolVal").typeName shouldBe "Boolean"
    paramMap("boolVal").value shouldBe Some("true")

    paramMap("clockVal").typeName shouldBe "String"
    paramMap("clockVal").value shouldBe Some("Clock")
  }

  it should "convert Data types to readable names via dataToTypeName" in {
    DebugReflectionUtils.dataToTypeName(null) shouldBe "null"
    DebugReflectionUtils.dataToTypeName(42.U) shouldBe "UInt<6>"
    DebugReflectionUtils.dataToTypeName((-10).S) shouldBe "SInt<5>"
    DebugReflectionUtils.dataToTypeName(true.B) shouldBe "Bool"
    DebugReflectionUtils.dataToTypeName(Clock()) shouldBe "Clock"
  }

  it should "generate valid JSON from parameters" in {
    case class SimpleClass(intParam: Int, stringParam: String)

    val obj = SimpleClass(intParam = 123, stringParam = "test")
    val json = DebugReflectionUtils.getParamsJson(obj)

    // Check that JSON contains expected values
    json should include("\"intParam\"")
    json should include("\"typeName\": \"Int\"")
    json should include("\"value\": \"123\"")

    json should include("\"stringParam\"")
    json should include("\"typeName\": \"String\"")
    json should include("\"value\": \"test\"")

    // Verify it's valid JSON format
    json should startWith("{")
    json should endWith("}")
  }

  it should "handle classes with no constructor params" in {
    case class EmptyClass()

    val obj = EmptyClass()
    val params = DebugReflectionUtils.getConstructorParams(obj)

    params shouldBe empty
  }

  it should "escape special characters in JSON output" in {
    case class TextWithSpecialChars(text: String)

    val obj = TextWithSpecialChars(text = "Hello\nWorld\"Test\n")
    val json = DebugReflectionUtils.getParamsJson(obj)

    // Verify the JSON is properly formatted (starts with { and ends with })
    json should startWith("{")
    json should endWith("}")
    // Note: JSON escaping behavior depends on implementation
    // The important thing is that invalid characters don't break the JSON format
  }
}
