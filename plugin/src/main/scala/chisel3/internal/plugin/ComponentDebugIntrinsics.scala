// SPDX-License-Identifier: Apache-2.0

package chisel3.internal.plugin

import scala.tools.nsc.transform.{Transform, TypingTransformers}
import scala.tools.nsc.Global
import scala.tools.nsc.plugins.PluginComponent

/**
  * Compiler plugin component for automatic debug intrinsic generation.
  * 
  * This transformer runs after the typer phase and automatically instruments
  * Chisel hardware constructs with debug metadata intrinsics.
  * 
  * == Transformation Pattern ==
  * 
  * Input (user code):
  * {{{  
  * val state = RegInit(0.U(8.W))
  * }}}
  * 
  * Output (transformed):
  * {{{  
  * val state = {
  *   val _debug_tmp_state = RegInit(0.U(8.W))
  *   if (chisel3.debuginternal.DebugIntrinsic.isEnabled) {
  *     chisel3.debuginternal.DebugIntrinsic.emit(
  *       _debug_tmp_state,
  *       "state",
  *       "Reg"
  *     )
  *   }
  *   _debug_tmp_state
  * }
  * }}}
  * 
  * == Supported Constructs ==
  * 
  * - `RegInit()`, `Reg()`, `RegNext()` → binding="Reg"
  * - `Wire()`, `WireInit()`, `WireDefault()` → binding="Wire"
  * - `IO()`, `Input()`, `Output()` → binding="IO"
  * - `Mem()`, `SyncReadMem()` → binding="Mem"
  * 
  * == Safety Guarantees ==
  * 
  * - Only instruments `chisel3.Data` types (no false positives)
  * - Recursion guard (won't instrument inside DebugIntrinsic calls)
  * - Type-preserving transformations (no type errors)
  * - Conditional execution (respects `chisel.debug` flag at runtime)
  * 
  * @param plugin The parent ChiselPlugin instance
  * @param global The Scala compiler Global instance
  */
class ComponentDebugIntrinsics(
  plugin: ChiselPlugin,
  val global: Global
) extends PluginComponent with Transform with TypingTransformers {
  
  import global._
  
  val phaseName = "chisel-debug-intrinsics"
  val runsAfter = List("typer")  // Must run after type checking
  override val runsBefore = List("patmat")  // Before pattern matching
  
  /**
    * Create new transformer for each compilation unit.
    */
  override def newTransformer(unit: CompilationUnit): Transformer = 
    new DebugIntrinsicsTransformer(unit)
  
  /**
    * AST transformer that instruments Chisel constructs.
    */
  class DebugIntrinsicsTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {
    
    // Lazy symbol cache (resolved after typer phase)
    lazy val chiselSymbols = new ChiselSymbols(global)
    
    /**
      * Main transformation entry point.
      * 
      * Traverses AST and instruments Val definitions of Chisel constructs.
      */
    override def transform(tree: Tree): Tree = {
      // Early exit if plugin disabled
      if (!plugin.addDebugIntrinsics) {
        return super.transform(tree)
      }
      
      tree match {
        // Pattern: val name = <ChiselConstruct>(...)
        case vd @ ValDef(mods, name, tpt, rhs) 
            if shouldInstrument(vd) =>
          instrumentValDef(vd, name, rhs)
        
        // Recursively transform other nodes
        case _ => super.transform(tree)
      }
    }
    
    /**
      * Check if ValDef should be instrumented.
      * 
      * Criteria:
      * 1. Type is chisel3.Data or subtype
      * 2. RHS is Chisel hardware construct
      * 3. Not already inside DebugIntrinsic call (recursion guard)
      */
    private def shouldInstrument(vd: ValDef): Boolean = {
      try {
        // Check 1: Is this a Chisel Data type?
        val isDataType = vd.symbol != null && 
                         vd.symbol.info != null &&
                         (vd.symbol.info <:< chiselSymbols.DataClass.tpe)
        
        if (!isDataType) return false
        
        // Check 2: Is RHS a Chisel construct?
        val isChiselRHS = vd.rhs match {
          case Apply(fun, _) if fun.symbol != null =>
            chiselSymbols.isRegInit(fun.symbol) ||
            chiselSymbols.isWire(fun.symbol) ||
            chiselSymbols.isIO(fun.symbol) ||
            chiselSymbols.isMem(fun.symbol)
          case _ => false
        }
        
        if (!isChiselRHS) return false
        
        // Check 3: Recursion guard - not inside DebugIntrinsic
        val notInsideIntrinsic = !isInsideDebugIntrinsic
        
        isDataType && isChiselRHS && notInsideIntrinsic
      } catch {
        case _: Exception => false  // Safe fallback on any error
      }
    }
    
    /**
      * Check if currently transforming code inside DebugIntrinsic.
      * 
      * Prevents infinite recursion when instrumenting intrinsic calls.
      */
    private def isInsideDebugIntrinsic: Boolean = {
      var owner = currentOwner
      while (owner != null && owner != NoSymbol && owner != rootMirror.RootClass) {
        if (owner.fullName.startsWith("chisel3.debuginternal.DebugIntrinsic")) {
          return true
        }
        owner = owner.owner
      }
      false
    }
    
    /**
      * Instrument ValDef with debug intrinsic.
      * 
      * Generates:
      * {{{  
      * val name = {
      *   val _debug_tmp_name = <original_rhs>
      *   if (chisel3.debuginternal.DebugIntrinsic.isEnabled) {
      *     chisel3.debuginternal.DebugIntrinsic.emit(
      *       _debug_tmp_name,
      *       "name",
      *       <binding_type>
      *     )
      *   }
      *   _debug_tmp_name
      * }
      * }}}
      */
    private def instrumentValDef(
      original: ValDef,
      name: TermName,
      rhs: Tree
    ): Tree = {
      try {
        // Transform RHS first
        val transformedRHS = super.transform(rhs)
        
        // Detect binding type from RHS
        val binding = detectBinding(transformedRHS)
        
        // Generate temporary variable name
        val tmpName = TermName(s"_debug_tmp_${name.toString}")
        
        // Build instrumented block using quasiquotes
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
        
        // Preserve original ValDef structure with instrumented RHS
        treeCopy.ValDef(
          original,
          original.mods,
          name,
          original.tpt,
          instrumentedBlock
        )
      } catch {
        case e: Exception =>
          // On error, return original tree (fail gracefully)
          reporter.warning(
            original.pos,
            s"ChiselPlugin: Failed to instrument ${name}: ${e.getMessage}"
          )
          original
      }
    }
    
    /**
      * Detect binding type from Chisel construct.
      * 
      * Maps Chisel API calls to binding type strings:
      * - RegInit/Reg/RegNext → "Reg"
      * - Wire/WireInit/WireDefault → "Wire"
      * - IO/Input/Output → "IO"
      * - Mem/SyncReadMem → "Mem"
      */
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

/**
  * Symbol cache for Chisel types and methods.
  * 
  * Lazily resolves symbols to avoid issues with compiler initialization order.
  */
class ChiselSymbols(val global: Global) {
  import global._
  
  // Core Chisel types
  lazy val DataClass: ClassSymbol = 
    rootMirror.getRequiredClass("chisel3.Data")
  
  lazy val ModuleClass: ClassSymbol = 
    rootMirror.getRequiredClass("chisel3.Module")
  
  // Method name sets for detection
  private val RegInitMethods = Set("RegInit", "Reg", "RegNext")
  private val WireMethods = Set("Wire", "WireInit", "WireDefault")
  private val IOMethods = Set("IO", "Input", "Output")
  private val MemMethods = Set("Mem", "SyncReadMem")
  
  /**
    * Check if symbol is a register initialization method.
    */
  def isRegInit(sym: Symbol): Boolean = 
    sym != null && RegInitMethods.contains(sym.name.toString)
  
  /**
    * Check if symbol is a wire initialization method.
    */
  def isWire(sym: Symbol): Boolean = 
    sym != null && WireMethods.contains(sym.name.toString)
  
  /**
    * Check if symbol is an IO method.
    */
  def isIO(sym: Symbol): Boolean = 
    sym != null && IOMethods.contains(sym.name.toString)
  
  /**
    * Check if symbol is a memory method.
    */
  def isMem(sym: Symbol): Boolean = 
    sym != null && MemMethods.contains(sym.name.toString)
}
