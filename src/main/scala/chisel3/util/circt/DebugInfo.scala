// SPDX-License-Identifier: Apache-2.0

package chisel3.util.circt

import chisel3._
import chisel3.experimental.{SourceInfo, UnlocatableSourceInfo}

/** CIRCT DebugInfo intrinsics for hardware debugging infrastructure.
  * 
  * This object provides the user-facing API for emitting debug metadata intrinsics
  * that preserve high-level type information (Records, Bundles, Vecs, Enums) through
  * the FIRRTL→CIRCT→Verilog compilation pipeline.
  * 
  * Debug information enables:
  * - Source-level debugging in waveform viewers (Tywaves, Surfer, etc.)
  * - Interactive debuggers (HGDB) with VPI breakpoints
  * - Hardware trace correlation with software execution
  * - Automated test generation from RTL types
  * 
  * The intrinsics are lowered by CIRCT's Debug dialect to:
  * - `dbg.*` MLIR operations (type definitions, source locations)
  * - JSON debug manifests (`hw-debug-info.json`)
  * - VCD/FST metadata annotations
  * 
  * @example {{{\n  * import chisel3.util.circt.DebugInfo
  * 
  * class MyModule extends Module {
  *   val io = IO(new Bundle {
  *     val ctrl = Input(new ControlBundle)
  *     val data = Output(Vec(4, UInt(8.W)))
  *   })
  *   
  *   // Explicitly annotate a signal for debug metadata
  *   DebugInfo.annotate(io.ctrl)
  *   
  *   // Or enable automatic annotation via CHISEL_DEBUG=true env var
  * }
  * }}}
  * 
  * @see [[https://circt.llvm.org/docs/Dialects/Debug/ CIRCT Debug Dialect]]
  * @see [[https://github.com/rameloni/tywaves-chisel Tywaves Viewer]]
  * @see [[https://github.com/Kuree/hgdb HGDB Interactive Debugger]]
  */
object DebugInfo {
  
  /** Annotate a Chisel Data signal with debug metadata.
    * 
    * This marks the signal to have its type information preserved through
    * the compilation pipeline and exported in debug manifests.
    * 
    * The intrinsic is emitted as:
    * {{{\n    *   intrinsic(circt_debug_typeinfo<target="io.field", typeName="MyBundle", ...> : UInt<1>)
    * }}}
    * 
    * @param signal The Chisel signal to annotate
    * @param name Optional hierarchical name override (default: uses signal's binding name)
    * @param sourceInfo Source location information (automatically provided by compiler)
    * @return The original signal (passthrough for use in expressions)
    * 
    * @example {{{\n    * val myReg = RegInit(0.U(8.W))
    * DebugInfo.annotate(myReg)  // Adds debug metadata intrinsic
    * 
    * val io = IO(new MyBundle)
    * DebugInfo.annotate(io, "top_io")  // Custom name
    * }}}
    */
  def annotate[T <: Data](
    signal: T,
    name:   String = ""
  )(implicit sourceInfo: SourceInfo = UnlocatableSourceInfo): T = {
    // Delegate to internal implementation
    val targetName = if (name.nonEmpty) name else "signal"
    chisel3.debuginternal.DebugIntrinsic.emit(signal, targetName, "User")
    signal
  }
  
  /** Recursively annotate a signal and all its nested fields (Bundles/Vecs).
    * 
    * For complex structures like nested Bundles, this generates intrinsics
    * for the parent and all children, preserving the full type hierarchy.
    * 
    * @param signal The root signal to annotate recursively
    * @param name Optional hierarchical name override
    * @param sourceInfo Source location (auto-provided)
    * @return The original signal (passthrough)
    * 
    * @example {{{\n    * class NestedBundle extends Bundle {
    *   val inner = new Bundle {
    *     val x = UInt(8.W)
    *     val y = UInt(8.W)
    *   }
    *   val flag = Bool()
    * }
    * 
    * val io = IO(new NestedBundle)
    * DebugInfo.annotateRecursive(io, "io")  // Annotates io, io.inner, io.inner.x, etc.
    * }}}
    */
  def annotateRecursive[T <: Data](
    signal: T,
    name:   String = ""
  )(implicit sourceInfo: SourceInfo = UnlocatableSourceInfo): T = {
    val targetName = if (name.nonEmpty) name else "signal"
    chisel3.debuginternal.DebugIntrinsic.emitRecursive(signal, targetName, "User")
    signal
  }
  
  /** Check if Chisel debug info generation is enabled.
    * 
    * Debug intrinsic generation can be controlled via:
    * - Environment variable: `CHISEL_DEBUG=true`
    * - System property: `-Dchisel.debug=true`
    * 
    * When disabled, `annotate()` calls become no-ops with zero overhead.
    * 
    * @return true if debug info generation is enabled
    */
  def isEnabled: Boolean = {
    chisel3.debuginternal.DebugIntrinsic.isEnabled
  }
}
