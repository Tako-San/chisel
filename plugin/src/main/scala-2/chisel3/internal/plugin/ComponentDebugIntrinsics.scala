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

    // Symbols needed for instrumentation
    private lazy val chiselDataSym = rootMirror.getClassIfDefined("chisel3.Data")
    
    // Use definitions.getModule for more robust symbol loading
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
                
                // Ensure symbols are loaded. If not, fallback to original tree to avoid crashes.
                if (unlocatableSourceInfoSym == NoSymbol || debugIntrinsicModuleSym == NoSymbol) {
                   return treeCopy.ValDef(vd, mods, name, tpt, transformedRHS)
                }

                // Correctly reference the object value
                val sourceInfoArg = gen.mkAttributedRef(unlocatableSourceInfoSym)
                
                // Correctly reference the DebugIntrinsic.emit method
                val emitSym = debugIntrinsicModuleSym.info.member(TermName("emit"))
                val emitRef = Select(gen.mkAttributedRef(debugIntrinsicModuleSym), TermName("emit"))
                                .setType(emitSym.info) // Explicitly set type to help Erasure

                // Build: DebugIntrinsic.emit(rhs, name, binding)(sourceInfo)
                val emitCall = Apply(
                  Apply(
                    emitRef,
                    List(transformedRHS, Literal(Constant(name.toString)), Literal(Constant(binding)))
                  ),
                  List(sourceInfoArg)
                )
                // Set type for the Apply node if possible, though localTyper.typed will do it too
                // emitCall.setType(typeOf[Option[Unit]]) 
                
                val instrumentedRHS = Block(List(emitCall), transformedRHS)
                val typedInstrumented = localTyper.typed(instrumentedRHS)
                treeCopy.ValDef(vd, mods, name, tpt, typedInstrumented)
                
              case None =>
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

    // Strict binding extraction
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
        
        case _ => None
      }
    }
  }
}