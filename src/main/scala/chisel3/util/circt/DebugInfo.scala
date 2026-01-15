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
  * == Overview ==
  * 
  * Debug information enables:
  * - '''Source-level debugging''' in waveform viewers (Tywaves, Surfer, etc.)
  * - '''Interactive debuggers''' (HGDB) with VPI breakpoints
  * - '''Hardware trace correlation''' with software execution
  * - '''Automated test generation''' from RTL types
  * 
  * The intrinsics are lowered by CIRCT's Debug dialect to:
  * - `dbg.*` MLIR operations (type definitions, source locations)
  * - JSON debug manifests (`hw-debug-info.json`)
  * - VCD/FST metadata annotations
  * 
  * == Usage ==
  * 
  * '''Enable debug mode:'''
  * {{{  
  * // Via environment variable
  * export CHISEL_DEBUG=true
  * 
  * // Or via system property
  * sbt -Dchisel.debug=true
  * 
  * // Or programmatically
  * sys.props("chisel.debug") = "true"
  * }}}
  * 
  * '''Annotate signals:'''
  * {{{  
  * import chisel3.util.circt.DebugInfo
  * 
  * class MyModule extends Module {
  *   val io = IO(new Bundle {
  *     val ctrl = Input(new ControlBundle)
  *     val data = Output(Vec(4, UInt(8.W)))
  *   })
  *   
  *   // Explicitly annotate a signal
  *   DebugInfo.annotate(io.ctrl, "io.ctrl")
  *   
  *   // Recursively annotate all nested fields
  *   DebugInfo.annotateRecursive(io, "io")
  * }
  * }}}
  * 
  * == Implementation ==
  * 
  * Uses Chisel 6+ Probe API for reliable signal binding:
  * {{{  
  * wire _probe = probe(io.field)
  * intrinsic(circt_debug_typeinfo<target="io.field", ...>, read(_probe))
  * }}}
  * 
  * This ensures metadata→RTL mapping survives FIRRTL optimizations (DCE, CSE, inlining).
  * 
  * @see [[https://circt.llvm.org/docs/Dialects/Debug/ CIRCT Debug Dialect]]
  * @see [[https://github.com/rameloni/tywaves-chisel Tywaves Viewer]]
  * @see [[https://github.com/Kuree/hgdb HGDB Interactive Debugger]]
  * @see [[chisel3.debuginternal.DebugIntrinsic]] for internal implementation
  */
object DebugInfo {
  
  /** Annotate a Chisel Data signal with debug metadata.
    * 
    * This marks the signal to have its type information preserved through
    * the compilation pipeline and exported in debug manifests.
    * 
    * The intrinsic is emitted as:
    * {{{  
    * intrinsic(circt_debug_typeinfo<
    *   target="io.field",
    *   typeName="MyBundle",
    *   binding="IO",
    *   parameters="width=8",
    *   sourceFile="MyModule.scala",
    *   sourceLine=42
    * >, read(_probe_io_field))
    * }}}
    * 
    * @param signal The Chisel signal to annotate (must be hardware-typed Data)
    * @param name Hierarchical name for the signal (e.g., "io.ctrl.valid"). 
    *             If empty, uses signal's binding name. Should match VCD signal path.
    * @param sourceInfo Source location information (automatically provided by compiler).
    *                   Captures file name and line number for debugging.
    * @return The original signal unchanged (passthrough for use in expressions)
    * 
    * @example Basic usage:
    * {{{  
    * val myReg = RegInit(0.U(8.W))
    * DebugInfo.annotate(myReg, "counter")
    * // Generates: intrinsic(circt_debug_typeinfo<target="counter", typeName="UInt", ...>)
    * }}}
    * 
    * @example Chaining:
    * {{{  
    * val io = IO(new MyBundle)
    * val annotatedIO = DebugInfo.annotate(io, "top_io")
    * annotatedIO.field1 := 42.U  // Can continue using the signal
    * }}}
    * 
    * @example Custom name:
    * {{{  
    * val data = Wire(UInt(32.W))
    * DebugInfo.annotate(data, "cpu.alu.result")
    * }}}
    * 
    * @note Requires debug mode enabled:
    *       - Environment: `CHISEL_DEBUG=true`
    *       - Property: `sys.props("chisel.debug") = "true"`
    * @note When disabled, this method becomes a no-op with zero overhead
    * @see [[annotateRecursive]] for Bundle/Vec traversal
    * @see [[isEnabled]] to check if debug mode is active
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
    * This is the recommended method for annotating IO bundles or complex
    * data structures, as it captures the complete type information.
    * 
    * @param signal The root signal to annotate recursively
    * @param name Hierarchical name prefix (e.g., "io"). 
    *             Child fields will be suffixed ("io.field1", "io.field2", etc.)
    * @param sourceInfo Source location (automatically provided)
    * @return The original signal unchanged (passthrough)
    * 
    * @example Nested Bundle:
    * {{{  
    * class InnerBundle extends Bundle {
    *   val x = UInt(8.W)
    *   val y = UInt(8.W)
    * }
    * 
    * class OuterBundle extends Bundle {
    *   val inner = new InnerBundle
    *   val flag = Bool()
    * }
    * 
    * val io = IO(new OuterBundle)
    * DebugInfo.annotateRecursive(io, "io")
    * 
    * // Generates intrinsics for:
    * // - io (OuterBundle)
    * // - io.inner (InnerBundle)
    * // - io.inner.x (UInt<8>)
    * // - io.inner.y (UInt<8>)
    * // - io.flag (Bool)
    * }}}
    * 
    * @example Vec of UInts:
    * {{{  
    * val vec = Wire(Vec(4, UInt(8.W)))
    * DebugInfo.annotateRecursive(vec, "registers")
    * 
    * // Generates intrinsics for:
    * // - registers (Vec[4, UInt<8>])
    * // Note: Individual elements not annotated by default (performance)
    * }}}
    * 
    * @example Module IO:
    * {{{  
    * class MyModule extends Module {
    *   val io = IO(new Bundle {
    *     val in = Input(UInt(8.W))
    *     val out = Output(UInt(8.W))
    *   })
    *   
    *   // Annotate entire IO bundle
    *   DebugInfo.annotateRecursive(io, "io")
    * }
    * }}}
    * 
    * @note For large structures (100+ fields), consider selective annotation
    *       to avoid FIRRTL size bloat
    * @note Vec elements are not recursively annotated by default (configurable)
    * @see [[annotate]] for single-signal annotation
    */
  def annotateRecursive[T <: Data](
    signal: T,
    name:   String = ""
  )(implicit sourceInfo: SourceInfo = UnlocatableSourceInfo): T = {
    val targetName = if (name.nonEmpty) name else "signal"
    chisel3.debuginternal.DebugIntrinsic.emitRecursive(signal, targetName, "User")
    signal
  }
  
  /** Check if Chisel debug info generation is currently enabled.
    * 
    * Debug intrinsic generation is controlled by:
    * - Environment variable: `CHISEL_DEBUG=true`
    * - System property: `-Dchisel.debug=true` or `sys.props("chisel.debug") = "true"`
    * 
    * When disabled, all `annotate()` and `annotateRecursive()` calls become
    * no-ops with zero overhead (no intrinsics generated, no FIRRTL bloat).
    * 
    * @return `true` if debug info generation is enabled, `false` otherwise
    * 
    * @example Conditional annotation:
    * {{{  
    * if (DebugInfo.isEnabled) {
    *   println("Debug intrinsics will be generated")
    * }
    * 
    * val io = IO(new MyBundle)
    * DebugInfo.annotateRecursive(io, "io")  // No-op if disabled
    * }}}
    * 
    * @example Guard expensive operations:
    * {{{  
    * if (DebugInfo.isEnabled) {
    *   // Only compute debug info when needed
    *   val debugSignals = collectAllSignals()
    *   debugSignals.foreach { s => DebugInfo.annotate(s) }
    * }
    * }}}
    * 
    * @note This check is also performed internally by annotate methods,
    *       so explicit checking is optional
    */
  def isEnabled: Boolean = {
    chisel3.debuginternal.DebugIntrinsic.isEnabled
  }
}
