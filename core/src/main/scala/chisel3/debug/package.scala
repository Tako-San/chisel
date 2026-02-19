package chisel3

import chisel3.experimental.{SourceInfo, SourceLine}
import chisel3.internal.firrtl.ir.{DefIntrinsic, Arg}
import scala.annotation.nowarn

package object debug {

  /** Manually mark a single signal for debug instrumentation.
    * Emits `circt_dbg_variable` directly – no placeholder, no registry.
    * Prefer `AutoInstrumentDebugInfo` for full-design instrumentation.
    */
  @deprecated("Experimental API", "Chisel 7.0.0")
  def debug[T <: chisel3.Data](data: T, name: String = "")(implicit src: SourceInfo): T = {
    import chisel3.internal.Builder
    val sigName = if (name.isEmpty) data.instanceName else name
    Builder.pushCommand(
      DefIntrinsic(src, "circt_dbg_variable", Seq.empty[Arg], Seq(
        "name" -> StringParam(sigName),
        "type" -> StringParam(data.typeName)
      ))
    )
    data
  }

  implicit class DataDebugOps[T <: chisel3.Data](private val data: T) extends AnyVal {
    @nowarn("cat=deprecation")
    def instrumentDebug(name: String = "")(implicit src: SourceInfo): T = debug(data, name)
  }
}