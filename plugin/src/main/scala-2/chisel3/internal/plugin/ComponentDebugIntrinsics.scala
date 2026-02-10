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
            val tempName = TermName(currentUnit.fresh.newName("debug_tmp"))
            val transformedRHS = transform(rhs)
            
            val binding = extractBinding(rhs)
            val sourcePath = if (tree.pos.isDefined && tree.pos.source != null) tree.pos.source.path else ""
            val sourceLine = if (tree.pos.isDefined) tree.pos.line else 0
            
            // Fix: Use an IIFE (Immediately Invoked Function Expression) to safely bind the temporary variable.
            // This prevents the compiler from trying to lift 'debug_tmp' into a class field, which causes 'Unexpected tree in genLoad'.
            // Structure: ((debug_tmp: T) => { emit(debug_tmp); debug_tmp })(rhs)
            
            val newRhs = q"""(( $tempName: $tpt ) => {
              chisel3.debuginternal.DebugIntrinsic.emit($tempName, ${name.toString}, $binding)(
                chisel3.experimental.SourceLine($sourcePath, $sourceLine, 0)
              );
              $tempName 
            })($transformedRHS)"""
            
            val newValDef = treeCopy.ValDef(vd, mods, name, tpt, newRhs)
            localTyper.typed(newValDef)
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