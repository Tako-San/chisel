// SPDX-License-Identifier: Apache-2.0

package chiselTests

import chisel3._
import chisel3.debuginternal.DebugIntrinsic
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Unit tests for DebugIntrinsic internal logic.
  * 
  * Focuses on helper methods:
  * - Type name extraction
  * - Parameter extraction (Bundles)
  * - Enum definition extraction and name cleaning
  * 
  * Note: End-to-end FIRRTL generation and Probe API usage are tested 
  * comprehensively in DebugInfoIntegrationSpec.
  */
class DebugIntrinsicSpec extends AnyFlatSpec with Matchers {
  
  behavior of "DebugIntrinsic Helpers"
  
  it should "extract Bundle constructor parameters" in {
    class ParametricBundle(val dataWidth: Int, val depth: Int) extends Bundle {
      val data = UInt(dataWidth.W)
      val valid = Bool()
    }
    
    val bundle = new ParametricBundle(32, 1024)
    val params = DebugIntrinsic.extractBundleParams(bundle)
    
    params should contain("dataWidth" -> "32")
    params should contain("depth" -> "1024")
  }
  
  it should "extract enum definitions in correct format" in {
    // ChiselEnum generates internal Type representations
    object TestEnum extends ChiselEnum {
      val IDLE = Value
      val RUN = Value
    }
    
    // Extract enum definition
    val enumDef = DebugIntrinsic.extractEnumDef(TestEnum.IDLE)
    
    println(enumDef)

    // Should contain numeric mappings
    withClue("Enum definition should contain numeric indices") {
      enumDef should include regex "\"\\d+\":"
    }
    
    // Should NOT have Scala artifacts like trailing $
    withClue("Enum values should not contain trailing $ artifacts") {
      enumDef should not (include regex "\\$\\d+:")
    }
  }
  
  it should "extract correct type names for all Data types" in {
    DebugIntrinsic.extractTypeName(UInt(8.W)) shouldBe "UInt"
    DebugIntrinsic.extractTypeName(SInt(8.W)) shouldBe "SInt"
    DebugIntrinsic.extractTypeName(Bool()) shouldBe "Bool"
    
    class CustomBundle extends Bundle {
      val x = UInt(8.W)
    }
    val name = DebugIntrinsic.extractTypeName(new CustomBundle)
    // Should be cleaned of Scala artifacts
    name should (equal("CustomBundle") or startWith("CustomBundle"))
  }
  
  it should "clean Scala type name artifacts" in {
    // Test that $N suffixes are removed
    class TestBundle extends Bundle {
      val field = UInt(8.W)
    }
    
    val bundle = new TestBundle
    val typeName = DebugIntrinsic.extractTypeName(bundle)
    
    // Should be "TestBundle", not "TestBundle$1" or similar
    typeName should not include "$"
    typeName should startWith("TestBundle")
  }
}
