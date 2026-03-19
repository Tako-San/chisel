// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import scala.collection.mutable

import chisel3._
import chisel3.experimental.{BaseModule, SourceInfo, SourceLine}
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

  private var maxStructureDepth:                         Int = 32
  private[chisel3] final def MaxStructureDepth:          Int = maxStructureDepth
  private[chisel3] def setMaxStructureDepth(depth: Int): Unit = { maxStructureDepth = depth }

  private def debugPolicyOf(data: Data): DebugMetaPolicy = data match {
    case p: HasDebugMetaPolicy => p.debugMetaPolicy
    case _ => DebugMetaPolicy()
  }

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

  private def cleanSimpleName(cls: Class[_]): String =
    stripAnonNumericSuffix(stripCompanionSuffix(cls.getSimpleName.stripSuffix("$")))

  private def isDebuggable(data: Data): Boolean =
    debugPolicyOf(data).emitTypeTag &&
      data.topBindingOpt.exists {
        case _: OpBinding => Builder.getDebugMeta(data).isDefined
        case _ => bindingStr(data).isDefined
      }

  private def sourceLocParts(
    metaOpt: Option[Builder.DebugMeta],
    siOpt:   Option[SourceInfo]
  ): (String, BigInt) =
    metaOpt
      .filter(_.sourceFile.nonEmpty)
      .map(m => (m.sourceFile, BigInt(m.sourceLine)))
      .orElse(siOpt.collect { case l: SourceLine => (l.filename, BigInt(l.line)) })
      .getOrElse(("", BigInt(-1)))

  private def resolvedWidth(data: Data): Option[Int] = data match {
    case _: Aggregate => None
    case _ =>
      try data.widthOption
      catch { case _: Exception => None }
  }

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
        emitMemMetaTyped(m.asInstanceOf[MemBase[Data] @unchecked])
      case _ =>
    }
  }

  private def emitDataMeta(data: Data)(implicit si: SourceInfo): Unit = {
    val neededEnums = collectRequiredEnums(data)
    val enumNames = emitPendingEnumDefs(neededEnums)

    val meta = Builder.getDebugMeta(data)
    val (sourceFile, sourceLine) = sourceLocParts(meta, Some(si))

    // Single circt_debug_typetag with the SSA value as operand for all types.
    // For Bundle/Vec, the CIRCT lowering pass builds dbg.struct / dbg.array
    // by recursively applying firrtl.subfield / firrtl.subindex — mirroring
    // MaterializeDebugInfo.  firrtl.subfield always returns a passive type
    // (flip is stored in BundleElement.isFlip, not in the result type), so
    // no PassiveType constraint violation occurs even for non-passive bundles.
    val baseParams: Seq[(String, Param)] = Seq(
      "name" -> StringParam(data.instanceName),
      "className" -> StringParam(extractClassName(data.getClass, meta)),
      "width" -> IntParam(resolvedWidth(data).map(BigInt(_)).getOrElse(BigInt(-1))),
      "binding" -> StringParam(bindingStr(data).getOrElse("unknown")),
      "direction" -> StringParam(directionStr(data)),
      "sourceFile" -> StringParam(sourceFile),
      "sourceLine" -> IntParam(sourceLine)
    ) ++
      meta.map(_.params).filter(_.nonEmpty).map("params" -> StringParam(_)).toSeq ++
      getEnumPairOpt(data, enumNames).fold(Seq.empty[(String, Param)]) { case (simpleName, fqn) =>
        Seq("enumType" -> StringParam(simpleName), "enumTypeFqn" -> StringParam(fqn))
      }

    pushCommand(DefIntrinsic(si, TypeTagIntrinsic, Seq(Node(data)), baseParams))
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
    val (sourceFile, sourceLine) = sourceLocParts(meta, Some(si))

    val baseParams = Seq(
      "memName" -> StringParam(mem.instanceName),
      "memoryKind" -> StringParam(memKind),
      "depth" -> IntParam(mem.length),
      "sourceFile" -> StringParam(sourceFile),
      "sourceLine" -> IntParam(sourceLine),
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
      val (sourceFile, sourceLine) = sourceLocParts(debugMetaOpt, Some(si))
      val className = extractClassName(mod.getClass, debugMetaOpt, isModule = true)

      val ctorParamsArg = serializeCtorArgs(Builder.peekPendingCtorArgs(), mod.name)

      val baseParams = Seq(
        "className" -> StringParam(className),
        "name" -> StringParam(mod.name),
        "sourceFile" -> StringParam(sourceFile),
        "sourceLine" -> IntParam(sourceLine)
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
    if (clean.isEmpty) (if (isModule) "AnonymousModule" else "AnonymousBundle") else clean
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
    val seen = Builder.emittedDebugEnums
    enums.toSeq
      .sortBy(_.getClass.getName)
      .map { ce =>
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
              "value" -> ujson.Num(e.litValue.toDouble),
              "valueStr" -> ujson.Str(e.litValue.toString)
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

        ce -> (simpleName, fqnNorm)
      }
      .toMap
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
    data:             Data,
    enumNames:        Map[ChiselEnum, (String, String)],
    includeDirection: Boolean,
    depth:            Int
  ): ujson.Obj = {
    val meta = Builder.getDebugMeta(data)
    val obj = ujson.Obj(
      "className" -> ujson.Str(extractClassName(data.getClass, meta)),
      "width" -> ujson.Num(resolvedWidth(data).map(_.toDouble).getOrElse(-1.0))
    )
    if (includeDirection) obj("direction") = ujson.Str(directionStr(data))

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
            fieldsObj(name) = buildDataJson(fieldData, enumNames, includeDirection = true, depth + 1)
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
                obj("element") = buildDataJson(elem, enumNames, includeDirection = false, depth + 1)
              }
          }
        }

      case _ =>
    }
    obj
  }

  private def buildTypeJson(t: Data, enumNames: Map[ChiselEnum, (String, String)] = Map.empty): ujson.Value =
    buildDataJson(t, enumNames, includeDirection = false, depth = 0)

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
