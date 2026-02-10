package chisel3.internal.plugin

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
    // Tests are crucial for verification, do not skip them!
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
            // 1. Create unique temporary variable name
            val tempName = TermName(currentUnit.fresh.newName("debug_tmp"))
            val transformedRHS = transform(rhs)
            
            val binding = extractBinding(rhs)
            val sourcePath = if (tree.pos.isDefined && tree.pos.source != null) tree.pos.source.path else ""
            val sourceLine = if (tree.pos.isDefined) tree.pos.line else 0
            
            // 2. Build the emit call: DebugIntrinsic.emit(debug_tmp, "name", "binding")(SourceLine(...))
            val debugIntrinsicModule = rootMirror.getModuleIfDefined("chisel3.debuginternal.DebugIntrinsic")
            val sourceLineClass = rootMirror.getClassIfDefined("chisel3.experimental.SourceLine")
            
            val emitCall = if (debugIntrinsicModule != NoSymbol && sourceLineClass != NoSymbol) {
              Apply(
                Apply(
                  Select(
                    Ident(debugIntrinsicModule),
                    TermName("emit")
                  ),
                  List(
                    Ident(tempName),
                    Literal(Constant(name.toString)),
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
              ).setPos(tree.pos)
            } else {
              // Fallback
              Ident(tempName).setPos(tree.pos)
            }
            
            // 3. Create Match: rhs match { case tempName => emit(tempName, ...); tempName }
            // Using Match instead of Block prevents the "Unexpected tree in genLoad" error 
            // where the backend confuses local ValDefs in class body for members.
            val caseDef = CaseDef(
              Bind(tempName, Ident(nme.WILDCARD)),
              EmptyTree,
              Block(
                List(emitCall),
                Ident(tempName).setPos(tree.pos)
              )
            ).setPos(tree.pos)

            val matchTree = Match(
              transformedRHS,
              List(caseDef)
            ).setPos(tree.pos)
            
            // 4. Type the match
            val typedMatch = localTyper.typed(matchTree)
            
            // 5. Return updated ValDef with the match as RHS
            val newValDef = treeCopy.ValDef(vd, mods, name, tpt, typedMatch)
            newValDef
          } else {
            super.transform(tree)
          }
        case _ => super.transform(tree)
      }
    }

    private def extractBinding(rhs: Tree): String = {
      rhs match {
        case Apply(Select(_, TermName("RegInit" | "RegNext" | "Reg")), _) => "Reg"
        case Apply(Select(_, TermName("Wire" | "WireInit" | "WireDefault")), _) => "Wire"
        case Apply(Select(_, TermName("IO")), _) => "IO"
        case Apply(Select(_, TermName("Mem")), _) => "Mem"
        case _ => "Wire" // Default to Wire for unknown constructs that return Data
      }
    }
  }
}