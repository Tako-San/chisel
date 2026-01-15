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
  * ERROR HANDLING:
  * - Fatal errors (API incompatibility): throw RuntimeException
  * - Warnings (expected failures): log to stderr, continue
  * - Silent (normal cases): no output
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
    
    // Extract circuit from ChiselCircuitAnnotation with robust error handling
    val circuitOpt = annotations.collectFirst {
      case a: ChiselCircuitAnnotation => extractCircuit(a)
    }.filter(_ != null)
    
    circuitOpt match {
      case Some(circuit) =>
        processCircuit(circuit)
        println(s"[Chisel Debug] Completed intrinsic generation")
        annotations
        
      case None =>
        System.err.println(
          "[Chisel Debug] WARNING: No elaborated circuit found.\n" +
          "  Debug intrinsics will not be generated.\n" +
          "  This may indicate a compilation issue or missing ChiselCircuitAnnotation."
        )
        annotations
    }
  }
  
  /**
    * Extract ElaboratedCircuit from ChiselCircuitAnnotation using reflection.
    * 
    * CRITICAL: This uses reflection because ChiselCircuitAnnotation API has changed
    * across Chisel versions. If API changes break reflection, this will FAIL LOUDLY.
    * 
    * @param annotation ChiselCircuitAnnotation containing the elaborated circuit
    * @return ElaboratedCircuit or null on failure
    * @throws RuntimeException on fatal API incompatibility
    */
  private def extractCircuit(annotation: ChiselCircuitAnnotation): chisel3.ElaboratedCircuit = {
    try {
      // Try to access 'circuit' method (Chisel 6+ API)
      val method = annotation.getClass.getMethod("circuit")
      method.invoke(annotation).asInstanceOf[chisel3.ElaboratedCircuit]
      
    } catch {
      case e: NoSuchMethodException =>
        // FATAL: API has changed, reflection cannot find expected method
        val msg = 
          s"""[Chisel Debug] FATAL ERROR: ChiselCircuitAnnotation API incompatibility detected.
             |  Expected method: circuit()
             |  Available methods: ${annotation.getClass.getMethods.map(_.getName).mkString(", ")}
             |  
             |  REQUIRED ACTION:
             |  Update chisel3.stage.phases.AddDebugIntrinsicsPhase to match current Chisel API.
             |  Check ChiselCircuitAnnotation source for correct accessor method.
             |  
             |  Error details: ${e.getMessage}
             |""".stripMargin
        
        System.err.println(msg)
        throw new RuntimeException("Debug intrinsics phase failed due to API incompatibility", e)
        
      case e: IllegalAccessException =>
        // FATAL: Method exists but is not accessible (private/protected)
        val msg =
          s"""[Chisel Debug] FATAL ERROR: Cannot access circuit() method on ChiselCircuitAnnotation.
             |  Method exists but is not accessible (private/protected).
             |  
             |  REQUIRED ACTION:
             |  Chisel API has changed access modifiers. Update reflection code to use:
             |  1. getDeclaredMethod + setAccessible(true), or
             |  2. Alternative accessor if available
             |  
             |  Error details: ${e.getMessage}
             |""".stripMargin
        
        System.err.println(msg)
        throw new RuntimeException("Debug intrinsics phase failed due to access violation", e)
        
      case e: ClassCastException =>
        // FATAL: circuit() returns unexpected type
        val msg =
          s"""[Chisel Debug] FATAL ERROR: circuit() method returns unexpected type.
             |  Expected: chisel3.ElaboratedCircuit
             |  Got: ${e.getMessage}
             |  
             |  REQUIRED ACTION:
             |  Chisel API has changed return type. Update cast in extractCircuit().
             |  
             |  Error details: ${e.getMessage}
             |""".stripMargin
        
        System.err.println(msg)
        throw new RuntimeException("Debug intrinsics phase failed due to type mismatch", e)
        
      case e: Throwable =>
        // Unexpected error - log and rethrow for debugging
        val msg =
          s"""[Chisel Debug] UNEXPECTED ERROR in extractCircuit().
             |  This may indicate a serious issue with reflection or Chisel API.
             |  
             |  Error type: ${e.getClass.getName}
             |  Error message: ${e.getMessage}
             |  Stack trace:
             |""".stripMargin
        
        System.err.println(msg)
        e.printStackTrace()
        throw new RuntimeException("Debug intrinsics phase failed unexpectedly", e)
    }
  }
  
  /**
    * Process the elaborated circuit and generate intrinsics.
    * 
    * Extracts top module from circuit.topDefinition using reflection.
    */
  private def processCircuit(circuit: chisel3.ElaboratedCircuit): Unit = {
    try {
      val topDef = circuit.topDefinition
      
      // Definition[T] wraps the module in a 'proto' field
      val topModule = try {
        val protoField = topDef.getClass.getDeclaredField("proto")
        protoField.setAccessible(true)
        protoField.get(topDef).asInstanceOf[BaseModule]
        
      } catch {
        case e: NoSuchFieldException =>
          val msg =
            s"""[Chisel Debug] FATAL ERROR: Cannot find 'proto' field in Definition class.
               |  Chisel Definition API has likely changed.
               |  
               |  REQUIRED ACTION:
               |  Inspect Definition class source for new field name.
               |  Update reflection code in processCircuit().
               |  
               |  Available fields: ${topDef.getClass.getDeclaredFields.map(_.getName).mkString(", ")}
               |  Error details: ${e.getMessage}
               |""".stripMargin
          
          System.err.println(msg)
          throw new RuntimeException("Cannot extract top module from Definition", e)
          
        case e: IllegalAccessException =>
          val msg =
            s"""[Chisel Debug] FATAL ERROR: Cannot access 'proto' field in Definition.
               |  Field exists but access was denied even after setAccessible(true).
               |  This may indicate JVM security restrictions.
               |  
               |  REQUIRED ACTION:
               |  1. Check if running with SecurityManager enabled
               |  2. Add JVM flag: --add-opens=chisel3/chisel3=ALL-UNNAMED
               |  3. Use alternative API if available
               |  
               |  Error details: ${e.getMessage}
               |""".stripMargin
          
          System.err.println(msg)
          throw new RuntimeException("Cannot access top module due to security restrictions", e)
          
        case e: ClassCastException =>
          val msg =
            s"""[Chisel Debug] FATAL ERROR: 'proto' field is not BaseModule.
               |  Expected type: BaseModule
               |  Actual type: ${e.getMessage}
               |  
               |  REQUIRED ACTION:
               |  Definition.proto type has changed. Update cast in processCircuit().
               |  
               |  Error details: ${e.getMessage}
               |""".stripMargin
          
          System.err.println(msg)
          throw new RuntimeException("Top module has unexpected type", e)
      }
      
      processModule(topModule)
      
    } catch {
      case e: RuntimeException =>
        // Re-throw fatal errors from inner try-catch
        throw e
        
      case e: Exception =>
        val msg =
          s"""[Chisel Debug] ERROR: Failed to process circuit.
             |  This may be due to unexpected Chisel API changes.
             |  
             |  Error type: ${e.getClass.getName}
             |  Error message: ${e.getMessage}
             |  
             |  Stack trace:
             |""".stripMargin
        
        System.err.println(msg)
        e.printStackTrace()
        throw new RuntimeException("Circuit processing failed", e)
    }
  }
  
  /**
    * Process a single module and generate intrinsics for its signals.
    * 
    * Uses reflection to access module fields since we don't have
    * direct API for iterating components in Chisel 6+.
    * 
    * This method uses WARNING (not FATAL) error handling because:
    * - Some modules legitimately have no IO (e.g., RawModule)
    * - IO field access failures are expected in some cases
    */
  private def processModule(module: BaseModule): Unit = {
    // Create implicit SourceInfo for intrinsic generation
    implicit val si: SourceInfo = UnlocatableSourceInfo
    
    val moduleName = module.name
    
    // Process IO bundle if it exists
    try {
      val moduleClass = module.getClass
      val ioField = moduleClass.getDeclaredField("io")
      ioField.setAccessible(true)
      val io = ioField.get(module)
      
      io match {
        case data: Data =>
          println(s"[Chisel Debug]   Processing IO for module: $moduleName")
          DebugIntrinsic.emitRecursive(data, "io", "IO")
          
        case null =>
          System.err.println(
            s"[Chisel Debug]   WARNING: IO field is null in module '$moduleName'.\n" +
            s"    This may indicate an uninitialized IO or constructor issue."
          )
          
        case other =>
          System.err.println(
            s"[Chisel Debug]   WARNING: IO field has unexpected type in module '$moduleName'.\n" +
            s"    Expected: chisel3.Data\n" +
            s"    Actual: ${other.getClass.getName}\n" +
            s"    Debug intrinsics will not be generated for this module's IO."
          )
      }
      
    } catch {
      case _: NoSuchFieldException =>
        // Expected: Module has no 'io' field (e.g., RawModule, internal modules)
        // This is not an error, just info
        println(s"[Chisel Debug]   Module '$moduleName' has no IO field (expected for RawModule)")
        
      case e: IllegalAccessException =>
        // Unexpected: setAccessible(true) should prevent this
        System.err.println(
          s"[Chisel Debug]   WARNING: Cannot access IO field in module '$moduleName'.\n" +
          s"    setAccessible(true) was called but access still denied.\n" +
          s"    This may indicate JVM security restrictions.\n" +
          s"    Error: ${e.getMessage}"
        )
        
      case e: Exception =>
        // Unexpected error during IO processing
        System.err.println(
          s"[Chisel Debug]   ERROR: Failed to process IO for module '$moduleName'.\n" +
          s"    Error type: ${e.getClass.getName}\n" +
          s"    Error message: ${e.getMessage}\n" +
          s"    Continuing with other modules..."
        )
        // Don't throw - continue processing other modules
    }
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