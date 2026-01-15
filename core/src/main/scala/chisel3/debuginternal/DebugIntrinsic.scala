// SPDX-License-Identifier: Apache-2.0

package chisel3.debuginternal

import chisel3._
import chisel3.probe.{Probe, ProbeValue, define, read}
import chisel3.experimental.SourceInfo

/**
  * Generates CIRCT debug intrinsics using Probe API for strong metadata→RTL binding.
  * 
  * This replaces three previous weak-binding approaches with unified Probe API implementation:
  * - Chisel 6+ ProbeValue() creates stable references
  * - Intrinsics consume probe via read()
  * - FIRRTL lowers to hierarchical name resolution
  * - Survives DCE/CSE/inlining optimizations
  * 
  * Architecture:
  * ```scala
  * val probe = Wire(Probe(data.cloneType))
  * define(probe, ProbeValue(data))      // Create probe reference
  * Intrinsic("circt_debug_typeinfo", ...)(read(probe))  // Strong binding
  * ```
  * 
  * This internal API is used by:
  * - [[chisel3.util.circt.DebugInfo]] (user-facing)
  * - [[chisel3.internal.plugin.ComponentDebugIntrinsics]] (compiler plugin)
  * 
  * @see [[https://circt.llvm.org/docs/Dialects/Debug/ CIRCT Debug Dialect]]
  * @see [[https://www.chisel-lang.org/docs/explanations/probes Chisel Probe API]]
  */
object DebugIntrinsic {
  
  def isEnabled: Boolean = {
    sys.env.get("CHISEL_DEBUG").exists(_.toLowerCase == "true") ||
    sys.props.get("chisel.debug").exists(_.toLowerCase == "true")
  }
  
  /**
    * Emit debug intrinsic using Probe API for strong binding.
    * 
    * Generates:
    * ```firrtl
    * wire _dbg_probe : Probe<UInt<8>>
    * define(_dbg_probe, probe(data))
    * node _dbg_info = intrinsic(circt_debug_typeinfo<...>, read(_dbg_probe))
    * ```
    * 
    * @param data Signal to annotate
    * @param target Hierarchical name (e.g., "io.ctrl.valid")
    * @param binding Signal type ("IO", "Wire", "Reg", "User")
    * @return Some(Unit) if emitted, None if disabled
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
    
    val intrinsicParams: Seq[(String, Param)] = Seq(
      "target" -> StringParam(target),
      "typeName" -> StringParam(typeName),
      "binding" -> StringParam(binding),
      "parameters" -> StringParam(serializeParams(params)),
      "sourceFile" -> StringParam(sourceFile),
      "sourceLine" -> IntParam(sourceLine)
    )
    
    val allParams = data match {
      case e: EnumType =>
        intrinsicParams :+ ("enumDef" -> StringParam(extractEnumDef(e)))
      case _ => intrinsicParams
    }
    
    try {
      val probeWire = Wire(Probe(data.cloneType))
      define(probeWire, ProbeValue(data))
      Intrinsic("circt_debug_typeinfo", allParams: _*)(read(probeWire))
      Some(())
    } catch {
      case e: Exception =>
        if (sys.props.get("chisel.debug.verbose").exists(_.toLowerCase == "true")) {
          Console.err.println(s"[DebugIntrinsic] Failed for $target: ${e.getMessage}")
        }
        None
    }
  }
  
  /**
    * Recursively emit intrinsics for nested structures.
    * 
    * @param data Root signal
    * @param target Name prefix (children get "parent.child" names)
    * @param binding Signal type
    */
  def emitRecursive(
    data: Data,
    target: String,
    binding: String
  )(implicit sourceInfo: SourceInfo): Option[Unit] = {
    if (!isEnabled) return None
    
    emit(data, target, binding)
    
    data match {
      case bundle: Bundle =>
        bundle.elements.foreach { case (fieldName, fieldData) =>
          emitRecursive(fieldData, s"$target.$fieldName", binding)
        }
      case _ => // Vec elements not recursively annotated by default
    }
    
    Some(())
  }
  
  /**
    * Extract type name with guards for subtypes.
    * 
    * Guards prevent mis-classification:
    * - Bool checked before UInt (Bool extends UInt)
    * - AsyncReset before Reset (AsyncReset extends Reset)
    */
  def extractTypeName(data: Data): String = {
    data match {
      case _: Bool => "Bool"
      case _: UInt if !data.isInstanceOf[Bool] => "UInt"
      case _: SInt => "SInt"
      case _: Clock => "Clock"
      case _: AsyncReset => "AsyncReset"
      case _: Reset if !data.isInstanceOf[AsyncReset] => "Reset"
      case _: Vec[_] => "Vec"
      case e: EnumType => 
        e.factory.getClass.getSimpleName.stripSuffix("$").stripSuffix("Type")
      case b: Bundle => 
        b.getClass.getSimpleName.stripSuffix("$").replaceAll("\\$\\d+$", "")
      case _ => 
        data.getClass.getSimpleName.stripSuffix("$").replaceAll("\\$\\d+$", "")
    }
  }
  
  def extractAllParams(data: Data): Map[String, String] = {
    data match {
      case u: UInt =>
        u.widthOption.map(w => Map("width" -> w.toString)).getOrElse(Map.empty)
      case s: SInt =>
        s.widthOption.map(w => Map("width" -> w.toString)).getOrElse(Map.empty)
      case v: Vec[_] =>
        Map("length" -> v.length.toString, "elementType" -> extractTypeName(v.sample_element))
      case b: Bundle => extractBundleParams(b)
      case _ => Map.empty
    }
  }
  
  def extractBundleParams(bundle: Bundle): Map[String, String] = {
    try {
      val clazz = bundle.getClass
      val constructors = clazz.getConstructors
      if (constructors.isEmpty) return fallbackBundleParams(bundle)
      
      val constructor = constructors.head
      val paramNames = constructor.getParameters.map(_.getName)
      
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
      
      if (params.isEmpty) fallbackBundleParams(bundle) else params.toMap
    } catch {
      case _: Exception => fallbackBundleParams(bundle)
    }
  }
  
  private def fallbackBundleParams(bundle: Bundle): Map[String, String] = {
    bundle.elements.map { case (name, field) => name -> extractTypeName(field) }.toMap
  }
  
  /**
    * Extract enum definition with Scala artifact cleaning.
    * 
    * Removes compiler-generated 's' prefix from enum values:
    * - "sIDLE" → "IDLE" (artifact)
    * - "sleep" → "sleep" (user-defined, preserved)
    */
  def extractEnumDef(`enum`: EnumType): String = {
    try {
      val allValues = `enum`.factory.all
      val enumTypeName = `enum`.factory.getClass.getSimpleName
        .stripSuffix("$").stripSuffix("Type")
      
      allValues.map { e =>
        val cleanName = e.getClass.getSimpleName
          .stripSuffix("$")
          .replaceFirst("^s(?=[A-Z])", "")
          .stripPrefix("$")
        s"${e.litValue}:$enumTypeName(${e.litValue}=$cleanName)"
      }.mkString(",")
    } catch {
      case _: Exception => 
        val cleanName = `enum`.getClass.getSimpleName.stripSuffix("$").stripSuffix("Type")
        s"${`enum`.litValue}:$cleanName"
    }
  }
  
  private def serializeParams(params: Map[String, String]): String = {
    params.map { case (k, v) => s"$k=$v" }.mkString(";")
  }
}