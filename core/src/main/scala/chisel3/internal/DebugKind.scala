// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

private[chisel3] trait HasDebugKind {
  def debugKind: String
}

private[chisel3] trait HasDebugSramMeta {
  def debugSramDepth:           BigInt
  def debugSramNumReadPorts:    Int
  def debugSramNumWritePorts:   Int
  def debugSramNumRWPorts:      Int
  def debugSramMasked:          Boolean
  def debugSramMaskGranularity: Int
}
