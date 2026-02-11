package chisel3.stage.phases

import chisel3.stage.{ChiselCircuitAnnotation, ChiselOptions}
import chisel3.debuginfo.DebugIntrinsicEmitter
import firrtl.AnnotationSeq
import firrtl.options.{Dependency, Phase, StageOptions}
import firrtl.annotations.NoTargetAnnotation

/** Annotation to enable debug intrinsic emission.
  *
  * When present, the EmitDebugIntrinsics phase will instrument the circuit
  * with debug metadata intrinsics for consumption by CIRCT's debug dialect.
  */
case class EmitDebugInfoAnnotation() extends NoTargetAnnotation

/** Phase that emits debug intrinsics for type metadata propagation.
  *
  * This phase runs after circuit elaboration but before conversion to FIRRTL.
  * It instruments the Chisel IR with debug intrinsics that carry Scala type
  * information, which CIRCT can consume via the debug dialect.
  *
  * Enable via: `--emit-debug-info` flag or programmatically with EmitDebugInfoAnnotation.
  *
  * **Integration with ChiselStage:**
  * This phase should be inserted between Elaborate and Convert phases:
  * ```
  * Elaborate -> EmitDebugIntrinsics -> Convert -> ...
  * ```
  *
  * **CIRCT Integration:**
  * The emitted `circt_debug_type_info` intrinsics will be lowered by firtool to:
  * - `dbg.variable` ops for signals/wires/regs
  * - `dbg.moduleinfo` ops for module metadata
  * - Eventually consumed by Tywaves viewer or other debug tools
  */
class EmitDebugIntrinsics extends Phase {

  override def prerequisites = Seq(Dependency[chisel3.stage.phases.Elaborate])
  override def optionalPrerequisites = Seq.empty
  override def optionalPrerequisiteOf = Seq(Dependency[chisel3.stage.phases.Convert])
  override def invalidates(a: Phase) = false

  override def transform(annotations: AnnotationSeq): AnnotationSeq = {
    // Check if debug info emission is enabled
    val emitDebugInfo = annotations.exists {
      case EmitDebugInfoAnnotation() => true
      case _ => false
    }

    if (!emitDebugInfo) {
      // Debug info disabled, pass through
      return annotations
    }

    // Find the ChiselCircuitAnnotation containing the elaborated circuit
    annotations.flatMap {
      case ChiselCircuitAnnotation(circuit) =>
        // Instrument the circuit with debug intrinsics
        DebugIntrinsicEmitter.generate(circuit)
        
        // Return the annotation unchanged (circuit is mutated in-place)
        Some(ChiselCircuitAnnotation(circuit))
        
      case other => Some(other)
    }
  }
}

/** Companion object for EmitDebugIntrinsics phase. */
object EmitDebugIntrinsics {
  
  /** Add this phase to ChiselStage's phase manager.
    *
    * Usage:
    * ```scala
    * import chisel3.stage.ChiselStage
    * import chisel3.stage.phases.EmitDebugIntrinsics
    *
    * (new ChiselStage).execute(
    *   Array("--target-dir", "generated", "--emit-debug-info"),
    *   Seq(ChiselGeneratorAnnotation(() => new MyModule))
    * )
    * ```
    */
  def apply(): EmitDebugIntrinsics = new EmitDebugIntrinsics
}
