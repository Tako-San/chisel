// SPDX-License-Identifier: Apache-2.0

package chisel3.debug.plugin

import scala.tools.nsc.transform.{Transform, TypingTransformers}
import scala.tools.nsc.plugins.PluginComponent

/**
  * AST transformation component for debug intrinsics.
  * 
  * Transforms Chisel hardware construction code to inject debug metadata.
  * Runs after `typer` phase to ensure type information is available.
  * 
  * == Transformation Pattern ==
  * 
  * For each `val name = ChiselConstruct(args)`, transforms to:
  * {{{  
  * val name = {
  *   val _debug_name = ChiselConstruct(args)
  *   if (chisel3.debuginternal.DebugIntrinsic.isEnabled) {
  *     chisel3.debuginternal.DebugIntrinsic.emit(
  *       _debug_name,
  *       "name",
  *       "ConstructType"
  *     )
  *   }
  *   _debug_name
  * }
  * }}}
  * 
  * == Supported Constructs (Phase 1 MVP) ==
  * 
  * - `RegInit(init)` → binding="Reg"
  * 
  * == Future Constructs (Phase 2) ==
  * 
  * - `Reg()` → binding="Reg"
  * - `Wire(tpe)` → binding="Wire"
  * - `IO(bundle)` → binding="IO"
  * - `Mem(size, tpe)` → binding="Mem"
  * - `Module(new M)` → recurse into submodule
  */
class DebugTransformComponent(plugin: DebugIntrinsicsPlugin)
  extends PluginComponent
  with Transform
  with TypingTransformers {
  
  import global._
  
  val phaseName = "chisel-debug-transform"
  val runsAfter = List("typer")  // After type checking
  val runsBefore = List("patmat") // Before pattern matching
  
  override def newTransformer(unit: CompilationUnit): Transformer =
    new DebugTransformer(unit)
  
  /**
    * AST transformer that injects debug intrinsics.
    */
  class DebugTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {
    
    // Statistics
    private var instrumentedCount = 0
    private var skippedCount = 0
    
    override def transform(tree: Tree): Tree = {
      if (!plugin.enabled) return super.transform(tree)
      
      tree match {
        // Match: val name = RegInit(init)
        case vd @ ValDef(mods, name, tpt, rhs)
            if isRegInit(rhs) && shouldInstrument(tree) =>
          
          val instrumented = instrumentValDef(vd, name, rhs, "Reg")
          instrumentedCount += 1
          
          if (plugin.verbose) {
            println(s"[chisel-debug]   Instrumented: val $name = RegInit(...) → binding=Reg")
          }
          
          instrumented
        
        // Default: recurse into children
        case _ => super.transform(tree)
      }
    }
    
    /**
      * Instrument a ValDef with debug intrinsic.
      * 
      * Generates:
      * {{{  
      * val name = {
      *   val _debug_name = originalRHS
      *   if (DebugIntrinsic.isEnabled) {
      *     DebugIntrinsic.emit(_debug_name, "name", binding)
      *   }
      *   _debug_name
      * }
      * }}}
      */
    private def instrumentValDef(
      original: ValDef,
      name: TermName,
      rhs: Tree,
      binding: String
    ): Tree = {
      val transformedRHS = transform(rhs)  // Recurse into RHS
      
      // Generate temporary variable name
      val tmpName = TermName(s"_debug_${name}")
      
      // Build instrumented block
      val block = atPos(original.pos) {
        Block(
          // val _debug_name = originalRHS
          List(
            ValDef(
              Modifiers(),
              tmpName,
              TypeTree(),
              transformedRHS
            ),
            
            // if (DebugIntrinsic.isEnabled) { ... }
            If(
              Select(
                Select(
                  Select(
                    Ident(TermName("chisel3")),
                    TermName("debuginternal")
                  ),
                  TermName("DebugIntrinsic")
                ),
                TermName("isEnabled")
              ),
              
              // DebugIntrinsic.emit(...)
              Apply(
                Select(
                  Select(
                    Select(
                      Ident(TermName("chisel3")),
                      TermName("debuginternal")
                    ),
                    TermName("DebugIntrinsic")
                  ),
                  TermName("emit")
                ),
                List(
                  Ident(tmpName),                      // signal
                  Literal(Constant(name.toString)),    // name
                  Literal(Constant(binding))           // binding
                )
              ),
              
              EmptyTree  // else branch (none)
            )
          ),
          
          // Return _debug_name
          Ident(tmpName)
        )
      }
      
      // Wrap in new ValDef
      treeCopy.ValDef(
        original,
        original.mods,
        original.name,
        original.tpt,
        localTyper.typed(block)  // Type-check the block
      )
    }
    
    /**
      * Detect if Tree is a RegInit call.
      * 
      * Matches:
      * - `RegInit(init)`
      * - `chisel3.RegInit(init)`
      * - Qualified imports: `import chisel3.{RegInit => R}; R(init)`
      */
    private def isRegInit(tree: Tree): Boolean = tree match {
      case Apply(fun, _) => isRegInitSymbol(fun.symbol)
      case TypeApply(Apply(fun, _), _) => isRegInitSymbol(fun.symbol)  // RegInit[T](...)
      case _ => false
    }
    
    private def isRegInitSymbol(sym: Symbol): Boolean = {
      if (sym == null || sym == NoSymbol) return false
      
      val fullName = sym.fullName
      fullName == "chisel3.RegInit" ||
      fullName.endsWith(".RegInit") ||
      sym.name.toString == "RegInit"
    }
    
    /**
      * Check if this tree should be instrumented.
      * 
      * Applies whitelist/blacklist filters based on enclosing class.
      */
    private def shouldInstrument(tree: Tree): Boolean = {
      // Find enclosing class/module
      val enclosingClass = tree.symbol.enclClass
      if (enclosingClass == null || enclosingClass == NoSymbol) return true
      
      val className = enclosingClass.name.toString
      plugin.shouldInstrument(className)
    }
    
    /**
      * Print statistics after compilation unit is done.
      */
    override def transformUnit(unit: CompilationUnit): Unit = {
      super.transformUnit(unit)
      
      if (plugin.verbose && instrumentedCount > 0) {
        println(s"[chisel-debug] Instrumented $instrumentedCount signals in ${unit.source.file.name}")
      }
    }
  }
}
