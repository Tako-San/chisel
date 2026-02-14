// SPDX-License-Identifier: Apache-2.0

package chisel3.experimental.debug

import _root_.chisel3._
import _root_.chisel3.experimental.UnlocatableSourceInfo
import _root_.chisel3.experimental.fromStringToStringParam
import _root_.chisel3.{Mem, MemBase, SyncReadMem}
import _root_.chisel3.internal.Builder
import chisel3.internal.firrtl.ir.DefMemory
import _root_.chisel3.internal.firrtl.ir.DefReg
import _root_.chisel3.internal.firrtl.ir.DefRegInit
import _root_.chisel3.internal.firrtl.ir.DefWire
import _root_.chisel3.internal.firrtl.ir.DefIntrinsic
import chisel3.experimental.debug.DebugTypeReflector
import logger.LazyLogging
import scala.collection.mutable
import scala.reflect.runtime.universe._
import scala.util.control.NonFatal

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
        e.printStackTrace()
    }
  }

  private def generateIntrinsicForPort(data: Data, name: String, typeName: String, module: RawModule)(
    implicit sourceInfo: chisel3.experimental.SourceInfo
  ): Unit = {
    val direction = chisel3.reflect.DataMirror.directionOf(data) match {
      case ActualDirection.Input  => "INPUT"
      case ActualDirection.Output => "OUTPUT"
      case _                      => "UNKNOWN"
    }

    // Get constructor parameters for Record/Bundle types
    val constructorParams: String = if (DebugTypeReflector.shouldReflect(data)) {
      val params = DebugTypeReflector.getConstructorParams(data)
      params.map { p =>
        // Escape quotes for JSON
        val safeValue = p.value.replace("\"", "\\\"")
        s"""{"name": "${p.name}", "type": "${p.typeName}", "value": "$safeValue", "isComplex": ${p.isComplex}}"""
      }.mkString("[", ",", "]")
    } else "[]"

    val scalaClass = if (DebugTypeReflector.shouldReflect(data)) {
      data.getClass.getSimpleName
    } else ""

    // Create intrinsic command directly and add it to the module
    val intrinsicCmd = DefIntrinsic(
      sourceInfo,
      DebugIntrinsics.PortInfo,
      Seq(data.ref),
      Seq(
        "name" -> name,
        "direction" -> direction,
        "type" -> typeName,
        "scalaClass" -> scalaClass,
        "constructorParams" -> constructorParams
      )
    )
    chisel3.IRTraverser.addIntrinsicCommand(module, intrinsicCmd)
  }

  private def processIOPorts(module: RawModule, visited: mutable.Map[Data, String]): Unit = {
    val instanceMirror = mirror.reflect(module.asInstanceOf[AnyRef])
    val tpe = mirror.classSymbol(module.getClass).toType

    val ioSymbol = tpe.member(TermName("io"))
    if (ioSymbol != NoSymbol) {
      try {
        if (!ioSymbol.isTerm) {
          logger.warn(s"Error processing IO port for module ${module.name}: io symbol is not a term")
          return
        }
        val ioValue = instanceMirror.reflectField(ioSymbol.asTerm).get
        if (ioValue.isInstanceOf[Data]) {
          val io = ioValue.asInstanceOf[Data]
          annotatePortDataRecursive(io, "io", visited, currentDepth = 0, module)(UnlocatableSourceInfo)
        }
      } catch {
        case e @ scala.util.control.NonFatal(_) =>
          logger.warn(s"Error processing IO port for module ${module.name}: ${e.getMessage}")
          e.printStackTrace()
      }
    }
  }

  private def annotatePortDataRecursive(
    data:         Data,
    name:         String,
    visited:      mutable.Map[Data, String],
    currentDepth: Int,
    module:       RawModule
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
            val typeName = data match {
              case _: Bool => "Bool"
              case u: UInt => s"UInt<${u.getWidth}>"
              case s: SInt => s"SInt<${s.getWidth}>"
              case _ => data.getClass.getSimpleName
            }
            generateIntrinsicForPort(data, name, typeName, module)
        }
      case record: Record if record.elements.nonEmpty =>
        visited.get(data) match {
          case Some(_) => // Already processed
          case None =>
            visited(data) = name
            record.elements.foreach { case (fieldName, fieldData) =>
              annotatePortDataRecursive(fieldData, s"$name.$fieldName", visited, currentDepth + 1, module)
            }
        }
      case vec: Vec[_] =>
        visited.get(data) match {
          case Some(_) => // Already processed
          case None =>
            visited(data) = name
            val size = vec.size
            val maxVecElems = math.min(size, MaxVectorCaptureSize)
            for (i <- 0 until maxVecElems) {
              annotatePortDataRecursive(vec(i), s"$name[$i]", visited, currentDepth + 1, module)
            }
            if (size > MaxVectorCaptureSize) {
              logger.info(s"Truncated vector $name to first $MaxVectorCaptureSize elements (total size: $size)")
            }
        }
      case _ =>
        visited.get(data) match {
          case Some(_) => // Already processed
          case None =>
            visited(data) = name
            val typeName = data match {
              case _: Bool => "Bool"
              case u: UInt => s"UInt<${u.getWidth}>"
              case s: SInt => s"SInt<${s.getWidth}>"
              case _ => data.getClass.getSimpleName
            }
            generateIntrinsicForPort(data, name, typeName, module)
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
    val commands = chisel3.IRTraverser.getCommands(module)

    commands.foreach { cmd =>
      cmd match {
        case w: DefWire =>
          // Try to get the original name from seedOpt, which contains the user-provided name
          val name = w.id.seedOpt.getOrElse(w.id.instanceName)
          if (!name.startsWith("_")) {
            generateIntrinsic(w.id, name, "source")(UnlocatableSourceInfo)
          }

        case r: DefReg =>
          val name = r.id.seedOpt.getOrElse(r.id.instanceName)
          generateIntrinsic(r.id, name, "source")(UnlocatableSourceInfo)

        case ri: DefRegInit =>
          val name = ri.id.seedOpt.getOrElse(ri.id.instanceName)
          generateIntrinsic(ri.id, name, "source")(UnlocatableSourceInfo)

        case m: DefMemory =>
          annotateMemoryFromIR(m, module)(UnlocatableSourceInfo)

        case _ =>
        // Ignore Connect, Stop, Printf and other commands
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
        val constructorParams: String = if (DebugTypeReflector.shouldReflect(data)) {
          val params = DebugTypeReflector.getConstructorParams(data)
          params.map { p =>
            // Escape quotes for JSON
            val safeValue = p.value.replace("\"", "\\\"")
            s"""{"name": "${p.name}", "type": "${p.typeName}", "value": "$safeValue", "isComplex": ${p.isComplex}}"""
          }.mkString("[", ",", "]")
        } else "[]"

        val scalaClass = if (DebugTypeReflector.shouldReflect(data)) {
          data.getClass.getSimpleName
        } else ""

        // Create intrinsic command directly since module body may be closed
        val intrinsicCmd = DefIntrinsic(
          sourceInfo,
          DebugIntrinsics.PortInfo,
          Seq(data.ref),
          Seq(
            "name" -> name,
            "direction" -> direction,
            "type" -> typeName,
            "scalaClass" -> scalaClass,
            "constructorParams" -> constructorParams
          )
        )

        // Find the module from the data's binding and add the command
        data.topBindingOpt match {
          case Some(b) =>
            b.location match {
              case Some(m: RawModule) =>
                logger.info(s"Adding PortInfo intrinsic to module ${m.name} for data $name")
                chisel3.IRTraverser.addIntrinsicCommand(m, intrinsicCmd)
              case _ =>
                // Data is not bound to a module, fall back to Intrinsic.apply
                Intrinsic(
                  DebugIntrinsics.PortInfo,
                  "name" -> name,
                  "direction" -> direction,
                  "type" -> typeName,
                  "scalaClass" -> scalaClass,
                  "constructorParams" -> constructorParams
                )(data)
            }
          case _ =>
            // Data is not bound, fall back to Intrinsic.apply
            Intrinsic(
              DebugIntrinsics.PortInfo,
              "name" -> name,
              "direction" -> direction,
              "type" -> typeName,
              "scalaClass" -> scalaClass,
              "constructorParams" -> constructorParams
            )(data)
        }
      } else {
        // For source kind (called from processInternalData), we need to explicitly
        // create the command and add it to the module since module body may be complete
        val intrinsicCmd = DefIntrinsic(
          sourceInfo,
          DebugIntrinsics.SourceInfo,
          Seq(data.ref),
          Seq("field_name" -> name, "type" -> typeName)
        )

        // Find the module from the data's binding and add the command
        data.topBindingOpt match {
          case Some(b) =>
            b.location match {
              case Some(m: RawModule) =>
                chisel3.IRTraverser.addIntrinsicCommand(m, intrinsicCmd)
              case _ =>
                // Data is not bound to a module, this can happen for some IR commands
                // Try using the Intrinsic object as fallback
                Intrinsic(DebugIntrinsics.SourceInfo, "field_name" -> name, "type" -> typeName)(data)
            }
          case _ =>
            // Data is not bound, try using the Intrinsic object as fallback
            Intrinsic(DebugIntrinsics.SourceInfo, "field_name" -> name, "type" -> typeName)(data)
        }
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

  private def annotateMemoryFromIR(cmd: DefMemory, module: RawModule)(
    implicit sourceInfo: chisel3.experimental.SourceInfo
  ): Unit = {
    try {
      Intrinsic(
        DebugIntrinsics.Memory,
        "name" -> cmd.id.instanceName,
        "depth" -> cmd.size.toString,
        "type" -> cmd.t.toString
      )()
    } catch {
      case e @ scala.util.control.NonFatal(_) =>
        logger.warn(s"Error annotating memory ${cmd.id.instanceName}: ${e.getMessage}")
    }
  }
}

object DebugIntrinsics {
  val PortInfo:   String = "chisel.debug.port_info"
  val SourceInfo: String = "chisel.debug.source_info"
  val AliasInfo:  String = "chisel.debug.alias_info"
  val Memory:     String = "chisel.debug.memory"
}
