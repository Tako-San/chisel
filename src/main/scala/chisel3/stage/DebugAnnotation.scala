// SPDX-License-Identifier: Apache-2.0

package chisel3.stage

import firrtl.{AnnotationSeq, seqToAnnoSeq}
import firrtl.annotations.NoTargetAnnotation
import firrtl.options.{HasShellOptions, ShellOption, OptionsException}
import chisel3.stage.ChiselGeneratorAnnotation
import scala.util.control.NonFatal

/** Annotation to enable capture of debug information during Chisel compilation.
  *
  * When present with `enabled = true`, the [[circt.stage.phases.CaptureDebugInfo]] phase
  * will traverse the elaborated circuit and collect debug intrinsics.
  */
case class CaptureDebugInfoAnnotation(enabled: Boolean = false) extends NoTargetAnnotation

object CaptureDebugInfoAnnotation extends HasShellOptions {
  val options = Seq(
    new ShellOption[Boolean](
      longOption = "capture-debug",
      toAnnotationSeq = { b: Boolean => Seq(CaptureDebugInfoAnnotation(b)) },
      helpText = "Enable capture of debug information for the circuit",
      helpValueName = Some("<true|false>")
    )
  )
}
