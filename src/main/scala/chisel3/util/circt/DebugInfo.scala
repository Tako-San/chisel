// SPDX-License-Identifier: Apache-2.0

package chisel3.util.circt

import chisel3._
import chisel3.experimental.{SourceInfo, UnlocatableSourceInfo}

/** CIRCT DebugInfo intrinsics for hardware debugging infrastructure.
  *
  * Provides API for emitting debug metadata intrinsics that preserve high-level 
  * type information (Bundles, Vecs, Enums) through FIRRTL->CIRCT.
  *
  * @see [[https://circt.llvm.org/docs/Dialects/Debug/ CIRCT Debug Dialect]]
  */
object DebugInfo {

  /** Check if debug mode is enabled via environment or system property.
    * Returns true if CHISEL_DEBUG=true or -Dchisel.debug=true
    */
  def isEnabled: Boolean = chisel3.debuginternal.DebugIntrinsic.isEnabled

  // Atomic counter for unique default names
  private val _autoNameCounter = new java.util.concurrent.atomic.AtomicLong(0)

  private def getTargetName(name: String): String =
    if (name.nonEmpty) name else s"autogen_signal_${_autoNameCounter.getAndIncrement()}"

  /** Annotate a signal with debug metadata.
    *
    * Uses Probe API for strong binding that survives optimizations.
    * No-op when debug mode is disabled.
    *
    * @param signal Hardware signal to annotate (chisel3.Data)
    * @param name Hierarchical path (optional)
    */
  def annotate[T <: Data](
    signal: T,
    name:   String = ""
  )(implicit sourceInfo: SourceInfo = UnlocatableSourceInfo): T = {
    chisel3.debuginternal.DebugIntrinsic.emit(signal, getTargetName(name), "User")(sourceInfo)
    signal
  }

  /** Recursively annotate a signal and all nested fields.
    *
    * Generates intrinsics for parent and all children.
    * Recommended for IO bundles.
    *
    * @param signal Root signal to annotate
    * @param name Hierarchical prefix
    */
  def annotateRecursive[T <: Data](
    signal: T,
    name:   String = ""
  )(implicit sourceInfo: SourceInfo = UnlocatableSourceInfo): T = {
    chisel3.debuginternal.DebugIntrinsic.emitRecursive(signal, getTargetName(name), "User")(sourceInfo)
    signal
  }

  /** Emit SystemVerilog with debug metadata preserved.
    * Convenience wrapper that temporarily enables debug mode.
    */
  def emitSystemVerilog(
    gen:  => RawModule,
    args: Array[String] = Array.empty
  ): String = {
    chisel3.debuginternal.DebugIntrinsic.withDebugMode {
      circt.stage.ChiselStage.emitSystemVerilog(
        gen,
        args ++ Array(
          "--preserve-aggregate=all",
          "--export-module-hierarchy",
          "--lowering-options=disallowPackedArrays"
        )
      )
    }
  }

  /** Emit CHIRRTL with debug intrinsics.
    * Convenience wrapper that temporarily enables debug mode.
    */
  def emitCHIRRTL(
    gen:  => RawModule,
    args: Array[String] = Array.empty
  ): String = {
    chisel3.debuginternal.DebugIntrinsic.withDebugMode {
      circt.stage.ChiselStage.emitCHIRRTL(gen, args)
    }
  }
}
