package chisel3.debug

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chisel3.util.debug.TypeReflector
import chisel3._

class MySimpleBundle(val dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val valid = Bool()
}

class TypeReflectorSpec extends AnyFlatSpec with Matchers {
  "TypeReflector" should "extract constructor parameters from a Bundle" in {
    val bundle = new MySimpleBundle(32)
    val params = TypeReflector.getConstructorParams(bundle)

    (params should have).length(1)
    params.head.name shouldBe "dataWidth"
    params.head.typeName shouldBe "Int"
    params.head.value shouldBe "32"
  }

  it should "handle multiple parameters" in {
    class MyMultiBundle(val a: Int, val b: String, val c: Boolean) extends Bundle
    val bundle = new MyMultiBundle(1, "test", true)
    val params = TypeReflector.getConstructorParams(bundle)

    (params.map(_.name) should contain).allOf("a", "b", "c")
  }

  it should "return empty for non-val/var parameters" in {
    class MyBundleNonVal(width: Int) extends Bundle {
      val data = UInt(width.W)
    }
    val bundle = new MyBundleNonVal(8)
    val params = TypeReflector.getConstructorParams(bundle)

    params.map(_.name) should contain("width")
    params.find(_.name == "width").get.value shouldBe "unknown"
  }

  it should "handle empty parameter list" in {
    class EmptyBundle extends Bundle {
      val data = UInt(8.W)
    }
    val bundle = new EmptyBundle
    val params = TypeReflector.getConstructorParams(bundle)

    params shouldBe empty
  }
}
