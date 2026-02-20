// SPDX-License-Identifier: Apache-2.0
package chisel3.stage.phases

import chisel3.stage.{ChiselCircuitAnnotation, EmitDebugInfoAnnotation}
import chisel3.experimental.StringParam
import chisel3.EnumType
import chisel3.internal.firrtl.ir._
import firrtl.{annoSeqToSeq, AnnotationSeq}
import firrtl.options.{Dependency, Phase}
import scala.annotation.nowarn

/** Emits `circt_dbg_variable` intrinsics for all signals in the design.
  *
  * This phase traverses the Chisel internal IR (post-elaboration) and appends
  * debug intrinsics to each module's top-level block via `addSecretCommand`.
  * Secret commands are serialized alongside regular commands by the Serializer,
  * but do not interfere with the block's closure state.
  *
  * Activation: only runs when [[EmitDebugInfoAnnotation]] is present.
  *
  * Emitted intrinsic parameters:
  *   - `name`: signal's local name within its module (e.g., "counter")
  *   - `type`: FIRRTL type string (e.g., "UInt<8>", "{ a : UInt<4>, b : SInt<4> }")
  *   - `chiselType`: Chisel class name, only when it differs from FIRRTL type
  *     (e.g., "AXI4Bundle", "Vec<UInt>"). Omitted for ground types.
  *
  * SourceInfo from the original definition is preserved on each intrinsic,
  * enabling source-level mapping in HGDB and Tywaves.
  */
class EmitDebugInfo extends Phase {

  override def prerequisites = Seq(Dependency[Elaborate])
  override def optionalPrerequisiteOf = Seq(Dependency[Convert])
  override def invalidates(a: Phase) = false

  override def transform(annotations: AnnotationSeq): AnnotationSeq = {
    if (!annotations.contains(EmitDebugInfoAnnotation)) return annotations

    // Find and instrument Chisel circuit with debug intrinsics
    val circuitAnnotation = annotations.find(_.getClass.getName == "chisel3.stage.ChiselCircuitAnnotation")
    if (circuitAnnotation.isDefined) {
      val elaborated = circuitAnnotation.get.asInstanceOf[chisel3.stage.ChiselCircuitAnnotation].elaboratedCircuit
      instrumentCircuit(elaborated._circuit)
    }

    annotations
  }

  // --------------- Circuit traversal ---------------

  private def instrumentCircuit(circuit: Circuit): Unit =
    circuit.components.foreach {
      case DefModule(_, moduleName, _, _, ports, block) =>
        instrumentPorts(block, moduleName, ports)
        collectAndInstrument(block, block, moduleName)
      case _ => // BlackBox, IntrinsicModule — skip
    }

  private def instrumentPorts(
    target:     Block,
    moduleName: String,
    ports:      Seq[Port]
  ): Unit =
    ports.foreach { port =>
      val data = port.id
      emitVariable(target, data.instanceName, data.typeName, chiselTypeName(data), port.sourceInfo)
    }

  /** Recurse through all nested blocks, but always emit into the
    * module-level `target` block. This ensures debug intrinsics are
    * unconditional — not gated by `when` conditions.
    */
  private def collectAndInstrument(
    current:    Block,
    target:     Block,
    moduleName: String
  ): Unit =
    current.getCommands().toList.foreach {
      case DefWire(si, id: chisel3.Data) =>
        emitVariable(target, id.instanceName, id.typeName, chiselTypeName(id), si)
      case DefReg(si, id: chisel3.Data, _) =>
        emitVariable(target, id.instanceName, id.typeName, chiselTypeName(id), si)
      case DefRegInit(si, id: chisel3.Data, _, _, _) =>
        emitVariable(target, id.instanceName, id.typeName, chiselTypeName(id), si)
      case DefMemory(si, memId, memType, _) =>
        emitVariable(target, nameOf(memId), memType.typeName, chiselTypeName(memType), si)
      case DefSeqMemory(si, memId, memType, _, _) =>
        emitVariable(target, nameOf(memId), memType.typeName, chiselTypeName(memType), si)
      case w: When =>
        collectAndInstrument(w.ifRegion, target, moduleName)
        if (w.hasElse)
          collectAndInstrument(w.elseRegion, target, moduleName)
      case lb: LayerBlock =>
        collectAndInstrument(lb.region, target, moduleName)
      case c: DefContract =>
        collectAndInstrument(c.region, target, moduleName)
      case _ =>
    }

  // --------------- Intrinsic emission ---------------

  @nowarn("cat=deprecation")
  private def emitVariable(
    target:     Block,
    sigName:    String,
    firrtlType: String,
    chiselType: String,
    sourceInfo: chisel3.experimental.SourceInfo
  ): Unit = {
    val params = Seq(
      "name" -> StringParam(sigName),
      "type" -> StringParam(firrtlType)
    ) ++ (
      if (chiselType.nonEmpty && chiselType != firrtlType)
        Seq("chiselType" -> StringParam(chiselType))
      else Seq.empty
    )
    target.addSecretCommand(
      DefIntrinsic(sourceInfo, "circt_dbg_variable", Seq.empty[Arg], params)
    )
  }

  // --------------- Chisel type extraction ---------------

  /** Extract the Chisel-level class name. Returns "" for ground types
    * where FIRRTL type is sufficient (UInt, SInt, Clock, etc.).
    */
  private def chiselTypeName(data: chisel3.Data): String = data match {
    case e: EnumType       => serializeEnum(e)
    case _: chisel3.UInt   => ""
    case _: chisel3.SInt   => ""
    case _: chisel3.Clock  => ""
    case _: chisel3.Reset  => "" // AsyncReset is a subtype of Reset, covered here
    case b: chisel3.Record => b.className
    case v: chisel3.Vec[_] =>
      s"Vec<${v.sample_element.typeName}>[${v.length}]"
    case _ => ""
  }

  // --------------- Enum serialization ---------------

  /** Serialize a ChiselEnum type into a compact string with all variant mappings.
    *
    * Output format: `State(Idle=0, Busy=1, Done=2)`
    */
  private def serializeEnum(e: EnumType): String = {
    val factory = e.factory
    val variants = factory.all.zip(factory.allNames).map { case (v, name) =>
      s"$name=${v.litValue}"
    }
    s"${factory.getClass.getSimpleName.init}(${variants.mkString(", ")})"
  }

  private def nameOf(id: Any): String = id match {
    case d: chisel3.Data                    => d.instanceName
    case m: chisel3.experimental.BaseModule => m.instanceName
    case h: chisel3.internal.HasId          => h.instanceName
    case other => other.toString
  }
}
