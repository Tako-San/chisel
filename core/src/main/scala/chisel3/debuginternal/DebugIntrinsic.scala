// SPDX-License-Identifier: Apache-2.0

package chisel3.debuginternal

// This package contains internal implementation for CIRCT debug metadata intrinsics.
// User-facing API is in chisel3.util.circt.DebugInfo

import chisel3._
import chisel3.experimental.{Param, SourceInfo, StringParam, IntParam}
import scala.collection.immutable.ListMap

/**
  * Generates CIRCT debug intrinsics for preserving high-level type information
  * through FIRRTL compilation.
  * 
  * Uses Chisel 6+ Intrinsic API for proper FIRRTL intrinsic emission:
  *   intrinsic(circt_debug_typeinfo<target="io.field", ...> : UInt<1>)
  * 
  * These intrinsics are lowered by CIRCT's Debug dialect to:
  * - dbg.* MLIR operations (dbg.struct, dbg.variable, etc.)
  * - hw-debug-info.json manifest
  * - VCD/FST metadata for waveform viewers
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
    * Generates a FIRRTL intrinsic statement:
    *   intrinsic(circt_debug_typeinfo<target="io.field", typeName="MyBundle", ...> : UInt<1>)
    * 
    * The intrinsic has no inputs/outputs - it's purely a metadata annotation.
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
    
    // Generate intrinsic statement (no inputs/outputs)
    // Result: intrinsic(circt_debug_typeinfo<...> : UInt<1>)
    Intrinsic("circt_debug_typeinfo", allParams: _*)()
    
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
    * Extract type name from Data element
    */
  def extractTypeName(data: Data): String = {
    data match {
      case _: UInt => "UInt"
      case _: SInt => "SInt"
      case _: Bool => "Bool"
      case _: Clock => "Clock"
      case _: Reset => "Reset"
      case _: AsyncReset => "AsyncReset"
      case v: Vec[_] => "Vec"
      case e: EnumType => 
        e.factory.getClass.getSimpleName.stripSuffix("$").stripSuffix("Type")
      case b: Bundle => 
        b.getClass.getSimpleName.stripSuffix("$")
      case _ => 
        data.getClass.getSimpleName.stripSuffix("$")
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
    * Captures parameterized Bundle configurations (e.g., width, depth).
    */
  def extractBundleParams(bundle: Bundle): Map[String, String] = {
    try {
      val clazz = bundle.getClass
      val constructors = clazz.getConstructors
      
      if (constructors.isEmpty) return Map.empty
      
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
      
      params.toMap
    } catch {
      case _: Exception => Map.empty
    }
  }
  
  /**
    * Extract enum definition as "0:IDLE,1:RUN,2:DONE" format
    */
  def extractEnumDef(`enum`: EnumType): String = {
    try {
      val allValues = `enum`.factory.all
      
      allValues.map { e =>
        s"${e.litValue}:${e.toString}"
      }.mkString(",")
    } catch {
      case _: Exception => 
        // Fallback: just use the current value
        s"${`enum`.litValue}:${`enum`.toString}"
    }
  }
  
  /**
    * Serialize parameter map as "key1=value1;key2=value2"
    */
  private def serializeParams(params: Map[String, String]): String = {
    params.map { case (k, v) => s"$k=$v" }.mkString(";")
  }
}
