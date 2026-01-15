// SPDX-License-Identifier: Apache-2.0

package chisel3.debuginternal

// This package contains internal implementation for CIRCT debug metadata intrinsics.
// User-facing API is in chisel3.util.circt.DebugInfo

import chisel3._
import chisel3.probe.{Probe, ProbeValue, read}
import chisel3.experimental.SourceInfo
import scala.collection.immutable.ListMap

/**
  * Generates CIRCT debug intrinsics for preserving high-level type information
  * through FIRRTL compilation.
  * 
  * == Architecture ==
  * 
  * Uses Chisel 6+ Probe API for reliable signal binding:
  *   1. `ProbeValue(signal)` creates persistent reference tracking signal identity
  *   2. Intrinsic consumes probe via `read()`, creating metadata→RTL dependency
  *   3. CIRCT tracks probe through transforms, maintaining accurate mapping
  * 
  * These intrinsics are lowered by CIRCT's Debug dialect to:
  * - `dbg.*` MLIR operations (dbg.struct, dbg.variable, etc.)
  * - `hw-debug-info.json` manifest for runtime tools
  * - VCD/FST metadata for waveform viewers
  * 
  * == Design Pattern ==
  * 
  * Architecture follows ChiselTrace approach:
  * - Probe-based references survive optimization passes (DCE, CSE, inlining)
  * - Metadata stays bound to actual RTL signal after FIRRTL transforms
  * - Enables Tywaves/HGDB to correlate VCD signals with Chisel source types
  * 
  * @note This is internal API - users should use [[chisel3.util.circt.DebugInfo]]
  * @see [[https://www.chisel-lang.org/docs/explanations/probes Chisel Probe API]]
  * @see [[https://circt.llvm.org/docs/Dialects/Debug/ CIRCT Debug Dialect]]
  * @see [[https://github.com/jarlb ChiselTrace (reference implementation)]]
  */
object DebugIntrinsic {
  
  /**
    * Check if Chisel debug mode is enabled via environment variable.
    * 
    * @return true if `CHISEL_DEBUG=true` or `sys.props("chisel.debug")="true"`
    */
  def isEnabled: Boolean = {
    sys.env.get("CHISEL_DEBUG").exists(_.toLowerCase == "true") ||
    sys.props.get("chisel.debug").exists(_.toLowerCase == "true")
  }
  
  /**
    * Emit a debug intrinsic for a Data element.
    * 
    * Generates a FIRRTL intrinsic statement with Probe-based binding:
    * {{{  
    * wire _probe_io_field : Probe<UInt<8>>
    * define(_probe_io_field, probe(io.field))
    * intrinsic(circt_debug_typeinfo<target="io.field", ...>, read(_probe_io_field))
    * }}}
    * 
    * == Critical Design Decision: Probe API (P2 Fix) ==
    * 
    * '''Why Probe API is required:'''
    * 
    * Direct signal reference creates weak dependency:
    * {{{  
    * // BAD: Weak binding (deprecated approach)
    * Intrinsic("circt_debug_typeinfo", params)(data)
    * // Problem: FIRRTL transforms may rename/eliminate 'data'
    * // Result: Intrinsic parameter 'target="io.field"' becomes stale string
    * // Impact: CIRCT cannot map metadata to final RTL signal
    * }}}
    * 
    * Probe-based reference persists through transforms:
    * {{{  
    * // GOOD: Strong binding (current implementation)
    * val probe = ProbeValue(data)
    * val probeRead = read(probe)
    * Intrinsic("circt_debug_typeinfo", params)(probeRead)
    * // Benefit: Probe tracks signal identity through all transforms
    * // Result: metadata→RTL mapping survives DCE, CSE, inlining
    * }}}
    * 
    * @param data The signal to attach metadata to (must be hardware-typed)
    * @param target Hierarchical name (e.g., "io.field1.subfield")
    * @param binding Signal binding type ("IO", "Wire", "Reg", "OpResult", "User")
    * @param sourceInfo Source location (file name, line number)
    * @return Some(Unit) if intrinsic was emitted, None if debug mode disabled
    * 
    * @note When debug mode is disabled, returns None with zero overhead
    * @see [[emitRecursive]] for Bundle/Vec traversal
    */
  def emit(
    data: Data, 
    target: String, 
    binding: String
  )(implicit sourceInfo: SourceInfo): Option[Unit] = {
    if (!isEnabled) return None
    
    val typeName = extractTypeName(data)
    val params = extractAllParams(data)
    val sourceFile = sourceInfo.filenameOption.getOrElse("<unknown>")
    val sourceLine = sourceInfo match {
      case s: chisel3.experimental.SourceLine => s.line.toLong
      case _ => 0L
    }
    
    // Build parameter list for intrinsic
    val intrinsicParams: Seq[(String, Param)] = Seq(
      "target" -> StringParam(target),
      "typeName" -> StringParam(typeName),
      "binding" -> StringParam(binding),
      "parameters" -> StringParam(serializeParams(params)),
      "sourceFile" -> StringParam(sourceFile),
      "sourceLine" -> IntParam(sourceLine)
    )
    
    // Add enumDef if present
    val allParams = data match {
      case e: EnumType =>
        val enumDef = extractEnumDef(e)
        intrinsicParams :+ ("enumDef" -> StringParam(enumDef))
      case _ =>
        intrinsicParams
    }
    
    // FIX P2: Use Probe API for reliable signal binding
    // 
    // WHY: Direct Intrinsic(name, params)(data) creates weak dependency:
    // - FIRRTL transforms may rename/eliminate 'data'
    // - Intrinsic parameter 'target="io.field"' becomes stale string
    // - CIRCT cannot map metadata to final RTL signal
    // 
    // SOLUTION: Probe-based reference persists through transforms:
    // - ProbeValue creates probe that tracks signal identity
    // - read(probe) dereferences for intrinsic, maintaining binding
    // - CIRCT Debug dialect uses probe infrastructure for tracking
    // 
    // Generated FIRRTL:
    //   wire _debug_probe_io_field : Probe<UInt<8>>
    //   define(_debug_probe_io_field, probe(io.field))
    //   intrinsic(circt_debug_typeinfo<...>, read(_debug_probe_io_field))
    // 
    // This ensures metadata→RTL mapping survives DCE, CSE, inlining.
    val probe = ProbeValue(data)
    val probeRead = read(probe)
    Intrinsic("circt_debug_typeinfo", allParams: _*)(probeRead)
    
    Some(())
  }
  
  /**
    * Recursively emit intrinsics for a Data element and all its children.
    * 
    * For Bundles: emits for bundle itself + all fields (depth-first traversal).
    * For Vecs: emits for vec itself (elements handled separately if needed).
    * For ground types: emits single intrinsic (no children).
    * 
    * @param data The root signal to annotate recursively
    * @param target Hierarchical name prefix (children will be suffixed)
    * @param binding Signal binding type
    * @param sourceInfo Source location
    * @return Some(Unit) if any intrinsics emitted, None if disabled
    * 
    * @example Bundle traversal:
    * {{{  
    * // Input: Bundle { field1: UInt, field2: Bool }
    * emitRecursive(bundle, "io", "IO")
    * 
    * // Generates:
    * // - intrinsic for "io" (Bundle)
    * // - intrinsic for "io.field1" (UInt)
    * // - intrinsic for "io.field2" (Bool)
    * }}}
    */
  def emitRecursive(
    data: Data,
    target: String,
    binding: String
  )(implicit sourceInfo: SourceInfo): Option[Unit] = {
    if (!isEnabled) return None
    
    // Emit for parent
    emit(data, target, binding)
    
    // Recursively emit for children
    data match {
      case bundle: Bundle =>
        bundle.elements.foreach { case (fieldName, fieldData) =>
          emitRecursive(fieldData, s"$target.$fieldName", binding)
        }
      
      case vec: Vec[_] =>
        // Optionally emit for each element (disabled by default for performance)
        // vec.zipWithIndex.foreach { case (elem, idx) =>
        //   emitRecursive(elem, s"$target($idx)", binding)
        // }
      
      case _ => // Ground types have no children
    }
    
    Some(())
  }
  
  /**
    * Extract type name from Data element.
    * 
    * == P0 FIX: Explicit Type Guards ==
    * 
    * Type guards prevent subtype mis-classification:
    * - Bool extends UInt, so check Bool first with guard
    * - AsyncReset extends Reset, so check AsyncReset first with guard
    * 
    * Without guards, reordering cases would break Bool/AsyncReset detection.
    * 
    * == Scala Artifact Cleaning ==
    * 
    * Removes Scala compiler artifacts from type names:
    * - Inner class suffixes: `MyBundle$1` → `MyBundle`
    * - Enum type suffix: `StateType` → `State`
    * - Trailing dollar: `State$` → `State`
    * 
    * @param data The Data element to extract type name from
    * @return Clean type name (e.g., "UInt", "MyBundle", "MyEnum")
    */
  def extractTypeName(data: Data): String = {
    data match {
      // P0: Bool MUST be checked before UInt (Bool extends UInt)
      case _: Bool => "Bool"
      // Guard ensures UInt doesn't match Bool instances
      case _: UInt if !data.isInstanceOf[Bool] => "UInt"
      case _: SInt => "SInt"
      case _: Clock => "Clock"
      // P0: AsyncReset MUST be checked before Reset
      case _: AsyncReset => "AsyncReset"
      // Guard ensures Reset doesn't match AsyncReset instances
      case _: Reset if !data.isInstanceOf[AsyncReset] => "Reset"
      case v: Vec[_] => "Vec"
      case e: EnumType => 
        // Clean enum type name (remove Scala artifacts)
        e.factory.getClass.getSimpleName
          .stripSuffix("$")
          .stripSuffix("Type")
      case b: Bundle => 
        // Clean bundle class name (remove Scala inner class suffix like $1)
        b.getClass.getSimpleName
          .stripSuffix("$")
          .replaceAll("\\$\\d+$", "") // Remove anonymous class suffixes like $1, $2
      case _ => 
        // Fallback for custom Data types
        data.getClass.getSimpleName
          .stripSuffix("$")
          .replaceAll("\\$\\d+$", "") // Remove anonymous class suffixes
    }
  }
  
  /**
    * Extract all type-specific parameters as key-value map.
    * 
    * Parameters are serialized as `"key1=val1;key2=val2"` for CIRCT consumption.
    * 
    * @param data The Data element to extract parameters from
    * @return Map of parameter name to value (e.g., Map("width" -> "8"))
    */
  def extractAllParams(data: Data): Map[String, String] = {
    data match {
      case u: UInt =>
        u.widthOption.map(w => Map("width" -> w.toString)).getOrElse(Map.empty)
      
      case s: SInt =>
        s.widthOption.map(w => Map("width" -> w.toString)).getOrElse(Map.empty)
      
      case v: Vec[_] =>
        Map(
          "length" -> v.length.toString,
          "elementType" -> extractTypeName(v.sample_element)
        )
      
      case b: Bundle =>
        extractBundleParams(b)
      
      case _ => Map.empty
    }
  }
  
  /**
    * Extract constructor parameters from Bundle using reflection.
    * 
    * Uses Java reflection to access Bundle constructor parameters and their
    * current values. Falls back to element-based structure for anonymous Bundles.
    * 
    * @param bundle The Bundle instance to extract parameters from
    * @return Map of parameter names to values (e.g., Map("width" -> "32"))
    * 
    * @example Parametric Bundle:
    * {{{  
    * class MyBundle(val width: Int, val depth: Int) extends Bundle {
    *   val data = UInt(width.W)
    * }
    * 
    * val b = new MyBundle(32, 1024)
    * extractBundleParams(b)
    * // Returns: Map("width" -> "32", "depth" -> "1024")
    * }}}
    */
  def extractBundleParams(bundle: Bundle): Map[String, String] = {
    try {
      val clazz = bundle.getClass
      val constructors = clazz.getConstructors
      
      if (constructors.isEmpty) return fallbackBundleParams(bundle)
      
      val constructor = constructors.head
      val paramNames = constructor.getParameters.map(_.getName)
      
      // Extract parameter values via reflection
      val params = paramNames.flatMap { paramName =>
        try {
          val field = clazz.getDeclaredField(paramName)
          field.setAccessible(true)
          val value = field.get(bundle)
          Some(paramName -> value.toString)
        } catch {
          case _: NoSuchFieldException => None
          case _: IllegalAccessException => None
        }
      }
      
      // Fallback if no valid params extracted
      if (params.isEmpty) {
        fallbackBundleParams(bundle)
      } else {
        params.toMap
      }
    } catch {
      case _: Exception => fallbackBundleParams(bundle)
    }
  }
  
  /**
    * Fallback: extract Bundle structure from elements.
    * 
    * Used for anonymous Bundles that have no constructor parameters:
    * {{{  
    * val io = IO(new Bundle { val data = UInt(8.W) })
    * }}}
    * 
    * @param bundle Bundle instance
    * @return Map of field names to type names (e.g., Map("data" -> "UInt"))
    */
  private def fallbackBundleParams(bundle: Bundle): Map[String, String] = {
    bundle.elements.map { case (name, field) =>
      name -> extractTypeName(field)
    }.toMap
  }
  
  /**
    * Extract enum definition as "0:EnumName(0=IDLE),1:EnumName(1=RUN)" format.
    * 
    * == Scala Artifact Cleaning ==
    * 
    * Cleans enum value names with smart 's' prefix handling:
    * - Scala compiler generates: `sIDLE$`, `sRUN$` (with 's' prefix)
    * - Only strips 's' before uppercase (Scala naming convention)
    * - Preserves user-defined names: "sleep", "start"
    * 
    * Examples:
    * - `"sIDLE"` → `"IDLE"` (Scala artifact, clean)
    * - `"sRUN"` → `"RUN"` (Scala artifact, clean)
    * - `"sleep"` → `"sleep"` (user-defined, preserve)
    * - `"start"` → `"start"` (user-defined, preserve)
    * 
    * @param enum EnumType instance
    * @return Serialized enum definition for CIRCT consumption
    * 
    * @example ChiselEnum:
    * {{{  
    * object State extends ChiselEnum {
    *   val IDLE, RUN, DONE = Value
    * }
    * 
    * extractEnumDef(State.IDLE)
    * // Returns: "0:State(0=IDLE),1:State(1=RUN),2:State(2=DONE)"
    * }}}
    */
  def extractEnumDef(`enum`: EnumType): String = {
    try {
      val allValues = `enum`.factory.all
      val enumTypeName = `enum`.factory.getClass.getSimpleName
        .stripSuffix("$")
        .stripSuffix("Type")
      
      allValues.map { e =>
        // Clean value name with smart 's' prefix handling
        val cleanName = e.getClass.getSimpleName
          .stripSuffix("$")
          .replaceFirst("^s(?=[A-Z])", "")  // Only remove 's' before uppercase
          .stripPrefix("$")  // Remove any remaining $ prefix
        
        // Format: "0:MyState(0=IDLE),1:MyState(1=RUN)"
        s"${e.litValue}:$enumTypeName(${e.litValue}=$cleanName)"
      }.mkString(",")
    } catch {
      case _: Exception => 
        // Fallback: just use current value
        val cleanName = `enum`.getClass.getSimpleName
          .stripSuffix("$")
          .stripSuffix("Type")
        s"${`enum`.litValue}:$cleanName"
    }
  }
  
  /**
    * Serialize parameter map as "key1=value1;key2=value2".
    * 
    * Format is designed for CIRCT parsing compatibility.
    * 
    * @param params Parameter map
    * @return Serialized string (e.g., "width=8;depth=1024")
    */
  private def serializeParams(params: Map[String, String]): String = {
    params.map { case (k, v) => s"$k=$v" }.mkString(";")
  }
}