package chiselTests.debug

import chisel3.experimental.debug.DebugReflection
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ReflectionTest extends AnyFlatSpec with Matchers {
  "DebugReflection" should "extract class name" in {
    case class TestConfig(width: Int, name: String)
    val cfg = TestConfig(42, "test")

    val info = DebugReflection.extract(cfg)
    info.className should include("TestConfig")
  }

  "DebugReflection" should "extract case class fields" in {
    case class TestConfig(width: Int, name: String)
    val cfg = TestConfig(42, "test")

    val info = DebugReflection.extract(cfg)

    val widthField = info.fields.find(_.name == "width")
    widthField shouldBe defined
    widthField.get.value shouldBe "42"

    val nameField = info.fields.find(_.name == "name")
    nameField shouldBe defined
    nameField.get.value shouldBe "test"
  }

  "DebugReflection" should "return stable values for repeated calls" in {
    case class TestConfig(value: Int)
    val cfg = TestConfig(123)

    val info1 = DebugReflection.extract(cfg)
    val info2 = DebugReflection.extract(cfg)

    info1.className shouldBe info2.className
    info1.fields.map(_.name) shouldBe info2.fields.map(_.name)
    info1.fields.map(_.value) shouldBe info2.fields.map(_.value)
  }

  "DebugReflection" should "handle nested case class structures" in {
    case class Inner(field1: Int, field2: String)
    case class Outer(name: String, inner: Inner)

    val cfg = Outer("test", Inner(42, "nested"))

    val info = DebugReflection.extract(cfg)
    info.className should include("Outer")

    val innerField = info.fields.find(_.name == "inner")
    innerField shouldBe defined
  }

  "DebugReflection" should "handle Bundle parameters" in {
    // Simulate a Bundle-like case class
    case class MyBundle(
      enable:    Boolean,
      dataWidth: Int,
      name:      String
    )
    val bundle = MyBundle(enable = true, dataWidth = 32, name = "test_bundle")

    val info = DebugReflection.extract(bundle)
    info.className should include("MyBundle")

    info.fields.find(_.name == "enable").get.value shouldBe "true"
    info.fields.find(_.name == "dataWidth").get.value shouldBe "32"
    info.fields.find(_.name == "name").get.value shouldBe "test_bundle"
  }
}
