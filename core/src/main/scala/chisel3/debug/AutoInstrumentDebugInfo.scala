// SPDX-License-Identifier: Apache-2.0

package chisel3.debug

import chisel3.experimental.{SourceInfo, StringParam, UnlocatableSourceInfo}
import chisel3.internal.firrtl.ir._
import scala.annotation.nowarn

/** Automatically instruments all signals after Builder.build() with debug information.
  *
  * Traverses the Chisel internal IR circuit and emits `circt_dbg_variable` intrinsics
  * for all wire, register, and memory definitions.
  */
private[chisel3] object AutoInstrumentDebugInfo {

  /** Add debug intrinsic for a definition */
  @nowarn("cat=deprecation")
  private def addDebugIntrinsic(
    block:      Block,
    moduleName: String,
    defName:    String,
    typeName:   String,
    sourceInfo: SourceInfo
  ): Unit = {
    val qualifiedName = s"$moduleName.$defName"
    val debugIntrinsic = DefIntrinsic(
      sourceInfo,
      "circt_dbg_variable",
      Seq.empty[Arg],
      Seq(
        "name" -> StringParam(qualifiedName),
        "type" -> StringParam(typeName)
      )
    )
    block.addSecretCommand(debugIntrinsic)
  }

  /** Apply automatic instrumentation to a Chisel internal circuit.
    *
    * Traverses all modules in the circuit and adds circt_dbg_variable intrinsics
    * for all wire, register, and memory definitions.
    *
    * @param circuit The Chisel internal circuit to instrument
    */
  def apply(circuit: chisel3.internal.firrtl.ir.Circuit): Unit = {
    circuit.components.foreach {
      case DefModule(id, name, _, _, ports, block) =>
        // Instrument all definitions in this module's block
        instrumentBlock(block, name)

        // Add debug for ports
        ports.foreach { port =>
          addDebugIntrinsic(block, name, port.id.instanceName, port.id.typeName, port.sourceInfo)
        }

      case _: DefBlackBox =>
      // Don't instrument black boxes
      case _: DefIntrinsicModule =>
      // Don't instrument intrinsic modules
      case _ =>
      // Ignore other component types
    }
  }

  /** Recursively instrument a block */
  private def instrumentBlock(block: Block, moduleName: String): Unit = {
    val commands = block.getCommands()

    commands.foreach {
      case DefWire(sourceInfo, dataId: chisel3.Data) =>
        addDebugIntrinsic(block, moduleName, dataId.instanceName, dataId.typeName, sourceInfo)
      case DefReg(sourceInfo, dataId: chisel3.Data, _) =>
        addDebugIntrinsic(block, moduleName, dataId.instanceName, dataId.typeName, sourceInfo)
      case DefRegInit(sourceInfo, dataId: chisel3.Data, _, _, _) =>
        addDebugIntrinsic(block, moduleName, dataId.instanceName, dataId.typeName, sourceInfo)
      case DefMemory(sourceInfo, memId, memType, _) =>
        val memName = memId match {
          case data: chisel3.Data => data.instanceName
          case _ => memId.toString
        }
        addDebugIntrinsic(block, moduleName, memName, memType.typeName, sourceInfo)
      case DefSeqMemory(sourceInfo, memId, memType, _, _) =>
        val memName = memId match {
          case data: chisel3.Data => data.instanceName
          case _ => memId.toString
        }
        addDebugIntrinsic(block, moduleName, memName, memType.typeName, sourceInfo)
      case when: When =>
        // Recursively instrument when blocks
        instrumentBlock(when.ifRegion, moduleName)
        if (when.hasElse) {
          instrumentBlock(when.elseRegion, moduleName)
        }
      case layerBlock: LayerBlock =>
        // Recursively instrument layer blocks
        instrumentBlock(layerBlock.region, moduleName)
      case contract: DefContract =>
        // Recursively instrument contract regions
        instrumentBlock(contract.region, moduleName)
      case _ => // Ignore other commands
    }
  }
}
