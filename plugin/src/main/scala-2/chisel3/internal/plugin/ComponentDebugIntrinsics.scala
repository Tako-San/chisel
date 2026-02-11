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
            
            // Generate emission call
            // Fix: Use SourceLine explicitly instead of incorrectly calling macro materialize
            // SourceLine(filename: String, line: Int, col: Int)
            val block = q"""{ 
              val $tempName = $transformedRHS;
              chisel3.debuginternal.DebugIntrinsic.emit($tempName, ${name.toString}, $binding)(
                chisel3.experimental.SourceLine($sourcePath, $sourceLine, 0)
              );
              $tempName 
            }"""
            localTyper.typed(block)
          } else {
            super.transform(tree)
          }
        case _ => super.transform(tree)
      }
    }

    /**
     * Recursively unwrap Chisel naming wrappers (withName, prefix) to find the actual constructor call.
     * 
     * Chisel wraps constructors for automatic naming:
     *   val state = RegInit(0.U) 
     * becomes:
     *   Apply(withName("state"), [Apply(prefix("state"), [Apply(RegInit, ...)])])
     * 
     * This function strips those wrappers to access the underlying constructor.
     */
    @tailrec
    private def unwrapWrappers(tree: Tree): Tree = tree match {
      // Pattern: Apply(Apply(TypeApply(Select(..., wrapperName), ...), ...), [..., body])
      // Matches both withName and prefix.apply patterns
      case Apply(Apply(TypeApply(Select(_, name), _), _), args) 
        if args.nonEmpty && (name.toString == "withName" || name.toString == "apply") =>
        unwrapWrappers(args.last)
      
      // Base case: no more wrappers
      case other => other
    }

    /**
     * Extract binding type from RHS expression by unwrapping naming helpers
     * and pattern matching on the actual Chisel constructor.
     */
    private def extractBinding(rhs: Tree): String = {
      val unwrapped = unwrapWrappers(rhs)
      
      unwrapped match {
        // RegInit, RegNext, Reg
        case Apply(Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("RegInit" | "RegNext" | "Reg")), _), _), _), _) => 
          "RegBinding"
        
        // Wire, WireDefault, WireInit
        case Apply(Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Wire" | "WireDefault" | "WireInit")), _), _), _), _) => 
          "WireBinding"
        
        // Input
        case Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Input")), _), _), _) => 
          "PortBinding(INPUT)"
        
        // Output
        case Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Output")), _), _), _) => 
          "PortBinding(OUTPUT)"
        
        // IO (contains ports)
        case Apply(Apply(TypeApply(Select(_, TermName("IO")), _), _), _) => 
          "PortBinding"
        
        // Mem
        case Apply(Apply(TypeApply(Select(Select(Ident(TermName("chisel3")), TermName("Mem")), _), _), _), _) => 
          "MemBinding"
        
        // Fallback for unknown Data constructs
        case _ => "WireBinding"
      }
    }
  }
}