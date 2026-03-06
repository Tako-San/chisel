// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import scala.collection.mutable

import chisel3._
import chisel3.EnumType
import chisel3.experimental.{BaseModule, SourceInfo}
import chisel3.internal.{binding, Builder}
import chisel3.internal.Builder.pushCommand
import chisel3.internal.binding._
import chisel3.internal.firrtl.ir.{DefIntrinsic, Node}

import logger.LazyLogging

import ujson._

private[chisel3] object DebugMetaEmitter extends LazyLogging {

  private final val TypeTagIntrinsic = "circt_debug_typetag"
  private final val ModuleInfoIntrinsic = "circt_debug_moduleinfo"
  private final val EnumDefIntrinsic = "circt_debug_enumdef"
  private final val MemInfoIntrinsic = "circt_debug_meminfo"

  private final val MaxStructureDepth = 32

  private final val FieldUnknown = "unknown"
  private final val FieldModule = "module"
  private final val FieldFields = "fields"
  private final val FieldVecLength = "vecLength"
  private final val FieldElement = "element"
  private final val FieldName = "name"
  private final val FieldVariants = "variants"
  private final val FieldMem = "mem"
  private final val SchemaVersion = "1.0"

  private def emittedEnums: Option[mutable.HashSet[String]] = Builder.emittedDebugEnums

  private def bindingStr(data: Data): Option[String] =
    data.topBindingOpt.collect {
      case _: PortBinding       => "port"
      case _: RegBinding        => "reg"
      case _: WireBinding       => "wire"
      case _: MemoryPortBinding => "memport"
      case _: SramPortBinding   => "sramport"
      case _: OpBinding         => "node"
    }

  private def directionStr(data: Data): String = data.specifiedDirection match {
    case SpecifiedDirection.Input       => "input"
    case SpecifiedDirection.Output      => "output"
    case SpecifiedDirection.Flip        => "flip"
    case SpecifiedDirection.Unspecified => "unspecified"
  }

  private def stripCompanionSuffix(name: String): String = name.stripSuffix("$")

  private def stripAnonNumericSuffix(name: String): String = {
    val AnonPattern = """^(.*?)(\$\d+)+$""".r
    name match {
      case AnonPattern(base, _)   => base
      case s if s.startsWith("$") => ""
      case s                      => s
    }
  }

  private def normalizeFqn(jvmName: String): String = stripCompanionSuffix(jvmName)

  private def widthStr(data: Data): String =
    data.widthOption.map(_.toString).getOrElse("inferred")

  private def addCtorParamsFromArgs(
    obj:     ujson.Obj,
    modName: String,
    args:    Option[Seq[Any]]
  ): Unit = args.foreach { seq =>
    if (seq.nonEmpty) {
      val argsObj = ujson.Obj()
      seq.zipWithIndex.foreach { case (v, i) =>
        val key = s"arg$i"
        val jsonVal: ujson.Value = v match {
          case null => ujson.Null
          case b: Boolean => ujson.Bool(b)
          case i: Int     => ujson.Num(i.toDouble)
          case l: Long    => ujson.Num(l.toDouble)
          case f: Float   => ujson.Num(f.toDouble)
          case d: Double  => ujson.Num(d)
          case s: String =>
            val truncated = if (s.length > 128) s.take(128) + "..." else s
            ujson.Str(truncated)
          case c: Char => ujson.Str(c.toString)
          case other =>
            logger.warn(s"[DebugMetaEmitter] Module '$modName' arg$i: unsupported type ${other.getClass}")
            ujson.Null
        }
        argsObj(key) = jsonVal
      }
      obj("ctorParams") = argsObj
    }
  }

  private def isDebuggable(data: Data): Boolean =
    data.topBindingOpt.exists {
      case _: OpBinding => Builder.getDebugMeta(data).isDefined
      case _ => bindingStr(data).isDefined
    } && !data.isInstanceOf[HasDebugSramMeta]

  private[chisel3] def emitModuleMetaInScope(
    mod: RawModule,
    ids: Iterable[HasId]
  )(implicit si: SourceInfo): Unit = {
    val saved = Builder.currentModule
    Builder.currentModule = Some(mod)
    try { emitModuleMeta(ids) }
    finally { Builder.currentModule = saved }
  }

  private def emitModuleMeta(ids: Iterable[HasId])(implicit si: SourceInfo): Unit = {
    emitModuleInfo()
    ids.foreach {
      case d: Data if d.isSynthesizable && isDebuggable(d) =>
        emitDataMeta(d)
      case m: MemBase[_] =>
        emitMemMeta(m)
      case _ =>
    }
  }

  private def emitDataMeta(data: Data)(implicit si: SourceInfo): Unit = {
    val neededEnums = collectRequiredEnums(data)
    val enumNames = emitPendingEnumDefs(neededEnums)
    val json = buildJson(data, enumNames)
    pushCommand(DefIntrinsic(si, TypeTagIntrinsic, Seq(Node(data)), Seq("info" -> StringParam(json))))
  }

  private def emitMemMeta(mem: MemBase[_])(implicit si: SourceInfo): Unit = {
    emitMemMetaTyped(mem.asInstanceOf[MemBase[Data] @unchecked])
  }

  private def emitMemMetaTyped[T <: Data](mem: MemBase[T])(implicit si: SourceInfo): Unit = {
    val neededEnums = collectRequiredEnums(mem.t)
    val enumNames = emitPendingEnumDefs(neededEnums)
    val json = buildMemJson(mem, enumNames)
    pushCommand(
      DefIntrinsic(
        si,
        MemInfoIntrinsic,
        Seq.empty,
        Seq(
          "info" -> StringParam(json),
          "memName" -> StringParam(mem.instanceName)
        )
      )
    )
  }

  private def emitModuleInfo()(implicit si: SourceInfo): Unit = {
    Builder.currentModule.collect {
      case mod if !mod.isInstanceOf[BaseBlackBox] => mod
    }.foreach { mod =>
      val debugMetaOpt = Builder.getDebugMeta(mod)
      val obj = buildModuleInfoJson(mod.name, mod.getClass, debugMetaOpt, si)

      val ctorArgs = Builder.popPendingCtorArgs()
      addCtorParamsFromArgs(obj, mod.name, ctorArgs)

      val jsonString = ujson.write(obj)
      pushCommand(
        DefIntrinsic(si, ModuleInfoIntrinsic, Seq.empty, Seq("info" -> StringParam(jsonString)))
      )
    }
  }

  private def buildModuleInfoJson(
    modName:      String,
    modClass:     Class[_],
    debugMetaOpt: Option[Builder.DebugMeta],
    si:           SourceInfo
  ): ujson.Obj = {
    val sourceLoc = debugMetaOpt.fold {
      si match {
        case l: chisel3.experimental.SourceLine => s"${l.filename}:${l.line}"
        case _ => FieldUnknown
      }
    } { m => if (m.sourceFile.nonEmpty) s"${m.sourceFile}:${m.sourceLine}" else FieldUnknown }
    ujson.Obj(
      "schemaVersion" -> ujson.Str(SchemaVersion),
      "kind" -> ujson.Str(FieldModule),
      "className" -> ujson.Str(extractClassName(modClass, debugMetaOpt, isModule = true)),
      "name" -> ujson.Str(modName),
      "sourceLoc" -> ujson.Str(sourceLoc)
    )
  }

  private def extractClassName(
    cls:          Class[_],
    debugMetaOpt: Option[Builder.DebugMeta],
    isModule:     Boolean = false
  ): String = {
    val name = debugMetaOpt.fold(cls.getSimpleName.stripSuffix("$"))(_.className)
    val withoutTypeParams = stripAnonNumericSuffix(stripCompanionSuffix(name))
    if (withoutTypeParams.isEmpty) {
      if (isModule) "AnonymousModule" else "AnonymousBundle"
    } else withoutTypeParams
  }

  private def collectRequiredEnums(data: Data): Set[ChiselEnum] = data match {
    case e: EnumType =>
      Option(e.factory).collect { case ce: ChiselEnum => ce }.toSet
    case r: Record =>
      r.elements.values.flatMap(collectRequiredEnums).toSet
    case v: Vec[_] if v.length > 0 =>
      Option(v.sample_element)
        .map(collectRequiredEnums)
        .getOrElse(Set.empty)
    case _ =>
      Set.empty
  }

  private def emitPendingEnumDefs(
    enums: Set[ChiselEnum]
  )(implicit si: SourceInfo): Map[ChiselEnum, (String, String)] = {
    val sortedEnums = enums.toSeq.sortBy(_.getClass.getName)
    val enumNames = sortedEnums.map { ce =>
      val enumFqn = ce.getClass.getName
      val fqnNorm = normalizeFqn(enumFqn)
      val simpleName = {
        val s = stripAnonNumericSuffix(stripCompanionSuffix(ce.getClass.getSimpleName.stripSuffix("$")))
        if (s.isEmpty) fqnNorm else s
      }
      (ce, enumFqn, fqnNorm, simpleName)
    }

    enumNames.foreach { case (ce, enumFqn, fqnNorm, simpleName) =>
      Builder.emittedDebugEnums.foreach { seen =>
        if (!seen.contains(enumFqn)) {
          val variants = ujson.Arr.from(ce.all.collect { case e: EnumType =>
            ujson.Obj(
              "name" -> ujson.Str(ce.nameOfValue(e.litValue).getOrElse("unknown")),
              "value" -> ujson.Num(e.litValue.toDouble),
              "valueStr" -> ujson.Str(e.litValue.toString)
            )
          })
          val obj = ujson.Obj(
            "schemaVersion" -> ujson.Str(SchemaVersion),
            FieldName -> ujson.Str(simpleName),
            "fqn" -> ujson.Str(fqnNorm),
            FieldVariants -> variants
          )
          pushCommand(
            DefIntrinsic(si, EnumDefIntrinsic, Seq.empty, Seq("info" -> StringParam(ujson.write(obj))))
          )
          seen.add(enumFqn)
        }
      }
    }

    enumNames.map { case (ce, _, fqnNorm, simpleName) =>
      ce -> (simpleName, fqnNorm)
    }.toMap
  }

  private def buildBaseFieldObject(
    data:             Data,
    enumPairOpt:      Option[(String, String)] = None,
    includeDirection: Boolean = true
  ): ujson.Obj = {
    val baseClassName = data.getClass.getSimpleName.stripSuffix("$")
    val strippedClassName = stripAnonNumericSuffix(stripCompanionSuffix(baseClassName))
    val className = if (strippedClassName.isEmpty) {
      "AnonymousBundle"
    } else {
      strippedClassName
    }
    val obj = ujson.Obj(
      "className" -> ujson.Str(className),
      "width" -> ujson.Str(widthStr(data))
    )
    if (includeDirection)
      obj("direction") = ujson.Str(directionStr(data))
    enumPairOpt.foreach { case (simpleName, fqn) =>
      obj("enumType") = ujson.Str(simpleName)
      obj("enumTypeFqn") = ujson.Str(fqn)
    }
    obj
  }

  private def buildJson(data: Data, enumNames: Map[ChiselEnum, (String, String)]): String = {
    val enumPairOpt = data match {
      case e: EnumType =>
        Option(e.factory).collect { case ce: ChiselEnum => ce }.flatMap(enumNames.get)
      case _ => None
    }

    val meta = Builder.getDebugMeta(data)
    val className = extractClassName(data.getClass, meta)
    val sourceLoc = meta.fold(FieldUnknown) { r =>
      if (r.sourceFile.nonEmpty) s"${r.sourceFile}:${r.sourceLine}"
      else FieldUnknown
    }

    val baseFields: Seq[(String, ujson.Value)] = Seq(
      "schemaVersion" -> ujson.Str(SchemaVersion),
      "className" -> ujson.Str(className),
      "width" -> ujson.Str(widthStr(data)),
      "binding" -> ujson.Str(bindingStr(data).getOrElse(FieldUnknown)),
      "direction" -> ujson.Str(directionStr(data)),
      "sourceLoc" -> ujson.Str(sourceLoc)
    )

    val optionalFields: Seq[(String, ujson.Value)] = {
      meta.map(_.params).filter(_.nonEmpty).map("params" -> ujson.Str(_)).toSeq ++
        buildStructureJson(data, enumNames).getOrElse(Seq.empty) ++
        enumPairOpt.map { case (simpleName, fqn) =>
          Seq("enumType" -> ujson.Str(simpleName), "enumTypeFqn" -> ujson.Str(fqn))
        }.getOrElse(Seq.empty)
    }

    val finalObj = ujson.Obj.from(baseFields ++ optionalFields)
    ujson.write(finalObj)
  }

  private def buildStructureJson(
    data:      Data,
    enumNames: Map[ChiselEnum, (String, String)],
    depth:     Int = 0
  ): Option[Seq[(String, ujson.Value)]] = {
    if (depth >= MaxStructureDepth) {
      logger.warn(
        s"[DebugMetaEmitter] Max nesting depth ($MaxStructureDepth) exceeded for " +
          s"${data.getClass.getSimpleName}. Structural info truncated."
      )
      Some(
        Seq(
          "__truncated" -> ujson.Bool(true),
          "truncatedAtDepth" -> ujson.Num(depth),
          FieldFields -> ujson.Obj() // empty fields object
        )
      )
    } else {
      data match {
        case r: Record =>
          val fields = ujson.Obj()
          r.elements.foreach { case (name, fieldData) =>
            val enumPairOpt = Option(fieldData).collect { case e: EnumType =>
              Option(e.factory).collect { case ce: ChiselEnum => ce }
                .flatMap(enumNames.get)
            }.flatten
            val fObj = buildBaseFieldObject(fieldData, enumPairOpt)
            buildStructureJson(fieldData, enumNames, depth + 1).foreach { entries =>
              entries.foreach { case (k, v) => fObj.obj.put(k, v) }
            }
            fields(name) = fObj
          }
          val extra = data match {
            case hdk: HasDebugKind => Seq("kind" -> ujson.Str(hdk.debugKind))
            case _ => Seq.empty
          }
          if (fields.obj.nonEmpty) Some(Seq(FieldFields -> fields) ++ extra)
          else if (extra.nonEmpty) Some(extra)
          else None

        case v: Vec[_] if v.length > 0 =>
          Option(v.sample_element) match {
            case None =>
              logger.warn(
                s"[DebugMetaEmitter] Vec(length=${v.length}): sample_element is null, " +
                  "omitting element type descriptor."
              )
              Some(Seq(FieldVecLength -> ujson.Num(v.length)))
            case Some(elem) =>
              val enumPairOpt = elem match {
                case e: EnumType =>
                  Option(e.factory).collect { case ce: ChiselEnum => ce }.flatMap(enumNames.get)
                case _ => None
              }
              val elemObj = buildBaseFieldObject(elem, enumPairOpt, includeDirection = false)
              buildStructureJson(elem, enumNames, depth + 1).foreach { entries =>
                entries.foreach { case (k, v2) => elemObj.obj.put(k, v2) }
              }
              Some(Seq(FieldVecLength -> ujson.Num(v.length), FieldElement -> elemObj))
          }

        case v: Vec[_] =>
          Some(Seq(FieldVecLength -> ujson.Num(v.length)))

        case _ => None
      }
    }
  }

  private def buildMemJson[T <: Data](
    mem:       MemBase[T],
    enumNames: Map[ChiselEnum, (String, String)] = Map.empty
  ): String = {
    val memKind = mem match {
      case _: SyncReadMem[_] => "SyncReadMem"
      case _ => "Mem"
    }
    val readUnderWrite = mem match {
      case srm: SyncReadMem[_] => Some(srm.readUnderWrite.toString)
      case _ => None
    }
    val dataTypeJson = buildTypeJson(mem.t, enumNames)
    val sourceLocOpt = Builder.getDebugMeta(mem).flatMap { meta =>
      if (meta.sourceFile.nonEmpty) Some(s"${meta.sourceFile}:${meta.sourceLine}") else None
    }
    val baseFields: Seq[(String, ujson.Value)] = Seq(
      "schemaVersion" -> ujson.Str(SchemaVersion),
      "kind" -> ujson.Str(FieldMem),
      "name" -> ujson.Str(mem.instanceName),
      "memoryKind" -> ujson.Str(memKind),
      "dataType" -> dataTypeJson,
      "depth" -> ujson.Str(mem.length.toString),
      "sourceLoc" -> ujson.Str(sourceLocOpt.getOrElse(FieldUnknown))
    )
    val optionalFields = readUnderWrite.map("readUnderWrite" -> ujson.Str(_)).toSeq
    ujson.write(ujson.Obj.from(baseFields ++ optionalFields))
  }

  private def buildTypeJson(
    t:         Data,
    enumNames: Map[ChiselEnum, (String, String)] = Map.empty
  ): ujson.Value = {
    val enumPairOpt = t match {
      case e: EnumType =>
        Option(e.factory).collect { case ce: ChiselEnum => ce }.flatMap(enumNames.get)
      case _ => None
    }

    val obj = t match {
      case e: EnumType =>
        buildBaseFieldObject(e, enumPairOpt, includeDirection = false)
      case _ =>
        buildBaseFieldObject(t, None, includeDirection = false)
    }

    buildStructureJson(t, enumNames).foreach {
      _.foreach { case (k, v) => obj.obj.put(k, v) }
    }

    obj
  }
}
