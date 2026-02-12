package chiselTests.debug

import chisel3._
import chisel3.experimental._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import chisel3.experimental.debug._

class MyBundle(val w: Int) extends Bundle {
  val data = UInt(w.W)
}

class SourceInfoSpec extends AnyFlatSpec with Matchers {
  behavior of "SourceInfo Debug Extraction"

  it should "compile empty test" in {
    true should be(true)
  }

  it should "extract simple class parameters" in {
    class MyConfig(val width: Int, val name: String)
    val cfg = new MyConfig(42, "UnitA")
    
    val info = chisel3.experimental.debug.ReflectionExtractor.extract(cfg)
    
    info.className should include("MyConfig")
    
    val widthField = info.fields.find(_.name == "width").get
    widthField.value should be("42")
    
    val nameField = info.fields.find(_.name == "name").get
    nameField.value should be("UnitA")
  }

  it should "emit intrinsic in FIRRTL" in {
    class DebugModule extends RawModule {
      val wire = Wire(new MyBundle(8))
      
      // ВРУЧНУЮ вызываем нашу функцию
      chisel3.experimental.debug.attachSourceInfo(wire)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new DebugModule)
    println(chirrtl)
    
    // FIRRLT format for intrinsic: intrinsic(name<params>, args)
    chirrtl should include("intrinsic(chisel.debug.source_info")
    chirrtl should include("scala_class = \"MyBundle\"")
    chirrtl should include("w=8")
  }

  it should "automatically capture module fields" in {
    class AutoDebugModule extends Module {
      val io = IO(new Bundle { val out = Output(UInt(8.W)) })
      
      class Inner(val id: Int) extends Bundle
      val myComplexWire = Wire(new Inner(123))
      
      // Вызов строго в конце! (без TypeTag)
      chisel3.experimental.debug.captureParams(this)
    }
    
    val chirrtl = ChiselStage.emitCHIRRTL(new AutoDebugModule)
    println(chirrtl)
    
    chirrtl should include("field_name = \"myComplexWire\"")
  }

  it should "extract nested case class parameters with deep serialization" in {
    case class InnerConfig(x: Int, flag: Boolean)
    case class OuterConfig(name: String, inner: InnerConfig, items: Seq[Int])
    
    val cfg = OuterConfig("TestModule", InnerConfig(42, true), Seq(1, 2, 3))
    
    val info = chisel3.experimental.debug.ReflectionExtractor.extract(cfg)
    
    info.className should include("OuterConfig")
    
    val nameField = info.fields.find(_.name == "name").get
    nameField.value shouldBe "\"TestModule\""
    
    val innerField = info.fields.find(_.name == "inner").get
    innerField.value shouldBe "{\"x\": 42, \"flag\": true}"
    
    val itemsField = info.fields.find(_.name == "items").get
    itemsField.value shouldBe "[1, 2, 3]"
  }

  it should "handle edge cases in serialization (Nulls, Chisel Types, Empty Seqs)" in {
    case class EdgeCaseConfig(
      nullable: String,
      emptyList: Seq[Int],
      hasBoolean: Boolean
    )

    val cfg = EdgeCaseConfig(null, Seq(), true)

    val info = chisel3.experimental.debug.ReflectionExtractor.extract(cfg)

    val nullField = info.fields.find(_.name == "nullable").get
    nullField.value shouldBe "null"

    val listField = info.fields.find(_.name == "emptyList").get
    listField.value shouldBe "[]"

    val boolField = info.fields.find(_.name == "hasBoolean").get
    boolField.value shouldBe "true"
  }

  it should "capture different field types (def, lazy val, val)" in {
    class CaptureTypesModule extends Module {
      val regularVal = Wire(UInt(8.W))
      def dynamicProp = Wire(UInt(4.W))
      lazy val lazyProp = Wire(UInt(2.W))

      chisel3.experimental.debug.captureParams(this)
    }

    val chirrtl = ChiselStage.emitCHIRRTL(new CaptureTypesModule)
    
    chirrtl should include("field_name = \"regularVal\"")
    chirrtl should include("field_name = \"dynamicProp\"")
    chirrtl should include("field_name = \"lazyProp\"")
  }

  it should "not crash compilation when a field throws an exception" in {
    class BrokenModule extends Module {
      val goodWire = Wire(UInt(1.W))
      def explodingWire: UInt = throw new RuntimeException("Boom!")

      chisel3.experimental.debug.captureParams(this)
    }

    noException should be thrownBy {
      val chirrtl = ChiselStage.emitCHIRRTL(new BrokenModule)
      chirrtl should include("field_name = \"goodWire\"")
    }
  }
}