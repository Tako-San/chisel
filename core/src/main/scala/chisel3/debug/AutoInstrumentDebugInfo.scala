// SPDX-License-Identifier: Apache-2.0

package chisel3.debug

/** Automatically instruments all signals after Builder.build() with debug information.
  *
  * This object provides a placeholder for automatic debug instrumentation.
  * The implementation is intentionally simplified since debug instrumentation
  * now happens during the FIRRTL conversion phase via the Converter.
  */
private[chisel3] object AutoInstrumentDebugInfo {

  /** Apply automatic instrumentation to a Chisel internal circuit.
    *
    * @param circuit The Chisel internal circuit (currently no-op)
    */
  def apply(circuit: chisel3.internal.firrtl.ir.Circuit): Unit = {
    // The instrumentation is now handled during FIRRTL conversion
    // This method exists for API compatibility
    ()
  }
}