// SPDX-License-Identifier: Apache-2.0

package chisel3.debug

import chisel3.internal.Builder
import chisel3.internal.HasId

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

  /** Called only by the Chisel compiler plugin. Do not use in user code. */
  @deprecated(
    "For internal use by the Chisel compiler plugin only. Do not call from user code.",
    since = "UNRELEASED"
  )
  def withCtorArgs[T](ctorArgs: Seq[Any])(block: => T): T = {
    Builder.pushPendingCtorArgs(ctorArgs)
    try { block }
    finally { Builder.popPendingCtorArgs() }
  }
}
