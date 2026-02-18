// See LICENSE for license details.

package chisel3.debug

import chisel3._
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3.stage.phases.Elaborate
import chisel3.experimental.{SourceInfo, SourceLine, UnlocatableSourceInfo}
import chisel3.debug.{DebugRegistry, DebugRegistryAnnotation}
import firrtl.{annoSeqToSeq, seqToAnnoSeq}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DebugSourceInfoSpec extends AnyFlatSpec with Matchers {

  private def elaborateAndGetEntries[T <: RawModule](gen: => T): Map[String, DebugEntry] = {
    val annos = Seq(ChiselGeneratorAnnotation(() => gen))
    val results = new Elaborate().transform(annos)
    results.toSeq.collectFirst { case DebugRegistryAnnotation(entries) =>
      entries
    }.get
  }

  "DebugEntry.src" should "be preserved when registering debug info" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("testWire")
      w.instrumentDebug()
    }

    val entries = elaborateAndGetEntries(new TestModule).toSeq
    entries should have size 1

    val (_, entry) = entries.head
    entry.src should not be UnlocatableSourceInfo
    entry.src.filenameOption should not be empty
  }

  "DebugEntry.src" should "preserve filename from SourceLine" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("testWire")
      w.instrumentDebug()
    }

    val entries = elaborateAndGetEntries(new TestModule).toSeq
    entries should have size 1

    val (_, entry) = entries.head
    entry.src match {
      case sl: SourceLine =>
        sl.filename should endWith(".scala")
        sl.line should be > 0
      case _ =>
        fail(s"Expected SourceLine but got ${entry.src.getClass.getName}")
    }
  }

  "DebugEntry.src" should "preserve line and column from SourceLine" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("testWire")
      w.instrumentDebug() // This line's source info should be captured
    }

    val entries = elaborateAndGetEntries(new TestModule).toSeq
    entries should have size 1

    val (_, entry) = entries.head
    entry.src match {
      case sl: SourceLine =>
        sl.line should be > 0
        // Column may be 0 if precise column tracking is not available
        sl.col should be >= 0
      case _ =>
        fail(s"Expected SourceLine but got ${entry.src.getClass.getName}")
    }
  }

  "DebugEntry.src" should "maintain consistency for multiple registrations" in {
    class TestModule extends RawModule {
      val w1 = Wire(UInt(8.W))
      w1.suggestName("wire1")
      w1.instrumentDebug()

      val w2 = Wire(UInt(16.W))
      w2.suggestName("wire2")
      w2.instrumentDebug()

      val w3 = Wire(Bool())
      w3.suggestName("wire3")
      w3.instrumentDebug()
    }

    val entries = elaborateAndGetEntries(new TestModule).toSeq
    entries should have size 3

    // All entries should have valid SourceInfo
    entries.foreach { case (_, entry) =>
      entry.src should not be UnlocatableSourceInfo
      entry.src.filenameOption should not be empty
    }
  }

  "DebugEntry.src" should "handle UnlocatableSourceInfo when available" in {
    // Test that we can handle cases where source info is not available
    // (e.g., for certain construct types)
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.instrumentDebug()
    }

    val entries = elaborateAndGetEntries(new TestModule).toSeq
    entries should have size 1

    val entry = entries.head._2
    // Verify we can call makeMessage without errors
    noException should be thrownBy {
      entry.src.makeMessage()
    }
  }

  "DebugEntry.src" should "serialize correctly for FIRRTL emission" in {
    class TestModule extends RawModule {
      val w = Wire(UInt(8.W))
      w.suggestName("testWire")
      w.instrumentDebug()
    }

    val entries = elaborateAndGetEntries(new TestModule).toSeq
    entries should have size 1

    val (_, entry) = entries.head
    entry.src match {
      case sl: SourceLine =>
        val serialized = sl.serialize
        serialized should include(" ")
        // Format: "filename line" or "filename line:col"
        (serialized.split(" ") should have).length(2)
      case _ =>
        // For other source info types, serialization may be empty
        val message = entry.src.makeMessage()
        message should not be null
    }
  }
}
