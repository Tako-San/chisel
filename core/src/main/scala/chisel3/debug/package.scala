package chisel3

import chisel3.experimental.{SourceInfo, SourceLine, StringParam}
import chisel3.internal.firrtl.ir.{Arg, DefIntrinsic}
import scala.annotation.nowarn

package object debug {

  /** Manually mark a single signal for debug instrumentation.
    * Emits `circt_dbg_variable` directly – no placeholder, no registry.
    *
    * For full-design instrumentation, use `AutoInstrumentDebugInfo` which is
    * automatically invoked circuit elaboration.
    */
  @nowarn("cat=deprecation")
  def debug[T <: chisel3.Data](data: T, name: String = "")(implicit src: SourceInfo): T = {
    import chisel3.internal.Builder
    val sigName = if (name.isEmpty) data.instanceName else name
    // Note: path requires full hierarchy which may not be available during construction.
    // CIRCT will construct the full path during FIRRTL conversion.
    Builder.pushCommand(
      DefIntrinsic(
        src,
        "circt_dbg_variable",
        Seq.empty[Arg],
        Seq(
          "name" -> StringParam(sigName),
          "type" -> StringParam(data.typeName)
        )
      )
    )
    data
  }

  implicit class DataDebugOps[T <: chisel3.Data](private val data: T) extends AnyVal {
    def instrumentDebug(name: String = "")(implicit src: SourceInfo): T = debug(data, name)
  }
}
