// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import java.util.Collections
import java.util.WeakHashMap
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Try

import chisel3._
import chisel3.{EnumType, SpecifiedDirection}
import chisel3.experimental.SourceInfo
import chisel3.internal.{binding, Builder}
import chisel3.internal.Builder.pushCommand
import chisel3.internal.binding._
import chisel3.internal.firrtl.ir.{DefIntrinsic, Node}

import logger.LazyLogging

import ujson._

private[chisel3] object DebugMetaEmitter extends LazyLogging {

  private final val TypeTagIntrinsic = "circt_debug_typetag"
  private final val ModuleInfoIntrinsic = "circt_debug_moduleinfo"

  private final val MaxStructureDepth = 100

  private final val JsonUnknown = "unknown"
  private final val JsonModule = "module"
  private final val JsonFields = "fields"
  private final val JsonVecLength = "vecLength"
  private final val JsonElement = "element"
  private final val JsonEnumDef = "enumDef"
  private final val JsonName = "name"
  private final val JsonVariants = "variants"

  private final val ValidCtorParamKeyPattern = "^[a-zA-Z0-9_\\-\\.]+$"
  private final val ChiselPackageName = "chisel3"

  private val enumJsonCache: mutable.Map[ChiselEnum, ujson.Obj] =
    Collections.synchronizedMap(new WeakHashMap[ChiselEnum, ujson.Obj]()).asScala

  private final val JsonMem = "mem"

  private def bindingStr(data: Data): Option[String] =
    data.topBindingOpt.collect {
      case _: PortBinding       => "port"
      case _: RegBinding        => "reg"
      case _: WireBinding       => "wire"
      case _: MemoryPortBinding => "memport"
      case _: SramPortBinding   => "sramport"
      case _: OpBinding         => "node"
    }

  private def directionStr(data: Data): String =
    data.specifiedDirection.toString().toLowerCase()

  private def widthStr(data: Data): String =
    data.widthOption.map(_.toString).getOrElse("inferred")

  private def validateCtorParamJson(obj: ujson.Obj, modName: String): Either[String, Unit] = {
    val invalidKeys = obj.obj.keys.filterNot(key => key.matches(ValidCtorParamKeyPattern)).toList
    Either.cond(
      invalidKeys.isEmpty,
      (),
      s"Invalid ctorParamJson for module $modName: invalid key names ${invalidKeys.mkString(", ")}"
    )
  }

  /** Determines whether a Data object should have debug info emitted.
    * Only port/reg/wire/memport bindings are included.
    * For OpBinding, only named bindings (explicitly defined val) are included.
    */
  private def isDebuggable(data: Data): Boolean =
    data.topBindingOpt.exists {
      case _: OpBinding => Builder.getDebugMeta(data).isDefined
      case _ => bindingStr(data).isDefined
    }

  /** Emit debug intrinsics for all debuggable Data objects in the module.
    *
    * @param ids all HasId objects owned by the module
    * @param si  implicit SourceInfo (typically `UnlocatableSourceInfo`)
    */
  def emitModuleMeta(ids: Iterable[HasId])(implicit si: SourceInfo): Unit = {
    emitModuleInfo()

    val bbItems = ids.collect { case bb: chisel3.internal.BaseBlackBox => bb }
    bbItems.foreach { bb =>
      val debugMetaOpt = Builder.getDebugMeta(bb)
      val obj = buildModuleInfoJson(bb.name, bb.getClass, debugMetaOpt)
      addOptionalCtorParams(obj, bb.name, debugMetaOpt)
      pushCommand(
        DefIntrinsic(si, ModuleInfoIntrinsic, Seq.empty, Seq("info" -> StringParam(ujson.write(obj))))
      )
    }

    val dataItems = ids.collect {
      case d: Data if d.isSynthesizable && isDebuggable(d) => d
    }
    dataItems.foreach(emitDataMeta)
    val memItems = ids.collect { case m: MemBase[_] =>
      m
    }
    memItems.foreach(emitMemMeta)
  }

  private def emitDataMeta(data: Data)(implicit si: SourceInfo): Unit = {
    val json = buildJson(data)

    pushCommand(
      DefIntrinsic(
        si,
        TypeTagIntrinsic,
        Seq(Node(data)),
        Seq("info" -> StringParam(json))
      )
    )
  }

  private def emitMemMeta(mem: MemBase[_])(implicit si: SourceInfo): Unit = {
    val json = buildMemJson(mem)
    pushCommand(
      DefIntrinsic(
        si,
        TypeTagIntrinsic,
        Seq(Node(mem.asInstanceOf[HasId])),
        Seq("info" -> StringParam(json))
      )
    )
  }

  private def emitModuleInfo()(implicit si: SourceInfo): Unit =
    Builder.currentModule.foreach { mod =>
      val debugMetaOpt = Builder.getDebugMeta(mod)
      val obj = buildModuleInfoJson(mod.name, mod.getClass, debugMetaOpt)
      addOptionalCtorParams(obj, mod.name, debugMetaOpt)
      pushCommand(
        DefIntrinsic(si, ModuleInfoIntrinsic, Seq.empty, Seq("info" -> StringParam(ujson.write(obj))))
      )
    }

  private def buildModuleInfoJson(
    modName:      String,
    modClass:     Class[_],
    debugMetaOpt: Option[Builder.DebugMeta]
  ): ujson.Obj =
    ujson.Obj(
      "kind" -> ujson.Str(JsonModule),
      "className" -> ujson.Str(extractClassName(modClass, debugMetaOpt)),
      "name" -> ujson.Str(modName)
    )

  private def addOptionalCtorParams(obj: ujson.Obj, modName: String, debugMetaOpt: Option[Builder.DebugMeta]): Unit =
    debugMetaOpt.flatMap(_.ctorParamJson).foreach { jsonStr =>
      Try(ujson.read(jsonStr)) match {
        case scala.util.Success(o: ujson.Obj) =>
          validateCtorParamJson(o, modName).fold(
            errorMsg => logger.warn(s"[DebugMetaEmitter] $errorMsg"),
            _ => obj("ctorParams") = o
          )
        case scala.util.Success(_) =>
          logger.warn(s"[DebugMetaEmitter] ctorParamJson for module $modName is not a JSON object")
        case scala.util.Failure(e) =>
          logger.warn(s"[DebugMetaEmitter] Failed to parse ctorParamJson for module $modName: ${e.getMessage}")
      }
    }

  private def extractClassName(cls: Class[_], debugMetaOpt: Option[Builder.DebugMeta]): String = {
    val name = debugMetaOpt.fold(cls.getSimpleName.stripSuffix("$"))(_.className)
    if (name.isEmpty) "AnonymousBundle" else name
  }

  private def validateEnumFactory(factory: AnyRef, enumType: EnumType): Option[AnyRef] = Option(factory) match {
    case Some(f) => Some(f)
    case None =>
      logger.warn(s"[DebugMetaEmitter] Null factory for enum ${enumType.getClass.getSimpleName}")
      None
  }

  private def collectEnumValues(factory: AnyRef): Option[Seq[EnumType]] =
    Option(factory).collect { case f: ChiselEnum => f.all.collect { case e: EnumType => e } }

  private def buildBaseFieldObject(data: Data): ujson.Obj =
    ujson.Obj(
      "type" -> ujson.Str(data.getClass.getSimpleName.stripSuffix("$")),
      "width" -> ujson.Str(widthStr(data)),
      "direction" -> ujson.Str(directionStr(data))
    )

  private def buildJson(data: Data): String = {
    val meta = Builder.getDebugMeta(data)
    val className = extractClassName(data.getClass, meta)
    val sourceLoc = meta.fold(JsonUnknown)(r =>
      Option(s"${r.sourceFile}:${r.sourceLine}")
        .filter(_.trim.nonEmpty)
        .getOrElse(JsonUnknown)
    )

    val baseFields: Seq[(String, ujson.Value)] = Seq(
      "className" -> ujson.Str(className),
      "width" -> ujson.Str(widthStr(data)),
      "binding" -> ujson.Str(bindingStr(data).getOrElse(JsonUnknown)),
      "direction" -> ujson.Str(directionStr(data)),
      "sourceLoc" -> ujson.Str(sourceLoc)
    )

    val optionalFields: Seq[(String, ujson.Value)] = {
      meta.map(_.params).filter(_.nonEmpty).map("params" -> ujson.Str(_)).toSeq ++
        buildStructureJson(data).getOrElse(Seq.empty) ++
        enumJson(data).map(JsonEnumDef -> _).toSeq
    }

    val finalObj = ujson.Obj.from(baseFields ++ optionalFields)
    ujson.write(finalObj)
  }

  private def enumJson(data: Data): Option[ujson.Obj] = data match {
    case e: EnumType => cachedEnumJson(e)
    case _ => None
  }

  private def cachedEnumJson(e: EnumType): Option[ujson.Obj] =
    enumJsonCache.get(e.factory).orElse(freshEnumJson(e))

  private def freshEnumJson(e: EnumType): Option[ujson.Obj] = {
    val chiselEnumOpt = for {
      factory <- validateEnumFactory(e.factory, e)
      chiselEnum <- Option(factory).collect { case ce: ChiselEnum => ce }
      enumValues <- collectEnumValues(chiselEnum)
    } yield {
      val enumName = chiselEnum.getClass.getSimpleName.stripSuffix("$")
      val variants = enumVariants(enumValues)
      cacheEnumJsonEntry(enumName, variants, chiselEnum)
    }

    chiselEnumOpt.orElse {
      logger.error(
        s"[DebugMetaEmitter] Failed to build enum JSON for ${e.getClass.getSimpleName}: invalid factory or enum type"
      )
      None
    }
  }

  private def enumVariants(enumTypes: Iterable[EnumType]): ujson.Arr =
    ujson.Arr.from(enumTypes.map { variant =>
      ujson.Obj(
        "name" -> ujson.Str(variant.factory.nameOfValue(variant.litValue).getOrElse("unknown")),
        "value" -> ujson.Num(variant.litValue.toDouble)
      )
    })

  private def cacheEnumJsonEntry(
    enumName:   String,
    variants:   ujson.Arr,
    chiselEnum: ChiselEnum
  ): ujson.Obj = {
    val result = ujson.Obj(JsonName -> ujson.Str(enumName), JsonVariants -> variants)
    enumJsonCache.put(chiselEnum, result)
    result
  }

  private def buildStructureJson(data: Data): Option[Seq[(String, ujson.Value)]] =
    buildStructureJson(data, depth = 0)

  private def buildStructureJson(data: Data, depth: Int): Option[Seq[(String, ujson.Value)]] = {
    if (depth >= MaxStructureDepth) {
      logger.warn(
        s"[DebugMetaEmitter] Max structure depth ($MaxStructureDepth) exceeded for ${data.getClass.getSimpleName}. " +
          "Possible cyclic reference. Returning truncated structure."
      )
      return Some(Seq(JsonFields -> ujson.Obj(JsonUnknown -> ujson.Str(depth.toString))))
    }

    data match {
      case sram: HasDebugSramMeta =>
        Some(
          Seq(
            "kind" -> ujson.Str("sram"),
            "depth" -> ujson.Num(sram.debugSramDepth.toDouble),
            "numReadPorts" -> ujson.Num(sram.debugSramNumReadPorts.toDouble),
            "numWritePorts" -> ujson.Num(sram.debugSramNumWritePorts.toDouble),
            "numRWPorts" -> ujson.Num(sram.debugSramNumRWPorts.toDouble),
            "masked" -> ujson.Bool(sram.debugSramMasked),
            "maskGranularity" -> ujson.Num(sram.debugSramMaskGranularity.toDouble)
          )
        )

      case r: Record @unchecked if r.isInstanceOf[HasDebugKind] =>
        val fields = ujson.Obj()
        r.elements.foreach { case (name, fieldData) =>
          val fObj = buildBaseFieldObject(fieldData)
          for {
            structureEntries <- buildStructureJson(fieldData, depth + 1)
            (key, value) <- structureEntries
          } fObj.obj.put(key, value)
          fields(name) = fObj
        }
        val base = if (fields.obj.nonEmpty) Seq(JsonFields -> fields) else Seq.empty
        Some(base ++ Seq("kind" -> ujson.Str(r.asInstanceOf[HasDebugKind].debugKind)))

      case r: Record =>
        val fields = ujson.Obj()
        r.elements.foreach { case (name, fieldData) =>
          val fObj = buildBaseFieldObject(fieldData)
          for {
            structureEntries <- buildStructureJson(fieldData, depth + 1)
            (key, value) <- structureEntries
          } fObj.obj.put(key, value)

          fields(name) = fObj
        }
        if (fields.obj.nonEmpty) Some(Seq(JsonFields -> fields)) else None

      case v: Vec[_] if v.length > 0 =>
        val elem = v.sample_element
        val elemObj = buildBaseFieldObject(elem)
        for {
          structureEntries <- buildStructureJson(elem, depth + 1)
          (key, value) <- structureEntries
        } elemObj.obj.put(key, value)
        Some(
          Seq(
            JsonVecLength -> ujson.Num(v.length),
            JsonElement -> elemObj
          )
        )

      case v: Vec[_] =>
        Some(Seq(JsonVecLength -> ujson.Num(0)))

      case _ => None
    }
  }

  private def buildMemJson(mem: MemBase[_]): String = {
    val memKind = mem match {
      case _: SyncReadMem[_] => "SyncReadMem"
      case _ => "Mem"
    }

    val readUnderWrite = mem match {
      case srm: SyncReadMem[_] => Some(srm.readUnderWrite.toString)
      case _ => None
    }

    val dataTypeJson = buildTypeJson(mem.t.asInstanceOf[Data])

    val sourceLocOpt = {
      try {
        Builder
          .getDebugMeta(mem.asInstanceOf[HasId])
          .flatMap(meta =>
            Option(s"${meta.sourceFile}:${meta.sourceLine}")
              .filter(_.trim.nonEmpty)
          )
      } catch {
        case _: Exception => None
      }
    }

    val baseFields: Seq[(String, ujson.Value)] = Seq(
      "kind" -> ujson.Str(JsonMem),
      "memoryKind" -> ujson.Str(memKind),
      "dataType" -> dataTypeJson,
      "depth" -> ujson.Num(mem.length.toDouble),
      "sourceLoc" -> ujson.Str(sourceLocOpt.getOrElse(JsonUnknown))
    )

    val optionalFields: Seq[(String, ujson.Value)] = readUnderWrite match {
      case Some(ruw) => Seq("readUnderWrite" -> ujson.Str(ruw))
      case None      => Seq.empty
    }

    val finalObj = ujson.Obj.from(baseFields ++ optionalFields)
    ujson.write(finalObj)
  }

  private def buildTypeJson(t: Data): ujson.Value = t match {
    case r: Record =>
      val fields = ujson.Obj()
      r.elements.foreach { case (name, fieldData) =>
        fields(name) = ujson.Obj(
          "type" -> ujson.Str(fieldData.getClass.getSimpleName.stripSuffix("$")),
          "width" -> ujson.Str(widthStr(fieldData)),
          "direction" -> ujson.Str(directionStr(fieldData))
        )
      }
      ujson.Obj(
        "kind" -> ujson.Str("Record"),
        "fields" -> fields
      )

    case v: Vec[_] =>
      val elem = v.sample_element
      ujson.Obj(
        "kind" -> ujson.Str("Vec"),
        "length" -> ujson.Num(v.length),
        "element" -> ujson.Obj(
          "type" -> ujson.Str(elem.getClass.getSimpleName.stripSuffix("$")),
          "width" -> ujson.Str(widthStr(elem))
        )
      )

    case _ =>
      ujson.Obj(
        "kind" -> ujson.Str(t.getClass.getSimpleName.stripSuffix("$")),
        "width" -> ujson.Str(widthStr(t))
      )
  }
}
