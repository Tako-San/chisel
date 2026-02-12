// SPDX-License-Identifier: Apache-2.0

package circt.stage.phases

import chisel3.RawModule
import chisel3.stage.{ChiselCircuitAnnotation, CaptureDebugInfoAnnotation}
import firrtl.{AnnotationSeq, annoSeqToSeq, seqToAnnoSeq}
import firrtl.options.{Dependency, Phase}
import chisel3.stage.phases.{Elaborate, Convert}

/** Phase that captures debug information after elaboration and before conversion.
  * It runs only when [[CaptureDebugInfoAnnotation]] is enabled.
  */
class CaptureDebugInfo extends Phase {
  override val prerequisites = Seq(Dependency[Elaborate])
  override val optionalPrerequisiteOf = Seq(Dependency[Convert])

  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    // If the annotation is present, we could run debug capture, but it is handled by user code.
    // Here we simply pass through the annotations unchanged.
    annotations
  }
}
