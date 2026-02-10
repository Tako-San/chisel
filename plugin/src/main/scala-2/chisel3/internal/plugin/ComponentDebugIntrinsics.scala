package chisel3.internal.plugin

import scala.tools.nsc
import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.transform.{Transform, TypingTransformers}
import scala.collection.mutable.ListBuffer

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

    // Helper to check for IO(...) - Skip these to prevent VerifyError
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

    private def createEmitCall(sym: Symbol, name: String, binding: String, pos: Position): Tree = {
      val debugIntrinsicModule = rootMirror.getModuleIfDefined("chisel3.debuginternal.DebugIntrinsic")
      val sourceLineClass = rootMirror.getClassIfDefined("chisel3.experimental.SourceLine")

      if (debugIntrinsicModule != NoSymbol && sourceLineClass != NoSymbol) {
        val sourcePath = if (pos.isDefined && pos.source != null) pos.source.path else ""
        val sourceLine = if (pos.isDefined) pos.line else 0

        Apply(
          Apply(
            Select(Ident(debugIntrinsicModule), TermName("emit")),
            List(
              Ident(sym).setPos(pos),
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
        // CASE 1: Handle Fields (members of a Class/Trait/Module)
        // We intercept at Template to insert sibling fields
        case t: Template =>
          val newBody = new ListBuffer[Tree]
          var changed = false

          for (stat <- t.body) {
            stat match {
              case vd @ ValDef(mods, name, tpt, rhs)
                  if !mods.isSynthetic && !name.toString.startsWith("debug_tmp") && rhs.nonEmpty &&
                    isChiselData(tpt) && !isIO(rhs) =>

                changed = true
                val transformedRHS = transform(rhs)

                // 1. Create Shadow Field (private[this] val debug_tmp)
                val tempName = TermName(currentUnit.fresh.newName("debug_tmp"))
                // use newTermSymbol for fields to ensure correct OWNER (the Class)
                val tempSym =
                  currentOwner.newTermSymbol(tempName, vd.pos, PRIVATE | LOCAL | SYNTHETIC)
                tempSym.setInfo(localTyper.typed(transformedRHS).tpe)

                val shadowValDef = ValDef(tempSym, transformedRHS).setPos(vd.pos)

                // 2. Create Emit Call using the shadow field symbol
                val emitCall = createEmitCall(tempSym, name.toString, extractBinding(rhs), vd.pos)

                // 3. Modify Original Field RHS to: { emit(...); debug_tmp }
                // This block executes during class initialization
                val block = Block(List(emitCall), Ident(tempSym).setPos(vd.pos)).setPos(vd.pos)
                val typedBlock = localTyper.typed(block)

                val newValDef = treeCopy.ValDef(vd, mods, name, tpt, typedBlock)

                // Add both to the class body
                newBody += shadowValDef
                newBody += newValDef

              case _ =>
                newBody += transform(stat)
            }
          }

          if (changed) treeCopy.Template(t, t.parents, t.self, newBody.toList)
          else super.transform(tree)

        // CASE 2: Handle Local Variables (inside Methods/Blocks)
        // We ensure we are NOT in a class context (currentOwner.isClass == false)
        case vd @ ValDef(mods, name, tpt, rhs)
            if !mods.isSynthetic && !name.toString.startsWith("debug_tmp") && rhs.nonEmpty &&
              !currentOwner.isClass &&
              isChiselData(tpt) && !isIO(rhs) =>

          val tempName = TermName(currentUnit.fresh.newName("debug_tmp"))
          val transformedRHS = transform(rhs)

          // Local variable -> newVariable (Args: Name, Position)
          val tempSym = currentOwner.newVariable(tempName, vd.pos)
          val typedRHS = localTyper.typed(transformedRHS)
          tempSym.setInfo(typedRHS.tpe)

          val tempValDef = ValDef(tempSym, typedRHS).setPos(vd.pos)
          val emitCall = createEmitCall(tempSym, name.toString, extractBinding(rhs), vd.pos)

          val block = Block(List(tempValDef, emitCall), Ident(tempSym).setPos(vd.pos)).setPos(vd.pos)
          localTyper.typed(block)

          treeCopy.ValDef(vd, mods, name, tpt, block)

        case _ => super.transform(tree)
      }
    }
  }
}
