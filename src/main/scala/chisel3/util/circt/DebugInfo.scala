// SPDX-License-Identifier: Apache-2.0

package chisel3.util.circt

import chisel3._
import chisel3.experimental.{SourceInfo, UnlocatableSourceInfo}

/** CIRCT DebugInfo intrinsics for hardware debugging infrastructure.
  *
  * Provides user-facing API for emitting debug metadata intrinsics that preserve
  * high-level type information (Bundles, Vecs, Enums) through FIRRTL->CIRCT->Verilog.
  *
  * == Quick Start ==
  *
  * {{{
  * // Enable debug mode
  * export CHISEL_DEBUG=true
  *
  * // Annotate signals (automatic enable check inside)
  * import chisel3.util.circt.DebugInfo
  *
  * class MyModule extends Module {
  *   val io = IO(new Bundle { val data = Output(UInt(8.W)) })
  *   DebugInfo.annotateRecursive(io, "io")
  * }
  * }}}
  *
  * == Features ==
  *
  * - Source-level debugging in waveform viewers (Tywaves, Surfer)
  * - Interactive debugging with VPI breakpoints (HGDB)
  * - Probe API ensures metadata survives FIRRTL optimizations
  * - Zero overhead when disabled (automatic runtime check)
  *
  * @see [CIRCT Debug Dialect](https://circt.llvm.org/docs/Dialects/Debug/)
  * @see [Tywaves Viewer](https://github.com/rameloni/tywaves-chisel)
  * @see [[chisel3.debuginternal.DebugIntrinsic]] for implementation details
  */
object DebugInfo {

  /** Check if debug mode is enabled via environment or system property.
    *
    * Useful for conditional logic in user code without redundant annotations.
    *
    * @return true if CHISEL_DEBUG=true or -Dchisel.debug=true
    * @example
    * {{{
    * if (DebugInfo.isEnabled) {
    *   println("Debug metadata generation active")
    * }
    * }}}
    */
  def isEnabled: Boolean = chisel3.debuginternal.DebugIntrinsic.isEnabled

  private def getTargetName(name: String): String =
    if (name.nonEmpty) name else "signal"

  /** Annotate a signal with debug metadata.
    *
    * Marks the signal to preserve type information through compilation.
    * Uses Probe API for strong binding that survives DCE/CSE/inlining.
    * Automatically checks if debug mode is enabled (no manual check needed).
    *
    * @param signal Hardware signal to annotate (must be chisel3.Data)
    * @param name Hierarchical path (e.g., "io.ctrl.valid")
    * @param sourceInfo Source location (auto-provided by compiler)
    * @return Original signal (chainable)
    *
    * @example
    * {{{
    * val state = RegInit(0.U(8.W))
    * DebugInfo.annotate(state, "cpu.state")
    * }}}
    *
    * @note Requires `CHISEL_DEBUG=true` or `chisel.debug=true`
    * @note No-op when disabled (zero overhead, automatic check)
    * @see [[annotateRecursive]] for Bundle/Vec traversal
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
    * For nested Bundles/Vecs, generates intrinsics for parent and all children,
    * preserving the full type hierarchy. Recommended for IO bundles.
    * Automatically checks if debug mode is enabled (no manual check needed).
    *
    * @param signal Root signal to annotate
    * @param name Hierarchical prefix (children get "parent.child" names)
    * @param sourceInfo Source location (auto-provided)
    * @return Original signal (chainable)
    *
    * @example
    * {{{
    * class MyBundle extends Bundle {
    *   val x = UInt(8.W)
    *   val y = UInt(8.W)
    * }
    *
    * val io = IO(new MyBundle)
    * DebugInfo.annotateRecursive(io, "io")
    * // Generates: io, io.x, io.y
    * }}}
    *
    * @note For large structures (100+ fields), consider selective annotation
    * @note Vec elements not recursively annotated (performance)
    * @see [[annotate]] for single-signal annotation
    */
  def annotateRecursive[T <: Data](
    signal: T,
    name:   String = ""
  )(implicit sourceInfo: SourceInfo = UnlocatableSourceInfo): T = {
    chisel3.debuginternal.DebugIntrinsic.emitRecursive(signal, getTargetName(name), "User")(sourceInfo)
    signal
  }

  /** Emit SystemVerilog with debug metadata preserved.
    *
    * Convenience method that:
    *  1. Enables debug mode automatically
    *  2. Emits SystemVerilog via firtool with debug-friendly args
    *  3. Restores previous debug state
    *
    * @param gen Module generator function
    * @param args Additional firtool arguments (appended to defaults)
    * @return SystemVerilog string with debug annotations
    *
    * @example
    * {{{
    * import chisel3.util.circt.DebugInfo
    *
    * val sv = DebugInfo.emitSystemVerilog(
    *   new MyModule,
    *   Array("--lowering-options=disallowLocalVariables")
    * )
    *
    * // Output contains:
    * // - Synthesizable Verilog
    * // - Debug metadata (if CIRCT supports hw-debug-info.json export)
    * }}}
    *
    * @note This is a **convenience wrapper** - for production, use ChiselStage
    *       with manual CHISEL_DEBUG=true to control compilation precisely.
    * @see [[emitCHIRRTL]] for FIRRTL output
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

  /** Emit CHIRRTL with debug intrinsics (for manual firtool pipeline).
    *
    * Convenience method that:
    *  1. Enables debug mode automatically
    *  2. Emits high-form FIRRTL (CHIRRTL) with circt_debug_typeinfo intrinsics
    *  3. Restores previous debug state
    *
    * Use this when you want to:
    *  - Manually run firtool with custom passes
    *  - Inspect FIRRTL for debugging
    *  - Save FIRRTL for later processing
    *
    * @param gen Module generator function
    * @param args Additional arguments for ChiselStage (e.g., annotations)
    * @return CHIRRTL string with debug intrinsics
    *
    * @example
    * {{{
    * import chisel3.util.circt.DebugInfo
    *
    * val firrtl = DebugInfo.emitCHIRRTL(new MyModule)
    *
    * // Save for manual firtool
    * import java.nio.file.{Files, Paths}
    * Files.write(Paths.get("output.fir"), firrtl.getBytes)
    *
    * // Then run:
    * // firtool output.fir --lower-to-hw --export-module-hierarchy
    * }}}
    *
    * @note Output contains symbolic references ("target=io.field").
    *       CIRCT resolves these to actual RTL signals during lowering.
    * @see [[emitSystemVerilog]] for direct Verilog output
    */
  def emitCHIRRTL(
    gen: => RawModule,
    args: Array[String] = Array.empty
  ): String = {
    chisel3.debuginternal.DebugIntrinsic.withDebugMode {
      circt.stage.ChiselStage.emitCHIRRTL(gen, args)
    }
  }
}
