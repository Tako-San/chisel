// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3.Data

private[chisel3] trait HasDebugKind {
  def debugKind: String
}

/** Marker trait to suppress typetag emission for SRAMInterface.
  * Metadata emitted via circt_debug_meminfo; future fields added with circt_debug_sraminfo.
  */
private[chisel3] trait HasDebugSramMeta
