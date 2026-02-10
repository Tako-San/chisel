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
  *   _root_.chisel3.debuginternal.DebugIntrinsic.emit(_tmp, "state", "Reg")
  *   _tmp
  * }
  * ```
  * 
  * Supported constructs: RegInit, Wire, IO, Mem
  * 
  * Safety:
  * - Type-safe (only chisel3.Data)
  * - Conditional execution (respects chisel.debug flag inside emit)
  * - Graceful degradation (errors fall back to original code)
  */
class ComponentDebugIntrinsics(
  plugin:     ChiselPlugin,
  val global: Global
) extends PluginComponent
    with Transform
    with TypingTransformers {

  import global._

  val phaseName = "chisel-debug-intrinsics"
  val runsAfter = List("typer")
  override val runsBefore = List("patmat")

  private object chiselSymbols {
    lazy val DataClass:   ClassSymbol = rootMirror.getRequiredClass("chisel3.Data")
    lazy val ModuleClass: ClassSymbol = rootMirror.getRequiredClass("chisel3.Module")

    private val RegInitMethods = Set("RegInit", "Reg", "RegNext")
    private val WireMethods = Set("Wire", "WireInit", "WireDefault")
    private val IOMethods = Set("IO")
    private val MemMethods = Set("Mem", "SyncReadMem")

    def isSubtypeOfData(tpe: Type): Boolean = {
      tpe <:< DataClass.tpe
    }

    def isRegInit(sym: Symbol): Boolean = sym != null && RegInitMethods.contains(sym.name.toString)
    def isWire(sym:    Symbol): Boolean = sym != null && WireMethods.contains(sym.name.toString)
    def isIO(sym:      Symbol): Boolean = sym != null && IOMethods.contains(sym.name.toString)
    def isMem(sym:     Symbol): Boolean = sym != null && MemMethods.contains(sym.name.toString)
  }

  override def newTransformer(unit: CompilationUnit): Transformer =
    new DebugIntrinsicsTransformer(unit)

  class DebugIntrinsicsTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {
    override def transform(tree: Tree): Tree = {
      if (!plugin.addDebugIntrinsics) return super.transform(tree)

      tree match {
        case vd: ValDef =>
          // Optimization: Check if instrumentable AND get binding type in one pass
          getChiselBinding(vd) match {
            case Some(binding) => instrumentValDef(vd, binding)
            case None          => super.transform(tree)
          }
        case _ => super.transform(tree)
      }
    }

    /**
      * Check if ValDef should be instrumented and return the binding type.
      * Guards: (1) Chisel constructor (RegInit/Wire/IO/Mem), (2) chisel3.Data type (relaxed for Mem)
      * 
      * @return Some(bindingType) if instrumentable, None otherwise
      */
    private def getChiselBinding(vd: ValDef): Option[String] = {
      try {
        vd.rhs match {
          case Apply(fun, _) if fun.symbol != null =>
            // 1. Identify constructor type
            val bindingOpt = 
              if (chiselSymbols.isRegInit(fun.symbol)) Some("Reg")
              else if (chiselSymbols.isWire(fun.symbol)) Some("Wire")
              else if (chiselSymbols.isIO(fun.symbol)) Some("IO")
              else if (chiselSymbols.isMem(fun.symbol)) Some("Mem")
              else None

            bindingOpt match {
              case Some(binding) =>
                // 2. Validate type compatibility
                // We typically require subtype of Data, but strict check might fail for Mem
                // in some compiler phases or versions. We allow Mem explicitly.
                val isData = vd.symbol != null && 
                             vd.symbol.info != null && 
                             chiselSymbols.isSubtypeOfData(vd.symbol.info)

                if (isData || binding == "Mem") Some(binding) else None
                
              case None => None
            }
          case _ => None
        }
      } catch {
        case _: Exception => None
      }
    }

    private def instrumentValDef(original: ValDef, binding: String): Tree = {
      val name = original.name
      val rhs = original.rhs

      try {
        val transformedRHS = super.transform(rhs)
        val tmpName = TermName(s"_debug_tmp_${name.toString}")

        val instrumentedBlock = localTyper.typed {
          q"""
            {
              val $tmpName = $transformedRHS
              _root_.chisel3.debuginternal.DebugIntrinsic.emit(
                $tmpName,
                ${name.toString},
                $binding
              )
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
  }
}
