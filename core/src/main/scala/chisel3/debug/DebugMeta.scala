// SPDX-License-Identifier: Apache-2.0

package chisel3.debug

import chisel3.internal.Builder
import chisel3.internal.HasId

/** Debug metadata API for attaching type and structure information to Chisel modules.
  *
  * @since UNRELEASED
  * @note This API is experimental and subject to change.
  */
object DebugMeta {

  /** Records debug metadata for a Chisel construct and returns it.
    * This helper avoids creating intermediate values that could break lambda lifting.
    *
    * @param target     The target Chisel construct with an ID
    * @param className  The name of the class being constructed
    * @param params     Constructor parameter string
    * @param sourceFile Source file name
    * @param sourceLine Source line number
    * @return The target Chisel construct
    */
  def record[T <: HasId](
    target:     T,
    className:  String,
    params:     String,
    sourceFile: String,
    sourceLine: Int
  ): T = {
    Builder.recordDebugMeta(target, className, params, sourceFile, sourceLine)
    target
  }

  /** Wraps a Module() instantiation to record pending constructor arguments.
    * Called only by the Chisel compiler plugin. Do not use in user code.
    */
  def withCtorArgs[T](ctorArgs: Option[Seq[Any]])(block: => T): T = {
    Builder.pushPendingCtorArgs(ctorArgs)
    block
  }
}
