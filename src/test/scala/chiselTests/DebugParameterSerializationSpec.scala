// SPDX-License-Identifier: Apache-2.0

package chiselTests

import chisel3._
import chisel3.debuginternal.DebugIntrinsic
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test suite for debug parameter serialization/deserialization.
  * 
  * CRITICAL: Validates parameter format matches CIRCT expectations.
  * Parameters are serialized as "key1=val1;key2=val2" strings.
  * CIRCT must be able to parse this format for hw-debug-info.json.
  */
class DebugParameterSerializationSpec extends AnyFlatSpec with Matchers {
  
  behavior of "DebugIntrinsic parameter serialization"
  
  /**
    * Helper: Parse serialized parameters back to Map.
    * Mimics CIRCT parsing logic.
    */
  def parseParameters(serialized: String): Map[String, String] = {
    if (serialized.isEmpty) return Map.empty
    
    serialized.split(";").map { kvPair =>
      val parts = kvPair.split("=", 2)
      require(parts.length == 2, s"Invalid parameter format: $kvPair")
      parts(0).trim -> parts(1).trim
    }.toMap
  }
  
  /**
    * Helper: Extract parameters string from FIRRTL intrinsic.
    */
  def extractParametersFromFIRRTL(firrtl: String): Option[String] = {
    val paramPattern = """parameters\\s*=\\s*"([^"]*)"""""".r
    paramPattern.findFirstMatchIn(firrtl).map(_.group(1))
  }
  
  it should "serialize UInt width parameter correctly" in {
    val params = Map("width" -> "8")
    val serialized = params.map { case (k, v) => s"$k=$v" }.mkString(";")
    
    serialized shouldBe "width=8"
    
    // Verify round-trip
    val parsed = parseParameters(serialized)
    parsed shouldBe params
  }
  
  it should "serialize multiple parameters with semicolon separator" in {
    val params = Map(
      "length" -> "16",
      "elementType" -> "UInt",
      "width" -> "32"
    )
    val serialized = params.map { case (k, v) => s"$k=$v" }.mkString(";")
    
    // Order may vary, but all keys must be present
    serialized should include("length=16")
    serialized should include("elementType=UInt")
    serialized should include("width=32")
    
    // Verify round-trip
    val parsed = parseParameters(serialized)
    parsed shouldBe params
  }
  
  it should "handle empty parameters" in {
    val params = Map.empty[String, String]
    val serialized = params.map { case (k, v) => s"$k=$v" }.mkString(";")
    
    serialized shouldBe ""
    
    // Verify round-trip
    val parsed = parseParameters(serialized)
    parsed shouldBe params
  }
  
  it should "serialize Bundle parameters from reflection" in {
    class TestBundle(val width: Int, val depth: Int) extends Bundle {
      val data = UInt(width.W)
    }
    
    val bundle = new TestBundle(32, 1024)
    val params = DebugIntrinsic.extractBundleParams(bundle)
    val serialized = params.map { case (k, v) => s"$k=$v" }.mkString(";")
    
    // Verify format
    serialized should include regex "width=\\d+"
    serialized should include regex "depth=\\d+"
    
    // Verify round-trip
    val parsed = parseParameters(serialized)
    parsed("width") shouldBe "32"
    parsed("depth") shouldBe "1024"
  }
  
  it should "serialize Vec parameters correctly" in {
    sys.props("chisel.debug") = "true"
    
    class VecParamModule extends Module {
      val io = IO(new Bundle {
        val vec = Output(Vec(8, UInt(16.W)))
      })
      io.vec := VecInit(Seq.fill(8)(0.U))
      
      DebugIntrinsic.emit(io.vec, "io.vec", "IO")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new VecParamModule)
    val paramsOpt = extractParametersFromFIRRTL(firrtl)
    
    paramsOpt should not be None
    val params = parseParameters(paramsOpt.get)
    
    // Verify Vec parameters
    params should contain("length" -> "8")
    params should contain("elementType" -> "UInt")
    
    sys.props.remove("chisel.debug")
  }
  
  it should "handle Bundle with complex parameters" in {
    sys.props("chisel.debug") = "true"
    
    class ComplexBundle(val dataWidth: Int, val addrWidth: Int, val numEntries: Int) extends Bundle {
      val data = UInt(dataWidth.W)
      val addr = UInt(addrWidth.W)
    }
    
    class ComplexParamModule extends Module {
      val io = IO(new Bundle {
        val bus = Output(new ComplexBundle(64, 32, 256))
      })
      io.bus.data := 0.U
      io.bus.addr := 0.U
      
      DebugIntrinsic.emit(io.bus, "io.bus", "IO")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new ComplexParamModule)
    val paramsOpt = extractParametersFromFIRRTL(firrtl)
    
    paramsOpt should not be None
    val params = parseParameters(paramsOpt.get)
    
    // Verify all Bundle constructor parameters captured
    withClue(s"Missing parameters in: $params") {
      params.keys should contain allOf ("dataWidth", "addrWidth", "numEntries")
    }
    
    params("dataWidth") shouldBe "64"
    params("addrWidth") shouldBe "32"
    params("numEntries") shouldBe "256"
    
    sys.props.remove("chisel.debug")
  }
  
  it should "handle parameters with special characters gracefully" in {
    // Test edge cases that might break parsing
    val params = Map(
      "typeName" -> "MyBundle$1",  // Scala inner class suffix
      "description" -> "A_simple_test"  // Underscores
    )
    val serialized = params.map { case (k, v) => s"$k=$v" }.mkString(";")
    
    // Should not contain unescaped semicolons or equals in values
    val parsed = parseParameters(serialized)
    parsed shouldBe params
  }
  
  it should "serialize and parse enum definitions" in {
    sys.props("chisel.debug") = "true"
    
    object TestState extends ChiselEnum {
      val IDLE, FETCH, DECODE, EXECUTE = Value
    }
    
    class EnumParamModule extends Module {
      val state = RegInit(TestState.IDLE)
      state := TestState.FETCH
      
      DebugIntrinsic.emit(state, "state", "Reg")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new EnumParamModule)
    
    // Extract enumDef parameter
    val enumDefPattern = """enumDef\\s*=\\s*"([^"]*)"""""".r
    val enumDefOpt = enumDefPattern.findFirstMatchIn(firrtl).map(_.group(1))
    
    enumDefOpt should not be None
    val enumDef = enumDefOpt.get
    
    // Verify enum definition format: "0:State(0=IDLE),1:State(1=FETCH),..."
    enumDef should include("IDLE")
    enumDef should include("FETCH")
    
    // Parse enum values
    val enumEntries = enumDef.split(",").map { entry =>
      val parts = entry.split(":")
      parts(0).toInt -> parts(1)
    }.toMap
    
    // Verify sequential encoding
    enumEntries.keys.toSeq.sorted shouldBe Seq(0, 1, 2, 3)
    
    sys.props.remove("chisel.debug")
  }
  
  it should "verify FIRRTL parameter format matches CIRCT expectations" in {
    sys.props("chisel.debug") = "true"
    
    class CIRCTCompatModule extends Module {
      val io = IO(new Bundle {
        val data = Output(UInt(32.W))
      })
      io.data := 0.U
      
      DebugIntrinsic.emit(io.data, "io.data", "IO")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new CIRCTCompatModule)
    
    // Extract full intrinsic statement
    val intrinsicPattern = """intrinsic\(circt_debug_typeinfo<([^>]*)>""".r
    val intrinsicParamsOpt = intrinsicPattern.findFirstMatchIn(firrtl).map(_.group(1))
    
    intrinsicParamsOpt should not be None
    val intrinsicParams = intrinsicParamsOpt.get
    
    // Verify required parameters present
    intrinsicParams should include("target = ")
    intrinsicParams should include("typeName = ")
    intrinsicParams should include("binding = ")
    intrinsicParams should include("parameters = ")
    intrinsicParams should include("sourceFile = ")
    intrinsicParams should include("sourceLine = ")
    
    // Extract parameters value
    val paramsOpt = extractParametersFromFIRRTL(firrtl)
    paramsOpt should not be None
    
    // Verify parameters can be parsed
    noException should be thrownBy {
      parseParameters(paramsOpt.get)
    }
    
    sys.props.remove("chisel.debug")
  }
  
  it should "handle nested Bundle parameters correctly" in {
    sys.props("chisel.debug") = "true"
    
    class InnerBundle(val size: Int) extends Bundle {
      val value = UInt(size.W)
    }
    
    class OuterBundle(val count: Int) extends Bundle {
      val inner = new InnerBundle(16)
    }
    
    class NestedParamModule extends Module {
      val io = IO(Output(new OuterBundle(4)))
      io.inner.value := 0.U
      
      DebugIntrinsic.emitRecursive(io, "io", "IO")
    }
    
    val firrtl = ChiselStage.emitCHIRRTL(new NestedParamModule)
    
    // Extract all parameter strings from intrinsics
    val allParams = extractParametersFromFIRRTL(firrtl)
    
    // Should have parameters for both outer and inner bundles
    allParams should not be None
    
    // Verify parsing succeeds for all parameter strings
    noException should be thrownBy {
      val parsed = parseParameters(allParams.get)
    }
    
    sys.props.remove("chisel.debug")
  }
  
  it should "round-trip serialize and deserialize without data loss" in {
    val testCases = Seq(
      Map("width" -> "8"),
      Map("length" -> "4", "elementType" -> "SInt"),
      Map("dataWidth" -> "32", "addrWidth" -> "16", "depth" -> "1024"),
      Map.empty[String, String]
    )
    
    testCases.foreach { originalParams =>
      val serialized = originalParams.map { case (k, v) => s"$k=$v" }.mkString(";")
      val parsed = if (serialized.isEmpty) Map.empty else parseParameters(serialized)
      
      withClue(s"Round-trip failed for: $originalParams") {
        parsed shouldBe originalParams
      }
    }
  }
  
  it should "handle parameters with numeric values correctly" in {
    val params = Map(
      "width" -> "128",
      "depth" -> "65536",
      "latency" -> "3"
    )
    val serialized = params.map { case (k, v) => s"$k=$v" }.mkString(";")
    val parsed = parseParameters(serialized)
    
    // Verify all values preserved as strings (CIRCT converts)
    parsed("width") shouldBe "128"
    parsed("depth") shouldBe "65536"
    parsed("latency") shouldBe "3"
    
    // Verify numeric conversion would work
    parsed("width").toInt shouldBe 128
    parsed("depth").toInt shouldBe 65536
    parsed("latency").toInt shouldBe 3
  }
  
  behavior of "CIRCT compatibility edge cases"
  
  it should "handle empty parameter values" in {
    // Edge case: parameter with empty value
    val paramsWithEmpty = "width=8;name=;depth=1024"
    
    // This might happen if reflection fails
    val parsed = parseParameters(paramsWithEmpty)
    parsed("width") shouldBe "8"
    parsed("name") shouldBe ""
    parsed("depth") shouldBe "1024"
  }
  
  it should "reject malformed parameter strings" in {
    val malformedCases = Seq(
      "width",  // Missing =
      "width=8=extra",  // Multiple =
      ";",  // Empty entry
      "width=8;;",  // Double separator
    )
    
    malformedCases.foreach { malformed =>
      withClue(s"Should reject: $malformed") {
        an[Exception] should be thrownBy {
          parseParameters(malformed)
        }
      }
    }
  }
}