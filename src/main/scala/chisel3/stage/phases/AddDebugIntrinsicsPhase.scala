// SPDX-License-Identifier: Apache-2.0

package chisel3.stage.phases

import chisel3._
import chisel3.stage.{ChiselCircuitAnnotation, ChiselOptions, CircuitSerializationAnnotation}
import chisel3.experimental.{BaseModule, SourceInfo, UnlocatableSourceInfo}
import chisel3.debuginternal.DebugIntrinsic
import firrtl.annotations.{Annotation, NoTargetAnnotation}
import firrtl.options.{Dependency, Phase, StageOptions}
import firrtl.AnnotationSeq
import java.lang.reflect.Method

/**
  * Modern elaboration phase for generating CIRCT debug intrinsics.
  * 
  * This phase automatically instruments Chisel circuits with debug metadata
  * intrinsics after elaboration. It traverses the module hierarchy and generates
  * intrinsics for:
  * - IO ports (with hierarchical names)
  * - Bundle fields (recursively)
  * - Vec elements (optionally)
  * 
  * The phase is enabled via:
  * 1. Environment variable: CHISEL_DEBUG=true
  * 2. System property: -Dchisel.debug=true
  * 3. Annotation: EnableDebugAnnotation()
  * 
  * Implementation based on ChiselTrace approach:
  * - Uses reflection to access elaborated module fields
  * - No dependency on removed FIRRTL IR classes
  * - Compatible with Chisel 6+ API
  * 
  * @see https://github.com/jarlb/chiseltrace
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
    val enableDebug = annotations.exists(_.isInstanceOf[EnableDebugAnnotation]) ||
                      DebugIntrinsic.isEnabled
    
    if (!enableDebug) {
      return annotations
    }
    
    println("[Chisel Debug] Generating debug intrinsics for type metadata...")
    
    // FIX 1: Safely extract circuit from ChiselCircuitAnnotation using reflection
    // This handles cases where it's not a case class or fields are protected in newer Chisel versions
    val circuitOpt = annotations.collectFirst {
      case a: ChiselCircuitAnnotation => 
        try {
          // Use reflection to access 'circuit' method/field
          val method = a.getClass.getMethod("circuit")
          method.invoke(a).asInstanceOf[chisel3.ElaboratedCircuit]
        } catch {
          case _: Throwable => 
            println("[Chisel Debug] Warning: Could not access .circuit on ChiselCircuitAnnotation")
            null
        }
    }.filter(_ != null)
    
    circuitOpt match {
      case Some(circuit) =>
        processCircuit(circuit)
        println(s"[Chisel Debug] Completed intrinsic generation")
        annotations
        
      case None =>
        println("[Chisel Debug] Warning: No circuit found, skipping intrinsic generation")
        annotations
    }
  }
  
  /**
    * Process the elaborated circuit and generate intrinsics.
    */
  private def processCircuit(circuit: chisel3.ElaboratedCircuit): Unit = {
    // FIX 2: Extract BaseModule from Definition using reflection
    // circuit.topDefinition returns a Definition[T], which wraps the actual module in 'proto'
    try {
      val topDef = circuit.topDefinition
      
      // Definition wraps the module in a private 'proto' field
      val protoField = topDef.getClass.getDeclaredField("proto")
      protoField.setAccessible(true)
      val topModule = protoField.get(topDef).asInstanceOf[BaseModule]
      
      processModule(topModule)
    } catch {
      case e: Exception =>
        println(s"[Chisel Debug] Error extracting top module: ${e.getMessage}")
    }
  }
  
  /**
    * Process a single module and generate intrinsics for its signals.
    * 
    * Uses reflection to access module fields since we don't have
    * direct API for iterating components in Chisel 6+.
    */
  private def processModule(module: BaseModule): Unit = {
    // Create implicit SourceInfo for intrinsic generation
    implicit val si: SourceInfo = UnlocatableSourceInfo
    
    val moduleName = module.name
    
    // Process IO bundle if it exists
    try {
      // Try to access 'io' field via reflection
      val moduleClass = module.getClass
      val ioField = moduleClass.getDeclaredField("io")
      ioField.setAccessible(true)
      val io = ioField.get(module)
      
      io match {
        case data: Data =>
          println(s"[Chisel Debug]   Processing IO for module: $moduleName")
          DebugIntrinsic.emitRecursive(data, "io", "IO")
          
        case _ =>
          println(s"[Chisel Debug]   Warning: IO field is not Data type in $moduleName")
      }
    } catch {
      case _: NoSuchFieldException =>
        // Module has no io field (e.g., RawModule)
        println(s"[Chisel Debug]   Module $moduleName has no IO field")
        
      case e: Exception =>
        println(s"[Chisel Debug]   Warning: Failed to process IO for $moduleName: ${e.getMessage}")
    }
    
    // Optional: Process other Data fields (wires, regs)
    // This is more invasive and may generate many intrinsics
    // For MVP, we focus on IO which is the main debugging interface
  }
}

/**
  * Annotation to enable debug intrinsic generation.
  * 
  * Can be added via command-line or programmatically:
  * ```
  * val annos = Seq(EnableDebugAnnotation())
  * ChiselStage.emitVerilog(new MyModule, annos)
  * ```
  */
case class EnableDebugAnnotation() extends NoTargetAnnotation
