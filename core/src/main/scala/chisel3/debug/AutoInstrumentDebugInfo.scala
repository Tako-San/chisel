// SPDX-License-Identifier: Apache-2.0

package chisel3.debug

import chisel3.experimental.{SourceInfo, UnlocatableSourceInfo}
import chisel3.internal.Builder
import chisel3.internal.firrtl.ir.{DefIntrinsic, Arg}
import firrtl.ir._

/** Automatically instruments all signals after Builder.build() with debug information.
  *
  * This object traverses the FIRRTL IR modules and emits `circt_dbg_variable` intrinsics
  * for DefWire, DefRegister, and DefNode statements. It handles nested blocks and
  * conditional statements.
  */
private[chisel3] object AutoInstrumentDebugInfo {

  /** Apply automatic instrumentation to a FIRRTL circuit.
    *
    * @param circuit The FIRRTL circuit to instrument
    */
  def apply(circuit: Circuit): Unit = {
    circuit.modules.foreach { module =>
      instrumentModule(module)
    }
  }

  /** Instrument a single module by traversing its statements.
    *
    * @param module The module to instrument (only firrtl.ir.Module has a body)
    */
  private def instrumentModule(module: firrtl.ir.DefModule): Unit = {
    module match {
      case m: firrtl.ir.Module =>
        handleStatement(m.body, Seq.empty)
      case _ =>
        // Skip other DefModule types (like ExtModule) that don't have a body
        ()
    }
  }

  /** Recursively instrument statements, handling nested blocks and conditionals.
    *
    * @param stmt The statement to instrument
    * @param path The hierarchical path prefix for the current context
    */
  private def instrumentStatement(stmt: Statement, path: Seq[String]): Unit = stmt match {
    case firrtl.ir.DefWire(_, name, tpe) =>
      emitDebugIntrinsic(name, path, tpe)

    case firrtl.ir.DefRegister(_, name, tpe, _) =>
      emitDebugIntrinsic(name, path, tpe)

    case firrtl.ir.DefNode(_, name, expr) =>
      emitDebugIntrinsic(name, path, expr.tpe)

    case firrtl.ir.DefMemory(_, name, tpe, _, _, _, _, _, _, _) =>
      emitDebugIntrinsic(name, path, tpe)

    case firrtl.ir.Conditionally(_, _, conseq, alt) =>
      handleStatement(conseq, path)
      handleStatement(alt, path)

    case block: firrtl.ir.Block =>
      instrumentBlock(block, path)

    case _ =>
      // Skip other statement types like Connect, Printf, etc.
      ()
  }

  /** Recursively instrument a block of statements.
    *
    * @param block The block to instrument
    * @param path The hierarchical path prefix for the block context
    */
  private def instrumentBlock(block: firrtl.ir.Block, path: Seq[String]): Unit = {
    block.stmts.foreach { stmt =>
      instrumentStatement(stmt, path)
    }
  }

  /** Handle a statement that may or may not be a Block.
    */
  private def handleStatement(stmt: Statement, path: Seq[String]): Unit = {
    stmt match {
      case block: firrtl.ir.Block => instrumentBlock(block, path)
      case _ => instrumentStatement(stmt, path)
    }
  }

  /** Emit a debug intrinsic for a signal.
    *
    * @param name The name of the signal
    * @param path The hierarchical path prefix
    * @param tpe The type of the signal
    */
  private def emitDebugIntrinsic(name: String, path: Seq[String], tpe: firrtl.ir.Type): Unit = {
    implicit val sourceInfo: SourceInfo = UnlocatableSourceInfo

    val fullName = if (path.isEmpty) name else path.mkString(".") + "." + name
    val typeStr = firrtl.ir.Serializer.serialize(tpe)

    val params = Seq(
      "name" -> chisel3.StringParam(fullName),
      "type" -> chisel3.StringParam(typeStr)
    )

    chisel3.internal.Builder.pushCommand(
      DefIntrinsic(sourceInfo, "circt_dbg_variable", Seq.empty[Arg], params)
    )
  }
}