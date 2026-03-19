// SPDX-License-Identifier: Apache-2.0

package chisel3.internal.plugin

import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}
import dotty.tools.dotc.core.Contexts.Context

// Scala 3 stub for ChiselComponent debug metadata plugin.
//
// The -P:chiselplugin:emitDebugTypeInfo flag has no effect on Scala 3.
// Elaboration-time fields remain available; compile-time fields
// (sourceLoc, params, ctorParams) will be "unknown" / absent.
class ChiselComponent extends StandardPlugin {
  val name:        String = "ChiselPlugin"
  val description: String = "Chisel compiler plugin (Scala 3 - debug metadata not yet supported)"

  override def init(options: List[String]): List[PluginPhase] = Nil
}
