package chisel3.internal.plugin

import scala.annotation.tailrec
import scala.tools.nsc
import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.transform.{TypingTransformers, Transform}

class ComponentDebugIntrinsics(plugin: ChiselPlugin, val global: Global) extends PluginComponent with TypingTransformers with Transform {
  import global._

  val phaseName: String = "componentDebugIntrinsics"
  val runsAfter: List[String] = List("typer")
  override val runsRightAfter: Option[String] = Some("typer")

  def newTransformer(unit: CompilationUnit): Transformer = new DebugIntrinsicsTransformer(unit)

  class DebugIntrinsicsTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {
    val shouldSkip = false

    def isPluginEnabled: Boolean = plugin.addDebugIntrinsics

    // Cache symbols to avoid repeated lookups
    private lazy val chiselDataSym = rootMirror.getClassIfDefined("chisel3.Data")
    private lazy val unlocatableSourceInfoSym = rootMirror.getModuleIfDefined("chisel3.experimental.UnlocatableSourceInfo")
    private lazy val debugIntrinsicModuleSym = rootMirror.getModuleIfDefined("chisel3.debuginternal.DebugIntrinsic")

    override def transform(tree: Tree): Tree = {
      if (shouldSkip || !isPluginEnabled) {
        return super.transform(tree)
      }

      tree match {
        case vd @ ValDef(mods, name, tpt, rhs) 
          if !mods.isSynthetic && 
             !name.toString.startsWith("debug_tmp") && 
             !name.toString.startsWith("_probe") && 
             rhs.nonEmpty =>
          
          val isChiselData = chiselDataSym != NoSymbol && tpt.tpe != null && tpt.tpe <:< chiselDataSym.tpe

          if (isChiselData) {
            val bindingOpt = extractBinding(rhs)
            
            bindingOpt match {
              case Some(binding) =>
                val transformedRHS = transform(rhs)
                
                // Use mkAttributedRef to safely generate reference to UnlocatableSourceInfo object
                // This avoids NPEs in Erasure phase by ensuring symbols are attached
                val sourceInfoArg = gen.mkAttributedRef(unlocatableSourceInfoSym)

                // Build: DebugIntrinsic.emit(rhs, name, binding)(sourceInfo)
                val emitCall = Apply(
                  Apply(
                    Select(gen.mkAttributedRef(debugIntrinsicModuleSym), TermName("emit")),
                    List(transformedRHS, Literal(Constant(name.toString)), Literal(Constant(binding)))
                  ),
                  List(sourceInfoArg)
                )
                
                val instrumentedRHS = Block(List(emitCall), transformedRHS)
                val typedInstrumented = localTyper.typed(instrumentedRHS)
                treeCopy.ValDef(vd, mods, name, tpt, typedInstrumented)
                
              case None =>
                // Skip instrumentation for non-hardware Data (e.g. types like UInt(8.W), plain Bundles)
                super.transform(tree)
            }
          } else {
            super.transform(tree)
          }
        case _ => super.transform(tree)
      }
    }

    @tailrec
    private def unwrapWrappers(tree: Tree): Tree = tree match {
      case Apply(Apply(TypeApply(Select(_, name), _), _), args) 
        if args.nonEmpty && (name.toString == "withName" || name.toString == "apply") =>
        unwrapWrappers(args.last)
      case other => other
    }

    // Strict binding extraction - only instrument known hardware constructors
    private def extractBinding(rhs: Tree): Option[String] = {
      val unwrapped = unwrapWrappers(rhs)
      unwrapped match {
        case Apply(Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("RegInit" | "RegNext" | "Reg")), _), _), _), _) => Some("RegBinding")
        case Apply(Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Wire" | "WireDefault" | "WireInit")), _), _), _), _) => Some("WireBinding")
        // IOs
        case Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Input")), _), _), _) => Some("PortBinding(INPUT)")
        case Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Output")), _), _), _) => Some("PortBinding(OUTPUT)")
        case Apply(Apply(TypeApply(Select(_, TermName("IO")), _), _), _) => Some("PortBinding")
        // Memories
        case Apply(Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Mem")), _), _), _), _) => Some("MemBinding")
        case Apply(Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("SyncReadMem")), _), _), _), _) => Some("MemBinding")
        
        // Explicitly return None for everything else to avoid "ExpectedHardwareException" on types
        case _ => None
      }
    }
  }
}