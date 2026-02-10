// SPDX-License-Identifier: Apache-2.0

package chisel3.debuginternal

import chisel3._
import chisel3.probe.{define, read, Probe, ProbeValue}
import chisel3.experimental.SourceInfo
import scala.util.control.NonFatal
import scala.collection.concurrent.TrieMap
import scala.util.matching.Regex

/**
  * Generates CIRCT debug intrinsics using Probe API for strong metadata->RTL binding.
  *
  * Implements unified Probe API approach that survives DCE/CSE/inlining
  * optimizations. Used by user-facing API and compiler plugin.
  *
  * @see [[https://circt.llvm.org/docs/Dialects/Debug/ CIRCT Debug Dialect]]
  * @see [[https://www.chisel-lang.org/docs/explanations/probes Chisel Probe API]]
  */
object DebugIntrinsic {

  final val IntrinsicName = "circt_debug_typeinfo"

  private val enumCache = TrieMap[String, String]()

  def isEnabled: Boolean = {
    sys.env.get("CHISEL_DEBUG").exists(_.toLowerCase == "true") ||
    sys.props.get("chisel.debug").exists(_.toLowerCase == "true")
  }

  /** Execute a block with debug mode temporarily enabled.
    *
    * Ensures system property is cleaned up even if block fails.
    */
  def withDebugMode[T](block: => T): T = {
    val previousDebug = sys.props.get("chisel.debug")
    try {
      sys.props("chisel.debug") = "true"
      block
    } finally {
      previousDebug match {
        case Some(value) => sys.props("chisel.debug") = value
        case None        => sys.props.remove("chisel.debug")
      }
    }
  }

  /**
    * Emit debug intrinsic using Probe API for strong binding.
    *
    * Generates FIRRTL:
    * ```
    * wire _probe : Probe<UInt<8>>
    * define(_probe, probe(data))
    * node _info = intrinsic(circt_debug_typeinfo<...>, read(_probe))
    * ```
    *
    * @param data Signal to annotate
    * @param target Hierarchical name (e.g., "io.ctrl.valid")
    * @param binding Signal type ("IO", "Wire", "Reg", "User")
    * @return Some(Unit) if emitted, None if disabled or failed gracefully
    */
  def emit(
    data:    Data,
    target:  String,
    binding: String
  )(implicit sourceInfo: SourceInfo): Option[Unit] = {
    if (!isEnabled) return None
    emitImpl(data, target, binding, sourceInfo)
  }

  private def emitImpl(
    data:       Data,
    target:     String,
    binding:    String,
    sourceInfo: SourceInfo
  ): Option[Unit] = {
    val params = buildIntrinsicParams(data, target, binding, sourceInfo)
    emitProbeIntrinsic(data, params, target)(sourceInfo)
  }

  /**
    * Recursively emit intrinsics for nested structures.
    *
    * @param data Root signal
    * @param target Name prefix (children get "parent.child" names)
    * @param binding Signal type
    */
  def emitRecursive(
    data:    Data,
    target:  String,
    binding: String
  )(implicit sourceInfo: SourceInfo): Option[Unit] = {
    if (!isEnabled) return None
    emitRecursiveImpl(data, target, binding, sourceInfo)
  }

  private def emitRecursiveImpl(
    data:       Data,
    target:     String,
    binding:    String,
    sourceInfo: SourceInfo
  ): Option[Unit] = {
    emitImpl(data, target, binding, sourceInfo)

    data match {
      case bundle: Bundle =>
        bundle.elements.foreach { case (fieldName, fieldData) =>
          emitRecursiveImpl(fieldData, s"$target.$fieldName", binding, sourceInfo)
        }
      case _ => // Vec elements not recursively annotated by default
    }

    Some(())
  }

  private def emitProbeIntrinsic(
    data:   Data,
    params: Seq[(String, Param)],
    target: String
  )(implicit sourceInfo: SourceInfo): Option[Unit] = {
    try {
      val probeWire = Wire(Probe(data.cloneType))
      define(probeWire, ProbeValue(data))
      Intrinsic(IntrinsicName, params: _*)(read(probeWire))
      Some(())
    } catch {
      case NonFatal(e) =>
        // CRITICAL: Probe API infrastructure failure -> fail fast
        if (e.getMessage != null && e.getMessage.contains("Probe")) {
          Console.err.println(s"[DebugIntrinsic] CRITICAL: Probe API failure for '$target': ${e.getMessage}")
          throw e // Re-throw to fail fast
        }

        // Non-critical: Graceful degradation for other errors
        if (sys.props.get("chisel.debug.verbose").exists(_.toLowerCase == "true")) {
          Console.err.println(s"[DebugIntrinsic] Warning: Failed for '$target': ${e.getMessage}")
        }
        None
    }
  }

  private def buildIntrinsicParams(
    data:       Data,
    target:     String,
    binding:    String,
    sourceInfo: SourceInfo
  ): Seq[(String, Param)] = {
    val typeName = extractTypeName(data)
    val params = extractAllParams(data)
    val (sourceFile, sourceLine) = extractSourceLocation(sourceInfo)

    val baseParams = Seq(
      "target" -> StringParam(target),
      "typeName" -> StringParam(typeName),
      "binding" -> StringParam(binding),
      "parameters" -> StringParam(serializeParams(params)),
      "sourceFile" -> StringParam(sourceFile),
      "sourceLine" -> IntParam(sourceLine)
    )

    data match {
      case e: EnumType =>
        baseParams :+ ("enumDef" -> StringParam(extractEnumDef(e)))
      case _ => baseParams
    }
  }

  private def extractSourceLocation(sourceInfo: SourceInfo): (String, Long) = {
    val file = sourceInfo.filenameOption.getOrElse("<unknown>")
    val line = sourceInfo match {
      case s: chisel3.experimental.SourceLine => s.line.toLong
      case _ => 0L
    }
    (file, line)
  }

  /**
    * Extract type name with guards for subtypes.
    *
    * Pattern matching order prevents mis-classification:
    * - Bool checked before UInt (Bool extends UInt)
    * - AsyncReset before Reset (AsyncReset extends Reset)
    * No explicit guards needed - case ordering ensures correctness.
    */
  def extractTypeName(data: Data): String = {
    data match {
      case _: Bool       => "Bool"
      case _: UInt       => "UInt" // Bool already matched above
      case _: SInt       => "SInt"
      case _: Clock      => "Clock"
      case _: AsyncReset => "AsyncReset"
      case _: Reset      => "Reset" // AsyncReset already matched above
      case _: Vec[_]     => "Vec"
      case e: EnumType =>
        ScalaArtifacts.cleanTypeName(e.factory.getClass.getSimpleName, stripTypeSuffix = true)
      case _ =>
        ScalaArtifacts.cleanTypeName(data.getClass.getSimpleName)
    }
  }

  /**
    * Extract type-specific parameters for intrinsic metadata.
    *
    * Guards prevent unnecessary reflection:
    * - EnumType uses extractEnumDef() instead (handled in buildIntrinsicParams)
    * - Bundle uses reflection for constructor params
    */
  def extractAllParams(data: Data): Map[String, String] = {
    data match {
      case u: UInt => u.widthOption.map(w => Map("width" -> w.toString)).getOrElse(Map.empty)
      case s: SInt => s.widthOption.map(w => Map("width" -> w.toString)).getOrElse(Map.empty)
      case v: Vec[_] =>
        Map("length" -> v.length.toString, "elementType" -> extractTypeName(v.sample_element))
      case _: EnumType => Map.empty // Enum metadata via extractEnumDef(), not reflection
      case b: Bundle   => extractBundleParams(b)
      case _ => Map.empty
    }
  }

  /**
    * Extract Bundle constructor parameters via reflection.
    *
    * Uses scala.util.Try for idiomatic error handling.
    */
  def extractBundleParams(bundle: Bundle): Map[String, String] = {
    try {
      val clazz = bundle.getClass
      val constructors = clazz.getConstructors
      if (constructors.isEmpty) return Map.empty

      val constructor = constructors.head
      val paramNames = constructor.getParameters.map(_.getName)

      paramNames
        .filterNot(name => name.startsWith("$") || name.startsWith("bitmap$"))
        .flatMap { paramName =>
          scala.util.Try {
            val field = clazz.getDeclaredField(paramName)
            field.setAccessible(true)
            paramName -> field.get(bundle).toString
          }.toOption
        }
        .toMap
    } catch {
      case NonFatal(_) => Map.empty
    }
  }

  /**
    * Extract enum definition with Scala artifact cleaning.
    * Uses caching to avoid repeated expensive operations.
    *
    * Removes compiler-generated 's' prefix from enum values:
    * - "sIDLE" -> "IDLE" (artifact)
    * - "sleep" -> "sleep" (user-defined, preserved)
    */
  def extractEnumDef(`enum`: EnumType): String = {
    val key = `enum`.factory.getClass.getName

    enumCache.getOrElseUpdate(
      key, {
        try {
          val allValues = `enum`.factory.all
          val enumTypeName = extractTypeName(`enum`)

          allValues.map { e =>
            val cleanName = ScalaArtifacts.cleanEnumValueName(
              e.toString,
              removeEnumPrefix = true
            )
            s"""\"${e.litValue}\":\"$cleanName\""""
          }.mkString("{", ",", "}")
        } catch {
          case NonFatal(_) =>
            val cleanName = ScalaArtifacts.cleanTypeName(
              `enum`.getClass.getSimpleName,
              stripTypeSuffix = true
            )
            s"${`enum`.litValue}:$cleanName"
        }
      }
    )
  }

  private def serializeParams(params: Map[String, String]): String = {
    params.map { case (k, v) => s"$k=$v" }.mkString(";")
  }

  private object ScalaArtifacts {
    private val AnonymousClassSuffix: Regex = "\\$\\d+$".r
    private val EnumPrefix:           Regex = "^s(?=[A-Z])".r

    def clean(name: String, removeEnumPrefix: Boolean = false): String = {
      val base = AnonymousClassSuffix.replaceAllIn(name.stripSuffix("$"), "")

      if (removeEnumPrefix) {
        EnumPrefix.replaceFirstIn(base, "")
      } else {
        base
      }
    }

    def cleanEnumValueName(name: String, removeEnumPrefix: Boolean = false): String = {
      """\((?:\d+=)?(.+)\)""".r.findFirstMatchIn(name) match {
        case Some(m) => m.group(1)
        case None    => name
      }
    }

    def cleanTypeName(name: String, stripTypeSuffix: Boolean = false): String = {
      val cleaned = clean(name, removeEnumPrefix = false)
      if (stripTypeSuffix) cleaned.stripSuffix("Type") else cleaned
    }
  }
}
