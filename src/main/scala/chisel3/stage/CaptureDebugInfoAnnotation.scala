package chisel3.stage

import firrtl.annotations.NoTargetAnnotation
import firrtl.options.{HasShellOptions, ShellOption}
import firrtl.seqToAnnoSeq

case class CaptureDebugInfoAnnotation(enabled: Boolean = false) extends NoTargetAnnotation

object CaptureDebugInfoAnnotation extends HasShellOptions {
  val options = Seq(
    new ShellOption[Boolean](
      longOption = "capture-debug",
      toAnnotationSeq = (b: Boolean) => seqToAnnoSeq(Seq(CaptureDebugInfoAnnotation(b))),
      helpText = "Enable automatic debug info capture"
    )
  )
}
