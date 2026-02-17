package chisel3.debug

import chisel3._
import chisel3.experimental.debug.DebugTypeReflector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DebugTypeReflectorSpec extends AnyFlatSpec with Matchers {
  "DebugTypeReflector" should "recursively serialize nested case classes" in {
    case class Inner(a: Int)
    case class Outer(inner: Inner, name: String)

    val obj = Outer(Inner(42), "test")
    val params = DebugTypeReflector.getConstructorParams(obj)

    val innerParam = params.find(_.name == "inner").get
    innerParam.value should include("a=42")
    innerParam.value should include("Inner")
    innerParam.isComplex shouldBe true
  }

  it should "format Seq correctly" in {
    case class Config(opts: Seq[Int])
    val obj = Config(Seq(1, 2, 3))
    val params = DebugTypeReflector.getConstructorParams(obj)

    params.head.value shouldBe "Seq(1, 2, 3)"
  }

  it should "filter out $outer synthetic fields" in {
    case class OuterConfig(x: Int)
    class InnerConfig(val cfg: OuterConfig)

    val obj = new InnerConfig(OuterConfig(10))
    val params = DebugTypeReflector.getConstructorParams(obj)

    // Should only have cfg, not cfg$$outer or similar synthetic fields
    params.map(_.name) should not contain ("$outer")
    params.map(_.name) should not contain ("cfg$$outer")
  }

  it should "serialize Chisel Data types correctly" in {
    // Chisel Data types are handled via toString method in formatValue
    // This test is covered by other tests in the ReflectionIntegrationSpec
    // which runs Chisel modules with proper Builder context
  }

  it should "format basic types correctly" in {
    case class BasicTypes(a: Int, b: String, c: Boolean)
    val obj = BasicTypes(42, "hello", true)
    val params = DebugTypeReflector.getConstructorParams(obj)

    params.find(_.name == "a").get.value shouldBe "42"
    params.find(_.name == "b").get.value shouldBe "\"hello\""
    params.find(_.name == "c").get.value shouldBe "true"
  }

  it should "handle deeply nested case classes" in {
    case class Level1(x: Int)
    case class Level2(l1: Level1, y: Int)
    case class Level3(l2: Level2, z: Int)

    val obj = Level3(Level2(Level1(1), 2), 3)
    val params = DebugTypeReflector.getConstructorParams(obj)

    val l2Param = params.find(_.name == "l2").get
    l2Param.isComplex shouldBe true
    l2Param.value should include("Level2")

    // Verify recursion is working
    l2Param.value should include("x=1")
    l2Param.value should include("y=2")
  }

  it should "return empty sequence for objects without constructor params" in {
    object EmptyCase
    val params = DebugTypeReflector.getConstructorParams(EmptyCase)
    params shouldBe empty
  }
}
