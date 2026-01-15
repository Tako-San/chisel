// SPDX-License-Identifier: Apache-2.0

package chisel3.internal.plugin

import scala.tools.nsc.Global
import scala.tools.nsc.plugins.Plugin
import scala.tools.nsc.plugins.PluginComponent

/**
  * Chisel Compiler Plugin for automatic code transformations.
  * 
  * This plugin provides:
  * - Automatic debug intrinsic generation (zero-code instrumentation)
  * - Signal naming support (future)
  * 
  * == Configuration ==
  * 
  * Enable debug intrinsics:
  * {{{  
  * scalacOptions += "-P:chisel:add-debug-intrinsics"
  * }}}
  * 
  * Or via environment:
  * {{{  
  * export CHISEL_PLUGIN_DEBUG_INTRINSICS=true
  * }}}
  * 
  * == How It Works ==
  * 
  * The plugin runs as part of the Scala compilation pipeline:
  * 
  * 1. Source code parsed → AST
  * 2. Type checker runs (typer phase)
  * 3. Plugin transforms AST (after typer)
  * 4. Transformed code continues through compiler
  * 
  * Transformation is transparent to user code.
  * 
  * @see [[ComponentDebugIntrinsics]] for AST transformation logic
  */
class ChiselPlugin(val global: Global) extends Plugin {
  val name = "chisel-debug"
  val description = "Chisel Compiler Plugin for automatic debug instrumentation"
  
  // Configuration flags
  private var _addDebugIntrinsics: Boolean = false
  
  /**
    * Process plugin options from scalac command line.
    * 
    * Supported options:
    * - `add-debug-intrinsics`: Enable automatic debug metadata generation
    */
  override def processOptions(options: List[String], error: String => Unit): Unit = {
    for (option <- options) {
      option match {
        case "add-debug-intrinsics" => 
          _addDebugIntrinsics = true
        
        case _ => 
          error(s"Unknown Chisel plugin option: $option")
          error(s"Valid options: add-debug-intrinsics")
      }
    }
    
    // Also check environment variable
    if (sys.env.get("CHISEL_PLUGIN_DEBUG_INTRINSICS").exists(_.toLowerCase == "true")) {
      _addDebugIntrinsics = true
    }
  }
  
  /**
    * Plugin components (phases) that perform AST transformations.
    */
  val components: List[PluginComponent] = List(
    new ComponentDebugIntrinsics(this, global)
  )
  
  /**
    * Check if debug intrinsics generation is enabled.
    */
  def addDebugIntrinsics: Boolean = _addDebugIntrinsics
  
  /**
    * Plugin help text.
    */
  override val optionsHelp: Option[String] = Some(
    """  -P:chisel:add-debug-intrinsics    Automatically instrument Chisel constructs with debug metadata
      |                                      (requires chisel.debug=true at runtime)
      |""".stripMargin
  )
}
