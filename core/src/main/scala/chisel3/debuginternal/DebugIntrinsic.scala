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
  * Uses Chisel 6+ Probe API for reliable signal binding:
  *   1. ProbeValue(signal) creates persistent reference
  *   2. Intrinsic consumes probe, creating metadata→RTL dependency
  *   3. CIRCT tracks probe through transforms, maintaining mapping
  * 
  * These intrinsics are lowered by CIRCT's Debug dialect to:
  * - dbg.* MLIR operations (dbg.struct, dbg.variable, etc.)
  * - hw-debug-info.json manifest
  * - VCD/FST metadata for waveform viewers
  * 
  * Architecture follows ChiselTrace approach:
  * - Probe-based references survive optimization passes
  * - Metadata stays bound to actual RTL signal after transforms
  * - Enables Tywaves/HGDB to correlate VCD signals with source types
  */
object DebugIntrinsic {
  
  /**
    * Check if Chisel debug mode is enabled via environment variable
    */
  def isEnabled: Boolean = {
    sys.env.get("CHISEL_DEBUG").exists(_.toLowerCase == "true") ||
    sys.props.get("chisel.debug").exists(_.toLowerCase == "true")
  }
  
  /**
    * Emit a debug intrinsic for a Data element.
    * 
    * Generates a FIRRTL intrinsic statement with Probe-based binding:
    *   wire _probe = probe(io.field)
    *   intrinsic(circt_debug_typeinfo<target="io.field", ...>, read(_probe))
    * 
    * CRITICAL: Uses Probe API to create persistent signal reference.
    * - ProbeValue(data): creates probe reference to signal
    * - read(probe): dereferences probe for intrinsic consumption
    * - CIRCT tracks probe through transforms, preserving metadata binding
    * 
    * This solves the "dangling reference" problem where optimizations
    * rename/eliminate signals, breaking metadata→RTL correspondence.
    * 
    * @param data The signal to attach metadata to
    * @param target Hierarchical name (e.g., "io.field1.subfield")
    * @param binding Signal binding type ("IO", "Wire", "Reg", "OpResult")
    * @return Some(Unit) if intrinsic was emitted, None if disabled
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
    * For Bundles: emits for bundle itself + all fields.
    * For Vecs: emits for vec itself (elements handled separately if needed).
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
    * P0 FIX: Explicit type guards prevent subtype mis-classification:
    * - Bool extends UInt, so check Bool first with guard
    * - AsyncReset extends Reset, so check AsyncReset first with guard
    * 
    * Without guards, reordering cases would break Bool/AsyncReset detection.
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
    * Extract all type-specific parameters as key-value map
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
    * IMPROVEMENT: Fallback to element inspection for anonymous Bundles:
    *   val io = IO(new Bundle { val data = UInt(8.W) })
    * These have no constructor params but still have meaningful structure.
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
    * Used for anonymous Bundles that have no constructor parameters.
    */
  private def fallbackBundleParams(bundle: Bundle): Map[String, String] = {
    bundle.elements.map { case (name, field) =>
      name -> extractTypeName(field)
    }.toMap
  }
  
  /**
    * Extract enum definition as "0:EnumName(0=IDLE),1:EnumName(1=RUN)" format.
    * 
    * IMPROVEMENT: Clean enum value names:
    * - Remove package prefixes ("chisel3.ChiselEnum$sIDLE$" -> "IDLE")
    * - Remove Scala compiler artifacts ($, Type suffixes)
    */
  def extractEnumDef(`enum`: EnumType): String = {
    try {
      val allValues = `enum`.factory.all
      val enumTypeName = `enum`.factory.getClass.getSimpleName
        .stripSuffix("$")
        .stripSuffix("Type")
      
      allValues.map { e =>
        // Clean value name: "sIDLE$" -> "IDLE"
        val cleanName = e.getClass.getSimpleName
          .stripSuffix("$")
          .stripPrefix("s")  // Chisel enum values often start with 's'
          .stripPrefix("$")
        
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
    * Serialize parameter map as "key1=value1;key2=value2"
    */
  private def serializeParams(params: Map[String, String]): String = {
    params.map { case (k, v) => s"$k=$v" }.mkString(";")
  }
}