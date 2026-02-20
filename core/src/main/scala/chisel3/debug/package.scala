// SPDX-License-Identifier: Apache-2.0
package chisel3

import chisel3.experimental.{SourceInfo, StringParam}
import chisel3.internal.{Builder, HasId}
import chisel3.internal.firrtl.ir.{Arg, DefIntrinsic, Node}
import scala.annotation.nowarn

/** Debug instrumentation API for Chisel signals.
  *
  * Provides manual annotation of individual signals with `circt_dbg_variable`
  * intrinsics. Unlike `--emit-debug-info` (which instruments everything),
  * this API allows precise control over which signals carry debug metadata.
  *
  * {{{
  * import chisel3.debug._
  *
  * class MyModule extends Module {
  *   val counter = RegInit(0.U(8.W))
  *   debug(counter, "my_counter")  // explicit instrumentation
  *   // or equivalently:
  *   counter.instrumentDebug("my_counter")
  * }
  * }}}
  *
  * The emitted intrinsic works regardless of `--emit-debug-info`.
  * If both are used, the signal will have two `circt_dbg_variable` entries
  * (one manual, one automatic) — CIRCT deduplicates at lowering.
  */
package object debug {

  /** Mark a signal for debug instrumentation.
    *
    * Emits a `circt_dbg_variable` intrinsic at the current Builder position
    * (inside the module constructor). This works during elaboration when
    * blocks are still open, so uses `Builder.pushCommand` directly.
    *
    * @param data signal to instrument
    * @param name display name; defaults to signal's instanceName
    * @return the same signal (chainable)
    */
  @nowarn("cat=deprecation")
  def debug[T <: chisel3.Data](data: T, name: String = "")(
    implicit src: SourceInfo
  ): T = {
    val sigName = if (name.isEmpty) data.instanceName else name
    Builder.pushCommand(
      DefIntrinsic(
        src,
        "circt_dbg_variable",
        Seq(Node(data)),
        Seq(
          "name" -> StringParam(sigName),
          "type" -> StringParam(data.typeName)
        )
      )
    )
    data
  }

  /** Implicit extension adding `.instrumentDebug()` to any `Data`. */
  implicit class DataDebugOps[T <: chisel3.Data](private val data: T) extends AnyVal {
    def instrumentDebug(name: String = "")(
      implicit src: SourceInfo
    ): T = debug(data, name)
  }
}
