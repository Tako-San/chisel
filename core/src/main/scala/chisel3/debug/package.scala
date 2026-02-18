package chisel3

import chisel3.experimental.SourceInfo
import chisel3.internal.firrtl.ir._

package object debug {

  /** Mark a signal for hardware debug instrumentation.
    *
    * Generates a `circt_dbg_placeholder` intrinsic that CollectDebugInfo
    * phase replaces with `circt_dbg_variable` for firtool consumption.
    *
    * @param data  hardware signal to instrument
    * @param name  source-language name (defaults to signal's instanceName)
    */
  def debug[T <: chisel3.Data](data: T, name: String = "")(
    implicit src: SourceInfo
  ): T = {
    import chisel3.Intrinsic
    import java.security.MessageDigest
    // Use filename, line, global call sequence, and name for deterministic ID
    val sourceFile = src.filenameOption.getOrElse("_")
    val sourceLine = src match {
      case sl: chisel3.experimental.SourceLine => sl.line.toString
      case _ => "0"
    }
    val callSeq = DebugRegistry.nextCallId()
    val input = s"$sourceFile:$sourceLine:$callSeq:$name"

    val id = {
      val md = MessageDigest.getInstance("SHA-256")
      val digest = md.digest(input.getBytes("UTF-8"))
      digest.take(8).map("%02x".format(_)).mkString // 16 hex chars
    }

    Intrinsic("circt_dbg_placeholder", "id" -> StringParam(id))(data)
    DebugRegistry.register(id, data, debugName = if (name.isEmpty) None else Some(name))
    data
  }

  /** Provides instrumentation methods for Data signals.
    *
    * This implicit class is used to add debugging instrumentation methods
    * to all Data types without making binary-incompatible changes to the Data class.
    */
  implicit class DataDebugOps[T <: chisel3.Data](private val data: T) extends AnyVal {

    /** Marks this signal for hardware debug instrumentation.
      *
      * Generates a `circt_dbg_placeholder` intrinsic that the [[chisel3.stage.phases.CollectDebugInfo]]
      * phase replaces with `circt_dbg_variable` before FIRRTL emission.
      *
      * @note This API is experimental and subject to change. It requires `CollectDebugInfo`
      *       to be present in the elaboration pipeline; otherwise the placeholder intrinsic
      *       will remain in the output.
      * @note If called outside a Module elaboration context, this will throw an exception.
      * @param name optional source-language debug name; defaults to `instanceName`
      * @return this signal (for chaining)
      */
    def instrumentDebug(name: String = "")(implicit src: SourceInfo): T = {
      debug(data, name)
    }
  }
}
