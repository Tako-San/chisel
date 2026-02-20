// SPDX-License-Identifier: Apache-2.0
package chisel3.stage

import firrtl.annotations.NoTargetAnnotation
import firrtl.options.{HasShellOptions, ShellOption}
import firrtl.{seqToAnnoSeq, AnnotationSeq}

/** Enables automatic debug instrumentation of all signals.
  *
  * When present, the [[chisel3.stage.phases.EmitDebugInfo]] phase will
  * emit `circt_dbg_variable` intrinsics for every wire, register, memory,
  * and port in the design. These intrinsics carry name, FIRRTL type,
  * and Chisel-level type information for consumption by debug tools
  * such as Tywaves and HGDB.
  *
  * Can be set via CLI `--emit-debug-info` or by adding this annotation
  * programmatically to the annotation sequence.
  */
case object EmitDebugInfoAnnotation extends NoTargetAnnotation with HasShellOptions {
  val options = Seq(
    new ShellOption[Unit](
      longOption = "emit-debug-info",
      toAnnotationSeq = _ => Seq(EmitDebugInfoAnnotation),
      helpText = "Emit circt_dbg_variable intrinsics for all signals, carrying " +
        "Chisel type metadata for Tywaves/HGDB debug tools"
    )
  )
}
