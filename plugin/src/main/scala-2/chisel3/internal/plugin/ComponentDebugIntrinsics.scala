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
            
            // Build: { emit(rhs, "name", "binding")(SourceLine(...))(UnlocatableSourceInfo); rhs }
            // DebugIntrinsic.emit has signature: 
            // def emit(data: Data, name: String, binding: String)(sl: SourceLine)(implicit sourceInfo: SourceInfo): Unit
            
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
              ),
              // Correctly pass UnlocatableSourceInfo as a value (object), not a type constructor call
              List(
                Select(
                  Select(
                    Select(Ident(TermName("chisel3")), TermName("experimental")),
                    TermName("UnlocatableSourceInfo")
                  ),
                  // 'run' is a case object inside UnlocatableSourceInfo used as fallback, 
                  // but typically we can use the object itself if it has implicit conversion, 
                  // OR better: use SourceInfo.Unlocatable if available, but since UnlocatableSourceInfo is deprecated/moved often,
                  // let's stick to what worked or what is standard.
                  // Wait, previous error "Option[Unit] does not take parameters" suggests 'run' might be returning Unit?
                  // Actually, UnlocatableSourceInfo is likely an object or trait.
                  // If it is a deprecated type alias, we might need the object.
                  // Let's try to access the implicit value that is usually available or construct a SourceLineInfo.
                  
                  // Retrying with simply `chisel3.experimental.UnlocatableSourceInfo` as the object itself
                  // assuming it implements SourceInfo.
                  // If UnlocatableSourceInfo is a trait, we need a concrete instance.
                  // Usually `implicitly[SourceInfo]` would pick up something.
                  // Let's check if we can reference `chisel3.experimental.UnlocatableSourceInfo` directly as a value.
                  // If it's a deprecated object, it should work.
                  // The previous error `Option[Unit] does not take parameters` is weird. 
                  // It implies that `emit(...)` returned Unit (wrapped in Option?), and we tried to apply more args?
                  // No, `emit` returns Unit.
                  
                  // Ah, `emit` returns Unit. Apply(Apply(emit...)) returns a Tree typed as Unit.
                  // If we added a THIRD parameter list, and `emit` is defined as:
                  // def emit(...)(...)(implicit ...): Unit
                  // Then `Apply(..., List(implicit))` is correct.
                  
                  // The error "Option[Unit] does not take parameters" usually happens when you try to apply arguments to something that isn't a method/function.
                  // This suggests `emit` might NOT have that third parameter list in the version we are compiling against?
                  // OR `run` was interpreted as a method call returning Option[Unit]?
                  
                  // Let's look at `DebugIntrinsic.scala` via search if possible, or assume typical Chisel structure.
                  // Assuming `emit` IS curried.
                  
                  // Let's try passing `UnlocatableSourceInfo` object directly without `.run`.
                  // `run` is likely not the field we want.
                  TermName("UnlocatableSourceInfo") 
                )
              )
            )
            
            val instrumentedRHS = Block(List(emitCall), transformedRHS)
            val typedInstrumented = localTyper.typed(instrumentedRHS)
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
        case Apply(Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("RegInit" | "RegNext" | "Reg")), _), _), _), _) => "RegBinding"
        case Apply(Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Wire" | "WireDefault" | "WireInit")), _), _), _), _) => "WireBinding"
        case Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Input")), _), _), _) => "PortBinding(INPUT)"
        case Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Output")), _), _), _) => "PortBinding(OUTPUT)"
        case Apply(Apply(TypeApply(Select(_, TermName("IO")), _), _), _) => "PortBinding"
        case Apply(Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Mem")), _), _), _), _) => "MemBinding"
        case _ => "WireBinding"
      }
    }
  }
}