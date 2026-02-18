// SPDX-License-Identifier: Apache-2.0

package chisel3.stage.phases

import chisel3.stage.ChiselCircuitAnnotation
import chisel3.ElaboratedCircuit
import chisel3.{StringParam, _}
import chisel3.debug.{DebugEntry, DebugReflectionUtils, DebugRegistryAnnotation}
import chisel3.internal.firrtl.ir._
import chisel3.internal.firrtl.ir
import firrtl.{annoSeqToSeq, seqToAnnoSeq, AnnotationSeq}
import firrtl.options.{Dependency, Phase}
import logger.LazyLogging
import scala.util.Try

/** Collects debug information from the elaborated circuit and replaces placeholder intrinsics.
  *
  * This phase runs after Elaboration and before Convert, performing two main tasks:
  * 1. Transforms the IR by replacing DefIntrinsic("circt_dbg_placeholder") with
  *    DefIntrinsic("circt_dbg_variable", ...) using data from DebugRegistryAnnotation
  * 2. Collects debug-related information such as full paths, type names, and constructor
  *    parameters from registered debug entries
  */
class CollectDebugInfo extends Phase with LazyLogging {

  override def prerequisites: Seq[Dependency[Phase]] =
    Seq(Dependency[Elaborate])

  override def optionalPrerequisites = Seq.empty

  override def optionalPrerequisiteOf: Seq[Dependency[Phase]] =
    Seq(Dependency[Convert])

  override def invalidates(a: Phase) = false

  private def transformBlock(block: Block, debugEntries: Map[String, DebugEntry]): Block = {
    val newBlock = new Block(block.sourceInfo)
    block.getCommands().foreach { cmd =>
      cmd match {
        case defIntrinsic @ DefIntrinsic(sourceInfo, "circt_dbg_placeholder", _, params) =>
          // Extract the ID from the 'id' parameter
          val debugId = params.collectFirst { case ("id", p: StringParam) =>
            p.value
          }.getOrElse("")

          // Look up the debug entry in the local cache
          debugEntries.get(debugId) match {
            case Some(entry) =>
              // Use pre-populated values from registry (computed in transform prior to transformBlock)
              val fullPath = entry.pathName.getOrElse("")
              val typeName = entry.typeName.getOrElse("")
              val paramsJson = entry.paramsJson.getOrElse("{}")

              // Create parameters for the new intrinsic
              val sigName = entry.debugName.getOrElse(entry.instanceName.getOrElse(""))
              val newParams = Seq(
                "name" -> StringParam(sigName),
                "path" -> StringParam(fullPath),
                "type" -> StringParam(typeName),
                "params" -> StringParam(paramsJson)
              )

              // Replace with the new intrinsic
              val transformed = defIntrinsic.copy(intrinsic = "circt_dbg_variable", params = newParams)
              newBlock.addCommand(transformed)

            case None =>
              // If not found, keep the placeholder unchanged
              newBlock.addCommand(defIntrinsic)
          }
        case when: When =>
          // Create new When as a copy, transforming both blocks
          val newWhen = new When(when.sourceInfo, when.pred)
          val transformedIfRegion = transformBlock(when.ifRegion, debugEntries)
          transformedIfRegion.getCommands().foreach(newWhen.ifRegion.addCommand)
          if (when.hasElse) {
            val transformedElseRegion = transformBlock(when.elseRegion, debugEntries)
            transformedElseRegion.getCommands().foreach(newWhen.elseRegion.addCommand)
          }
          newBlock.addCommand(newWhen)
        case layerBlock: ir.LayerBlock =>
          val newLayerBlock = new ir.LayerBlock(layerBlock.sourceInfo, layerBlock.layer)
          copyRegion(layerBlock.region, newLayerBlock.region, debugEntries)
          newBlock.addCommand(newLayerBlock)
        case defContract: ir.DefContract =>
          val newDefContract = new ir.DefContract(defContract.sourceInfo, defContract.ids, defContract.exprs)
          copyRegion(defContract.region, newDefContract.region, debugEntries)
          newBlock.addCommand(newDefContract)
        case other => newBlock.addCommand(other)
      }
    }
    newBlock
  }

  private def transformComponent(component: Component, debugEntries: Map[String, DebugEntry]): Component = {
    component match {
      case module: ir.DefModule =>
        module.copy(block = transformBlock(module.block, debugEntries))
      case defClass: ir.DefClass =>
        defClass.copy(block = transformBlock(defClass.block, debugEntries))
      case other => other
    }
  }

  private def transformCircuit(circuit: Circuit, debugEntries: Map[String, DebugEntry]): Circuit = {
    circuit.copy(components = circuit.components.map(c => transformComponent(c, debugEntries)))
  }

  private def copyRegion(src: Block, dst: Block, entries: Map[String, DebugEntry]): Unit =
    transformBlock(src, entries).getCommands().foreach(dst.addCommand)

  private def preprocessEntries(entries: Map[String, DebugEntry]): Map[String, DebugEntry] = {
    entries.map { case (id, entry) =>
      val fullPath = Try(entry.data.pathName).toOption

      val typeName = DebugReflectionUtils.dataToTypeName(entry.data)
      val params = entry.data._parent match {
        case Some(mod) => DebugReflectionUtils.getParamsJson(mod)
        case None      => "{}"
      }

      id -> entry.copy(
        pathName = fullPath,
        typeName = Some(typeName),
        paramsJson = Some(params)
      )
    }
  }

  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val annos = annoSeqToSeq(annotations)

    // Extract and consume DebugRegistryAnnotation
    val registryEntries = annos.collectFirst { case DebugRegistryAnnotation(entries) =>
      entries
    }.getOrElse(Map.empty)

    // Pre-process entries to populate missing fields - local val, no mutable state
    val debugEntries = preprocessEntries(registryEntries)

    // Transform circuit and consume DebugRegistryAnnotation in a single flatMap
    seqToAnnoSeq(annos.flatMap {
      case a: ChiselCircuitAnnotation =>
        val elaboratedCircuit = a.elaboratedCircuit
        val newCircuit = transformCircuit(elaboratedCircuit._circuit, debugEntries)
        val newElaboratedCircuit = chisel3.ElaboratedCircuit(newCircuit, elaboratedCircuit.annotations.toSeq)
        Some(ChiselCircuitAnnotation(newElaboratedCircuit))
      // Consume DebugRegistryAnnotation so it doesn't propagate to later phases
      case _: DebugRegistryAnnotation => None
      case a => Some(a)
    })
  }
}
