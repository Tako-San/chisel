package chisel3.internal.plugin

import scala.tools.nsc
import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.transform.{Transform, TypingTransformers}

class ComponentDebugIntrinsics(plugin: ChiselPlugin, val global: Global)
    extends PluginComponent
    with TypingTransformers
    with Transform {
  import global._
  import global.Flag._

  val phaseName:               String = "componentDebugIntrinsics"
  val runsAfter:               List[String] = List("typer")
  override val runsRightAfter: Option[String] = Some("typer")

  def newTransformer(unit: CompilationUnit): Transformer = new DebugIntrinsicsTransformer(unit)

  class DebugIntrinsicsTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {
    val shouldSkip = false
    def isPluginEnabled: Boolean = plugin.addDebugIntrinsics

    private def isIO(rhs: Tree): Boolean = {
      rhs match {
        case Apply(Select(_, TermName("IO")), _)               => true
        case Apply(TypeApply(Select(_, TermName("IO")), _), _) => true
        case _                                                 => false
      }
    }

    private def isChiselData(tpt: Tree): Boolean = {
      try {
        val dataSym = rootMirror.getClassIfDefined("chisel3.Data")
        dataSym != NoSymbol && tpt.tpe != null && tpt.tpe <:< dataSym.tpe
      } catch {
        case _: Throwable => false
      }
    }

    private def extractBinding(rhs: Tree): String = {
      rhs match {
        case Apply(Select(_, TermName("RegInit" | "RegNext" | "Reg")), _)       => "Reg"
        case Apply(Select(_, TermName("Wire" | "WireInit" | "WireDefault")), _) => "Wire"
        case Apply(Select(_, TermName("IO")), _)                                => "IO"
        case Apply(Select(_, TermName("Mem")), _)                               => "Mem"
        case _                                                                  => "Wire"
      }
    }

    private def createEmitCall(tempIdent: Tree, name: String, binding: String, pos: Position): Tree = {
      val debugIntrinsicModule = rootMirror.getModuleIfDefined("chisel3.debuginternal.DebugIntrinsic")
      val sourceLineClass = rootMirror.getClassIfDefined("chisel3.experimental.SourceLine")

      if (debugIntrinsicModule != NoSymbol && sourceLineClass != NoSymbol) {
        val sourcePath = if (pos.isDefined && pos.source != null) pos.source.path else ""
        val sourceLine = if (pos.isDefined) pos.line else 0

        Apply(
          Apply(
            Select(Ident(debugIntrinsicModule), TermName("emit")),
            List(
              tempIdent,
              Literal(Constant(name)),
              Literal(Constant(binding))
            )
          ),
          List(
            Apply(
              Select(Ident(sourceLineClass.companionModule), TermName("apply")),
              List(
                Literal(Constant(sourcePath)),
                Literal(Constant(sourceLine)),
                Literal(Constant(0))
              )
            )
          )
        ).setPos(pos)
      } else {
        EmptyTree
      }
    }

    override def transform(tree: Tree): Tree = {
      if (shouldSkip || !isPluginEnabled) return super.transform(tree)

      tree match {
        case vd @ ValDef(mods, name, tpt, rhs)
            if !mods.isSynthetic && 
               !mods.hasFlag(PARAM) &&
               !name.toString.startsWith("debug_tmp") && 
               rhs.nonEmpty &&
               tpt.nonEmpty &&
               isChiselData(tpt) && 
               !isIO(rhs) =>

          try {
            // Transform RHS first
            val transformedRHS = transform(rhs)

            // Create temp name
            val tempName = TermName(currentUnit.fresh.newName("debug_tmp"))

            // Force PRIVATE | LOCAL to prevent accessor generation in DelayedInit (App/Script)
            // SYNTHETIC is kept to mark it as compiler-generated
            val tempMods = Modifiers(PRIVATE | LOCAL | SYNTHETIC)

            val inlineBlock = Block(
              List(
                ValDef(tempMods, tempName, TypeTree(), transformedRHS)
              ),
              Block(
                List(
                  createEmitCall(Ident(tempName), name.toString, extractBinding(rhs), vd.pos)
                ),
                Ident(tempName)
              )
            )

            val typedBlock = localTyper.typed(inlineBlock)
            treeCopy.ValDef(vd, mods, name, tpt, typedBlock)

          } catch {
            case e: Throwable =>
              if (sys.props.get("chisel.debug.verbose").exists(_.toLowerCase == "true")) {
                Console.err.println(s"[ComponentDebugIntrinsics] Skipping ${name}: ${e.getMessage}")
              }
              treeCopy.ValDef(vd, mods, name, tpt, transform(rhs))
          }

        case _ => super.transform(tree)
      }
    }
  }
}
