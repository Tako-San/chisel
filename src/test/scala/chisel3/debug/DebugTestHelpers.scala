// See LICENSE for license details.

package chisel3.debug

import chisel3._
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3.stage.phases.Elaborate
import firrtl.seqToAnnoSeq

/** Helper trait for debug testing utilities */
trait DebugTestHelpers {

  /** Extract debug entries from a module generator
    *
    * @param gen Module generator function
    * @return Map of debug IDs to DebugEntry objects
    */
  protected def getDebugEntries[T <: RawModule](gen: () => T): Map[String, DebugEntry] = {
    val annos = seqToAnnoSeq(Seq(ChiselGeneratorAnnotation(gen)))
    val resultAnnos = new Elaborate().transform(annos)
    resultAnnos.toSeq.collectFirst { case DebugRegistryAnnotation(entries) => entries }.getOrElse(Map.empty)
  }
}
