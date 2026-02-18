// See LICENSE for license details.

package chisel3.debug

import chisel3._
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3.stage.phases.Elaborate
import chisel3.debug.{DebugRegistry, DebugRegistryAnnotation}
import firrtl.{annoSeqToSeq, seqToAnnoSeq}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DebugRegistrySpec extends AnyFlatSpec with Matchers {

  "DebugRegistry" should "register debug entries" in {

    // Define a simple test module with an instrumented wire
    class TestModule extends RawModule {
      val a = Wire(UInt(8.W))
      a.instrumentDebug()
    }

    // After the refactor, Elaborate wraps module construction in withFreshRegistry
    // and returns DebugRegistryAnnotation with the entries
    val annos = Seq(ChiselGeneratorAnnotation(() => new TestModule))
    val resultAnnos = new Elaborate().transform(annos)
    val registryEntries = resultAnnos.toSeq.collectFirst { case DebugRegistryAnnotation(entries) =>
      entries
    }.getOrElse(Map.empty)

    // Retrieve and verify the registry entries
    val entries = registryEntries.toSeq

    entries should have size 1

    val (id, entry) = entries.head
    entry.data shouldBe a[UInt]
    // pathName and typeName should be None since we only ran Elaborate
    entry.pathName shouldBe None
    entry.typeName shouldBe None
  }
}
