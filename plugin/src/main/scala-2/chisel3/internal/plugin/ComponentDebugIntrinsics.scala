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
          
          val isChiselData = try {
            val dataSym = rootMirror.getClassIfDefined("chisel3.Data")
            dataSym != NoSymbol && tpt.tpe != null && tpt.tpe <:< dataSym.tpe
          } catch {
            case _: Throwable => false
          }

          if (isChiselData) {
            val transformedRHS = transform(rhs)
            val binding = extractBinding(rhs)
            val sourcePath = if (tree.pos.isDefined && tree.pos.source != null) tree.pos.source.path else ""
            val sourceLine = if (tree.pos.isDefined) tree.pos.line else 0
            
            // Build: { emit(rhs, "name", "binding"); rhs }
            // Using Apply nodes directly to avoid DefDef creation
            val emitCall = Apply(
              Apply(
                Apply(
                  Select(
                    Select(
                      Select(Ident(TermName("chisel3")), TermName("debuginternal")),
                      TermName("DebugIntrinsic")
                    ),
                    TermName("emit")
                  ),
                  List(transformedRHS, Literal(Constant(name.toString)), Literal(Constant(binding)))
                ),
                Nil
              ),
              List(
                Apply(
                  Select(
                    Select(
                      Select(Ident(TermName("chisel3")), TermName("experimental")),
                      TermName("SourceLine")
                    ),
                    TermName("apply")
                  ),
                  List(Literal(Constant(sourcePath)), Literal(Constant(sourceLine)), Literal(Constant(0)))
                )
              )
            )
            
            // Construct block: { emit(...); transformedRHS }
            val instrumentedRHS = Block(List(emitCall), transformedRHS)
            val typedInstrumented = localTyper.typed(instrumentedRHS)
            
            // Return original ValDef with modified RHS
            treeCopy.ValDef(vd, mods, name, tpt, typedInstrumented)
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

    private def extractBinding(rhs: Tree): String = {
      val unwrapped = unwrapWrappers(rhs)
      
      unwrapped match {
        case Apply(Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("RegInit" | "RegNext" | "Reg")), _), _), _), _) => 
          "RegBinding"
        case Apply(Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Wire" | "WireDefault" | "WireInit")), _), _), _), _) => 
          "WireBinding"
        case Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Input")), _), _), _) => 
          "PortBinding(INPUT)"
        case Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Output")), _), _), _) => 
          "PortBinding(OUTPUT)"
        case Apply(Apply(TypeApply(Select(_, TermName("IO")), _), _), _) => 
          "PortBinding"
        case Apply(Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Mem")), _), _), _), _) => 
          "MemBinding"
        case _ => "WireBinding"
      }
    }
  }
}