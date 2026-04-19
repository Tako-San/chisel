// SPDX-License-Identifier: Apache-2.0
package chisel3.debug

import logger.LazyLogging

import chisel3._
import chisel3.{Param, StringParam}
import chisel3.internal._
import chisel3.internal.binding._
import chisel3.internal.firrtl.ir._
import chisel3.debug.CtorParamExtractor.{dataToTypeName, getCtorParams}
import chisel3.experimental.{BaseModule, SourceInfo, UnlocatableSourceInfo}

import scala.collection.mutable
import upickle.{default => json}

private[chisel3] object DebugIntrinsics {

  def generate(circuit: Circuit): Unit = {
    val emitter = new ComponentDebugEmitter()(UnlocatableSourceInfo)
    circuit.components.foreach(emitter.generate)
  }

  private class ComponentDebugEmitter(implicit val si: SourceInfo) extends LazyLogging {
    private val emittedEnums = mutable.HashSet.empty[String]
    private val emittedIds = mutable.HashSet.empty[HasId]

    def generate(component: Component): Unit = component match {
      case ctx @ DefModule(id, _, _, _, ports, block) =>
        processModule(id, ports ++ ctx.secretPorts, block)
      case ctx @ DefClass(id, _, ports, block) =>
        processModule(id.asInstanceOf[BaseModule], ports ++ ctx.secretPorts, block)
      case _: DefBlackBox        => ()
      case _: DefIntrinsicModule => ()
      case ctx =>
        throw new Exception(s"generate: unknown Component type: $ctx")
    }

    private def processModule(id: BaseModule, allPorts: Seq[Port], block: Block): Unit = {
      createIntrinsic(id).foreach(block.addSecretCommand)
      allPorts.foreach { p => createIntrinsic(p.id, None).foreach(block.addSecretCommand) }
      // Only regular commands are traversed; secret commands are intrinsics already added by this pass.
      block.getCommands().foreach { c => generate(c).foreach(block.addSecretCommand) }
    }

    private def generate(cmd: Command): Seq[Command] = cmd match {
      case e: DefPrim[_] =>
        createIntrinsic(e.id, None)
      case DefWire(_, id) =>
        createIntrinsic(id, None)
      case DefReg(_, id, _) =>
        createIntrinsic(id, None)
      case DefRegInit(_, id, _, _, _) =>
        createIntrinsic(id, None)
      case DefMemory(_, id, t, size) =>
        createIntrinsicMem(id, t, size)
      case DefSeqMemory(_, id, t, size, _) =>
        createIntrinsicMem(id, t, size)
      case FirrtlMemory(_, id, t, size, _, _, _, _, _) =>
        createIntrinsicMem(id, t, size)
      case DefMemPort(_, _, _, _, _, _) =>
        Seq.empty
      case When(_, _, ifRegion, elseRegion) =>
        ifRegion.flatMap(generate(_)) ++
          elseRegion.flatMap(generate(_))
      case _ => Seq.empty
    }

    private def extractParams(target: Any): Seq[ClassParam] = target match {
      case _: chisel3.Bits | _: chisel3.Clock | _: chisel3.Reset | _: chisel3.experimental.Analog => Nil
      case _ => getCtorParams(target)
    }

    private def createIntrinsicMem(
      target:    HasId,
      innerType: Data,
      size:      BigInt
    ): Seq[Command] = {
      val binding = target.getClass.getSimpleName
      val typeName = s"$binding[${dataToTypeName(innerType)}[$size]]"

      val name = target match {
        case m: MemBase[_] => m.instanceName
        case other => other.getClass.getSimpleName
      }
      if (name.isEmpty) return Seq.empty

      Seq(
        DefIntrinsic(
          si,
          "circt_debug_var",
          Nil,
          Seq(
            "typeName" -> StringParam(typeName),
            "name" -> StringParam(name),
            "params" -> StringParam(serializeClassParams(extractParams(target)))
          )
        )
      )
    }

    private def createIntrinsic(target: Data, parent: Option[String]): Seq[Command] = {
      if (!emittedIds.add(target)) return Seq.empty

      val typeName = dataToTypeName(target)

      val childCmds: Seq[Command] = target match {
        case record: Record =>
          record.elements.values.flatMap(createIntrinsic(_, Some(signalName(target)))).toSeq
        case vecLike: VecLike[_] =>
          vecLike.toSeq.flatMap(e => createIntrinsic(e.asInstanceOf[Data], Some(signalName(target))))
        case _ => Seq.empty
      }

      val enumDefCmd: Seq[Command] = target match {
        case e: EnumType => createEnumDefIntrinsic(e).toSeq
        case _ => Seq.empty
      }

      enumDefCmd ++ childCmds ++ createDebugIntrinsic(target, typeName, parent, extractParams(target)).toSeq
    }

    private case class EnumVariant(name: String, value: String)
    private implicit val enumVariantRW: json.ReadWriter[EnumVariant] = json.macroRW

    private def createEnumDefIntrinsic(e: EnumType): Option[Command] = {
      val factory = e.factory
      val fqn = factory.enumTypeName.stripSuffix("$")
      val simpleTypeName = fqn.split("\\.").last

      if (!emittedEnums.add(fqn)) return None

      val variants = factory.allWithNames.map { case (v, name) => EnumVariant(name, v.litValue.toString) }

      Some(
        DefIntrinsic(
          UnlocatableSourceInfo,
          "circt_debug_enumdef",
          Nil,
          Seq(
            "typeName" -> StringParam(simpleTypeName),
            "fqn" -> StringParam(fqn),
            "variants" -> StringParam(json.write(variants))
          )
        )
      )
    }

    private def createIntrinsic(target: BaseModule): Seq[Command] = {
      val params = getCtorParams(target)
      Seq(
        DefIntrinsic(
          UnlocatableSourceInfo,
          "circt_debug_moduleinfo",
          Nil,
          Seq(
            "typeName" -> StringParam(target.desiredName),
            "params" -> StringParam(serializeClassParams(params))
          )
        )
      )
    }

    private def createIntrinsic(target: HasId, parent: Option[String]): Seq[Command] = target match {
      case t: Data           => createIntrinsic(t, parent)
      case t: BaseModule     => createIntrinsic(t)
      case _: NamedComponent => Seq.empty
      case t =>
        logger.warn(s"createIntrinsic: unhandled HasId type: ${t.getClass.getName}")
        Seq.empty
    }

    private def createDebugIntrinsic(
      target:   Data,
      typeName: String,
      parent:   Option[String],
      params:   Seq[ClassParam]
    ): Option[Command] = {
      val name = signalRef(target)
      if (name.isEmpty) return None
      // Skip synthetic `_`-prefixed names; they often refer to intermediate
      // values that are not declared in the final FIRRTL IR, and
      // MaterializeDebugInfo skips them downstream anyway.
      if (parent.isEmpty && name.startsWith("_")) return None
      // firrtl.int.generic operands must be passive. A root-level
      // non-passive aggregate cannot be passed as an SSA operand.
      // In that case, emit the intrinsic with NO SSA operand
      val nonPassiveRoot =
        parent.isEmpty && target.direction.isInstanceOf[ActualDirection.Bidirectional]
      val ssaOperands: Seq[Arg] = if (nonPassiveRoot) Nil else Seq(Node(target))
      val intrinsicName = if (parent.isDefined) "circt_debug_subfield" else "circt_debug_var"
      val enumParam: Seq[(String, Param)] = target match {
        case e: EnumType =>
          val fqn = e.factory.enumTypeName.stripSuffix("$")
          val simpleTypeName = fqn.split("\\.").last
          Seq("enumTypeName" -> StringParam(simpleTypeName), "enumFqn" -> StringParam(fqn))
        case _ => Nil
      }
      val parentParam: Seq[(String, Param)] = parent.map("parent" -> StringParam(_)).toSeq
      Some(
        DefIntrinsic(
          UnlocatableSourceInfo,
          intrinsicName,
          ssaOperands,
          Seq(
            "typeName" -> StringParam(typeName),
            "name" -> StringParam(name)
          ) ++ parentParam ++ enumParam ++ Seq(
            "params" -> StringParam(serializeClassParams(params))
          )
        )
      )
    }
  }

  private def signalName(d: Data): String = {
    val t = d.toTarget
    val s = t.serialize
    val i = s.indexOf('>')
    if (i >= 0) s.substring(i + 1) else t.ref
  }

  private def signalRef(d: Data): String =
    d.getOptionRef.map(_.localName).getOrElse("")

  private def serializeClassParams(params: Seq[ClassParam]): String =
    json.write(params)
}
