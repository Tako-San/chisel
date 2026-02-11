package chisel3.internal.plugin

import scala.tools.nsc
import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.{Plugin, PluginComponent}
import scala.tools.nsc.transform.{Transform, TypingTransformers}

/** Compiler plugin component injecting debug intrinsics for Chisel signals.
 *
 * Strategy: Flat injection instead of wrapping ValDef RHS in blocks,
 * we insert `DebugIntrinsic.emit(...)` calls as separate statements
 * immediately after each qualifying ValDef.
 *
 * This avoids DelayedInit/App issues where block-wrapped vals break
 * symbol ownership tracking in later compiler phases.
 *
 * @see https://github.com/scala/bug/issues/11630 (DelayedInit regression)
 * @see https://github.com/chipsalliance/chisel/issues/4015 (Tywaves debug info)
 */
class ComponentDebugIntrinsics(plugin: ChiselPlugin, val global: Global) extends PluginComponent with Transform with TypingTransformers {
  import global._

  val runsAfter = List("typer")
  override val runsBefore = List("patmat") // Run before pattern matching to avoid AST
  val phaseName = "componentDebugIntrinsics"

  def newTransformer(unit: CompilationUnit): Transformer =
    new InjectionTransformer(unit)

  /** Main transformer implementing flat injection strategy */
  class InjectionTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {

    // Cached symbols for performance
    private lazy val emitMethod: Symbol = {
      val intrinsicPkg = rootMirror.getPackageIfDefined("chisel3.debuginternal")
      if (intrinsicPkg == NoSymbol) {
        if (settings.debug.value || plugin.addDebugIntrinsics) {
          reporter.warning(NoPosition, "chisel3.debuginternal package not found - debug intrinsics disabled")
        }
        NoSymbol
      } else {
        val intrinsicObj = intrinsicPkg.info.member(TermName("DebugIntrinsic"))
        if (intrinsicObj == NoSymbol) {
          if (settings.debug.value || plugin.addDebugIntrinsics) {
            reporter.warning(NoPosition, "DebugIntrinsic object not found in chisel3.debuginternal")
          }
          NoSymbol
        } else {
          intrinsicObj.info.member(TermName("emit"))
        }
      }
    }

    private lazy val chiselDataClass: Symbol = {
      val sym = rootMirror.getClassIfDefined("chisel3.Data")
      if (sym == NoSymbol && (settings.debug.value || plugin.addDebugIntrinsics)) {
        reporter.warning(NoPosition, "chisel3.Data not found")
      }
      sym
    }

    private lazy val chiselBundleClass: Symbol =
      rootMirror.getClassIfDefined("chisel3.Bundle")

    private lazy val chiselModuleClass: Symbol =
      rootMirror.getClassIfDefined("chisel3.experimental.BaseModule")

    /** Check if symbol represents Chisel Data type */
    private def isChiselData(sym: Symbol): Boolean = {
      if (sym == NoSymbol || sym.info == NoType) return false
      if (chiselDataClass == NoSymbol) return false
      sym.info.baseClasses.contains(chiselDataClass)
    }

    /** Check if type represents Chisel Data */
    private def isChiselType(tpe: Type): Boolean = {
      if (tpe == NoType) return false
      if (chiselDataClass == NoSymbol) return false
      tpe.baseClasses.contains(chiselDataClass)
    }

    /** Check if owner is a Module or Bundle */
    private def isChiselComponent(owner: Symbol): Boolean = {
      if (owner == NoSymbol) return false
      
      val isModule = if (chiselModuleClass != NoSymbol) {
        owner.baseClasses.contains(chiselModuleClass)
      } else {
        false
      }
      
      val isBundle = if (chiselBundleClass != NoSymbol) {
        owner.baseClasses.contains(chiselBundleClass)
      } else {
        false
      }
      
      isModule || isBundle
    }

    /** Extract binding type from ValDef (Wire, Reg, Input, Output, IO) */
    private def extractBinding(vd: ValDef): Option[String] = {
      
      // Extended matching logic to handle object apply calls (e.g. RegInit.apply)
      def matchesName(tree: Tree, names: Set[String]): Boolean = tree match {
        // Handle explicit .apply() calls on objects
        case Select(qual, TermName("apply")) => matchesName(qual, names)
        // Handle method calls
        case Select(_, TermName(n)) => names.contains(n)
        case Ident(TermName(n))     => names.contains(n)
        case _                      => false
      }

      vd.rhs match {
        // Wire variants: Wire(), WireInit(), WireDefault()
        case Apply(fun, _) if matchesName(fun, Set("Wire", "WireInit", "WireDefault")) =>
          Some("Wire")
        case Apply(TypeApply(fun, _), _) if matchesName(fun, Set("Wire", "WireInit", "WireDefault")) =>
          Some("Wire")

        // Register variants: Reg(), RegInit(), RegNext(), RegEnable()
        case Apply(fun, _) if matchesName(fun, Set("Reg", "RegInit", "RegNext", "RegEnable")) =>
          Some("Reg")
        case Apply(TypeApply(fun, _), _) if matchesName(fun, Set("Reg", "RegInit", "RegNext", "RegEnable")) =>
          Some("Reg")

        // IO wrapper - check direction (Input/Output/Flipped)
        case Apply(fun, List(inner)) if matchesName(fun, Set("IO")) =>
          inner match {
            case Apply(dirFun, _) if matchesName(dirFun, Set("Input")) =>
              Some("Input")
            case Apply(TypeApply(dirFun, _), _) if matchesName(dirFun, Set("Input")) =>
              Some("Input")
            case Apply(dirFun, _) if matchesName(dirFun, Set("Output")) =>
              Some("Output")
            case Apply(TypeApply(dirFun, _), _) if matchesName(dirFun, Set("Output")) =>
              Some("Output")
            case Apply(dirFun, _) if matchesName(dirFun, Set("Flipped")) =>
              Some("Flipped")
            case Apply(TypeApply(dirFun, _), _) if matchesName(dirFun, Set("Flipped")) =>
              Some("Flipped")
            case _ => Some("IO")
          }

        case Apply(TypeApply(fun, _), List(inner)) if matchesName(fun, Set("IO")) =>
          inner match {
            case Apply(dirFun, _) if matchesName(dirFun, Set("Input")) =>
              Some("Input")
            case Apply(TypeApply(dirFun, _), _) if matchesName(dirFun, Set("Input")) =>
              Some("Input")
            case Apply(dirFun, _) if matchesName(dirFun, Set("Output")) =>
              Some("Output")
            case Apply(TypeApply(dirFun, _), _) if matchesName(dirFun, Set("Output")) =>
              Some("Output")
            case Apply(dirFun, _) if matchesName(dirFun, Set("Flipped")) =>
              Some("Flipped")
            case Apply(TypeApply(dirFun, _), _) if matchesName(dirFun, Set("Flipped")) =>
              Some("Flipped")
            case _ => Some("IO")
          }

        case _ => None
      }
    }

    /** Determine if a ValDef should be instrumented */
    private def shouldInstrument(vd: ValDef): Boolean = {
      val mods = vd.mods

      // DIAGNOSTIC: Always log ValDef inspection
      if (plugin.addDebugIntrinsics) {
        reporter.warning(vd.pos, s"[DIAG] Inspecting ValDef: ${vd.name} (flags: ${mods.flags})")
      }

      // Skip constructor parameters
      if (mods.hasFlag(Flag.PARAM)) {
        if (plugin.addDebugIntrinsics) {
          reporter.warning(vd.pos, s"[DIAG] ${vd.name} SKIPPED: PARAM flag")
        }
        return false
      }

      // Skip synthetic fields, BUT allow Chisel Data types
      if (mods.hasFlag(Flag.SYNTHETIC)) {
        if (!isChiselData(vd.symbol)) {
          if (plugin.addDebugIntrinsics) {
            reporter.warning(vd.pos, s"[DIAG] ${vd.name} SKIPPED: SYNTHETIC but not Chisel Data")
          }
          return false
        }
      }

      // Skip private Chisel internal fields (convention: start with '_')
      if (vd.name.toString.startsWith("_")) {
        if (plugin.addDebugIntrinsics) {
          reporter.warning(vd.pos, s"[DIAG] ${vd.name} SKIPPED: starts with underscore")
        }
        return false
      }

      // Must have valid symbol and be Chisel Data
      val validSym = vd.symbol != NoSymbol
      val isData = isChiselData(vd.symbol)
      
      if (!validSym) {
        if (plugin.addDebugIntrinsics) {
          reporter.warning(vd.pos, s"[DIAG] ${vd.name} SKIPPED: NoSymbol")
        }
        return false
      }
      
      if (!isData) {
        if (plugin.addDebugIntrinsics) {
          reporter.warning(vd.pos, s"[DIAG] ${vd.name} SKIPPED: Not Chisel Data. Symbol type: ${vd.symbol.info}, Base classes: ${vd.symbol.info.baseClasses.take(5)}")
        }
        return false
      }

      if (plugin.addDebugIntrinsics) {
        reporter.warning(vd.pos, s"[DIAG] ${vd.name} PASSED all checks!")
      }
      
      true
    }

    /** Create typed DebugIntrinsic.emit call for a ValDef */
    private def mkEmitCall(vd: ValDef, bindingType: String): Tree = {
      if (emitMethod == NoSymbol) return EmptyTree

      if (settings.debug.value || plugin.addDebugIntrinsics) {
        reporter.warning(
          vd.pos,
          s"[INSTRUMENT-V2] ${vd.name} as $bindingType at ${vd.pos.source}:${vd.pos.line}"
        )
      }

      val emitCall = Apply(
        Select(
          Select(
            Select(Ident(TermName("chisel3")), TermName("debuginternal")),
            TermName("DebugIntrinsic")
          ),
          TermName("emit")
        ),
        List(
          Ident(vd.symbol),
          Literal(Constant(vd.name.toString)),
          Literal(Constant(bindingType))
        )
      )

      localTyper.typed(emitCall)
    }

    /** Inject emit calls into list of statements (flat strategy). */
    private def injectIntoStats(stats: List[Tree]): List[Tree] = {
      // DIAGNOSTIC: Log stats inspection
      if (plugin.addDebugIntrinsics) {
        reporter.warning(NoPosition, s"[DIAG] injectIntoStats called with ${stats.length} statements")
        stats.foreach {
          case vd: ValDef => reporter.warning(vd.pos, s"[DIAG] - Found ValDef: ${vd.name}")
          case other => reporter.warning(other.pos, s"[DIAG] - Found: ${other.getClass.getSimpleName}")
        }
      }

      stats.flatMap { stat =>
        stat match {
          case vd @ ValDef(mods, name, tpt, rhs) if shouldInstrument(vd) =>
            extractBinding(vd) match {
              case Some(binding) =>
                val emitCall = mkEmitCall(vd, binding)
                if (emitCall != EmptyTree) {
                  List(vd, emitCall)
                } else {
                  List(vd)
                }
              case None =>
                if (plugin.addDebugIntrinsics) {
                  reporter.warning(vd.pos, s"[DIAG] ${vd.name} passed shouldInstrument but no binding found")
                }
                List(vd)
            }
          case other =>
            List(other)
        }
      }
    }

    /** Main transformation entry point with explicit recursion control */
    override def transform(tree: Tree): Tree = tree match {
      // Handle class/object bodies (Template)
      case tmpl @ Template(parents, self, body) =>
        if (settings.debug.value || plugin.addDebugIntrinsics) {
          reporter.warning(tmpl.pos, s"[DEBUG-TEMPLATE-V2] Visiting template of ${currentOwner.name} with ${body.length} statements")
          reporter.warning(tmpl.pos, s"[DIAG] currentOwner=${currentOwner.name}, isChiselComponent=${isChiselComponent(currentOwner)}")
        }
        
        // Only process templates of Chisel components
        val shouldProcess = isChiselComponent(currentOwner)
        
        if (plugin.addDebugIntrinsics) {
          reporter.warning(tmpl.pos, s"[DIAG] shouldProcess=${shouldProcess}")
        }
        
        if (shouldProcess) {
          val injected = injectIntoStats(body)
          val transformed = injected.map(transform)

          val newTmpl =
            treeCopy.Template(tmpl, parents.map(transform), transform(self).asInstanceOf[ValDef], transformed)
          localTyper.typedPos(tmpl.pos)(newTmpl)
        } else {
          super.transform(tmpl)
        }

      // Handle method bodies and local blocks
      case blk @ Block(stats, expr) =>
        if (plugin.addDebugIntrinsics) {
          reporter.warning(blk.pos, s"[DIAG] Processing Block with ${stats.length} stats")
        }
        val injected = injectIntoStats(stats)
        val transformed = injected.map(transform)

        val newBlk = treeCopy.Block(blk, transformed, transform(expr))
        localTyper.typedPos(blk.pos)(newBlk)

      // Handle lazy val (DefDef with LAZY flag)
      case dd @ DefDef(mods, name, tparams, vparamss, tpt, rhs) if mods.hasFlag(Flag.LAZY) =>

        // Check return type of the method (not the method itself)
        val resultType = dd.symbol.info.resultType
        val isChiselResult = isChiselType(resultType)

        if (isChiselResult) {
          if (settings.debug.value || plugin.addDebugIntrinsics) {
            reporter.warning(
              dd.pos,
              s"[INSTRUMENT-V2] lazy val ${dd.name} with result type $resultType"
            )
          }

          val newRhs = rhs match {
            case Block(stats, expr) =>
              val injected = injectIntoStats(stats)
              val transformed = injected.map(transform)
              treeCopy.Block(rhs, transformed, transform(expr))
            case other => transform(other)
          }
          treeCopy.DefDef(dd, mods, name, tparams, vparamss, tpt, newRhs)
        } else {
          super.transform(dd)
        }

      // Handle Bundle AND Module constructors
      case dd @ DefDef(mods, nme.CONSTRUCTOR, tparams, vparamss, tpt, rhs) =>
        val ownerClass = currentOwner
        val isComponent = isChiselComponent(ownerClass)

        if (isComponent) {
          if (settings.debug.value || plugin.addDebugIntrinsics) {
            reporter.warning(
              dd.pos,
              s"[INSTRUMENT-V2] Component constructor in ${ownerClass.name}"
            )
          }

          val newRhs = rhs match {
            case Block(stats, expr) =>
              val injected = injectIntoStats(stats)
              val transformed = injected.map(transform)
              treeCopy.Block(rhs, transformed, transform(expr))
            case other => transform(other)
          }
          treeCopy.DefDef(dd, mods, nme.CONSTRUCTOR, tparams, vparamss, tpt, newRhs)
        } else {
          super.transform(dd)
        }

      case _ =>
        super.transform(tree)
    }
  }
}
