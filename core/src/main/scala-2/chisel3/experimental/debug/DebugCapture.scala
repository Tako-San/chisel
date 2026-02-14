// SPDX-License-Identifier: Apache-2.0

package chisel3.experimental.debug

import _root_.chisel3._
import _root_.chisel3.experimental.UnlocatableSourceInfo
import _root_.chisel3.experimental.fromStringToStringParam
import _root_.chisel3.{Mem, MemBase, SyncReadMem}
import _root_.chisel3.internal.Builder
import chisel3.internal.firrtl.ir._
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
        // First check and handle the record itself
        visited.get(data) match {
          case Some(existingName) =>
            // This aggregate data object was already seen - create an alias
            annotateAlias(data, name, existingName)
          case None =>
            // First time seeing this aggregate - mark as visited
            visited(data) = name
            // Generate port_info for the Bundle/Record itself
            val typeName = data.getClass.getSimpleName
            generateIntrinsicForPort(data, name, typeName, module)
        }
        // Always recursively process fields - use a temporary empty visited map
        // to prevent infinite recursion while allowing field intrinsics to be generated
        val tempVisited = mutable.Map[Data, String]()
        record.elements.foreach { case (fieldName, fieldData) =>
          val fieldTypeName = fieldData match {
            case _: Bool => "Bool"
            case u: UInt => s"UInt<${u.getWidth}>"
            case s: SInt => s"SInt<${s.getWidth}>"
            case _ => fieldData.getClass.getSimpleName
          }
          generateIntrinsicForPort(fieldData, s"$name.$fieldName", fieldTypeName, module)
          // Only recursively process if the field is also an aggregate
          fieldData match {
            case subRecord: Record if subRecord.elements.nonEmpty =>
              annotatePortDataRecursive(fieldData, s"$name.$fieldName", tempVisited, currentDepth + 1, module)
            case subVec: Vec[_] =>
              annotatePortDataRecursive(fieldData, s"$name.$fieldName", tempVisited, currentDepth + 1, module)
            case _ => // Primitive type, no recursion needed
          }
        }
      case vec: Vec[_] =>
        // For Vec ports, we don't generate an intrinsic for the aggregate
        // Instead, always process all elements
        val size = vec.size
        val maxVecElems = math.min(size, MaxVectorCaptureSize)
        for (i <- 0 until maxVecElems) {
          annotatePortDataRecursive(vec(i), s"$name[$i]", visited, currentDepth + 1, module)
        }
        if (size > MaxVectorCaptureSize) {
          logger.info(s"Truncated vector $name to first $MaxVectorCaptureSize elements (total size: $size)")
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

    logger.info(s"Processing ${commands.size} commands for module ${module.name}")
    commands.foreach { cmd =>
      logger.info(s"Processing command: ${cmd.getClass.getSimpleName}")
      cmd match {
        case w: DefWire =>
          // Try to get the original name from seedOpt, which contains the user-provided name
          val name = w.id.seedOpt.getOrElse(w.id.instanceName)
          if (!name.startsWith("_")) {
            // Process the Data object for this wire
            processInternalDataObject(w.id, name, visited, module)(UnlocatableSourceInfo)
          }

        case r: DefReg =>
          val name = r.id.seedOpt.getOrElse(r.id.instanceName)
          processInternalDataObject(r.id, name, visited, module)(UnlocatableSourceInfo)

        case ri: DefRegInit =>
          val name = ri.id.seedOpt.getOrElse(ri.id.instanceName)
          processInternalDataObject(ri.id, name, visited, module)(UnlocatableSourceInfo)

        case m: DefMemory =>
          val name = m.id.seedOpt.getOrElse(m.id.instanceName)
          logger.info(s"Processing DefMemory: name=$name, instanceName=${m.id.instanceName}")
          annotateMemoryFromIR(m, module)(UnlocatableSourceInfo)

        case sm: DefSeqMemory =>
          val name = sm.id.seedOpt.getOrElse(sm.id.instanceName)
          logger.info(s"Processing DefSeqMemory: name=$name, instanceName=${sm.id.instanceName}")
          annotateSeqMemoryFromIR(sm, module)(UnlocatableSourceInfo)

        case _ =>
          logger.info(s"Ignoring command: ${cmd.getClass.getSimpleName}")
        // Ignore Connect, Stop, Printf and other commands
      }
    }
  }

  private def processInternalDataObject(
    data:    Data,
    name:    String,
    visited: mutable.Map[Data, String],
    module:  RawModule
  )(
    implicit sourceInfo: chisel3.experimental.SourceInfo
  ): Unit = {
    if (chisel3.reflect.DataMirror.isIO(data)) {
      // Skip IO ports - they are already processed in processIOPorts
      return
    }

    visited.get(data) match {
      case Some(existingName) if name != existingName =>
        // Already seen with different name - this is an alias
        annotateAlias(data, name, existingName)
      case None =>
        // First time seeing this data - mark as visited
        visited(data) = name
        // Generate intrinsic for the top-level data
        generateIntrinsic(data, name, "source")
        // Recursively annotate Bundle fields and Vec indices
        annotateDataRecursive(data, name, "source", visited, currentDepth = 0)
      case _ =>
      // Same name already visited - skip
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
        // First check and handle the record itself
        visited.get(data) match {
          case Some(existingName) =>
            // This aggregate data object was already seen - create an alias
            annotateAlias(data, name, existingName)
          case None =>
            // First time seeing this aggregate - mark as visited
            visited(data) = name
            // Generate intrinsic for the aggregate type
            generateIntrinsic(data, name, kind)
        }
        // Always recursively process fields - use a temporary empty visited map
        // to prevent infinite recursion while allowing field intrinsics to be generated
        val tempVisited = mutable.Map[Data, String]()
        record.elements.foreach { case (fieldName, fieldData) =>
          generateIntrinsic(fieldData, s"$name.$fieldName", kind)
          // Only recursively process if the field is also an aggregate
          fieldData match {
            case subRecord: Record if subRecord.elements.nonEmpty =>
              annotateDataRecursive(fieldData, s"$name.$fieldName", kind, tempVisited, currentDepth + 1)
            case subVec: Vec[_] =>
              annotateDataRecursive(fieldData, s"$name.$fieldName", kind, tempVisited, currentDepth + 1)
            case _ => // Primitive type, no recursion needed
          }
        }
      case vec: Vec[_] =>
        // For Vec, we don't generate an intrinsic for the aggregate
        // Instead, always process all elements
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
      // Create intrinsic command directly and add it to the module
      val intrinsicCmd = DefIntrinsic(
        sourceInfo,
        DebugIntrinsics.AliasInfo,
        Seq(data.ref),
        Seq("name" -> aliasName, "target" -> targetName)
      )
      // Find the module from the data's binding and add the command
      data.topBindingOpt match {
        case Some(b) =>
          b.location match {
            case Some(m: RawModule) =>
              chisel3.IRTraverser.addIntrinsicCommand(m, intrinsicCmd)
            case _ =>
              // Data is not bound to a module, fall back to Intrinsic.apply
              Intrinsic(DebugIntrinsics.AliasInfo, "name" -> aliasName, "target" -> targetName)(data)
          }
        case _ =>
          // Data is not bound, fall back to Intrinsic.apply
          Intrinsic(DebugIntrinsics.AliasInfo, "name" -> aliasName, "target" -> targetName)(data)
      }
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
      // Try to get the user-provided name from seedOpt, otherwise use instanceName
      val name = cmd.id.seedOpt.getOrElse(cmd.id.instanceName)
      logger.info(s"Annotating memory: name=$name, instanceName=${cmd.id.instanceName}, seedOpt=${cmd.id.seedOpt}")

      // Create intrinsic command directly and add it to the module
      val intrinsicCmd = DefIntrinsic(
        sourceInfo,
        DebugIntrinsics.Memory,
        Seq(), // Memory doesn't have a data ref in the same way
        Seq(
          "name" -> name,
          "depth" -> cmd.size.toString,
          "type" -> cmd.t.toString
        )
      )
      logger.info(s"Adding memory intrinsic to module ${module.name}")
      chisel3.IRTraverser.addIntrinsicCommand(module, intrinsicCmd)
      logger.info(s"Successfully added memory intrinsic for $name")
    } catch {
      case e @ scala.util.control.NonFatal(_) =>
        logger.warn(s"Error annotating memory ${cmd.id.instanceName}: ${e.getMessage}")
    }
  }

  private def annotateSeqMemoryFromIR(cmd: DefSeqMemory, module: RawModule)(
    implicit sourceInfo: chisel3.experimental.SourceInfo
  ): Unit = {
    try {
      // Try to get the user-provided name from seedOpt, otherwise use instanceName
      val name = cmd.id.seedOpt.getOrElse(cmd.id.instanceName)
      logger.info(s"Annotating seq memory: name=$name, instanceName=${cmd.id.instanceName}, seedOpt=${cmd.id.seedOpt}")

      // Create intrinsic command directly and add it to the module
      val intrinsicCmd = DefIntrinsic(
        sourceInfo,
        DebugIntrinsics.Memory,
        Seq(), // Memory doesn't have a data ref in the same way
        Seq(
          "name" -> name,
          "depth" -> cmd.size.toString,
          "type" -> cmd.t.toString
        )
      )
      logger.info(s"Adding seq memory intrinsic to module ${module.name}")
      chisel3.IRTraverser.addIntrinsicCommand(module, intrinsicCmd)
      logger.info(s"Successfully added seq memory intrinsic for $name")
    } catch {
      case e @ scala.util.control.NonFatal(_) =>
        logger.warn(s"Error annotating seq memory ${cmd.id.instanceName}: ${e.getMessage}")
    }
  }

}

object DebugIntrinsics {
  val PortInfo:   String = "chisel.debug.port_info"
  val SourceInfo: String = "chisel.debug.source_info"
  val AliasInfo:  String = "chisel.debug.alias_info"
  val Memory:     String = "chisel.debug.memory"
}
