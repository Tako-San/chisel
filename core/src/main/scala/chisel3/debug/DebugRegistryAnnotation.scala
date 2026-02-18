// SPDX-License-Identifier: Apache-2.0

package chisel3.debug

import firrtl.annotations.{Annotation, NoTargetAnnotation}
import firrtl.options.Unserializable

/** Annotation containing debug registry entries collected during elaboration.
  *
  * This annotation is created by the Elaborate phase and consumed by CollectDebugInfo.
  * It contains all debug entries that were registered during module construction.
  *
  * @param entries Map of debug ID to DebugEntry containing registered debug information
  */
case class DebugRegistryAnnotation(entries: Map[String, DebugEntry]) extends NoTargetAnnotation with Unserializable
