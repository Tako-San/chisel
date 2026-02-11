// SPDX-License-Identifier: Apache-2.0

package chisel3.internal.plugin

import scala.tools.nsc
import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.PluginComponent

class ComponentDebugIntrinsics(val global: Global, arguments: ChiselPluginArguments) extends PluginComponent {
  import global._
  val runsAfter: List[String] = List("typer")
  val phaseName: String = "componentDebugIntrinsics"
  override def description: String = "Debug Intrinsics (Skeleton)"

  def newPhase(prev: Phase): Phase = new StdPhase(prev) {
    override def name: String = phaseName
    def apply(unit: CompilationUnit): Unit = {
        if (arguments.addDebugIntrinsics) {
            global.reporter.warning(unit.body.pos, "Debug Intrinsics Phase Running")
        }
    }
  }
}
