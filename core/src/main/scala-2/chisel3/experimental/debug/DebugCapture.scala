// SPDX-License-Identifier: Apache-2.0

package chisel3.experimental.debug

import chisel3._
import chisel3.experimental.{SourceLine, UnlocatableSourceInfo}
import chisel3.experimental.fromStringToStringParam
import chisel3.MemBase
import chisel3.internal.Builder
import chisel3.internal.firrtl.ir._
import chisel3.experimental.debug.DebugTypeReflector
import logger.LazyLogging
import scala.collection.mutable
import scala.util.control.NonFatal

object DebugCapture extends LazyLogging {
  private val MaxVectorCaptureSize = 1024

  object ParamKeys {
    val SourceFile = "source_file"
    val SourceLine = "source_line"
    val SourceCol = "source_col"
  }

  private def createLocationParams(sourceInfo: chisel3.experimental.SourceInfo): Seq[(String, chisel3.Param)] = {
    val result = sourceInfo match {
      case sl: SourceLine =>
        val params = Seq(
          ParamKeys.SourceFile -> sl.filename,
          ParamKeys.SourceLine -> sl.line.toString
        )
        val colParams = if (sl.col != 0) {
          Seq(ParamKeys.SourceCol -> sl.col.toString)
        } else {
          Seq.empty
        }
        params ++ colParams
      case _ => Seq.empty
    }
    result.map { case (k, v) => (k, chisel3.StringParam(v)) }
  }

  private def getTypeName(data: Data): String = data match {
    case _: Bool => "Bool"
    case u: UInt => s"UInt<${u.getWidth}>"
    case s: SInt => s"SInt<${s.getWidth}>"
    case _ => data.getClass.getSimpleName
  }

  private def getDirection(data: Data): String =
    chisel3.reflect.DataMirror.directionOf(data) match {
      case ActualDirection.Input  => "INPUT"
      case ActualDirection.Output => "OUTPUT"
      case _                      => "UNKNOWN"
    }

  private def buildConstructorParams(data: Data): String =
    if (DebugTypeReflector.shouldReflect(data)) {
      val params = DebugTypeReflector.getConstructorParams(data)
      // TODO: Use proper JSON serialization library to handle all edge cases
      params.map { p =>
        val safeValue = p.value.replace("\"", "\\\"")
        s"""{"name": "${p.name}", "type": "${p.typeName}", "value": "$safeValue", "isComplex": ${p.isComplex}}"""
      }.mkString("[", ",", "]")
    } else "[]"

  private def getScalaClass(data: Data): String =
    if (DebugTypeReflector.shouldReflect(data)) data.getClass.getSimpleName else ""

  private def addIntrinsicToModuleOrFallback(
    data:         Data,
    intrinsicCmd: DefIntrinsic,
    fallback:     () => Unit
  ): Unit = {
    data.topBindingOpt match {
      case Some(b) =>
        b.location match {
          case Some(m: RawModule) =>
            m.addCommand(intrinsicCmd)
          case _ => fallback()
        }
      case _ => fallback()
    }
  }

  def capture(module: RawModule): Unit = {
    val visited = mutable.Map[Data, String]()
    processIOPorts(module, visited)
    val isBlackBoxOrExtModule = (module: AnyRef) match {
      case _: chisel3.internal.BaseBlackBox => true
      case _ => false
    }
    if (!isBlackBoxOrExtModule) {
      processInternalData(module, visited)
    }
  }

  private def generateIntrinsicForPort(data: Data, name: String, typeName: String, module: RawModule)(
    implicit sourceInfo: chisel3.experimental.SourceInfo
  ): Unit = {
    val locSeq = createLocationParams(sourceInfo)
    val baseParams: Seq[(String, chisel3.Param)] = Seq(
      DebugIntrinsics.ParamKeys.Name -> chisel3.StringParam(name),
      DebugIntrinsics.ParamKeys.Direction -> chisel3.StringParam(getDirection(data)),
      DebugIntrinsics.ParamKeys.Type -> chisel3.StringParam(typeName),
      DebugIntrinsics.ParamKeys.ScalaClass -> chisel3.StringParam(getScalaClass(data)),
      DebugIntrinsics.ParamKeys.ConstructorParams -> chisel3.StringParam(buildConstructorParams(data))
    )
    val intrinsicCmd = DefIntrinsic(
      sourceInfo,
      DebugIntrinsics.PortInfo,
      Seq(data.ref),
      baseParams ++ locSeq
    )
    val finalParams = baseParams ++ locSeq
    addIntrinsicToModuleOrFallback(
      data,
      intrinsicCmd,
      () => Intrinsic(DebugIntrinsics.PortInfo, finalParams: _*)(data)
    )
  }

  private def processIOPorts(module: RawModule, visited: mutable.Map[Data, String]): Unit = {
    try {
      val portData = module._ids.collect {
        case data: Data if chisel3.reflect.DataMirror.isIO(data) => data
      }
      portData.foreach { data =>
        val name = data.earlyName
        val sourceInfo = module._getSourceLocator
        annotatePortDataIterative(data, name, visited, module)(sourceInfo)
      }
    } catch {
      case e @ NonFatal(_) =>
        logger.warn(s"Error processing IO ports for module ${module.name}: ${e.getMessage}")
    }
  }

  private case class AnnotationTask(
    data:      Data,
    name:      String,
    kind:      String,
    moduleOpt: Option[RawModule] = None
  )

  private def annotateDataIterative(
    task:    AnnotationTask,
    visited: mutable.Map[Data, String]
  )(implicit sourceInfo: chisel3.experimental.SourceInfo): Unit = {
    import scala.collection.mutable.{Queue, Set => MutableSet}

    val queue = Queue[AnnotationTask](task)
    val seen = MutableSet[Data]()

    def generateIntrinsicForData(data: Data, name: String, kind: String, moduleOpt: Option[RawModule]): Unit = {
      moduleOpt match {
        case Some(module) =>
          generateIntrinsicForPort(data, name, getTypeName(data), module)
        case None =>
          generateIntrinsic(data, name, kind)
      }
    }

    while (queue.nonEmpty) {
      val AnnotationTask(data, name, kind, moduleOpt) = queue.dequeue()

      if (!seen.contains(data)) {
        seen.add(data)

        data match {
          case _: chisel3.experimental.OpaqueType =>
            visited.get(data) match {
              case Some(existingName) =>
                if (existingName != name) annotateAlias(data, name, existingName)
              case None =>
                visited(data) = name
                generateIntrinsicForData(data, name, kind, moduleOpt)
            }

          case record: Record if record.elements.nonEmpty =>
            visited.get(data) match {
              case Some(existingName) =>
                if (existingName != name) annotateAlias(data, name, existingName)
              case None =>
                visited(data) = name
                generateIntrinsicForData(data, name, kind, moduleOpt)
            }
            record.elements.foreach { case (fieldName, fieldData) =>
              val fieldNameFull = s"$name.$fieldName"
              visited.get(fieldData) match {
                case None =>
                  visited(fieldData) = fieldNameFull
                  generateIntrinsicForData(fieldData, fieldNameFull, kind, moduleOpt)
                case Some(existingName) =>
                  if (existingName != fieldNameFull) annotateAlias(fieldData, fieldNameFull, existingName)
              }
              fieldData match {
                case subRecord: Record if subRecord.elements.nonEmpty =>
                  queue.enqueue(AnnotationTask(subRecord, fieldNameFull, kind, moduleOpt))
                case vec: Vec[_] =>
                  queue.enqueue(AnnotationTask(vec, fieldNameFull, kind, moduleOpt))
                case _ =>
              }
            }

          case vec: Vec[_] =>
            val size = vec.size
            val maxVecElems = math.min(size, MaxVectorCaptureSize)
            if (size > MaxVectorCaptureSize) {
              // TODO: Add logging when vector exceeds capture size limit
              // logger.warn(s"Vector size $size exceeds capture limit $MaxVectorCaptureSize, tail will be truncated")
            }
            for (i <- 0 until maxVecElems) {
              queue.enqueue(AnnotationTask(vec(i), s"$name[$i]", kind, moduleOpt))
            }

          case _ =>
            visited.get(data) match {
              case Some(_) =>
              case None =>
                visited(data) = name
                generateIntrinsicForData(data, name, kind, moduleOpt)
            }
        }
      }
    }
  }

  private def annotatePortDataIterative(
    data:    Data,
    name:    String,
    visited: mutable.Map[Data, String],
    module:  RawModule
  )(implicit sourceInfo: chisel3.experimental.SourceInfo): Unit = {
    annotateDataIterative(AnnotationTask(data, name, DebugIntrinsics.Kind.Port, Some(module)), visited)
  }

  private def processInternalData(module: RawModule, visited: mutable.Map[Data, String]): Unit = {
    val visitedMem = mutable.HashSet[MemBase[_]]()
    module._ids.foreach { id =>
      id match {
        case data: Data =>
          val name = id.seedOpt.getOrElse(id.instanceName)
          if (!name.startsWith("_")) {
            processInternalDataObject(data, name, visited, module)(UnlocatableSourceInfo)
          }
        case mem: MemBase[_] =>
          val name = mem.seedOpt.getOrElse(mem.instanceName)
          if (!name.startsWith("_") && visitedMem.add(mem)) {
            annotateMemory(mem, name)(module._getSourceLocator)
          }
        case _ =>
      }
    }

    traverseCommandsForData(module._body.getAllCommands(), visited, visitedMem, module)
  }

  private def traverseCommandsForData(
    commands:   Seq[Command],
    visited:    mutable.Map[Data, String],
    visitedMem: mutable.HashSet[MemBase[_]],
    module:     RawModule
  ): Unit = {
    def processInfoData(
      info:           chisel3.experimental.SourceInfo,
      data:           Data,
      extraPredicate: String => Boolean = _ => true
    ): Unit = {
      val name = data.seedOpt.getOrElse(data.instanceName)
      if (!name.startsWith("_") && extraPredicate(name)) {
        processInternalDataObject(data, name, visited, module)(info)
      }
    }

    commands.foreach { command =>
      command match {
        case DefWire(info, data: Data)             => processInfoData(info, data)
        case DefReg(info, data: Data, _)           => processInfoData(info, data)
        case DefRegInit(info, data: Data, _, _, _) => processInfoData(info, data)

        case DefMemory(info, id: MemBase[_], t: Data, _) =>
          val name = id.seedOpt.getOrElse(id.instanceName)
          if (!name.startsWith("_") && visitedMem.add(id)) {
            val effectiveSourceInfo = info match {
              case _: SourceLine => info
              case _ => module._getSourceLocator
            }
            annotateMemory(id, name)(effectiveSourceInfo)
          }

        case DefSeqMemory(info, id: MemBase[_], t: Data, _, _) =>
          val name = id.seedOpt.getOrElse(id.instanceName)
          if (!name.startsWith("_") && visitedMem.add(id)) {
            val effectiveSourceInfo = info match {
              case _: SourceLine => info
              case _ => module._getSourceLocator
            }
            annotateMemory(id, name)(effectiveSourceInfo)
          }

        case FirrtlMemory(info, id: MemBase[_], t: Data, _, _, _, _, _, _) =>
          val name = id.seedOpt.getOrElse(id.instanceName)
          if (!name.startsWith("_") && visitedMem.add(id)) {
            val effectiveSourceInfo = info match {
              case _: SourceLine => info
              case _ => module._getSourceLocator
            }
            annotateMemory(id, name)(effectiveSourceInfo)
          }

        case DefPrim(_, data: Data, _, _) => processInfoData(command.sourceInfo, data, _ != "?")

        case When(_, _, ifCommands, elseCommands) =>
          traverseCommandsForData(ifCommands, visited, visitedMem, module)
          traverseCommandsForData(elseCommands, visited, visitedMem, module)

        case LayerBlock(_, _, blockCommands) =>
          traverseCommandsForData(blockCommands, visited, visitedMem, module)

        case cmd: DefContract =>
          traverseCommandsForData(cmd.region.getAllCommands(), visited, visitedMem, module)

        case _ =>
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
      return
    }

    visited.get(data) match {
      case Some(existingName) if name != existingName =>
        annotateAlias(data, name, existingName)(sourceInfo)
      case None =>
        visited(data) = name
        val effectiveSourceInfo = sourceInfo match {
          case _: SourceLine => sourceInfo
          case _ => module._getSourceLocator
        }
        addSourceLocationIntrinsic(data, name, DebugIntrinsics.Kind.Source)(effectiveSourceInfo)
        annotateDataIterative(AnnotationTask(data, name, DebugIntrinsics.Kind.Source, None), visited)(
          effectiveSourceInfo
        )
      case _ =>
    }
  }

  private def generateIntrinsic(data: Data, name: String, kind: String)(
    implicit sourceInfo: chisel3.experimental.SourceInfo
  ): Unit = {
    val typeName = getTypeName(data)

    if (kind == DebugIntrinsics.Kind.Port) {
      val portParams: Seq[(String, chisel3.Param)] = Seq(
        DebugIntrinsics.ParamKeys.Name -> chisel3.StringParam(name),
        DebugIntrinsics.ParamKeys.Direction -> chisel3.StringParam(getDirection(data)),
        DebugIntrinsics.ParamKeys.Type -> chisel3.StringParam(typeName),
        DebugIntrinsics.ParamKeys.ScalaClass -> chisel3.StringParam(getScalaClass(data)),
        DebugIntrinsics.ParamKeys.ConstructorParams -> chisel3.StringParam(buildConstructorParams(data))
      )
      val intrinsicCmd = DefIntrinsic(
        sourceInfo,
        DebugIntrinsics.PortInfo,
        Seq(data.ref),
        portParams
      )
      addIntrinsicToModuleOrFallback(
        data,
        intrinsicCmd,
        () => Intrinsic(DebugIntrinsics.PortInfo, portParams: _*)(data)
      )
    } else {
      val locSeq = createLocationParams(sourceInfo)
      val baseParams: Seq[(String, chisel3.Param)] = Seq(
        DebugIntrinsics.ParamKeys.FieldName -> chisel3.StringParam(name),
        DebugIntrinsics.ParamKeys.Type -> chisel3.StringParam(typeName)
      )
      val intrinsicCmd = DefIntrinsic(
        sourceInfo,
        DebugIntrinsics.SourceInfo,
        Seq(data.ref),
        baseParams ++ locSeq
      )
      val finalParams = baseParams ++ locSeq
      addIntrinsicToModuleOrFallback(
        data,
        intrinsicCmd,
        () => Intrinsic(DebugIntrinsics.SourceInfo, finalParams: _*)(data)
      )
    }
  }

  private def addSourceLocationIntrinsic(
    data: Data,
    name: String,
    kind: String
  )(implicit sourceInfo: chisel3.experimental.SourceInfo): Unit = {
    val typeName = getTypeName(data)

    val locSeq = createLocationParams(sourceInfo)

    if (kind == DebugIntrinsics.Kind.Port) {
      val baseParams: Seq[(String, chisel3.Param)] = Seq(
        DebugIntrinsics.ParamKeys.Name -> chisel3.StringParam(name),
        DebugIntrinsics.ParamKeys.Direction -> chisel3.StringParam(getDirection(data)),
        DebugIntrinsics.ParamKeys.Type -> chisel3.StringParam(typeName),
        DebugIntrinsics.ParamKeys.ScalaClass -> chisel3.StringParam(getScalaClass(data)),
        DebugIntrinsics.ParamKeys.ConstructorParams -> chisel3.StringParam(buildConstructorParams(data))
      )
      val intrinsicCmd = DefIntrinsic(
        sourceInfo,
        DebugIntrinsics.PortInfo,
        Seq(data.ref),
        baseParams ++ locSeq
      )
      val finalParams = baseParams ++ locSeq
      addIntrinsicToModuleOrFallback(
        data,
        intrinsicCmd,
        () => Intrinsic(DebugIntrinsics.PortInfo, finalParams: _*)(data)
      )
    } else {
      val baseParams: Seq[(String, chisel3.Param)] = Seq(
        DebugIntrinsics.ParamKeys.FieldName -> chisel3.StringParam(name),
        DebugIntrinsics.ParamKeys.Type -> chisel3.StringParam(typeName)
      )
      val intrinsicCmd = DefIntrinsic(
        sourceInfo,
        DebugIntrinsics.SourceInfo,
        Seq(data.ref),
        baseParams ++ locSeq
      )
      val finalParams = baseParams ++ locSeq
      addIntrinsicToModuleOrFallback(
        data,
        intrinsicCmd,
        () => Intrinsic(DebugIntrinsics.SourceInfo, finalParams: _*)(data)
      )
    }
  }

  private def annotateAlias(
    data:       Data,
    aliasName:  String,
    targetName: String
  )(implicit sourceInfo: chisel3.experimental.SourceInfo): Unit = {
    val locSeq = createLocationParams(sourceInfo)
    val baseParams: Seq[(String, chisel3.Param)] = Seq(
      DebugIntrinsics.ParamKeys.Name -> chisel3.StringParam(aliasName),
      DebugIntrinsics.ParamKeys.Target -> chisel3.StringParam(targetName)
    )
    val intrinsicCmd = DefIntrinsic(
      sourceInfo,
      DebugIntrinsics.AliasInfo,
      Seq(data.ref),
      baseParams ++ locSeq
    )
    val finalParams = baseParams ++ locSeq
    addIntrinsicToModuleOrFallback(
      data,
      intrinsicCmd,
      () => Intrinsic(DebugIntrinsics.AliasInfo, finalParams: _*)(data)
    )
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
      val locSeq = createLocationParams(sourceInfo)
      val baseParams: Seq[(String, chisel3.Param)] = Seq(
        DebugIntrinsics.ParamKeys.Name -> chisel3.StringParam(name),
        DebugIntrinsics.ParamKeys.Depth -> chisel3.StringParam(mem.length.toString),
        DebugIntrinsics.ParamKeys.Type -> chisel3.StringParam(typename)
      )
      val intrinsicCmd = DefIntrinsic(
        sourceInfo,
        DebugIntrinsics.Memory,
        Seq.empty,
        baseParams ++ locSeq
      )
      Builder.pushCommand(intrinsicCmd)
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

  object ParamKeys {
    val Name:              String = "name"
    val Direction:         String = "direction"
    val Type:              String = "type"
    val ScalaClass:        String = "scalaClass"
    val ConstructorParams: String = "constructorParams"
    val Target:            String = "target"
    val Depth:             String = "depth"
    val FieldName:         String = "field_name"
  }

  object Kind {
    val Port:   String = "port"
    val Source: String = "source"
  }
}
