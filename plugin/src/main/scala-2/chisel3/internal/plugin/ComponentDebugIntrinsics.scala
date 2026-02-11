// SPDX-License-Identifier: Apache-2.0

package chisel3.internal.plugin

import scala.tools.nsc
import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.PluginComponent

/** Compiler plugin injecting debug intrinsics for Chisel signals.
  *
  * Generates debug metadata for Wire, Reg, IO, and other Chisel Data types.
  * Activated via compiler option: -P:chiselplugin:addDebugIntrinsics
  */
class ComponentDebugIntrinsics(
  val global: Global,
  arguments: ChiselPluginArguments
) extends PluginComponent {
  import global._

  val runsAfter: List[String] = List("typer")
  val phaseName: String = "componentDebugIntrinsics"

  override def description: String = "Inject debug metadata for Chisel Data signals"

  def newPhase(prev: Phase): ComponentDebugIntrinsicsPhase =
    new ComponentDebugIntrinsicsPhase(prev)

  class ComponentDebugIntrinsicsPhase(prev: Phase) extends StdPhase(prev) {
    override def name: String = phaseName

    def apply(unit: CompilationUnit): Unit = {
      if (ChiselPlugin.runComponent(global, arguments)(unit) && arguments.addDebugIntrinsics) {
        // Temporary logging to verify plugin activation in tests
        println(s"[CHISEL-DEBUG-INTRINSICS] Phase running on: ${unit.source.file.name}")

        // TODO: Implement actual transformation logic:
        // 1. Traverse AST for Wire/Reg/IO calls
        // 2. Extract source locations and type information
        // 3. Inject chisel3.reflect.DataMirror.addDebugInfo() calls
      }
    }
  }
}
