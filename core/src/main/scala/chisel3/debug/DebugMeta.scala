// SPDX-License-Identifier: Apache-2.0

package chisel3.debug

import chisel3.internal.Builder
import chisel3.internal.HasId

object DebugMeta {

  /** Records debug metadata for a Chisel construct.
    *
    * @param target        The target Chisel construct with an ID
    * @param className     The name of the class being constructed
    * @param params        Constructor parameter string
    * @param sourceFile    Source file name
    * @param sourceLine    Source line number
    * @param ctorParamJson Optional JSON serialization of constructor parameters
    */
  def record(
    target:        HasId,
    className:     String,
    params:        String,
    sourceFile:    String,
    sourceLine:    Int,
    ctorParamJson: Option[String] = None
  ): Unit = {
    Builder.recordDebugMeta(target, className, params, sourceFile, sourceLine, ctorParamJson)
  }

  /** Records debug metadata for a Chisel construct and returns it.
    * This helper avoids creating intermediate values that could break lambda lifting.
    *
    * @param target        The target Chisel construct with an ID
    * @param className     The name of the class being constructed
    * @param params        Constructor parameter string
    * @param sourceFile    Source file name
    * @param sourceLine    Source line number
    * @param ctorParamJson Optional JSON serialization of constructor parameters
    * @return The target Chisel construct
    */
  def recordWithReturn[T <: HasId](
    target:        T,
    className:     String,
    params:        String,
    sourceFile:    String,
    sourceLine:    Int,
    ctorParamJson: Option[String] = None
  ): T = {
    Builder.recordDebugMeta(target, className, params, sourceFile, sourceLine, ctorParamJson)
    target
  }
}
