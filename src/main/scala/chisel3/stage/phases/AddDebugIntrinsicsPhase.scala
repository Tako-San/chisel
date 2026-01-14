// SPDX-License-Identifier: Apache-2.0

package chisel3.stage.phases

import chisel3._
import chisel3.experimental.BaseModule
import chisel3.internal.Builder
import chisel3.internal.firrtl.{Component, Port, DefWire, DefReg, DefRegInit}
import chisel3.stage.ChiselCircuitAnnotation
import chisel3.debuginternal.DebugIntrinsic
import firrtl.annotations.{Annotation, NoTargetAnnotation}
import firrtl.options.{Dependency, Phase, PhaseException}
import firrtl.{AnnotationSeq, CircuitState}

/**
  * Elaboration phase that generates CIRCT debug intrinsics for type metadata.
  * 
  * This phase walks the elaborated Chisel circuit and creates intrinsic modules
  * for each Data element (Bundle, Vec, Enum, ground types) to preserve
  * high-level type information through FIRRTL compilation.
  * 
  * The phase runs AFTER elaboration but BEFORE conversion to FIRRTL, so it has
  * access to the full Chisel object graph with bindings and source locations.
  * 
  * Debug metadata enables:
  * - Source-level debugging in waveform viewers (Tywaves, Surfer)
  * - Interactive hardware debuggers (HGDB)
  * - Automated test generation from RTL types
  * - Hardware-software trace correlation
  */
class AddDebugIntrinsicsPhase extends Phase {
  
  override def prerequisites = Seq(
    Dependency[chisel3.stage.phases.Elaborate]
  )
  
  override def optionalPrerequisites = Seq.empty
  override def optionalPrerequisiteOf = Seq(
    Dependency[chisel3.stage.phases.Convert]
  )
  override def invalidates(a: Phase) = false
  
  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    // Check if debug mode is enabled
    val enableDebug = annotations.exists {
      case _: EnableDebugAnnotation => true
      case _ => false
    } || DebugIntrinsic.isEnabled
    
    if (!enableDebug) {
      return annotations
    }
    
    // Extract elaborated circuit
    val circuitOpt = annotations.collectFirst {
      case ChiselCircuitAnnotation(circuit) => circuit
    }
    
    circuitOpt match {
      case Some(circuit) =>
        println("[Chisel Debug] Generating debug intrinsics for type metadata...")
        
        // Walk all modules and generate intrinsics
        circuit.components.foreach {
          case module: BaseModule =>
            processModule(module)
          case _ => // Skip non-module components
        }
        
        println(s"[Chisel Debug] Generated intrinsics for ${circuit.components.size} components")
        annotations
        
      case None =>
        println("[Chisel Debug] Warning: No ChiselCircuitAnnotation found, skipping intrinsic generation")
        annotations
    }
  }
  
  /**
    * Process a single module and generate intrinsics for its signals
    */
  private def processModule(module: BaseModule): Unit = {
    implicit val sourceInfo = module.sourceInfo
    val moduleName = module.name
    
    // Process IO ports
    try {
      val ioField = module.getClass.getDeclaredField("io")
      ioField.setAccessible(true)
      val io = ioField.get(module).asInstanceOf[Data]
      
      DebugIntrinsic.emitRecursive(io, "io", "IO")
    } catch {
      case _: NoSuchFieldException => // Module has no io field
      case e: Exception => 
        println(s"[Chisel Debug] Warning: Failed to process IO for module $moduleName: ${e.getMessage}")
    }
    
    // Walk all components in this module
    // Note: This is a simplified approach. A full implementation would need to:
    // 1. Access Builder's component tracking
    // 2. Filter by parent module
    // 3. Process Wires, Regs, Mems, etc.
    // For MVP, we rely on explicit emit() calls in user code or IO processing above
  }
}

/**
  * Annotation to enable debug intrinsic generation
  */
case class EnableDebugAnnotation() extends NoTargetAnnotation {
  // NoTargetAnnotation doesn't need target-specific update logic
}

/**
  * Helper for generating debug intrinsics from Data elements within a module context
  */
object DebugIntrinsicGenerator {
  
  /**
    * Generate debug intrinsic for a Data element based on its type and binding
    * 
    * This is called during module elaboration when we have access to the
    * full Chisel IR with binding information.
    */
  def generate(
    data: Data,
    name: String
  )(implicit sourceInfo: chisel3.internal.sourceinfo.SourceInfo): Unit = {
    if (!DebugIntrinsic.isEnabled) return
    
    // Determine binding type
    val binding = data.binding match {
      case Some(chisel3.internal.binding.PortBinding(_)) => "IO"
      case Some(chisel3.internal.binding.WireBinding(_)) => "Wire" 
      case Some(chisel3.internal.binding.RegBinding(_)) => "Reg"
      case Some(chisel3.internal.binding.OpBinding(_, _)) => "OpResult"
      case _ => "Unknown"
    }
    
    data match {
      case b: Bundle =>
        generateForBundle(b, name, binding)
      case v: Vec[_] =>
        generateForVec(v, name, binding)
      case e: EnumType =>
        generateForEnum(e, name, binding)
      case u: UInt =>
        generateForGroundType(u, name, binding, "UInt")
      case s: SInt =>
        generateForGroundType(s, name, binding, "SInt")
      case bool: Bool =>
        generateForGroundType(bool, name, binding, "Bool")
      case _ =>
        // Unknown type, use generic handler
        DebugIntrinsic.emit(data, name, binding)
    }
  }
  
  private def generateForBundle(
    bundle: Bundle,
    name: String,
    binding: String
  )(implicit sourceInfo: chisel3.internal.sourceinfo.SourceInfo): Unit = {
    val typeName = bundle.getClass.getSimpleName.stripSuffix("$")
    val params = DebugIntrinsic.extractBundleParams(bundle)
    
    // Create intrinsic for the bundle itself
    DebugIntrinsic.emit(bundle, name, binding)
    
    // Recursively generate for fields
    bundle.elements.foreach { case (fieldName, fieldData) =>
      generate(fieldData, s"$name.$fieldName")
    }
  }
  
  private def generateForVec(
    vec: Vec[_],
    name: String,
    binding: String
  )(implicit sourceInfo: chisel3.internal.sourceinfo.SourceInfo): Unit = {
    DebugIntrinsic.emit(vec, name, binding)
    
    // Optionally generate for elements (disabled by default for performance)
    // vec.zipWithIndex.foreach { case (elem, idx) =>
    //   generate(elem, s"$name($idx)")
    // }
  }
  
  private def generateForEnum(
    enum: EnumType,
    name: String,
    binding: String
  )(implicit sourceInfo: chisel3.internal.sourceinfo.SourceInfo): Unit = {
    DebugIntrinsic.emit(enum, name, binding)
  }
  
  private def generateForGroundType(
    data: Data,
    name: String,
    binding: String,
    typeName: String
  )(implicit sourceInfo: chisel3.internal.sourceinfo.SourceInfo): Unit = {
    DebugIntrinsic.emit(data, name, binding)
  }
}
