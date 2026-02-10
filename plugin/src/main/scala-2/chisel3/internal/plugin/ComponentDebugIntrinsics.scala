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
    // Skip test files to avoid LambdaLift crashes and access issues with private types
    val shouldSkip = unit.source.path.contains("src/test") || 
                     unit.source.path.contains("chiselTests") ||
                     (unit.body match {
                       case PackageDef(pid, _) => pid.toString == "chiselTests"
                       case _ => false
                     })

    override def transform(tree: Tree): Tree = {
      if (shouldSkip) {
        return super.transform(tree)
      }

      tree match {
        case vd @ ValDef(mods, name, tpt, rhs) if !mods.isSynthetic && !name.toString.startsWith("debug_tmp") && rhs.nonEmpty =>
          val isChiselData = try {
            val dataSym = rootMirror.getClassIfDefined("chisel3.Data")
            dataSym != NoSymbol && tpt.tpe != null && tpt.tpe <:< dataSym.tpe
          } catch {
            case _: Throwable => false
          }

          if (isChiselData) {
            val tempName = TermName(currentUnit.fresh.newName("debug_tmp"))
            // Do NOT use resetAttrs as it breaks access to private Chisel types.
            // Using a Block wrapper for main code is generally safe from LambdaLift issues 
            // compared to complex test code.
            val transformedRHS = transform(rhs)
            
            // We use the block pattern: { val tmp = rhs; probe(tmp); tmp }
            // Using chisel3.probe.probe as the hook.
            val block = q"{ val $tempName = $transformedRHS; chisel3.probe.probe($tempName); $tempName }"
            localTyper.typed(block)
          } else {
            super.transform(tree)
          }
        case _ => super.transform(tree)
      }
    }
  }
}
