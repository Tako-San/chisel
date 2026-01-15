// SPDX-License-Identifier: Apache-2.0

package chisel3.debug.plugin

import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.{Plugin, PluginComponent}

/**
  * Compiler plugin for automatic debug metadata instrumentation.
  * 
  * This plugin transforms Chisel code at compile-time to automatically
  * insert debug intrinsics without requiring manual annotations.
  * 
  * == Usage ==
  * 
  * Enable via scalac option:
  * {{{  
  * scalac -Xplugin:chisel-debug-plugin.jar -P:chisel-debug:enable
  * }}}
  * 
  * Or in build.sbt:
  * {{{  
  * addCompilerPlugin("org.chipsalliance" %% "chisel-debug-plugin" % "0.1.0")
  * scalacOptions += "-P:chisel-debug:enable"
  * }}}
  * 
  * == What it does ==
  * 
  * Transforms:
  * {{{  
  * val state = RegInit(0.U)  // User code - no changes
  * }}}
  * 
  * Into:
  * {{{  
  * val state = {
  *   val _tmp = RegInit(0.U)
  *   if (DebugIntrinsic.isEnabled) {
  *     DebugIntrinsic.emit(_tmp, "state", "Reg")
  *   }
  *   _tmp
  * }
  * }}}
  * 
  * == Architecture ==
  * 
  * Plugin runs after `typer` phase:
  * 1. Detects Chisel constructs (RegInit, Wire, IO, Mem)
  * 2. Extracts variable names from AST
  * 3. Injects DebugIntrinsic.emit() calls
  * 4. Preserves original semantics (no behavior change)
  * 
  * @see [[DebugTransformComponent]] for AST transformation logic
  */
class DebugIntrinsicsPlugin(val global: Global) extends Plugin {
  val name = "chisel-debug"
  val description = "Automatic debug metadata instrumentation for Chisel hardware"
  
  // Plugin state
  private var _enabled: Boolean = false
  private var _verbose: Boolean = false
  private var _whitelist: Set[String] = Set.empty
  private var _blacklist: Set[String] = Set.empty
  
  // Components (transformation phases)
  val components = List[PluginComponent](
    new DebugTransformComponent(this)
  )
  
  /**
    * Process plugin options from command line.
    * 
    * Supported options:
    * - `enable` - Enable instrumentation (default: false)
    * - `verbose` - Print debug messages during compilation
    * - `whitelist:Module1,Module2` - Only instrument these modules
    * - `blacklist:Module1,Module2` - Skip these modules
    */
  override def processOptions(options: List[String], error: String => Unit): Unit = {
    for (option <- options) {
      option match {
        case "enable" =>
          _enabled = true
          
        case "verbose" =>
          _verbose = true
          
        case s if s.startsWith("whitelist:") =>
          val modules = s.stripPrefix("whitelist:").split(",").map(_.trim).toSet
          _whitelist = modules
          
        case s if s.startsWith("blacklist:") =>
          val modules = s.stripPrefix("blacklist:").split(",").map(_.trim).toSet
          _blacklist = modules
          
        case _ =>
          error(s"[chisel-debug] Unknown option: $option")
          error(s"  Valid options: enable, verbose, whitelist:Module1,Module2, blacklist:Module1,Module2")
      }
    }
    
    if (_verbose) {
      println(s"[chisel-debug] Plugin loaded")
      println(s"  Enabled: ${_enabled}")
      println(s"  Whitelist: ${if (_whitelist.isEmpty) "<all>" else _whitelist.mkString(", ")}")
      println(s"  Blacklist: ${if (_blacklist.isEmpty) "<none>" else _blacklist.mkString(", ")}")
    }
  }
  
  /** Check if plugin is enabled */
  def enabled: Boolean = _enabled
  
  /** Check if verbose mode is on */
  def verbose: Boolean = _verbose
  
  /** Check if module should be instrumented */
  def shouldInstrument(moduleName: String): Boolean = {
    if (!_enabled) return false
    
    // Blacklist takes priority
    if (_blacklist.contains(moduleName)) return false
    
    // Whitelist if specified
    if (_whitelist.nonEmpty) {
      return _whitelist.contains(moduleName)
    }
    
    // Default: instrument everything
    true
  }
}
