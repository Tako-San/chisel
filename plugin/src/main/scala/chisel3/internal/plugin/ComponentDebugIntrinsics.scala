// SPDX-License-Identifier: Apache-2.0

package chisel3.internal.plugin

import scala.tools.nsc.transform.{Transform, TypingTransformers}
import scala.tools.nsc.Global
import scala.tools.nsc.plugins.PluginComponent

/**
  * Compiler plugin for automatic debug intrinsic injection.
  * 
  * Transforms Chisel constructs to inject DebugIntrinsic.emit() calls.
  * All intrinsics use unified Probe API implementation (see DebugIntrinsic.scala).
  * 
  * Transformation:
  * ```scala
  * // Input:
  * val state = RegInit(0.U)
  * 
  * // Output:
  * val state = {
  *   val _tmp = RegInit(0.U)
  *   if (DebugIntrinsic.isEnabled) DebugIntrinsic.emit(_tmp, "state", "Reg")
  *   _tmp
  * }
  * ```
  * 
  * Supported constructs: RegInit, Wire, IO, Mem
  * 
  * Safety:
  * - Type-safe (only chisel3.Data)
  * - Recursion guard (won't instrument DebugIntrinsic itself)
  * - Conditional execution (respects chisel.debug flag)
  * - Graceful degradation (errors fall back to original code)
  */
class ComponentDebugIntrinsics(
  plugin: ChiselPlugin,
  val global: Global
) extends PluginComponent with Transform with TypingTransformers {
  
  import global._
  
  val phaseName = "chisel-debug-intrinsics"
  val runsAfter = List("typer")
  override val runsBefore = List("patmat")
  
  override def newTransformer(unit: CompilationUnit): Transformer = 
    new DebugIntrinsicsTransformer(unit)
  
  class DebugIntrinsicsTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {
    lazy val chiselSymbols = new ChiselSymbols(global)
    
    override def transform(tree: Tree): Tree = {
      if (!plugin.addDebugIntrinsics) return super.transform(tree)
      
      tree match {
        case vd @ ValDef(mods, name, tpt, rhs) if shouldInstrument(vd) =>
          instrumentValDef(vd, name, rhs)
        case _ => super.transform(tree)
      }
    }
    
    private def shouldInstrument(vd: ValDef): Boolean = {
      try {
        val isDataType = vd.symbol != null && 
                         vd.symbol.info != null &&
                         (vd.symbol.info <:< chiselSymbols.DataClass.tpe)
        
        if (!isDataType) return false
        
        val isChiselRHS = vd.rhs match {
          case Apply(fun, _) if fun.symbol != null =>
            chiselSymbols.isRegInit(fun.symbol) ||
            chiselSymbols.isWire(fun.symbol) ||
            chiselSymbols.isIO(fun.symbol) ||
            chiselSymbols.isMem(fun.symbol)
          case _ => false
        }
        
        isDataType && isChiselRHS && !isInsideDebugIntrinsic
      } catch {
        case _: Exception => false
      }
    }
    
    private def isInsideDebugIntrinsic: Boolean = {
      var owner = currentOwner
      while (owner != null && owner != NoSymbol && owner != rootMirror.RootClass) {
        if (owner.fullName.startsWith("chisel3.debuginternal.DebugIntrinsic")) return true
        owner = owner.owner
      }
      false
    }
    
    private def instrumentValDef(original: ValDef, name: TermName, rhs: Tree): Tree = {
      try {
        val transformedRHS = super.transform(rhs)
        val binding = detectBinding(transformedRHS)
        val tmpName = TermName(s"_debug_tmp_${name.toString}")
        
        val instrumentedBlock = localTyper.typed {
          q"""
            {
              val $tmpName = $transformedRHS
              if (_root_.chisel3.debuginternal.DebugIntrinsic.isEnabled) {
                _root_.chisel3.debuginternal.DebugIntrinsic.emit(
                  $tmpName,
                  ${name.toString},
                  $binding
                )
              }
              $tmpName
            }
          """
        }
        
        treeCopy.ValDef(original, original.mods, name, original.tpt, instrumentedBlock)
      } catch {
        case e: Exception =>
          reporter.warning(original.pos, s"ChiselPlugin: Failed to instrument ${name}: ${e.getMessage}")
          original
      }
    }
    
    private def detectBinding(tree: Tree): String = tree match {
      case Apply(fun, _) if fun.symbol != null =>
        if (chiselSymbols.isRegInit(fun.symbol)) "Reg"
        else if (chiselSymbols.isWire(fun.symbol)) "Wire"
        else if (chiselSymbols.isIO(fun.symbol)) "IO"
        else if (chiselSymbols.isMem(fun.symbol)) "Mem"
        else "Unknown"
      case _ => "Unknown"
    }
  }
}

class ChiselSymbols(val global: Global) {
  import global._
  
  lazy val DataClass: ClassSymbol = rootMirror.getRequiredClass("chisel3.Data")
  lazy val ModuleClass: ClassSymbol = rootMirror.getRequiredClass("chisel3.Module")
  
  private val RegInitMethods = Set("RegInit", "Reg", "RegNext")
  private val WireMethods = Set("Wire", "WireInit", "WireDefault")
  private val IOMethods = Set("IO", "Input", "Output")
  private val MemMethods = Set("Mem", "SyncReadMem")
  
  def isRegInit(sym: Symbol): Boolean = sym != null && RegInitMethods.contains(sym.name.toString)
  def isWire(sym: Symbol): Boolean = sym != null && WireMethods.contains(sym.name.toString)
  def isIO(sym: Symbol): Boolean = sym != null && IOMethods.contains(sym.name.toString)
  def isMem(sym: Symbol): Boolean = sym != null && MemMethods.contains(sym.name.toString)
}