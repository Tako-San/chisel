// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import scala.collection.mutable.HashMap
import scala.collection.immutable.SortedSet

import chisel3._
import chisel3.{Intrinsic, IntrinsicExpr}
import chisel3.experimental.{BaseModule, SourceInfo, SourceLine}
import chisel3.internal.Builder.pushCommand
import chisel3.internal.binding._
import chisel3.internal.firrtl.ir.{DefIntrinsic, Node}

import logger.LazyLogging

import ujson._

private[chisel3] object DebugMetaEmitter extends LazyLogging {

  private final val TypeTagIntrinsic = "circt_debug_typetag"
  private final val TypeNodeIntrinsic = "circt_debug_typenode"
  private final val ModuleInfoIntrinsic = "circt_debug_moduleinfo"
  private final val EnumDefIntrinsic = "circt_debug_enumdef"
  private final val MemInfoIntrinsic = "circt_debug_meminfo"

  private[chisel3] final def MaxStructureDepth:          Int = Builder.debugMaxStructureDepth
  private[chisel3] def setMaxStructureDepth(depth: Int): Unit = Builder.setDebugMaxStructureDepth(depth)

  private implicit val chiselEnumOrdering: Ordering[ChiselEnum] =
    Ordering.by(_.getClass.getName)

  private def debugPolicyOf(data: Data): DebugMetaPolicy = data match {
    case p: HasDebugMetaPolicy => p.debugMetaPolicy
    case _ => DebugMetaPolicy()
  }

  private def localName(data: Data): String = {
    val full = data.instanceName
    val dot = full.lastIndexOf('.')
    if (dot >= 0) full.substring(dot + 1) else full
  }

  private def stripCompanionSuffix(name: String): String = name.stripSuffix("$")

  private val AnonPattern = """^(.*?)(\$\d+)+$""".r

  private def stripAnonNumericSuffix(name: String): String = {
    name match {
      case AnonPattern(base, _)   => base
      case s if s.startsWith("$") => ""
      case s                      => s
    }
  }

  private val classNameCache = HashMap[Class[_], String]()

  private def cleanSimpleName(cls: Class[_]): String = {
    classNameCache.getOrElseUpdate(
      cls, {
        stripAnonNumericSuffix(stripCompanionSuffix(cls.getSimpleName.stripSuffix("$")))
      }
    )
  }

  private def isDebuggable(data: Data): Boolean =
    debugPolicyOf(data).emitTypeTag &&
      data.topBindingOpt.exists {
        case _: PortBinding | _: RegBinding | _: WireBinding | _: MemoryPortBinding | _: SramPortBinding => true
        case _                                                                                           => false
      }

  private[chisel3] def emitModuleMetaInScope(
    mod:       RawModule,
    ids:       Iterable[HasId],
    portSiMap: Map[Long, SourceInfo]
  )(implicit si: SourceInfo): Unit = {
    val saved = Builder.currentModule
    Builder.currentModule = Some(mod)
    try { emitModuleMeta(ids, portSiMap) }
    finally { Builder.currentModule = saved }
  }

  private def emitModuleMeta(ids: Iterable[HasId], portSiMap: Map[Long, SourceInfo])(implicit si: SourceInfo): Unit = {
    emitModuleInfo()
    ids.foreach {
      case d: Data if d.isSynthesizable && isDebuggable(d) =>
        val dataSi = portSiMap.getOrElse(d._id, si)
        emitDataMeta(d)(dataSi)
      case m: MemBase[_] =>
        val dataSi = portSiMap.getOrElse(m._id, si)
        emitMemMetaTyped(m.asInstanceOf[MemBase[Data] @unchecked])(dataSi)
      case _ =>
    }
  }

  private def emitDataMeta(data: Data)(implicit si: SourceInfo): Unit =
    data match {
      case r: Record => emitRecordMeta(r, parentFqn = None)
      case v: Vec[_] => emitVecMeta(v, parentFqn = None)
      case _ => emitLeafMeta(data, parentFqn = None)
    }

  private def emitRecordMeta(
    r:         Record,
    parentFqn: Option[String]
  )(implicit si: SourceInfo): Unit = {
    val meta = Builder.getDebugMeta(r)
    val className = extractClassName(r.getClass, meta)

    val params = Seq(
      "name" -> StringParam(r.instanceName),
      "className" -> StringParam(className)
    ) ++ parentFqn.map("parentFqn" -> StringParam(_))

    Intrinsic(
      TypeNodeIntrinsic,
      params: _*
    )()

    val myFqn = buildFqn(r.instanceName, parentFqn)
    r.elements.foreach { case (_, fdata) =>
      fdata match {
        case nested: Record => emitRecordMeta(nested, Some(myFqn))
        case nested: Vec[_] => emitVecMeta(nested, Some(myFqn))
        case leaf => emitLeafMeta(leaf, Some(myFqn))
      }
    }
  }

  private def buildFqn(name: String, parentFqn: Option[String]): String =
    parentFqn.fold(
      s"${Builder.currentModule.map(_.name).getOrElse("?")}.${name}"
    )(p => s"$p.$name")

  private def emitVecMeta(
    v:         Vec[_],
    parentFqn: Option[String]
  )(implicit si: SourceInfo): Unit = {
    val myFqn = buildFqn(v.instanceName, parentFqn)
    Intrinsic(
      TypeTagIntrinsic,
      "name" -> StringParam(v.instanceName),
      "className" -> StringParam("Vec"),
      "vecLength" -> IntParam(BigInt(v.length)),
      "parentFqn" -> StringParam(myFqn)
    )(v)
  }

  private def emitLeafMeta(
    data:      Data,
    parentFqn: Option[String]
  )(implicit si: SourceInfo): Unit = {
    if (data.topBindingOpt.isEmpty) return

    val neededEnums = collectRequiredEnums(data)
    val enumNames = emitPendingEnumDefs(neededEnums)
    val meta = Builder.getDebugMeta(data)

    val enumParams = getEnumPairOpt(data, enumNames)
      .fold(Seq.empty[(String, Param)]) { case (s, fqn) =>
        Seq("enumType" -> StringParam(s), "enumTypeFqn" -> StringParam(fqn))
      }

    val fqnParams = parentFqn
      .map(fqn => "parentFqn" -> StringParam(fqn))
      .toSeq

    val params = Seq(
      "name" -> StringParam(data.instanceName),
      "className" -> StringParam(extractClassName(data.getClass, meta))
    ) ++
      meta.map(_.params).filter(_.nonEmpty).map("params" -> StringParam(_)).toSeq ++
      enumParams ++ fqnParams

    Intrinsic(TypeTagIntrinsic, params: _*)(data)
  }

  private def emitMemMetaTyped[T <: Data](mem: MemBase[T])(implicit si: SourceInfo): Unit = {
    val neededEnums = collectRequiredEnums(mem.t)
    val enumNames = emitPendingEnumDefs(neededEnums)
    val memKind = mem match {
      case _: SyncReadMem[_] => "SyncReadMem"
      case _ => "Mem"
    }
    val dataTypeJson = buildTypeJson(mem.t, enumNames)
    val meta = Builder.getDebugMeta(mem)

    val baseParams = Seq(
      "memName" -> StringParam(mem.instanceName),
      "memoryKind" -> StringParam(memKind),
      "depth" -> IntParam(mem.length),
      "dataType" -> StringParam(ujson.write(dataTypeJson))
    )

    val optionalParams = mem match {
      case srm: SyncReadMem[_] =>
        Seq("readUnderWrite" -> StringParam(srm.readUnderWrite.toString))
      case _ =>
        Seq.empty
    }

    pushCommand(
      DefIntrinsic(
        si,
        MemInfoIntrinsic,
        Seq.empty,
        baseParams ++ optionalParams
      )
    )
  }

  private def emitModuleInfo()(implicit si: SourceInfo): Unit = {
    Builder.currentModule.collect {
      case mod if !mod.isInstanceOf[BaseBlackBox] => mod
    }.foreach { mod =>
      val debugMetaOpt = Builder.getDebugMeta(mod)
      val className = extractClassName(mod.getClass, debugMetaOpt, isModule = true)

      val ctorParamsArg = serializeCtorArgs(Builder.peekPendingCtorArgs(), mod.name)

      val baseParams = Seq(
        "className" -> StringParam(className),
        "name" -> StringParam(mod.name)
      )

      pushCommand(
        DefIntrinsic(si, ModuleInfoIntrinsic, Seq.empty, baseParams ++ ctorParamsArg)
      )
    }
  }

  private def extractClassName(
    cls:          Class[_],
    debugMetaOpt: Option[Builder.DebugMeta],
    isModule:     Boolean = false
  ): String = {
    val clean = debugMetaOpt match {
      case Some(m) => stripAnonNumericSuffix(stripCompanionSuffix(m.className))
      case None    => cleanSimpleName(cls)
    }
    if (clean.isEmpty) {
      val id = Integer.toHexString(System.identityHashCode(cls))
      val prefix = if (isModule) "AnonymousModule" else "AnonymousBundle"
      s"${prefix}_$id"
    } else { clean }
  }

  private def collectRequiredEnums(data: Data): SortedSet[ChiselEnum] = {
    val builder = SortedSet.newBuilder[ChiselEnum]

    def collect(d: Data): Unit = d match {
      case e: EnumType =>
        Option(e.factory).collect { case ce: ChiselEnum => ce }.foreach(builder += _)
      case r: Record =>
        r.elements.values.foreach(collect)
      case v: Vec[_] if v.length > 0 =>
        Option(v.sample_element).foreach(collect)
      case _ =>
    }

    collect(data)
    builder.result()
  }

  private def emitPendingEnumDefs(
    enums: SortedSet[ChiselEnum]
  )(implicit si: SourceInfo): Map[ChiselEnum, (String, String)] = {
    val seen = Builder.emittedDebugEnums
    val builder = Map.newBuilder[ChiselEnum, (String, String)]

    enums.foreach { ce =>
      val enumFqn = ce.getClass.getName
      val fqnNorm = stripCompanionSuffix(enumFqn)
      val simpleName = cleanSimpleName(ce.getClass) match {
        case "" => fqnNorm
        case s  => s
      }

      if (!seen.contains(enumFqn)) {
        val variants = ujson.Arr.from(ce.all.collect { case e: EnumType =>
          ujson.Obj(
            "name" -> ujson.Str(ce.nameOfValue(e.litValue).getOrElse("unknown")),
            "value" -> ujson.Num(e.litValue.toDouble)
          )
        })
        pushCommand(
          DefIntrinsic(
            si,
            EnumDefIntrinsic,
            Seq.empty,
            Seq(
              "name" -> StringParam(simpleName),
              "fqn" -> StringParam(fqnNorm),
              "variants" -> StringParam(ujson.write(variants))
            )
          )
        )
        seen.add(enumFqn)
      }

      builder += (ce -> (simpleName, fqnNorm))
    }

    builder.result()
  }

  private def getEnumPairOpt(
    data:      Data,
    enumNames: Map[ChiselEnum, (String, String)]
  ): Option[(String, String)] = data match {
    case e: EnumType =>
      Option(e.factory).collect { case ce: ChiselEnum => ce }.flatMap(enumNames.get)
    case _ => None
  }

  private def buildDataJson(
    data:      Data,
    enumNames: Map[ChiselEnum, (String, String)],
    depth:     Int
  ): ujson.Obj = {
    val meta = Builder.getDebugMeta(data)
    val obj = ujson.Obj(
      "className" -> ujson.Str(extractClassName(data.getClass, meta))
    )

    getEnumPairOpt(data, enumNames).foreach { case (simpleName, fqn) =>
      obj("enumType") = ujson.Str(simpleName)
      obj("enumTypeFqn") = ujson.Str(fqn)
    }

    data match {
      case r: Record =>
        val fields = if (depth >= MaxStructureDepth) {
          logger.warn(
            s"[DebugMetaEmitter] Max nesting depth ($MaxStructureDepth) exceeded for " +
              s"${data.getClass.getSimpleName}. Structural info truncated."
          )
          ujson.Obj("__truncated" -> ujson.Bool(true), "truncatedAtDepth" -> ujson.Num(depth))
        } else {
          val fieldsObj = ujson.Obj()
          r.elements.foreach { case (name, fieldData) =>
            fieldsObj(name) = buildDataJson(fieldData, enumNames, depth + 1)
          }
          fieldsObj
        }
        if (fields.value.nonEmpty) obj("fields") = fields

      case v: Vec[_] =>
        obj("vecLength") = ujson.Num(v.length.toDouble)
        if (v.length > 0) {
          Option(v.sample_element) match {
            case None =>
              logger.warn(
                s"[DebugMetaEmitter] Vec(length=${v.length}): sample_element is null, " +
                  "omitting element type descriptor."
              )
            case Some(elem) =>
              if (depth >= MaxStructureDepth) {
                logger.warn(
                  s"[DebugMetaEmitter] Max nesting depth ($MaxStructureDepth) exceeded for " +
                    s"${data.getClass.getSimpleName}. Structural info truncated."
                )
                obj("__truncated") = ujson.Bool(true)
                obj("truncatedAtDepth") = ujson.Num(depth)
              } else {
                obj("element") = buildDataJson(elem, enumNames, depth + 1)
              }
          }
        }

      case _ =>
    }
    obj
  }

  private def buildTypeJson(t: Data, enumNames: Map[ChiselEnum, (String, String)] = Map.empty): ujson.Value =
    buildDataJson(t, enumNames, depth = 0)

  private def serializeCtorArgs(args: Seq[Any], modName: String): Seq[(String, Param)] =
    Option
      .when(args.nonEmpty) {
        val fields = args.zipWithIndex.map { case (v, i) => s"arg$i" -> anyToJson(v, modName, i) }
        "ctorParams" -> StringParam(ujson.write(ujson.Obj.from(fields)))
      }
      .toSeq

  private def anyToJson(v: Any, modName: String, idx: Int): ujson.Value = v match {
    case null => ujson.Null
    case b: Boolean => ujson.Bool(b)
    case i: Int     => ujson.Num(i.toDouble)
    case l: Long    => if (l.abs <= (1L << 53)) ujson.Num(l.toDouble) else ujson.Str(l.toString)
    case f: Float   => ujson.Num(f.toDouble)
    case d: Double  => ujson.Num(d)
    case s: String  => ujson.Str(DebugMetaUtils.truncateCtorArgString(s))
    case c: Char    => ujson.Str(c.toString)
    case other =>
      logger.warn(s"[DebugMetaEmitter] Module '$modName' arg$idx: unsupported type ${other.getClass}")
      ujson.Null
  }
}
