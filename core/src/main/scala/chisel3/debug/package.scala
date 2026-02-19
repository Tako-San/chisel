package chisel3

import chisel3.experimental.{SourceInfo, SourceLine, StringParam}
import chisel3.internal.firrtl.ir.{Arg, DefIntrinsic}
import scala.annotation.nowarn

package object debug {

  /** Manually mark a single signal for debug instrumentation.
    * Emits `circt_dbg_variable` directly – no placeholder, no registry.
    *
    * For full-design instrumentation, use `AutoInstrumentDebugInfo` which is
    * automatically invoked during circuit elaboration.
    *
    * @param data The Chisel data signal to instrument for debugging
    * @param name The local signal name. If empty, defaults to the signal's instance name
    * @param path A partial hierarchical path for the signal. The full hierarchical path
    *             will be constructed by CIRCT/HGDB during FIRRTL conversion.
    *
    *             @note The `path` parameter currently contains only a simple qualified name
    *             (e.g., "ModuleName.signalName") rather than the full hierarchical path through
    *             all parent modules. CIRCT/HGDB will expand this to the complete hierarchical
    *             path based on the module instance hierarchy.
    *
    *             The `name` and `path` parameters serve different purposes:
    *             - `name`: Local identifier for the signal within its immediate module
    *             - `path`: Hierarchical identifier that will be expanded to the full instance path
    */
  @nowarn("cat=deprecation")
  def debug[T <: chisel3.Data](data: T, name: String = "", path: String = "")(implicit src: SourceInfo): T = {
    import chisel3.internal.Builder
    val sigName = if (name.isEmpty) data.instanceName else name
    // Note: path requires full hierarchy which may not be available during construction.
    // CIRCT will construct the full path during FIRRTL conversion when path is empty.
    val params = Seq(
      "name" -> StringParam(sigName),
      "type" -> StringParam(data.typeName)
    ) ++ (if (path.nonEmpty) Seq("path" -> StringParam(path)) else Seq.empty)
    Builder.pushCommand(
      DefIntrinsic(
        src,
        "circt_dbg_variable",
        Seq.empty[Arg],
        params
      )
    )
    data
  }

  implicit class DataDebugOps[T <: chisel3.Data](private val data: T) extends AnyVal {
    def instrumentDebug(name: String = "", path: String = "")(implicit src: SourceInfo): T = debug(data, name, path)
  }
}
