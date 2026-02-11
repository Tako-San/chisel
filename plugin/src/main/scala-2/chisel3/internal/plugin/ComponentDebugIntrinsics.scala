// SPDX-License-Identifier: Apache-2.0

package chisel3.internal.plugin

import scala.tools.nsc
import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.PluginComponent

/** Compiler plugin injecting debug intrinsics for Chisel signals.
  * 
  * This is a minimal dummy implementation for TDD.
  * It only verifies that the plugin loads correctly.
  */
class ComponentDebugIntrinsics(
  val global: Global,
  arguments: ChiselPluginArguments
) extends PluginComponent {
  import global._
  
  val runsAfter: List[String] = List("typer")
  val phaseName: String = "componentDebugIntrinsics"
  
  override def description: String = "[TDD] Inject debug metadata for Chisel Data signals"
  
  def newPhase(prev: Phase): ComponentDebugIntrinsicsPhase = 
    new ComponentDebugIntrinsicsPhase(prev)
  
  class ComponentDebugIntrinsicsPhase(prev: Phase) extends StdPhase(prev) {
    override def name: String = phaseName
    
    def apply(unit: CompilationUnit): Unit = {
      // Always print for TDD - simplify testing
      // Use println to make output visible in scala-cli
      println("[CHISEL-DEBUG-INTRINSICS] Phase running on: " + unit.source.file.name)
      
      // TODO: Add actual transformation logic here in future iterations
    }
  }
}
