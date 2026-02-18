package chisel3.debug

import chisel3._
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3.stage.phases.{CollectDebugInfo, Elaborate}
import firrtl.{annoSeqToSeq, seqToAnnoSeq}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

class DebugCollectionSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  "CollectDebugInfo Phase" should "resolve hierarchical paths" in {
    // Define hierarchy
    class Child extends RawModule {
      val signal = Wire(Bool()).suggestName("mySig")
      signal.instrumentDebug()
    }
    class Top extends RawModule {
      val child = Module(new Child).suggestName("inst1")
    }

    // Run Elaborate manually
    val annos = Seq(ChiselGeneratorAnnotation(() => new Top))
    val elaborated = new Elaborate().transform(annos)

    // Run CollectDebugInfo phase
    val result = new CollectDebugInfo().transform(elaborated)

    // Verify CHIRRTL output contains debug information
    val chirrtl = result.collectFirst { case a: chisel3.stage.ChiselCircuitAnnotation =>
      a
    }.get.elaboratedCircuit.serialize
    chirrtl should include("inst1")
    chirrtl should include("mySig")
  }
}
