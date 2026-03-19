// SPDX-License-Identifier: Apache-2.0
package chisel3.internal

/** Controls how DebugMetaEmitter treats a Data node.
  *
  * @param emitTypeTag Suppresses circt_debug_typetag if false (e.g., SRAMInterface uses circt_debug_meminfo).
  * @param extraKind   Adds "kind" field to typetag JSON if Some(s) (e.g., MixedVec vs Record).
  */
private[chisel3] final case class DebugMetaPolicy(
  emitTypeTag: Boolean = true,
  extraKind:   Option[String] = None
)

/** Mix into Data subclasses needing non-default debug emission policy.
  *
  * Implementors (e.g., MixedVec, SRAMInterface) live in src/main while
  * this trait lives in core, preserving the core -> util layering.
  */
private[chisel3] trait HasDebugMetaPolicy {
  def debugMetaPolicy: DebugMetaPolicy = DebugMetaPolicy()
}
