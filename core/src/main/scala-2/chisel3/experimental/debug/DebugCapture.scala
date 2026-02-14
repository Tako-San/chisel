package chisel3.experimental.debug

import chisel3._
import chisel3.experimental.UnlocatableSourceInfo
import chisel3.experimental.fromStringToStringParam
import chisel3.{Mem, MemBase, SyncReadMem}
import chisel3.internal.Builder
import chisel3.internal.firrtl.ir._
import chisel3.util.debug.TypeReflector
import logger.LazyLogging
import scala.collection.mutable
import scala.reflect.runtime.universe._

object DebugCapture extends LazyLogging {
  private val mirror = runtimeMirror(getClass.getClassLoader)

  private val MaxVectorCaptureSize = 100
  private val MaxRecursionDepth = 50

  private lazy val dataClassSymbol = mirror.classSymbol(classOf[chisel3.Data])
  private lazy val bundleClassSymbol = mirror.classSymbol(classOf[chisel3.Bundle])
  private lazy val recordClassSymbol = mirror.classSymbol(classOf[chisel3.Record])
  private lazy val vecClassSymbol = mirror.classSymbol(classOf[chisel3.Vec[_]])
  private lazy val memBaseClassSymbol = mirror.classSymbol(classOf[chisel3.MemBase[_]])
  private lazy val boolClassSymbol = mirror.classSymbol(classOf[chisel3.Bool])
  private lazy val uintClassSymbol = mirror.classSymbol(classOf[chisel3.UInt])
  private lazy val sintClassSymbol = mirror.classSymbol(classOf[chisel3.SInt])

  def captureCircuit(module: RawModule): Unit = {
    try {
      val visited = mutable.Map[Data, String]()
      processIOPorts(module, visited)
      val className = module.getClass.getSimpleName
      val isBlackBoxOrExtModule = className.contains("BlackBox") || (
        className == "ExtModule" || className.contains("BlackBox$")
      )
      if (!isBlackBoxOrExtModule) {
        processInternalData(module, visited)
      }
    } catch {
      case e @ scala.util.control.NonFatal(_) =>
        logger.warn(s"Error capturing debug info for module ${module.name}: ${e.getMessage}")
    }
  }

  private def processIOPorts(module: RawModule, visited: mutable.Map[Data, String]): Unit = {
    val instanceMirror = mirror.reflect(module.asInstanceOf[AnyRef])
    val tpe = mirror.classSymbol(module.getClass).toType

    val ioSymbol = tpe.member(TermName("io"))
    if (ioSymbol != NoSymbol) {
      try {
        val ioValue = instanceMirror.reflectField(ioSymbol.asTerm).get
        if (ioValue.isInstanceOf[Data]) {
          val io = ioValue.asInstanceOf[Data]
          annotateDataRecursive(io, "io", "port", visited, currentDepth = 0)(UnlocatableSourceInfo)
        }
      } catch {
        case e @ scala.util.control.NonFatal(_) =>
          logger.warn(s"Error processing IO port for module ${module.name}: ${e.getMessage}")
      }
    }
  }

  private def processValue(
    value:   Any,
    name:    String,
    visited: mutable.Map[Data, String]
  )(implicit sourceInfo: chisel3.experimental.SourceInfo): Unit = {
    value match {
      case mem:  MemBase[_] => annotateMemory(mem, name)
      case data: Data =>
        visited.get(data) match {
          case Some(existingName) =>
            annotateAlias(data, name, existingName)
          case None if !chisel3.reflect.DataMirror.isIO(data) =>
            annotateDataRecursive(data, name, "source", visited)
          case _ =>
        }
      case _ =>
    }
  }

  private def processInternalData(module: RawModule, visited: mutable.Map[Data, String]): Unit = {
    val instanceMirror = mirror.reflect(module.asInstanceOf[AnyRef])
    val tpe = mirror.classSymbol(module.getClass).toType

    val sortedMembers = tpe.members.toSeq.sortBy(_.name.toString)
    sortedMembers.foreach { member =>
      val memberName = member.name.toString
      if (
        !memberName.startsWith("_") &&
        !memberName.contains("$") &&
        memberName != "io"
      ) {

        member match {
          case m: TermSymbol if m.isVal || m.isVar =>
            try {
              val value = instanceMirror.reflectField(m.asTerm).get
              processValue(value, memberName, visited)(UnlocatableSourceInfo)
            } catch {
              case e @ scala.util.control.NonFatal(_) =>
                logger.warn(s"Error processing field $memberName for module ${module.name}: ${e.getMessage}")
            }
          case _ =>
        }
      }
    }
  }

  private def annotateDataRecursive(
    data:         Data,
    name:         String,
    kind:         String,
    visited:      mutable.Map[Data, String],
    currentDepth: Int = 0
  )(
    implicit sourceInfo: chisel3.experimental.SourceInfo
  ): Unit = {
    if (currentDepth > MaxRecursionDepth) {
      logger.warn(s"Max recursion depth $MaxRecursionDepth exceeded for $name, skipping nested elements")
      return
    }
    data match {
      case _: chisel3.experimental.OpaqueType =>
        visited.get(data) match {
          case Some(existingName) =>
            annotateAlias(data, name, existingName)
          case None =>
            visited(data) = name
            generateIntrinsic(data, name, kind)
        }
      case record: Record if record.elements.nonEmpty =>
        visited.get(data) match {
          case Some(existingName) =>
            annotateAlias(data, name, existingName)
          case None =>
            visited(data) = name
            // Generate intrinsic for the Bundle/Record itself
            generateIntrinsic(data, name, kind)
            // Then recursively annotate its fields
            record.elements.foreach { case (fieldName, fieldData) =>
              annotateDataRecursive(fieldData, s"$name.$fieldName", kind, visited, currentDepth + 1)
            }
        }
      case vec: Vec[_] =>
        vec.zipWithIndex.take(MaxVectorCaptureSize).foreach { case (element, index) =>
          annotateDataRecursive(element, s"$name[$index]", kind, visited, currentDepth + 1)
        }
      case _ =>
        visited.get(data) match {
          case Some(existingName) =>
            annotateAlias(data, name, existingName)
          case None =>
            visited(data) = name
            generateIntrinsic(data, name, kind)
        }
    }
  }

  private def generateIntrinsic(data: Data, name: String, kind: String)(
    implicit sourceInfo: chisel3.experimental.SourceInfo
  ): Unit = {
    val typeName = data match {
      case _: Bool => "Bool"
      case u: UInt => s"UInt<${u.getWidth}>"
      case s: SInt => s"SInt<${s.getWidth}>"
      case _ => data.getClass.getSimpleName
    }

    try {
      if (kind == "port") {
        val direction = chisel3.reflect.DataMirror.directionOf(data) match {
          case ActualDirection.Input  => "INPUT"
          case ActualDirection.Output => "OUTPUT"
          case _                      => "UNKNOWN"
        }

        // Get constructor parameters for Record/Bundle types
        val constructorParams: String = if (TypeReflector.shouldReflect(data)) {
          val params = TypeReflector.getConstructorParams(data)
          params.map { p =>
            // Escape quotes for JSON
            val safeValue = p.value.replace("\"", "\\\"")
            s"""{"name": "${p.name}", "type": "${p.typeName}", "value": "$safeValue", "isComplex": ${p.isComplex}}"""
          }.mkString("[", ",", "]")
        } else "[]"

        val scalaClass = if (TypeReflector.shouldReflect(data)) {
          data.getClass.getSimpleName
        } else ""

        Intrinsic(
          DebugIntrinsics.PortInfo,
          "name" -> name,
          "direction" -> direction,
          "type" -> typeName,
          "scalaClass" -> scalaClass,
          "constructorParams" -> constructorParams
        )(data)
      } else {
        Intrinsic(DebugIntrinsics.SourceInfo, "field_name" -> name, "type" -> typeName)(data)
      }
    } catch {
      case e @ scala.util.control.NonFatal(_) =>
        logger.warn(s"Error creating intrinsic for $name (kind=$kind, type=$typeName)")
    }
  }

  private def annotateAlias(
    data:       Data,
    aliasName:  String,
    targetName: String
  )(implicit sourceInfo: chisel3.experimental.SourceInfo): Unit = {
    try {
      Intrinsic(DebugIntrinsics.AliasInfo, "name" -> aliasName, "target" -> targetName)(data)
    } catch {
      case e @ scala.util.control.NonFatal(_) =>
        logger.warn(s"Error creating alias intrinsic for $aliasName (target=$targetName)")
    }
  }

  private def annotateMemory(mem: MemBase[_], name: String)(
    implicit sourceInfo: chisel3.experimental.SourceInfo
  ): Unit = {
    val typename = mem.t match {
      case _: Bool => "Bool"
      case u: UInt => s"UInt<${u.getWidth}>"
      case s: SInt => s"SInt<${s.getWidth}>"
      case _ => "Aggregate"
    }

    try {
      Intrinsic(DebugIntrinsics.Memory, "name" -> name, "depth" -> mem.length.toString, "type" -> typename)()
    } catch {
      case e @ scala.util.control.NonFatal(_) =>
        logger.warn(s"Error creating memory intrinsic for $name (depth=${mem.length}, type=$typename)")
    }
  }
}

object DebugIntrinsics {
  val PortInfo:   String = "chisel.debug.port_info"
  val SourceInfo: String = "chisel.debug.source_info"
  val AliasInfo:  String = "chisel.debug.alias_info"
  val Memory:     String = "chisel.debug.memory"
}
