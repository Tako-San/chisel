package chiselTests.debug

import chisel3._
import circt.stage.ChiselStage
import chisel3.experimental.debug.ReflectionExtractor
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DataHierarchySpec extends AnyFlatSpec with Matchers {

  it should "extract Bundle hierarchy" in {
    class MyBundle extends Bundle {
      val a = UInt(8.W)
      val b = new Bundle { val inner = SInt(4.W) }
    }

    class TestModule extends Module {
      val io = IO(Output(new MyBundle))

      val bundle = io
      val info = ReflectionExtractor.extract(bundle)

      (info.fields.map(_.name) should contain).allOf("a", "b", "b.inner")
      info.fields.find(_.name == "a").get.typeName should be("UInt<8>")
    }

    ChiselStage.emitCHIRRTL(new TestModule)
  }

  it should "extract Vec metadata" in {
    class TestModule extends Module {
      val io = IO(Output(Vec(5, UInt(8.W))))

      val vec = io
      val info = ReflectionExtractor.extract(vec)

      info.fields.find(_.name == "_element").get.typeName should be("UInt<8>")
      info.fields.find(_.name == "_element").get.value should include("5 elements")
    }

    ChiselStage.emitCHIRRTL(new TestModule)
  }
}
