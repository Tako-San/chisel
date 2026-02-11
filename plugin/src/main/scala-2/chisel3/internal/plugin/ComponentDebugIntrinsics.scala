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

      // DEBUG: Log RHS structure for critical signals
      if (vd.name.toString == "state" || vd.name.toString == "reg" || vd.name.toString == "regs") {
         reporter.warning(vd.pos, s"[DEBUG] Inspecting RHS for ${vd.name}: ${showRaw(vd.rhs)}")
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

      // Skip constructor parameters
      if (mods.hasFlag(Flag.PARAM)) return false

      // Skip synthetic fields, BUT allow Chisel Data types
      if (mods.hasFlag(Flag.SYNTHETIC)) {
        if (!isChiselData(vd.symbol)) return false
      }

      // Skip private Chisel internal fields (convention: start with '_')
      if (vd.name.toString.startsWith("_")) return false

      // Must have valid symbol and be Chisel Data
      val validSym = vd.symbol != NoSymbol
      val isData = isChiselData(vd.symbol)
      
      // DEBUG: Log filtering decisions for critical signals
      if (vd.name.toString == "state" || vd.name.toString == "reg" || vd.name.toString == "regs") {
        if (!validSym) {
          reporter.warning(vd.pos, s"[DEBUG] ${vd.name} skipped: NoSymbol")
        }
        if (!isData) {
          reporter.warning(vd.pos, s"[DEBUG] ${vd.name} skipped: Not Chisel Data. Base classes: ${vd.symbol.info.baseClasses}")
        }
      }
      
      validSym && isData
    }

    /** Create typed DebugIntrinsic.emit call for a ValDef */
    private def mkEmitCall(vd: ValDef, bindingType: String): Tree = {
      if (emitMethod == NoSymbol) return EmptyTree

      if (settings.debug.value || plugin.addDebugIntrinsics) {
        reporter.warning(
          vd.pos,
          s"[INSTRUMENT] ${vd.name} as $bindingType at ${vd.pos.source}:${vd.pos.line}"
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
                if (vd.name.toString == "state" || vd.name.toString == "reg" || vd.name.toString == "regs") {
                   reporter.warning(vd.pos, s"[DEBUG] Failed to extract binding for ${vd.name}")
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
        val injected = injectIntoStats(body)
        val transformed = injected.map(transform)

        val newTmpl =
          treeCopy.Template(tmpl, parents.map(transform), transform(self).asInstanceOf[ValDef], transformed)
        localTyper.typedPos(tmpl.pos)(newTmpl)

      // Handle method bodies and local blocks
      case blk @ Block(stats, expr) =>
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
              s"[INSTRUMENT] lazy val ${dd.name} with result type $resultType"
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

      // Handle Bundle constructor (fields of Bundle classes)
      case dd @ DefDef(mods, nme.CONSTRUCTOR, tparams, vparamss, tpt, rhs) =>
        val ownerClass = currentOwner.owner
        val isBundleConstructor = if (chiselBundleClass != NoSymbol) {
          ownerClass.baseClasses.contains(chiselBundleClass)
        } else {
          false
        }

        if (isBundleConstructor) {
          if (settings.debug.value || plugin.addDebugIntrinsics) {
            reporter.warning(
              dd.pos,
              s"[INSTRUMENT] Bundle constructor in ${ownerClass.name}"
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
